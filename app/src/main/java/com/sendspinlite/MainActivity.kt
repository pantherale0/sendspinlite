package com.sendspinlite

import android.Manifest
import android.content.res.Configuration
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.core.view.WindowCompat
import androidx.core.content.edit

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sendspin Lite Player", style = MaterialTheme.typography.h5)

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
                    SyncInfoRow("Offset", "${ui.offsetUs}μs")
                    val driftColor = when {
                        ui.driftPpm >= -5.0 && ui.driftPpm <= 5.0 -> Color.Green
                        ui.driftPpm >= -25.0 && ui.driftPpm <= 25.0 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    // Format drift: use scientific notation for very small values, regular format otherwise
                    val driftFormatted = when {
                        kotlin.math.abs(ui.driftPpm) < 0.01 -> String.format("%.2e ppm", ui.driftPpm)
                        else -> String.format("%.3f ppm", ui.driftPpm)
                    }
                    SyncInfoRow("Drift", driftFormatted, driftColor)
                    
                    // Color code RTT: green if <10ms, orange 10-50ms, red >50ms
                    val rttColor = when {
                        ui.rttUs < 10_000 -> Color.Green
                        ui.rttUs < 50_000 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    SyncInfoRow("RTT", "~${ui.rttUs}μs", rttColor)
                    
                    val bufferColor = when {
                        ui.queuedChunks >= 190 -> Color.Green
                        ui.queuedChunks >= 100 -> Color(0xFFFFA500)  // Orange
                        else -> MaterialTheme.colors.error
                    }
                    SyncInfoRow("Buffer", "${ui.queuedChunks} chunks (${ui.bufferAheadMs}ms ahead)", bufferColor)
                    SyncInfoRow("Playout Offset", "${ui.playoutOffsetMs}ms")
                    if (ui.lateDrops > 0) {
                        SyncInfoRow(
                            "Late Drops",
                            ui.lateDrops.toString(),
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