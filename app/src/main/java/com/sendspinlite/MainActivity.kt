package com.sendspinlite

import android.Manifest
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    
    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission grant result will be handled by the system
        // NSD discovery will start or continue based on permission grant
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure system bars remain visible and don't draw behind them
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Check if playout offset is provided via intent (overrides saved value)
        val playoutOffsetMs = intent.getLongExtra("playoutOffsetMs", Long.MIN_VALUE)
        if (playoutOffsetMs != Long.MIN_VALUE) {
            vm.setPlayoutOffsetMs(playoutOffsetMs)
        }
        
        // Check if Opus codec is requested via intent (overrides saved value)
        if (intent.hasExtra("enableOpusCodec")) {
            val enableOpusCodec = intent.getBooleanExtra("enableOpusCodec", false)
            vm.setEnableOpusCodec(enableOpusCodec)
        }
        
        // Check if we need to show battery optimization warning (only on first launch)
        val prefs = getSharedPreferences("SendspinPlayerPrefs", MODE_PRIVATE)
        val shownBatteryWarning = prefs.getBoolean("shown_battery_optimization_warning", false)
        
        // Request NEARBY_WIFI_DEVICES permission for mDNS service discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(vm, showBatteryWarning = !shownBatteryWarning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only disconnect if the activity is finishing (not just rotating or backgrounding)
        if (isFinishing) {
            vm.disconnect()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Update the UI state to reflect the new system volume after a brief delay
                // to allow the system to process the volume change
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    vm.updateAndroidVolumeState()
                }, 100)
                super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

