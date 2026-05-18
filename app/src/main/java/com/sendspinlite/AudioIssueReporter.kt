package com.sendspinlite

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
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

    /**
     * Build a privacy-redacted diagnostics report focused on the audio pipeline.
     *
     * Included: Android version, device model, app version, audio configuration,
     *           audio statistics, network quality metrics, and logcat lines from
     *           audio-related tags only.
     *
     * Excluded: server URL/IP address, client name, group name, and all track
     *           metadata (title, artist, album, year, track number, artwork URL).
     */
    fun buildReport(uiState: PlayerViewModel.UiState): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()

        sb.appendLine("=== Sendspin Lite Audio Issue Report ===")
        sb.appendLine("Timestamp   : $timestamp")
        sb.appendLine("App Version : ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Android     : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("========================================")
        sb.appendLine()

        // Audio configuration (no PII — codec choice, delay and speed are pipeline settings)
        sb.appendLine("=== AUDIO CONFIGURATION ===")
        sb.appendLine("Codec             : ${if (uiState.enableOpusCodec) "Opus" else "PCM"}")
        sb.appendLine("Static Delay      : ${uiState.staticDelayMs} ms")
        sb.appendLine("Playback Speed    : ${"%.3f".format(uiState.playbackSpeedMultiplier)}x")
        sb.appendLine("Low Memory Device : ${uiState.isLowMemoryDevice}")
        sb.appendLine("Is TV             : ${uiState.isTV}")
        sb.appendLine()

        // Connection / network — type and quality are useful for audio debugging;
        // the server address, client name and group name are intentionally omitted.
        sb.appendLine("=== CONNECTION / NETWORK ===")
        sb.appendLine("Status           : ${uiState.status}")
        sb.appendLine("Connected        : ${uiState.connected}")
        sb.appendLine("Connection Type  : ${uiState.connectionType}")
        sb.appendLine("Network Quality  : ${uiState.networkQuality}")
        sb.appendLine("Stability        : ${uiState.stability}")
        sb.appendLine("Connection Drops : ${uiState.connectionDrops}")
        sb.appendLine("Active Roles     : ${uiState.activeRoles.ifBlank { "-" }}")
        sb.appendLine()

        // Audio pipeline statistics
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
        sb.appendLine("Decode Latency    : ${uiState.decodeLatencyMs} ms")
        sb.appendLine("Network Jitter    : ${uiState.networkJitterMs} ms")
        sb.appendLine("Clock Updates     : ${uiState.clockUpdateCount}")
        sb.appendLine()

        sb.appendLine("=== DIAGNOSTIC HINTS ===")
        sb.appendLine(formatDiagnosticHints(uiState))
        sb.appendLine()

        // Logcat filtered to audio-related tags only.
        // The *:S wildcard silences all other tags so only Sendspin and Android
        // audio subsystem lines are included.
        sb.appendLine("=== AUDIO LOGCAT (filtered) ===")
        try {
            val process =
                Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", "800",
                        "SendspinPcmClient:V",
                        "ClockSync:V",
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
            val logs = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            process.destroy()
            sb.appendLine(logs)
        } catch (e: Exception) {
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
                "- Clock not ready for playback (converged=${uiState.clockUpdateCount >= 15}, " +
                    "uncertainty=${uiState.offsetUncertaintyUs / 1000}ms, rtt=${uiState.rttUs / 1000}ms)",
            )
        }
        if (uiState.playbackState == "playing" && !uiState.audioOutputStarted) {
            hints.add("- Group reports playing but local AudioTrack is not started (prestart/recovery stall)")
        }
        if (uiState.queuedChunks >= 60 && uiState.bufferAheadMs < -30) {
            hints.add("- Large prestart backlog with late head (possible prestart deadlock before fix)")
        }
        if (uiState.forceResyncActive) {
            hints.add("- Force-resync active: expect DIAG force_resync / prestart drops in logcat")
        }
        if (uiState.lateDrops > 100) {
            hints.add("- High late-drop count: clock skew, network loss, or catch-up trimming")
        }
        if (uiState.lastAudioCutAgeMs in 0..10_000) {
            hints.add("- Audio cut occurred recently (check DIAG audio_cut and serverLate)")
        }
        if (uiState.serverLatenessMs > 150) {
            hints.add("- Server lateness above cut threshold (${uiState.serverLatenessMs}ms)")
        }
        if (hints.isEmpty()) {
            hints.add("- No automatic hints; review DIAG lines and buffer/clock sections above")
        }
        return hints.joinToString("\n")
    }

    /**
     * Upload [report] to Sentry as a WARNING-level event and return the Sentry
     * event ID (a UUID string) on success, or `null` if Sentry is not available /
     * not initialised / the upload fails.
     *
     * The caller is responsible for ensuring that Sentry has been initialised
     * (i.e. crash reporting is enabled) before calling this method.
     */
    fun uploadToSentry(
        report: String,
        uiState: PlayerViewModel.UiState? = null,
    ): String? {
        if (!CrashReportingManager.isCrashReportingAvailable()) return null
        return try {
            val event =
                SentryEvent().apply {
                    level = SentryLevel.WARNING
                    message = io.sentry.protocol.Message().apply { message = "Audio issue report" }
                    setExtra("audio_report_tail", lastLines(report, 50))
                    setExtra("app_version", BuildConfig.VERSION_NAME)
                    setExtra("android_version", Build.VERSION.RELEASE)
                    setExtra("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    uiState?.let { state ->
                        setExtra("playback_recovery_status", state.playbackRecoveryStatus)
                        setExtra("last_recovery_event", state.lastRecoveryEvent)
                        setExtra("clock_ready", state.clockReadyForPlayback.toString())
                        setExtra("audio_output_started", state.audioOutputStarted.toString())
                        setExtra("server_lateness_ms", state.serverLatenessMs.toString())
                        setExtra("queued_chunks", state.queuedChunks.toString())
                    }
                }
            val hint = Hint()
            hint.addAttachment(Attachment(report.toByteArray(Charsets.UTF_8), "audio_report.txt", "text/plain"))
            val id: SentryId = Sentry.captureEvent(event, hint)
            val idStr = id.toString()
            // SentryId.EMPTY_ID represents a failed / no-op capture
            if (idStr == SentryId.EMPTY_ID.toString()) null else idStr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload audio issue to Sentry: ${e.message}")
            null
        }
    }

    /**
     * Write [report] to a private storage file and return a shareable content [Uri]
     * (via [FileProvider]), or `null` on failure.
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
