package com.sendspinlite.playback

import android.content.Context
import android.util.Log
import com.sendspinlite.system.SendspinSystemUtils
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

object SendspinAudioWarmup {
    private const val PREFS_NAME = "SendspinPlayerPrefs"
    private const val KEY_AUDIO_WARMUP_LAST_VERSION_CODE = "audio_warmup_last_version_code"
    private const val KEY_AUDIO_WARMUP_BASELINE_LATENCY_US = "audio_warmup_baseline_latency_us"
    private const val KEY_AUDIO_WARMUP_BASELINE_FORMAT = "audio_warmup_baseline_format"
    private const val KEY_AUDIO_WARMUP_BASELINE_TIMESTAMP_MS = "audio_warmup_baseline_timestamp_ms"

    private val warmupInProgress = AtomicBoolean(false)

    suspend fun runIfNeeded(
        context: Context,
        output: PcmAudioOutput,
        tag: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersionCode = SendspinSystemUtils.getCurrentVersionCode(context, tag)
        val warmedVersion = prefs.getLong(KEY_AUDIO_WARMUP_LAST_VERSION_CODE, -1L)
        if (warmedVersion == currentVersionCode) return
        if (!warmupInProgress.compareAndSet(false, true)) return

        var warmupSucceeded = false
        var baselineLatencyUs = 0L
        var selectedFormatText = ""

        try {
            val selected =
                output.selectHighestNativePcmFormat(
                    context = context,
                    sampleRateCandidates = listOf(48_000, 44_100),
                    channelCandidates = listOf(2, 1),
                    bitDepthCandidates = listOf(32, 24, 16),
                ) ?: PcmAudioOutput.PcmFormat(48_000, 2, 16)

            selectedFormatText = "${selected.sampleRate}/${selected.channels}/${selected.bitDepth}"
            output.start(selected.sampleRate, selected.channels, selected.bitDepth)

            if (!output.isStarted()) {
                Log.w(tag, "Warmup skipped: output failed to start format=$selectedFormatText")
                return
            }

            val bytesPerFrame = selected.channels * (selected.bitDepth / 8)
            if (bytesPerFrame <= 0) {
                Log.w(tag, "Warmup aborted: invalid bytesPerFrame for format=$selectedFormatText")
                return
            }

            val warmupMs = 250
            val warmupFrames = (selected.sampleRate * warmupMs) / 1000
            val silence = ByteArray((warmupFrames * bytesPerFrame).coerceAtLeast(bytesPerFrame * 1024))

            // Completion requirement #1: entire silence payload must be written successfully.
            val wroteAll = output.writePcm(silence)
            if (!wroteAll) {
                Log.w(tag, "Warmup incomplete: silence write did not fully complete format=$selectedFormatText")
                return
            }

            // Completion requirement #2: latency must be measured after write.
            repeat(4) {
                delay(20)
                baselineLatencyUs = output.getEstimatedPipelineLatencyUs()
            }

            if (baselineLatencyUs <= 0L) {
                Log.w(tag, "Warmup incomplete: latency estimate unavailable format=$selectedFormatText")
                return
            }

            warmupSucceeded = true
        } catch (e: Exception) {
            Log.w(tag, "Audio warmup failed", e)
        } finally {
            try {
                output.pause()
            } catch (_: Exception) {
            }
            warmupInProgress.set(false)
        }

        if (!warmupSucceeded) return

        prefs.edit()
            .putLong(KEY_AUDIO_WARMUP_LAST_VERSION_CODE, currentVersionCode)
            .putLong(KEY_AUDIO_WARMUP_BASELINE_LATENCY_US, baselineLatencyUs)
            .putString(KEY_AUDIO_WARMUP_BASELINE_FORMAT, selectedFormatText)
            .putLong(KEY_AUDIO_WARMUP_BASELINE_TIMESTAMP_MS, System.currentTimeMillis())
            .apply()

        Log.i(
            tag,
            "Audio warmup baseline saved: version=$currentVersionCode format=$selectedFormatText latencyMs=${baselineLatencyUs / 1000.0}",
        )
    }
}