@Composable
private fun PlayerScreen(vm: PlayerViewModel, showBatteryWarning: Boolean = false) {
    val ui by vm.ui.collectAsState()
    val discoveredServers by vm.discoveredServers.collectAsState()
    val scrollState = rememberScrollState()
    var showBatteryDialog by remember { mutableStateOf(showBatteryWarning) }
    
    // State for title tap counter (5 taps to export logs)
    var titleTapCount by remember { mutableStateOf(0) }
    var titleTapLastTime by remember { mutableStateOf(0L) }
    
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var logExportUri by remember { mutableStateOf<Uri?>(null) }
    
    // Launcher for saving file
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    val logsFile = File(context.filesDir, "sendspin_logs.txt")
                    if (logsFile.exists()) {
                        logsFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                        Log.i("MainActivity", "Logs exported successfully to $uri")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error exporting logs: ${e.message}")
            }
        }
    }
    
    fun handleTitleTap() {
        val currentTime = System.currentTimeMillis()
        // Reset counter if more than 3 seconds have passed
        if (currentTime - titleTapLastTime > 3000) {
            titleTapCount = 0
        }
        titleTapLastTime = currentTime
        titleTapCount++
        
        if (titleTapCount == 5) {
            titleTapCount = 0
            // Trigger log export
            exportAndShareLogs(context, ui) { uri ->
                if (uri != null) {
                    logExportUri = uri
                    showExportDialog = true
                    // Open file save dialog
                    val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                    saveFileLauncher.launch("sendspin_logs_$timestamp.txt")
                }
            }
        }
    }

    // Sync with system volume when UI is shown
    LaunchedEffect(Unit) {
        vm.updateAndroidVolumeState()
    }

    @Composable
    fun SyncInfoRow(
        label: String,
        value: String,
        color: androidx.compose.ui.graphics.Color = MaterialTheme.colors.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.body2,
                color = color,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }

    @Composable
    fun getNetworkQualityColor(quality: String): androidx.compose.ui.graphics.Color {
        return when (quality) {
            "GOOD" -> Color.Green
            "FAIR" -> Color(0xFFFFA500)  // Orange - more visible on white background
            "POOR" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    @Composable
    fun getStabilityColor(stability: String): androidx.compose.ui.graphics.Color {
        return when (stability) {
            "STABLE" -> Color.Green
            "CONVERGING" -> Color(0xFFFFA500)  // Orange - more visible on white background
            "UNSTABLE" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    @Composable
    fun getConnectionTypeColor(connectionType: String): androidx.compose.ui.graphics.Color {
        return when (connectionType) {
            "Ethernet" -> Color.Green
            "WiFi" -> Color(0xFFFFA500)  // Orange - more visible on white background
            "Cellular" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8927") }
    var showManualEntryDialog by remember { mutableStateOf(false) }
    var playoutOffsetInput by remember { mutableStateOf(ui.playoutOffsetMs.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Sendspin Lite Player",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.clickable { handleTitleTap() }
        )

        // Show discovery status or manual entry prompt
        if (!ui.connected) {
            if (discoveredServers.isNotEmpty()) {
                Text(
                    "Available Servers Found:",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary
                )
                Text(
                    "Connecting to: ${discoveredServers.first().name}",
                    style = MaterialTheme.typography.caption
                )
            } else if (!ui.discoveryTimeoutExpired) {
                Text(
                    "Searching for Sendspin servers...",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary
                )
            } else {
                // Timeout expired, show prompt dialog
                Button(
                    onClick = { showManualEntryDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cannot find server, enter IP address?")
                }
            }
        }

        if (ipAddress.isNotBlank() && port.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.connect(
                            "ws://${ipAddress}:${port}/sendspin"
                        )
                    },
                    enabled = !ui.connected
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    enabled = ui.connected
                ) { Text("Disconnect") }
            }
        }

        Divider()

        // Manual entry dialog
        if (showManualEntryDialog) {
            AlertDialog(
                onDismissRequest = { showManualEntryDialog = false },
                title = { Text("Enter Server Details") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                            label = { Text("IP Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (ipAddress.isNotBlank() && port.isNotBlank()) {
                                val url = "ws://${ipAddress}:${port}/sendspin"
                                vm.connect(url)
                                showManualEntryDialog = false
                            }
                        },
                        enabled = ipAddress.isNotBlank() && port.isNotBlank()
                    ) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showManualEntryDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Battery optimization warning dialog (Android 12+)
        if (showBatteryDialog) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("Disable Battery Optimization") },
                text = {
                    Text(
                        "To prevent the app from being stopped by battery optimization, " +
                                "please disable battery optimization for Sendspin Lite in your device settings.\n\n" +
                                "Tap 'Open Settings' below to go to the battery optimization screen."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // Open battery optimization settings
                            val intent =
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                            // Update prefs to prevent showing again
                            context.getSharedPreferences(
                                "SendspinPlayerPrefs",
                                ComponentActivity.MODE_PRIVATE
                            )
                                .edit {
                                    putBoolean("shown_battery_optimization_warning", true)
                                }
                            showBatteryDialog = false
                        }
                    ) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showBatteryDialog = false }
                    ) {
                        Text("Dismiss")
                    }
                }
            )
        }
        
        // Export logs dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Logs Exported") },
                text = { Text("Logs have been exported. You can find them using your device's file manager.") },
                confirmButton = {
                    Button(
                        onClick = { showExportDialog = false }
                    ) {
                        Text("OK")
                    }
                }
            )
        }

        // Connection Status
        Text("Connection Status", style = MaterialTheme.typography.h6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SyncInfoRow("Status", ui.status)
                if (ui.connected) {
                    SyncInfoRow("Roles", ui.activeRoles.ifBlank { "-" })
                    SyncInfoRow("Connection Type", ui.connectionType, getConnectionTypeColor(ui.connectionType))
                    SyncInfoRow("Network Quality", ui.networkQuality, getNetworkQualityColor(ui.networkQuality))
                    SyncInfoRow("Stability", ui.stability, getStabilityColor(ui.stability))
                }
                SyncInfoRow("Drops", ui.connectionDrops.toString())
            }
        }

        // Audio Sync Information (only show when connected and in player role)
        if (ui.connected && ui.activeRoles.contains("player")) {
            Divider()
            Text("Audio Sync", style = MaterialTheme.typography.h6)

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SyncInfoRow("Stream", ui.streamDesc.ifBlank { "-" })
                    
                    // Display sync uncertainty (lower = better sync stability)
                    val uncertaintyColor = when {
                        ui.offsetUncertaintyUs < 1_000 -> Color.Green
                        ui.offsetUncertaintyUs < 5_000 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    val uncertaintyMs = ui.offsetUncertaintyUs / 1000.0
                    SyncInfoRow("Sync Uncertainty", "±${"%.2f".format(uncertaintyMs)}ms", uncertaintyColor)
                    
                    val driftColor = when {
                        ui.driftPpm >= -5.0 && ui.driftPpm <= 5.0 -> Color.Green
                        ui.driftPpm >= -25.0 && ui.driftPpm <= 25.0 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    // Format drift: use scientific notation for very small values, regular format otherwise
                    val driftFormatted = when {
                        kotlin.math.abs(ui.driftPpm) < 0.01 -> "< 0.01 ppm"
                        else -> String.format("%.3f ppm", ui.driftPpm)
                    }
                    SyncInfoRow("Drift", driftFormatted, driftColor)
                    
                    // Color code RTT: green if <10ms, orange 10-50ms, red >50ms
                    val rttColor = when {
                        ui.rttUs < 10_000 -> Color.Green
                        ui.rttUs < 50_000 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    SyncInfoRow("RTT", "~${"%.2f".format(ui.rttUs / 1000.0)}ms", rttColor)
                    
                    val bufferColor = when {
                        ui.queuedChunks >= 190 -> Color.Green
                        ui.queuedChunks >= 100 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    SyncInfoRow("Buffer", "${ui.queuedChunks} chunks (${ui.bufferAheadMs}ms ahead)", bufferColor)
                    
                    // Display smoothed latency
                    val latencyColor = when {
                        ui.smoothedLatencyMs < 50.0 -> Color.Green
                        ui.smoothedLatencyMs < 100.0 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    SyncInfoRow("Audio Latency", "${"%.1f".format(ui.smoothedLatencyMs)}ms", latencyColor)
                    
                    // Display playback speed multiplier (only show if not 1.0x)
                    if (ui.playbackSpeedMultiplier != 1.0f) {
                        val speedColor = when {
                            ui.playbackSpeedMultiplier >= 0.999f && ui.playbackSpeedMultiplier <= 1.001f -> Color.Green
                            ui.playbackSpeedMultiplier >= 0.995f && ui.playbackSpeedMultiplier <= 1.005f -> Color(0xFFFFA500)  // Orange
                            else -> MaterialTheme.colors.error
                        }
                        SyncInfoRow("Playback Speed", "${"%.3f".format(ui.playbackSpeedMultiplier)}x", speedColor)
                    }
                    
                    // Playout Offset input field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Playout Offset",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = playoutOffsetInput,
                            onValueChange = { newValue ->
                                if (newValue.isEmpty() || newValue == "-" || newValue.toIntOrNull() != null) {
                                    playoutOffsetInput = newValue
                                    newValue.toLongOrNull()?.let { value ->
                                        if (value in -500L..500L) {
                                            vm.setPlayoutOffsetMs(value)
                                        }
                                    }
                                }
                            },
                            label = { Text("ms") },
                            singleLine = true,
                            modifier = Modifier.width(100.dp),
                            textStyle = MaterialTheme.typography.body2.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                    }
                    
                    if (ui.lateDrops > 0) {
                        SyncInfoRow(
                            "Late Drops",
                            ui.lateDrops.toString(),
                            color = MaterialTheme.colors.error
                        )
                    }
                    
                    // Display audible sync and Kalman error stats
                    if (ui.audibleSyncCount > 0) {
                        SyncInfoRow(
                            "Audible Syncs",
                            ui.audibleSyncCount.toString(),
                            color = Color(0xFFFFA500)  // Orange
                        )
                    }
                    if (ui.kalmanErrorCount > 0) {
                        SyncInfoRow(
                            "Kalman Errors",
                            ui.kalmanErrorCount.toString(),
                            color = MaterialTheme.colors.error
                        )
                    }
                }
            }
        }

        Text(
            "Sendspin Lite v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.caption
        )
    }
}

