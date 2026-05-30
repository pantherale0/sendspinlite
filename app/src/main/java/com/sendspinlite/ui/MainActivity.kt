package com.sendspinlite.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import com.sendspinlite.BuildConfig
import com.sendspinlite.R
import com.sendspinlite.diagnostics.AudioIssueReporter
import com.sendspinlite.diagnostics.CrashReportingManager
import com.sendspinlite.playback.PlaybackDiagnostics
import com.sendspinlite.system.SendspinSystemUtils
import java.util.*

class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "MainActivity"
    }

    private val vm: PlayerViewModel by viewModels()

    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            // Permission grant result will be handled by the system
            // NSD discovery will start or continue based on permission grant
        }

    // Needed on Android 9 and below so crash reports can be written to the public Documents folder.
    // On Android 10+ no permission is required (app-specific external storage is used instead).
    private val writeStoragePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* result handled implicitly — CrashReportingManager checks at write time */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure system bars remain visible and don't draw behind them
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Request battery optimization exemption on first launch (Android 6+)
        val prefs = getSharedPreferences("SendspinPlayerPrefs", MODE_PRIVATE)
        val shownBatteryWarning = prefs.getBoolean("shown_battery_optimization_warning", false)
        if (
            !shownBatteryWarning &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !SendspinSystemUtils.isBatteryOptimizationIgnored(this)
        ) {
            SendspinSystemUtils.requestBatteryOptimizationExemption(this)
            prefs.edit { putBoolean("shown_battery_optimization_warning", true) }
        }

        // Request NEARBY_WIFI_DEVICES permission for mDNS service discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Request WRITE_EXTERNAL_STORAGE on Android 9 and below so crash reports can be saved
        // to the public Documents folder on /sdcard. On Android 10+ this is not required.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(vm)
                }
            }
        }
    }

    override fun onResume() {
        try {
            super.onResume()
        } catch (e: IllegalArgumentException) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 && e.isAndroidTopOfTaskResumeFailure()) {
                Log.w(TAG, "Ignoring Android framework resume race from Activity.isTopOfTask", e)
                return
            }
            throw e
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only disconnect if the activity is finishing (not just rotating or backgrounding)
        if (isFinishing) {
            vm.disconnect()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: android.view.KeyEvent?,
    ): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            -> {
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

internal fun Throwable.isAndroidTopOfTaskResumeFailure(): Boolean =
    this is IllegalArgumentException &&
        stackTrace.any { it.className == "android.app.Activity" && it.methodName == "onResume" } &&
        stackTrace.any { it.className == "android.app.Activity" && it.methodName == "isTopOfTask" }

@Composable
private fun PlayerScreen(vm: PlayerViewModel) {
    val ui by vm.ui.collectAsState()
    val discoveredServers by vm.discoveredServers.collectAsState()
    val crashReportingEnabled by vm.crashReportingEnabled.collectAsState()
    val scrollState = rememberScrollState()

    // State for title tap counter (5 taps to export logs)
    var titleTapCount by remember { mutableStateOf(0) }
    var titleTapLastTime by remember { mutableStateOf(0L) }

    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }
    var logExportUri by remember { mutableStateOf<Uri?>(null) }

    // Crash report dialog state
    var showCrashReportDialog by remember { mutableStateOf(false) }

    // Audio issue reporting state
    var showAudioIssueDialog by remember { mutableStateOf(false) }
    var showAudioIssueResultDialog by remember { mutableStateOf(false) }
    var audioIssueSentryEventId by remember { mutableStateOf<String?>(null) }
    var isCollectingAudioReport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Launcher for saving file
    val saveFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
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

    // Launcher for saving the audio issue report to a user-chosen location
    val audioReportSaveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val reportFile = File(context.filesDir, "sendspin_audio_report.txt")
                        if (reportFile.exists()) {
                            reportFile.inputStream().use { input -> input.copyTo(output) }
                            Log.i("MainActivity", "Audio report saved to $uri")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error saving audio report: ${e.message}")
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

    /** Collect an audio report on IO, save it to filesDir, then open the file picker. */
    fun onSaveAudioReport() {
        isCollectingAudioReport = true
        scope.launch(Dispatchers.IO) {
            val report = AudioIssueReporter.buildReport(ui)
            val fileUri = AudioIssueReporter.saveReportToFile(context, report)
            withContext(Dispatchers.Main) {
                isCollectingAudioReport = false
                if (fileUri != null) {
                    val timestamp =
                        SimpleDateFormat(
                            "yyyy-MM-dd_HH-mm-ss",
                            Locale.getDefault(),
                        ).format(Date())
                    audioReportSaveLauncher.launch("sendspin_audio_report_$timestamp.txt")
                }
            }
        }
    }

    // Sync with system volume when UI is shown
    LaunchedEffect(Unit) {
        vm.updateAndroidVolumeState()
    }

    // Check for a pending crash report from a previous session
    LaunchedEffect(Unit) {
        val report = CrashReportingManager.getPendingCrashReport(context)
        if (report != null) {
            showCrashReportDialog = true
        }
    }

    @Composable
    fun SyncInfoRow(
        label: String,
        value: String,
        color: androidx.compose.ui.graphics.Color = MaterialTheme.colors.onSurface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.body2,
                color = color,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }

    @Composable
    fun getNetworkQualityColor(quality: String): androidx.compose.ui.graphics.Color {
        return when (quality) {
            "GOOD" -> Color.Green
            "FAIR" -> Color(0xFFFFA500) // Orange - more visible on white background
            "POOR" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    @Composable
    fun getStabilityColor(stability: String): androidx.compose.ui.graphics.Color {
        return when (stability) {
            "STABLE" -> Color.Green
            "CONVERGING" -> Color(0xFFFFA500) // Orange - more visible on white background
            "UNSTABLE" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    @Composable
    fun getConnectionTypeColor(connectionType: String): androidx.compose.ui.graphics.Color {
        return when (connectionType) {
            "Ethernet" -> Color.Green
            "WiFi" -> Color(0xFFFFA500) // Orange - more visible on white background
            "Cellular" -> MaterialTheme.colors.error
            else -> MaterialTheme.colors.onSurface
        }
    }

    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8927") }
    var showManualEntryDialog by remember { mutableStateOf(false) }
    var staticDelayInput by remember { mutableStateOf(ui.staticDelayMs.toString()) }

    // Keep staticDelayInput in sync when the value changes (e.g. from server command)
    LaunchedEffect(ui.staticDelayMs) {
        staticDelayInput = ui.staticDelayMs.toString()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sendspin Lite Player",
                style = MaterialTheme.typography.h5,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { handleTitleTap() },
            )
            IconButton(onClick = { showAudioIssueDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = "Report audio issue",
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        // Show discovery status or manual entry prompt
        if (!ui.connected) {
            if (discoveredServers.isNotEmpty()) {
                Text(
                    "Available Servers Found:",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary,
                )
                Text(
                    "Connecting to: ${discoveredServers.first().name}",
                    style = MaterialTheme.typography.caption,
                )
            } else if (!ui.discoveryTimeoutExpired) {
                Text(
                    "Searching for Sendspin servers...",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary,
                )
            } else {
                // Timeout expired, show prompt dialog
                Button(
                    onClick = { showManualEntryDialog = true },
                    modifier = Modifier.fillMaxWidth(),
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
                            "ws://$ipAddress:$port/sendspin",
                        )
                    },
                    enabled = !ui.connected,
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    enabled = ui.connected,
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                            label = { Text("IP Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (ipAddress.isNotBlank() && port.isNotBlank()) {
                                val url = "ws://$ipAddress:$port/sendspin"
                                vm.connect(url)
                                showManualEntryDialog = false
                            }
                        },
                        enabled = ipAddress.isNotBlank() && port.isNotBlank(),
                    ) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showManualEntryDialog = false },
                    ) {
                        Text("Cancel")
                    }
                },
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
                        onClick = { showExportDialog = false },
                    ) {
                        Text("OK")
                    }
                },
            )
        }

        // Audio issue report — collecting/uploading progress indicator
        if (isCollectingAudioReport) {
            AlertDialog(
                onDismissRequest = { /* non-dismissable while collecting */ },
                title = { Text("Collecting Audio Diagnostics…") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Please wait while audio diagnostics are collected.")
                    }
                },
                confirmButton = {},
            )
        }

        // Audio issue report — choose action dialog
        if (showAudioIssueDialog) {
            AlertDialog(
                onDismissRequest = { showAudioIssueDialog = false },
                title = { Text("Report Audio Issue") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This collects audio diagnostics to help identify the issue. " +
                                "No personal data, server addresses, or track metadata is included.",
                        )
                        if (vm.isCrashReportingAvailable && !crashReportingEnabled) {
                            Text(
                                "Enable crash & ANR reporting in Settings to also upload reports directly to Sentry.",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                confirmButton = {
                    if (vm.isCrashReportingAvailable && crashReportingEnabled) {
                        Button(
                            onClick = {
                                showAudioIssueDialog = false
                                isCollectingAudioReport = true
                                scope.launch(Dispatchers.IO) {
                                    val report = AudioIssueReporter.buildReport(ui)
                                    val eventId = AudioIssueReporter.uploadToSentry(report, ui)
                                    withContext(Dispatchers.Main) {
                                        audioIssueSentryEventId = eventId
                                        isCollectingAudioReport = false
                                        showAudioIssueResultDialog = true
                                    }
                                }
                            },
                        ) {
                            Text("Upload to Sentry")
                        }
                    } else {
                        Button(
                            onClick = {
                                showAudioIssueDialog = false
                                onSaveAudioReport()
                            },
                        ) {
                            Text("Save to File")
                        }
                    }
                },
                dismissButton = {
                    if (vm.isCrashReportingAvailable && crashReportingEnabled) {
                        TextButton(
                            onClick = {
                                showAudioIssueDialog = false
                                onSaveAudioReport()
                            },
                        ) {
                            Text("Save to File")
                        }
                    } else {
                        TextButton(onClick = { showAudioIssueDialog = false }) {
                            Text("Cancel")
                        }
                    }
                },
            )
        }

        // Audio issue report — result dialog (shown after Sentry upload)
        if (showAudioIssueResultDialog) {
            AlertDialog(
                onDismissRequest = { showAudioIssueResultDialog = false },
                title = { Text("Audio Report Uploaded") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (audioIssueSentryEventId != null) {
                            Text("The audio diagnostics report has been uploaded to Sentry.")
                            Text(
                                "Event ID:",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                            Text(
                                audioIssueSentryEventId!!,
                                style =
                                    MaterialTheme.typography.body2.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    ),
                            )
                            Text(
                                "Include this event ID in your GitHub issue so the report can be located.",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        } else {
                            Text(
                                "The upload failed. Please save the report to a file and attach it to your GitHub issue instead.",
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showAudioIssueResultDialog = false }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    if (audioIssueSentryEventId == null) {
                        TextButton(
                            onClick = {
                                showAudioIssueResultDialog = false
                                onSaveAudioReport()
                            },
                        ) {
                            Text("Save to File")
                        }
                    }
                },
            )
        }

        // Crash report dialog — shown on startup when a crash was detected in the previous session
        if (showCrashReportDialog) {
            AlertDialog(
                onDismissRequest = {
                    CrashReportingManager.clearPendingCrashReport(context)
                    showCrashReportDialog = false
                },
                title = { Text("App Crash Detected") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (crashReportingEnabled) {
                            Text(
                                "Sendspin Lite crashed during the last session. The crash was automatically reported to help improve the app.",
                            )
                        } else {
                            Text(
                                "Sendspin Lite crashed during the last session. Would you like to send an anonymous crash report to help improve the app?",
                            )
                            if (!vm.isCrashReportingAvailable) {
                                Text(
                                    "Note: Crash reporting is not available in this build.",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                )
                            } else {
                                Text(
                                    "You can enable automatic crash reporting in the Settings section below.",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (crashReportingEnabled) {
                        Button(
                            onClick = {
                                CrashReportingManager.clearPendingCrashReport(context)
                                showCrashReportDialog = false
                            },
                        ) {
                            Text("OK")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (vm.isCrashReportingAvailable) {
                                    vm.setCrashReportingEnabled(true)
                                    CrashReportingManager.sendPendingCrashReport(context)
                                } else {
                                    CrashReportingManager.clearPendingCrashReport(context)
                                }
                                showCrashReportDialog = false
                            },
                            enabled = vm.isCrashReportingAvailable,
                        ) {
                            Text("Send Report")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            CrashReportingManager.clearPendingCrashReport(context)
                            showCrashReportDialog = false
                        },
                    ) {
                        Text("Dismiss")
                    }
                },
            )
        }

        // Connection Status
        Text("Connection Status", style = MaterialTheme.typography.h6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp,
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
                elevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SyncInfoRow("Stream", ui.streamDesc.ifBlank { "-" })

                    // Display sync uncertainty (lower = better sync stability)
                    val uncertaintyColor =
                        when {
                            ui.offsetUncertaintyUs < 1_000 -> Color.Green
                            ui.offsetUncertaintyUs < 5_000 -> Color(0xFFFFA500) // Orange
                            else -> MaterialTheme.colors.error
                        }
                    val uncertaintyMs = ui.offsetUncertaintyUs / 1000.0
                    SyncInfoRow("Sync Uncertainty", "±${"%.2f".format(uncertaintyMs)}ms", uncertaintyColor)

                    val driftColor =
                        when {
                            ui.driftPpm >= -5.0 && ui.driftPpm <= 5.0 -> Color.Green
                            ui.driftPpm >= -25.0 && ui.driftPpm <= 25.0 -> Color(0xFFFFA500) // Orange
                            else -> MaterialTheme.colors.error
                        }
                    // Format drift: use scientific notation for very small values, regular format otherwise
                    val driftFormatted =
                        when {
                            kotlin.math.abs(ui.driftPpm) < 0.01 -> "< 0.01 ppm"
                            else -> String.format("%.3f ppm", ui.driftPpm)
                        }
                    SyncInfoRow("Drift", driftFormatted, driftColor)

                    // Color code RTT: green if <10ms, orange 10-50ms, red >50ms
                    val rttColor =
                        when {
                            ui.rttUs < 10_000 -> Color.Green
                            ui.rttUs < 50_000 -> Color(0xFFFFA500) // Orange
                            else -> MaterialTheme.colors.error
                        }
                    SyncInfoRow("RTT", "~${"%.2f".format(ui.rttUs / 1000.0)}ms", rttColor)

                    val bufferColor =
                        when {
                            ui.queuedChunks >= 190 -> Color.Green
                            ui.queuedChunks >= 100 -> Color(0xFFFFA500) // Orange
                            else -> MaterialTheme.colors.error
                        }
                    SyncInfoRow("Buffer", "${ui.queuedChunks} chunks (${ui.bufferAheadMs}ms ahead)", bufferColor)

                    // Display smoothed latency
                    val latencyColor =
                        when {
                            ui.smoothedLatencyMs < 300.0 -> Color.Green
                            ui.smoothedLatencyMs < 500.0 -> Color(0xFFFFA500) // Orange
                            else -> MaterialTheme.colors.error
                        }
                    SyncInfoRow("Audio Latency", "${"%.1f".format(ui.smoothedLatencyMs)}ms", latencyColor)

                    // Display playback speed multiplier (only show if not 1.0x)
                    if (ui.playbackSpeedMultiplier != 1.0f) {
                        val speedColor =
                            when {
                                ui.playbackSpeedMultiplier >= 0.999f && ui.playbackSpeedMultiplier <= 1.001f -> Color.Green
                                ui.playbackSpeedMultiplier >= 0.995f && ui.playbackSpeedMultiplier <= 1.005f -> Color(0xFFFFA500) // Orange
                                else -> MaterialTheme.colors.error
                            }
                        SyncInfoRow("Playback Speed", "${"%.3f".format(ui.playbackSpeedMultiplier)}x", speedColor)
                    }

                    // Static Delay input field (Sendspin spec: static_delay_ms, 0-5000ms)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Static Delay",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                        OutlinedTextField(
                            value = staticDelayInput,
                            onValueChange = { newValue ->
                                if (newValue.isEmpty() || newValue.toLongOrNull() != null) {
                                    staticDelayInput = newValue
                                    newValue.toLongOrNull()?.let { value ->
                                        if (value in 0L..5000L) {
                                            vm.setStaticDelayMs(value)
                                        }
                                    }
                                }
                            },
                            label = { Text("ms") },
                            singleLine = true,
                            modifier = Modifier.width(100.dp),
                            textStyle =
                                MaterialTheme.typography.body2.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                        )
                    }

                    if (ui.lateDrops > 0) {
                        SyncInfoRow(
                            "Late Drops",
                            ui.lateDrops.toString(),
                            color = MaterialTheme.colors.error,
                        )
                    }

                    // Display audible sync and Kalman error stats
                    if (ui.audibleSyncCount > 0) {
                        SyncInfoRow(
                            "Audible Syncs",
                            ui.audibleSyncCount.toString(),
                            // Orange accent for audible sync stat line
                            color = Color(0xFFFFA500),
                        )
                    }
                    if (ui.kalmanErrorCount > 0) {
                        SyncInfoRow(
                            "Kalman Errors",
                            ui.kalmanErrorCount.toString(),
                            color = MaterialTheme.colors.error,
                        )
                    }

                    val recoveryColor =
                        when (ui.playbackRecoveryStatus) {
                            PlaybackDiagnostics.STATUS_PLAYING,
                            PlaybackDiagnostics.STATUS_IDLE,
                            -> Color.Green

                            PlaybackDiagnostics.STATUS_WAITING_CLOCK,
                            PlaybackDiagnostics.STATUS_START_BACKOFF,
                            -> Color(0xFFFFA500)

                            else -> MaterialTheme.colors.error
                        }
                    SyncInfoRow(
                        "Recovery",
                        ui.playbackRecoveryStatus.ifBlank { "-" },
                        recoveryColor,
                    )
                    if (!ui.audioOutputStarted && ui.playbackState == "playing") {
                        SyncInfoRow(
                            "Output",
                            "not started",
                            color = MaterialTheme.colors.error,
                        )
                    }
                    if (ui.clockReadyForPlayback.not() && ui.connected) {
                        SyncInfoRow(
                            "Clock Gate",
                            "waiting",
                            color = Color(0xFFFFA500),
                        )
                    }
                    if (ui.serverLatenessMs > PlaybackDiagnostics.UI_SERVER_LATENESS_WARN_MS) {
                        SyncInfoRow(
                            "Server Late",
                            "${ui.serverLatenessMs}ms",
                            color = MaterialTheme.colors.error,
                        )
                    }
                    if (ui.lastRecoveryEvent.isNotBlank()) {
                        Text(
                            text = "Last: ${ui.lastRecoveryEvent}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // Settings section
        Divider()
        Text("Settings", style = MaterialTheme.typography.h6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Crash & ANR reporting opt-in toggle
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Crash & ANR Reporting",
                            style = MaterialTheme.typography.body2,
                        )
                        Text(
                            text =
                                if (vm.isCrashReportingAvailable) {
                                    "Send anonymous crash reports to Sentry to help improve the app"
                                } else {
                                    "Not available in this build"
                                },
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(
                        checked = crashReportingEnabled,
                        onCheckedChange = { vm.setCrashReportingEnabled(it) },
                        enabled = vm.isCrashReportingAvailable,
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "App Updates",
                    style = MaterialTheme.typography.body2,
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}. Track GitHub release updates with Obtainium.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Button(
                    onClick = {
                        SendspinSystemUtils.openExternalUrl(
                            context = context,
                            url = context.getString(R.string.obtainium_app_url),
                            failureMessage = "Could not open the Obtainium import link.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Follow Updates with Obtainium")
                }
            }
        }

        Text(
            "Sendspin Lite v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.caption,
        )
    }
}

/**
 * Export application logs for debugging purposes.
 * Collects logs from LogCat and creates a file in app's cache directory.
 */
private fun exportAndShareLogs(
    context: android.content.Context,
    uiState: PlayerViewModel.UiState,
    onLogsReady: (Uri?) -> Unit,
) {
    try {
        // Create logs file in app's files directory
        val logsFile = File(context.filesDir, "sendspin_logs.txt")
        logsFile.delete() // Clear previous logs
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
        stringBuilder.append("Static Delay: ${uiState.staticDelayMs}ms\n")
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
        stringBuilder.append("\nPlayback Recovery:\n")
        stringBuilder.append("  Recovery Status: ${uiState.playbackRecoveryStatus}\n")
        stringBuilder.append("  Audio Output Started: ${uiState.audioOutputStarted}\n")
        stringBuilder.append("  Clock Ready: ${uiState.clockReadyForPlayback}\n")
        stringBuilder.append("  Force Resync: ${uiState.forceResyncActive}\n")
        stringBuilder.append("  Late Start Loops: ${uiState.lateRestartLoops}\n")
        stringBuilder.append("  Server Lateness: ${uiState.serverLatenessMs}ms\n")
        stringBuilder.append("  Effective Buffer Ahead: ${uiState.effectiveBufferAheadMs}ms\n")
        stringBuilder.append("  Last Recovery Event: ${uiState.lastRecoveryEvent.ifBlank { "-" }}\n")
        stringBuilder.append("\nDiagnostic Hints:\n")
        stringBuilder.append(AudioIssueReporter.formatDiagnosticHints(uiState))
        stringBuilder.append("\n")
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
        val logsUri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logsFile,
            )

        onLogsReady(logsUri)
    } catch (e: Exception) {
        Log.e("MainActivity", "Error exporting logs: ${e.message}")
        onLogsReady(null)
    }
}
