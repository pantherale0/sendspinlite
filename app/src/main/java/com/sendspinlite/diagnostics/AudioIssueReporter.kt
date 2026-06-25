package com.sendspinlite.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.sendspinlite.BuildConfig
import com.sendspinlite.playback.PlaybackDiagnostics
import com.sendspinlite.ui.PlayerViewModel
import io.sentry.Attachment
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects and reports audio/playback issue diagnostics.
 *
 * Privacy: Only data relevant to diagnosing audio pipeline issues is included.
 * Personally-identifiable or content-related information (server URL/IP address,
 * client name, group name, and track metadata such as title/artist/album) is
 * intentionally excluded so that the report contains no personal data.
 */
object AudioIssueReporter {
    private const val TAG = "AudioIssueReporter"
    private const val REPORT_FILE = "sendspin_audio_report.txt"
    private const val US_PER_MS = 1000L

    private const val BAND_2_4_MIN = 2400
    private const val BAND_2_4_MAX = 2500
    private const val BAND_5_MIN = 4900
    private const val BAND_5_MAX = 5900
    private const val BAND_6_MIN = 5925
    private const val BAND_6_MAX = 7125

    /**
     * Build a privacy-redacted diagnostics report focused on the audio pipeline.
     */
    fun buildReport(
        uiState: PlayerViewModel.UiState,
        context: Context? = null,
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()

        sb.appendLine("=== Sendspin Lite Audio Issue Report ===")
        sb.appendLine("Timestamp   : $timestamp")
        sb.appendLine("App Version : ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Android     : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("========================================")
        sb.appendLine()

        appendSystemAndNetworkInfo(sb, uiState, context)
        appendPipelineAndTimingDiagnostics(sb, uiState)

        sb.appendLine("=== DIAGNOSTIC HINTS ===")
        sb.appendLine(formatDiagnosticHints(uiState))
        sb.appendLine()

        sb.appendLine("=== AUDIO LOGCAT (filtered) ===")
        try {
            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", "800",
                        "SendspinNativeClient:V",
                        "SendspinJni:V",
                        "PcmAudioOutput:V",
                        "PlayerViewModel:V",
                        "CrashReportingManager:V",
                        "AudioIssueReporter:V",
                        "AudioTrack:V",
                        "AudioRecord:V",
                        "AudioFlinger:V",
                        "AudioPolicyManager:V",
                        "*:S",
                    ),
                )
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    sb.appendLine(line)
                }
            }
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Logcat retrieval failed", e)
            sb.appendLine("(logcat unavailable: ${e.message})")
        }

        return sb.toString()
    }

    /**
     * Short, human-readable hints derived from the snapshot (no PII).
     */
    internal fun formatDiagnosticHints(uiState: PlayerViewModel.UiState): String {
        val hints = mutableListOf<String>()
        if (!uiState.clockReadyForPlayback) {
            hints.add(
                "- Clock not ready for playback " +
                    "(converged=${uiState.clockUpdateCount >= PlaybackDiagnostics.CLOCK_CONVERGED_MIN_UPDATES}, " +
                    "uncertainty=${uiState.offsetUncertaintyUs / US_PER_MS}ms, " +
                    "rtt=${uiState.rttUs / US_PER_MS}ms)",
            )
        }
        if (uiState.playbackState == "playing" && !uiState.audioOutputStarted) {
            hints.add("- Group reports playing but local AudioTrack is not started (prestart/recovery stall)")
        }
        if (uiState.queuedChunks >= PlaybackDiagnostics.PRESTART_BACKLOG_CHUNK_THRESHOLD &&
            uiState.bufferAheadMs < PlaybackDiagnostics.PRESTART_LATE_HEAD_MS
        ) {
            hints.add("- Large prestart backlog with late head (possible prestart deadlock before fix)")
        }
        if (uiState.forceResyncActive) {
            hints.add("- Force-resync active: expect DIAG force_resync / prestart drops in logcat")
        }
        if (uiState.lateDrops > PlaybackDiagnostics.HIGH_LATE_DROP_COUNT) {
            hints.add("- High late-drop count: clock skew, network loss, or catch-up trimming")
        }
        if (uiState.lastAudioCutAgeMs in 0..PlaybackDiagnostics.RECENT_AUDIO_CUT_MAX_AGE_MS) {
            hints.add("- Audio cut occurred recently (check DIAG audio_cut and serverLate)")
        }
        if (uiState.serverLatenessMs > PlaybackDiagnostics.AUDIO_CUT_SERVER_LATE_MS) {
            hints.add("- Server lateness above cut threshold (${uiState.serverLatenessMs}ms)")
        }
        if (hints.isEmpty()) {
            hints.add("- No automatic hints; review DIAG lines and buffer/clock sections above")
        }
        return hints.joinToString("\n")
    }

    /**
     * Upload [report] to Sentry as a WARNING-level event.
     */
    fun uploadToSentry(
        report: String,
        uiState: PlayerViewModel.UiState? = null,
        context: Context? = null,
    ): String? {
        if (!CrashReportingManager.isCrashReportingAvailable()) return null
        return try {
            val event =
                SentryEvent().apply {
                    level = SentryLevel.WARNING
                    message =
                        io.sentry.protocol.Message().apply {
                            message = "Audio issue report"
                        }
                    setExtra("audio_report_tail", lastLines(report, 50))
                    setExtra("app_version", BuildConfig.VERSION_NAME)
                    setExtra("android_version", Build.VERSION.RELEASE)
                    setExtra("device", "${Build.MANUFACTURER} ${Build.MODEL}")

                    context?.let { ctx ->
                        setSentryNetworkExtras(this, ctx)
                    }

                    uiState?.let { state ->
                        setSentryDiagnosticsExtras(this, state)
                    }
                }
            val hint = Hint()
            hint.addAttachment(
                Attachment(
                    report.toByteArray(Charsets.UTF_8),
                    "audio_report.txt",
                    "text/plain",
                ),
            )
            val id: SentryId = Sentry.captureEvent(event, hint)
            val idStr = id.toString()
            if (idStr == SentryId.EMPTY_ID.toString()) null else idStr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload audio issue to Sentry: ${e.message}")
            null
        }
    }

    private class WifiDetails(
        val frequency: Int,
        val linkSpeed: Int,
        val rssi: Int,
    )

    private fun getNetworkBandwidthInfo(context: Context): Pair<Double, Double>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        var result: Pair<Double, Double>? = null
        try {
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (capabilities != null) {
                val downSpeed = capabilities.linkDownstreamBandwidthKbps / 1000.0
                val upSpeed = capabilities.linkUpstreamBandwidthKbps / 1000.0
                result = Pair(downSpeed, upSpeed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network bandwidth info", e)
        }
        return result
    }

    private fun getWifiInfo(context: Context): WifiDetails? {
        var result: WifiDetails? = null
        try {
            val wm =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager

            @Suppress("DEPRECATION")
            val wifiInfo = wm?.connectionInfo
            if (wifiInfo != null) {
                result =
                    WifiDetails(
                        frequency = wifiInfo.frequency,
                        linkSpeed = wifiInfo.linkSpeed,
                        rssi = wifiInfo.rssi,
                    )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Wi-Fi info", e)
        }
        return result
    }

    private fun appendSystemAndNetworkInfo(
        sb: StringBuilder,
        uiState: PlayerViewModel.UiState,
        context: Context?,
    ) {
        sb.appendLine("=== AUDIO CONFIGURATION ===")
        sb.appendLine("Codec             : PCM")
        sb.appendLine("Static Delay      : ${uiState.staticDelayMs} ms")
        sb.appendLine("Playback Speed    : ${"%.3f".format(uiState.playbackSpeedMultiplier)}x")
        sb.appendLine("Low Memory Device : ${uiState.isLowMemoryDevice}")
        sb.appendLine("Is TV             : ${uiState.isTV}")
        sb.appendLine()

        sb.appendLine("=== CONNECTION / NETWORK ===")
        sb.appendLine("Status           : ${uiState.status}")
        sb.appendLine("Connected        : ${uiState.connected}")
        sb.appendLine("Connection Type  : ${uiState.connectionType}")
        sb.appendLine("Network Quality  : ${uiState.networkQuality}")
        sb.appendLine("Stability        : ${uiState.stability}")
        sb.appendLine("Connection Drops : ${uiState.connectionDrops}")
        sb.appendLine("Active Roles     : ${uiState.activeRoles.ifBlank { "-" }}")
        sb.appendLine()

        if (context == null) return
        sb.appendLine("=== DETAILED NETWORK ENVIRONMENT ===")
        val bandwidth = getNetworkBandwidthInfo(context)
        if (bandwidth != null) {
            sb.appendLine("Downstream Bandwidth : ${"%.2f".format(bandwidth.first)} Mbps")
            sb.appendLine("Upstream Bandwidth   : ${"%.2f".format(bandwidth.second)} Mbps")
        } else {
            sb.appendLine("Network Bandwidth    : unavailable")
        }

        val wifi = getWifiInfo(context)
        if (wifi == null) {
            sb.appendLine("Wi-Fi Environment    : unavailable")
            sb.appendLine()
            return
        }

        val freq = wifi.frequency
        if (freq > 0) {
            val band =
                when {
                    freq in BAND_2_4_MIN..BAND_2_4_MAX -> "2.4 GHz"
                    freq in BAND_5_MIN..BAND_5_MAX -> "5 GHz"
                    freq in BAND_6_MIN..BAND_6_MAX -> "6 GHz"
                    else -> "unknown band"
                }
            sb.appendLine("Wi-Fi Frequency      : $freq MHz ($band)")
        }
        if (wifi.linkSpeed > 0) {
            sb.appendLine("Wi-Fi Link Speed     : ${wifi.linkSpeed} Mbps")
        }
        sb.appendLine("Wi-Fi Signal (RSSI)  : ${wifi.rssi} dBm")
        sb.appendLine()
    }

    private fun appendPipelineAndTimingDiagnostics(
        sb: StringBuilder,
        uiState: PlayerViewModel.UiState,
    ) {
        sb.appendLine("=== AUDIO PIPELINE STATISTICS ===")
        sb.appendLine("Playback State    : ${uiState.playbackState.ifBlank { "-" }}")
        sb.appendLine("Stream Format     : ${uiState.streamDesc.ifBlank { "-" }}")
        sb.appendLine("Smoothed Latency  : ${"%.1f".format(uiState.smoothedLatencyMs)} ms")
        sb.appendLine("Offset Uncertainty : ±${"%.3f".format(uiState.offsetUncertaintyUs / 1000.0)} ms")
        sb.appendLine("Drift             : ${"%.3f".format(uiState.driftPpm)} ppm")
        sb.appendLine("Drift Uncertainty : ${"%.3f".format(uiState.driftUncertaintyPpm)} ppm")
        sb.appendLine("Drift SNR         : ${"%.2f".format(uiState.driftSnr)}")
        sb.appendLine("RTT               : ${"%.2f".format(uiState.rttUs / 1000.0)} ms")
        sb.appendLine("Queued Chunks     : ${uiState.queuedChunks}")
        sb.appendLine("Buffer Ahead      : ${uiState.bufferAheadMs} ms")
        sb.appendLine("Late Drops        : ${uiState.lateDrops}")
        sb.appendLine("Audible Syncs     : ${uiState.audibleSyncCount}")
        sb.appendLine("Kalman Errors     : ${uiState.kalmanErrorCount}")
        sb.appendLine()

        sb.appendLine("=== PLAYBACK RECOVERY ===")
        sb.appendLine("Recovery Status   : ${uiState.playbackRecoveryStatus.ifBlank { "-" }}")
        sb.appendLine("Audio Output On   : ${uiState.audioOutputStarted}")
        sb.appendLine("Clock Ready       : ${uiState.clockReadyForPlayback}")
        sb.appendLine("Force Resync      : ${uiState.forceResyncActive}")
        sb.appendLine("Discontinuity     : ${uiState.inDiscontinuityRecovery}")
        sb.appendLine("Late Start Loops  : ${uiState.lateRestartLoops}")
        sb.appendLine("Server Lateness   : ${uiState.serverLatenessMs} ms")
        sb.appendLine(
            "Last Audio Cut    : ${
                if (uiState.lastAudioCutAgeMs < 0) {
                    "never"
                } else {
                    "${uiState.lastAudioCutAgeMs} ms ago"
                }
            }",
        )
        sb.appendLine("Last Event        : ${uiState.lastRecoveryEvent.ifBlank { "-" }}")
        sb.appendLine()

        sb.appendLine("=== PLAYOUT TIMING ===")
        sb.appendLine("Effective Ahead   : ${uiState.effectiveBufferAheadMs} ms")
        sb.appendLine("Est. Offset       : ${uiState.estimatedOffsetMs} ms")
        sb.appendLine("Playout Offset    : ${uiState.playoutOffsetMs} ms")
        sb.appendLine("Network Jitter    : ${uiState.networkJitterMs} ms")
        sb.appendLine("Clock Updates     : ${uiState.clockUpdateCount}")
        sb.appendLine()
    }

    private fun setSentryNetworkExtras(
        event: SentryEvent,
        context: Context,
    ) {
        val bandwidth = getNetworkBandwidthInfo(context)
        if (bandwidth != null) {
            event.setExtra(
                "net_downstream_bandwidth_kbps",
                (bandwidth.first * 1000.0).toInt().toString(),
            )
            event.setExtra(
                "net_upstream_bandwidth_kbps",
                (bandwidth.second * 1000.0).toInt().toString(),
            )
        }

        val wifi = getWifiInfo(context)
        if (wifi != null) {
            if (wifi.frequency > 0) {
                event.setExtra("wifi_frequency_mhz", wifi.frequency.toString())
            }
            if (wifi.linkSpeed > 0) {
                event.setExtra("wifi_link_speed_mbps", wifi.linkSpeed.toString())
            }
            event.setExtra("wifi_rssi_dbm", wifi.rssi.toString())
        }
    }

    private fun setSentryDiagnosticsExtras(
        event: SentryEvent,
        state: PlayerViewModel.UiState,
    ) {
        // Pipeline Status
        event.setExtra("playback_state", state.playbackState)
        event.setExtra("playback_recovery_status", state.playbackRecoveryStatus)
        event.setExtra("last_recovery_event", state.lastRecoveryEvent)
        event.setExtra("clock_ready", state.clockReadyForPlayback.toString())
        event.setExtra("audio_output_started", state.audioOutputStarted.toString())
        event.setExtra("in_discontinuity_recovery", state.inDiscontinuityRecovery.toString())
        event.setExtra("force_resync_active", state.forceResyncActive.toString())

        // Individual Measurements
        event.setExtra("server_lateness_ms", state.serverLatenessMs.toString())
        event.setExtra("queued_chunks", state.queuedChunks.toString())
        event.setExtra("buffer_ahead_ms", state.bufferAheadMs.toString())
        event.setExtra("effective_buffer_ahead_ms", state.effectiveBufferAheadMs.toString())
        event.setExtra("smoothed_latency_ms", state.smoothedLatencyMs.toString())
        event.setExtra("offset_uncertainty_ms", (state.offsetUncertaintyUs / 1000.0).toString())
        event.setExtra("estimated_offset_ms", state.estimatedOffsetMs.toString())
        event.setExtra("playout_offset_ms", state.playoutOffsetMs.toString())
        event.setExtra("rtt_ms", (state.rttUs / 1000.0).toString())
        event.setExtra("network_jitter_ms", state.networkJitterMs.toString())
        event.setExtra("drift_ppm", state.driftPpm.toString())
        event.setExtra("drift_uncertainty_ppm", state.driftUncertaintyPpm.toString())
        event.setExtra("drift_snr", state.driftSnr.toString())

        // Statistics and health counters
        event.setExtra("late_drops", state.lateDrops.toString())
        event.setExtra("audible_syncs", state.audibleSyncCount.toString())
        event.setExtra("kalman_errors", state.kalmanErrorCount.toString())
        event.setExtra("clock_updates", state.clockUpdateCount.toString())
        event.setExtra("late_restart_loops", state.lateRestartLoops.toString())
        event.setExtra("connection_drops", state.connectionDrops.toString())
    }

    /**
     * Write [report] to a private storage file and return a shareable content [Uri].
     */
    fun saveReportToFile(
        context: Context,
        report: String,
    ): Uri? {
        return try {
            val file = File(context.filesDir, REPORT_FILE)
            file.writeText(report)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio report: ${e.message}")
            null
        }
    }
}