/**
 * Export application logs for debugging purposes.
 * Collects logs from LogCat and creates a file in app's cache directory.
 */
private fun exportAndShareLogs(context: android.content.Context, uiState: PlayerViewModel.UiState, onLogsReady: (Uri?) -> Unit) {
    try {
        // Create logs file in app's files directory
        val logsFile = File(context.filesDir, "sendspin_logs.txt")
        logsFile.delete()  // Clear previous logs
        logsFile.createNewFile()
        
        val stringBuilder = StringBuilder()
        stringBuilder.append("=== Sendspin Lite Logs ===\n")
        stringBuilder.append("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        stringBuilder.append("Android Version: ${Build.VERSION.RELEASE}\n")
        stringBuilder.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        stringBuilder.append("App Version: ${BuildConfig.VERSION_NAME}\n")
        stringBuilder.append("=========================\n\n")
        
        // Audio Stack Information
        stringBuilder.append("=== AUDIO STACK INFORMATION ===\n")
        stringBuilder.append("Low Memory Device: ${uiState.isLowMemoryDevice}\n")
        stringBuilder.append("Is TV: ${uiState.isTV}\n")
        stringBuilder.append("Playback Speed Multiplier: ${String.format("%.3f", uiState.playbackSpeedMultiplier)}x\n")
        stringBuilder.append("Playout Offset: ${uiState.playoutOffsetMs}ms\n")
        stringBuilder.append("Enable Opus Codec: ${uiState.enableOpusCodec}\n")
        stringBuilder.append("================================\n\n")
        
        // Current Stats Snapshot
        stringBuilder.append("=== CURRENT STATS SNAPSHOT ===\n")
        stringBuilder.append("Connection Status: ${uiState.status}\n")
        stringBuilder.append("Connected: ${uiState.connected}\n")
        stringBuilder.append("Connection Drops: ${uiState.connectionDrops}\n")
        stringBuilder.append("Active Roles: ${uiState.activeRoles.ifBlank { "-" }}\n")
        stringBuilder.append("Group Name: ${uiState.groupName.ifBlank { "-" }}\n")
        stringBuilder.append("Connection Type: ${uiState.connectionType}\n")
        stringBuilder.append("Network Quality: ${uiState.networkQuality}\n")
        stringBuilder.append("Stability: ${uiState.stability}\n")
        stringBuilder.append("\nPlayback:\n")
        stringBuilder.append("  Playback State: ${uiState.playbackState.ifBlank { "-" }}\n")
        stringBuilder.append("  Stream: ${uiState.streamDesc.ifBlank { "-" }}\n")
        stringBuilder.append("  Smoothed Latency: ${String.format("%.1f", uiState.smoothedLatencyMs)}ms\n")
        stringBuilder.append("\nAudio Sync:\n")
        stringBuilder.append("  Sync Uncertainty: ${uiState.offsetUncertaintyUs / 1000.0}µs\n")
        stringBuilder.append("  Drift: ${String.format("%.3f", uiState.driftPpm)} ppm\n")
        stringBuilder.append("  Drift Uncertainty: ${String.format("%.3f", uiState.driftUncertaintyPpm)} ppm\n")
        stringBuilder.append("  Drift SNR: ${String.format("%.2f", uiState.driftSnr)}\n")
        stringBuilder.append("  RTT: ${String.format("%.2f", uiState.rttUs / 1000.0)}ms\n")
        stringBuilder.append("\nBuffer:\n")
        stringBuilder.append("  Queued Chunks: ${uiState.queuedChunks}\n")
        stringBuilder.append("  Buffer Ahead: ${uiState.bufferAheadMs}ms\n")
        stringBuilder.append("  Late Drops: ${uiState.lateDrops}\n")
        stringBuilder.append("\nStatistics:\n")
        stringBuilder.append("  Audible Sync Count: ${uiState.audibleSyncCount}\n")
        stringBuilder.append("  Kalman Error Count: ${uiState.kalmanErrorCount}\n")
        stringBuilder.append("\nVolume:\n")
        stringBuilder.append("  Group Volume: ${uiState.groupVolume}%\n")
        stringBuilder.append("  Group Muted: ${uiState.groupMuted}\n")
        stringBuilder.append("  Player Volume: ${uiState.playerVolume}%\n")
        stringBuilder.append("  Player Muted: ${uiState.playerMuted}\n")
        stringBuilder.append("  From Server: ${uiState.playerVolumeFromServer}\n")
        
        if (uiState.hasMetadata) {
            stringBuilder.append("\nMetadata:\n")
            stringBuilder.append("  Title: ${uiState.trackTitle ?: "-"}\n")
            stringBuilder.append("  Artist: ${uiState.trackArtist ?: "-"}\n")
            stringBuilder.append("  Album: ${uiState.albumTitle ?: "-"}\n")
            stringBuilder.append("  Album Artist: ${uiState.albumArtist ?: "-"}\n")
            stringBuilder.append("  Year: ${uiState.trackYear ?: "-"}\n")
            stringBuilder.append("  Track Number: ${uiState.trackNumber ?: "-"}\n")
        }
        
        stringBuilder.append("================================\n\n")
        
        // Try to collect logs from logcat
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            val reader = process.inputStream.bufferedReader()
            val logs = reader.readText()
            stringBuilder.append("=== SYSTEM LOGS (LOGCAT) ===\n")
            stringBuilder.append(logs)
            reader.close()
            process.destroy()
        } catch (e: Exception) {
            stringBuilder.append("Note: Could not retrieve logcat output: ${e.message}\n")
            stringBuilder.append("App logs will be available on next connection.\n")
            Log.w("MainActivity", "Error collecting logcat: ${e.message}")
        }
        
        // Write to file
        logsFile.writeText(stringBuilder.toString())
        
        // Create URI using FileProvider
        val logsUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logsFile
        )
        
        onLogsReady(logsUri)
    } catch (e: Exception) {
        Log.e("MainActivity", "Error exporting logs: ${e.message}")
        onLogsReady(null)
    }
}