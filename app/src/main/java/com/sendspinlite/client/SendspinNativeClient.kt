package com.sendspinlite.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.sendspinlite.playback.PcmAudioOutput
import com.sendspinlite.system.SendspinSystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drop-in replacement for the old Kotlin client, backed by the native sendspin-cpp library.
 *
 * Kotlin keeps the Android shell concerns (foreground service, AudioTrack output, diagnostics UI)
 * while sendspin-cpp owns the protocol, time synchronization and audio scheduling. This class
 * implements [SendspinNativeCallbacks] to receive PCM and protocol events from the bridge and
 * exposes the same surface the service used for the old client.
 */
class SendspinNativeClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val context: Context,
) : SendspinNativeCallbacks {

    private val tag = "SendspinNativeClient"

    private val _diagnostics = MutableStateFlow(ClientDiagnostics())
    val diagnostics: StateFlow<ClientDiagnostics> = _diagnostics.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    /** Wired by the service to drive the WiFi high-performance lock. */
    @Volatile
    var onRequestHighPerformance: () -> Unit = {}

    @Volatile
    var onReleaseHighPerformance: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val output = PcmAudioOutput()
    private val isLowMemoryDevice = SendspinSystemUtils.checkIsLowMemoryDevice(context, tag)

    // Native handle ownership. Access is guarded by handleLock; 0 means not created / destroyed.
    private val handleLock = Any()
    @Volatile
    private var handle: Long = 0L
    private val started = AtomicBoolean(false)

    @Volatile
    private var status: String = "idle"
    @Volatile
    private var pendingStaticDelayMs: Long = 0L
    @Volatile
    private var firstTimeSyncedAtMs: Long = 0L

    private var feedbackJob: Job? = null
    private var diagnosticsJob: Job? = null

    init {
        synchronized(handleLock) {
            handle = SendspinNative.nativeCreate(
                callbacks = this,
                clientId = clientId,
                name = clientName,
                productName = PRODUCT_NAME,
                manufacturer = Build.MANUFACTURER ?: "Android",
                softwareVersion = SOFTWARE_VERSION,
                fixedDelayUs = BASELINE_FIXED_DELAY_US,
                audioBufferCapacity = AUDIO_BUFFER_CAPACITY,
                initialStaticDelayMs = 0,
            )
        }
        updateDiagnostics {
            it.copy(
                hasMetadata = true,
                activeRoles = ACTIVE_ROLES,
                isLowMemoryDevice = isLowMemoryDevice,
                connectionType = SendspinSystemUtils.getConnectionType(context, tag),
            )
        }
    }

    suspend fun connect() {
        val h = handle
        if (h == 0L) {
            Log.w(tag, "connect() called after destroy")
            return
        }
        status = "connecting"
        updateDiagnostics { it.copy(status = status) }

        withContext(Dispatchers.IO) {
            SendspinNative.nativeStart(h)
            SendspinNative.nativeConnect(h, wsUrl)
        }
        started.set(true)
        startFeedbackLoop()
        startDiagnosticsLoop()
    }

    fun close(reason: String) {
        started.set(false)
        feedbackJob?.cancel()
        diagnosticsJob?.cancel()
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) {
                try {
                    SendspinNative.nativeDisconnect(h, SendspinNative.GoodbyeReason.USER_REQUEST)
                } catch (e: Exception) {
                    Log.w(tag, "Error during nativeDisconnect", e)
                }
            }
        }
        try {
            output.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping audio output", e)
        }
        status = "closed:$reason"
        firstTimeSyncedAtMs = 0L
        updateDiagnostics {
            it.copy(
                status = status,
                connected = false,
                activeRoles = "",
                networkQuality = "UNKNOWN",
                stability = "UNSTABLE",
            )
        }
    }

    fun cleanupResources() {
        close("resource_cleanup")
        synchronized(handleLock) {
            val h = handle
            handle = 0L
            if (h != 0L) {
                try {
                    SendspinNative.nativeDestroy(h)
                } catch (e: Exception) {
                    Log.w(tag, "Error during nativeDestroy", e)
                }
            }
        }
        scope.cancel()
    }

    fun setStaticDelayMs(ms: Long) {
        val clamped = ms.coerceIn(0L, 5000L)
        pendingStaticDelayMs = clamped
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) {
                SendspinNative.nativeUpdateStaticDelay(h, clamped.toInt())
            }
        }
    }

    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) SendspinNative.nativeUpdateVolume(h, clamped)
        }
        updateDiagnostics { it.copy(playerVolume = clamped, playerVolumeFromServer = false) }
    }

    fun setPlayerMute(muted: Boolean) {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) SendspinNative.nativeUpdateMuted(h, muted)
        }
        updateDiagnostics { it.copy(playerMuted = muted, playerMutedFromServer = false) }
    }

    /**
     * Retained for API parity. sendspin-cpp exposes no runtime setter for the fixed pipeline
     * delay; sync is driven by notify_audio_played feedback instead.
     */
    fun setPlayoutOffsetAdjustmentMs(ms: Long) {
        Log.i(tag, "setPlayoutOffsetAdjustmentMs($ms) ignored: native sync owns playout offset")
    }

    // The native sync task uses a fixed ring buffer and silence-insert/drop scheduling rather than
    // a Kotlin jitter buffer, so there is nothing to trim on memory pressure.
    fun trimAudioBufferCritical() = Log.i(tag, "trimAudioBufferCritical: no-op (native ring buffer)")

    fun trimAudioBufferModerate() = Log.i(tag, "trimAudioBufferModerate: no-op (native ring buffer)")

    fun trimAudioBufferLow() = Log.i(tag, "trimAudioBufferLow: no-op (native ring buffer)")

    fun isHealthy(): Boolean {
        val h = handle
        if (h == 0L) return false
        return SendspinNative.nativeIsConnected(h) && SendspinNative.nativeIsTimeSynced(h)
    }

    // ========================================================================
    // Feedback + diagnostics loops
    // ========================================================================

    private fun startFeedbackLoop() {
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            while (isActive && started.get()) {
                val h = handle
                if (h != 0L && output.isStarted()) {
                    val nowUs = SendspinNative.nativeMonotonicTimeUs()
                    val progress = output.getPlaybackProgress(nowUs)
                    if (progress != null && progress.framesPlayed > 0) {
                        SendspinNative.nativeNotifyAudioPlayed(
                            h,
                            progress.framesPlayed.toInt(),
                            progress.finishTimestampUs,
                        )
                    }
                }
                delay(FEEDBACK_INTERVAL_MS)
            }
        }
    }

    private fun startDiagnosticsLoop() {
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            while (isActive && started.get()) {
                val h = handle
                if (h != 0L) {
                    val connected = SendspinNative.nativeIsConnected(h)
                    val timeSynced = SendspinNative.nativeIsTimeSynced(h)
                    val progressMs = SendspinNative.nativeGetTrackProgressMs(h)
                    val durationMs = SendspinNative.nativeGetTrackDurationMs(h)
                    val outputStarted = output.isStarted()
                    val outputQueueMs = if (outputStarted) output.getOutputQueueMs() else 0L
                    val latencyMs = if (outputStarted) output.getSmoothedLatencyMs() else 0.0
                    // ~20 ms nominal chunk size; maps output queue depth to a chunk count for the UI.
                    val queuedChunks =
                        if (outputQueueMs > 0L) {
                            ((outputQueueMs + 19L) / 20L).toInt().coerceAtLeast(1)
                        } else {
                            0
                        }
                    updateDiagnostics {
                        it.copy(
                            status = status,
                            connected = connected,
                            activeRoles = if (connected) ACTIVE_ROLES else "",
                            clockReadyForPlayback = timeSynced,
                            audioOutputStarted = outputStarted,
                            smoothedLatencyMs = latencyMs,
                            queuedChunks = queuedChunks,
                            bufferAheadMs = outputQueueMs,
                            effectiveBufferAheadMs = outputQueueMs,
                            staticDelayMs = SendspinNative.nativeGetStaticDelayMs(h).toLong(),
                            connectionType = SendspinSystemUtils.getConnectionType(context, tag),
                            networkQuality = deriveNetworkQuality(it.offsetUncertaintyUs),
                            stability = deriveClockStability(timeSynced, it.clockUpdateCount),
                            trackProgress = if (durationMs > 0) progressMs.toLong() else it.trackProgress,
                            trackDuration = if (durationMs > 0) durationMs.toLong() else it.trackDuration,
                        )
                    }
                }
                delay(DIAGNOSTICS_INTERVAL_MS)
            }
        }
    }

    private fun updateDiagnostics(block: (ClientDiagnostics) -> ClientDiagnostics) {
        _diagnostics.update(block)
    }

    // ========================================================================
    // SendspinNativeCallbacks (from native threads)
    // ========================================================================

    override fun onAudioWrite(buffer: ByteBuffer, length: Int, timeoutMs: Int): Int {
        if (!output.isStarted()) return 0
        return output.writePcm(buffer, length, timeoutMs)
    }

    override fun onStreamStart(sampleRate: Int, channels: Int, bitDepth: Int) {
        Log.i(tag, "Stream start sr=$sampleRate ch=$channels bd=$bitDepth")
        output.start(sampleRate, channels, bitDepth)
        output.syncPlaybackFeedbackBaseline()
        updateDiagnostics {
            it.copy(
                streamDesc = "PCM ${sampleRate}Hz ${channels}ch ${bitDepth}bit",
                playbackState = "playing",
            )
        }
    }

    override fun onStreamEnd() {
        Log.i(tag, "Stream end")
        output.pause()
        updateDiagnostics { it.copy(playbackState = "stopped") }
    }

    override fun onVolumeChanged(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        _events.tryEmit(ClientEvent.ServerVolumeChanged(clamped))
        updateDiagnostics { it.copy(playerVolume = clamped, playerVolumeFromServer = true) }
    }

    override fun onMuteChanged(muted: Boolean) {
        _events.tryEmit(ClientEvent.ServerMutedChanged(muted))
        updateDiagnostics { it.copy(playerMuted = muted, playerMutedFromServer = true) }
    }

    override fun onStaticDelayChanged(delayMs: Int) {
        _events.tryEmit(ClientEvent.ServerStaticDelayChanged(delayMs.toLong()))
        updateDiagnostics { it.copy(staticDelayMs = delayMs.toLong(), staticDelayMsFromServer = true) }
    }

    override fun onMetadataUpdate(
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        artworkUrl: String?,
        year: Int,
        track: Int,
        progressMs: Int,
        durationMs: Int,
    ) {
        updateDiagnostics {
            it.copy(
                metadataTimestamp = System.currentTimeMillis(),
                trackTitle = title,
                trackArtist = artist,
                albumTitle = album,
                albumArtist = albumArtist,
                artworkUrl = artworkUrl,
                trackYear = if (year >= 0) year else null,
                trackNumber = if (track >= 0) track else null,
                trackProgress = if (progressMs >= 0) progressMs.toLong() else it.trackProgress,
                trackDuration = if (durationMs >= 0) durationMs.toLong() else it.trackDuration,
            )
        }
    }

    override fun onMetadataClear() {
        updateDiagnostics {
            it.copy(
                metadataTimestamp = null,
                trackTitle = null,
                trackArtist = null,
                albumTitle = null,
                albumArtist = null,
                artworkUrl = null,
                trackYear = null,
                trackNumber = null,
                trackProgress = null,
                trackDuration = null,
            )
        }
    }

    override fun onGroupUpdate(playbackState: String?, groupId: String?, groupName: String?) {
        updateDiagnostics {
            it.copy(
                groupName = groupName ?: it.groupName,
                playbackState = playbackState ?: it.playbackState,
            )
        }
    }

    override fun onTimeSyncUpdated(errorUs: Float) {
        val uncertaintyUs = errorUs.toLong().coerceAtLeast(0L)
        if (firstTimeSyncedAtMs == 0L && uncertaintyUs > 0L) {
            firstTimeSyncedAtMs = System.currentTimeMillis()
        }
        updateDiagnostics {
            val count = it.clockUpdateCount + 1
            it.copy(
                offsetUncertaintyUs = uncertaintyUs,
                // get_error() is offset std-dev (µs); scale for a rough RTT display estimate.
                rttUs = uncertaintyUs * 2,
                clockUpdateCount = count,
                networkQuality = deriveNetworkQuality(uncertaintyUs),
                stability = deriveClockStability(true, count),
            )
        }
    }

    override fun onRequestHighPerformance() {
        Log.i(tag, "Native requested high-performance networking")
        try {
            onRequestHighPerformance.invoke()
        } catch (e: Exception) {
            Log.w(tag, "onRequestHighPerformance handler failed", e)
        }
    }

    override fun onReleaseHighPerformance() {
        Log.i(tag, "Native released high-performance networking")
        try {
            onReleaseHighPerformance.invoke()
        } catch (e: Exception) {
            Log.w(tag, "onReleaseHighPerformance handler failed", e)
        }
    }

    override fun onConnectionState(status: String, connected: Boolean) {
        this.status = status
        if (!connected) {
            firstTimeSyncedAtMs = 0L
        } else if (firstTimeSyncedAtMs == 0L && SendspinNative.nativeIsTimeSynced(handle)) {
            firstTimeSyncedAtMs = System.currentTimeMillis()
        }
        updateDiagnostics {
            it.copy(
                status = status,
                connected = connected,
                activeRoles = if (connected) ACTIVE_ROLES else "",
                networkQuality = if (connected) deriveNetworkQuality(it.offsetUncertaintyUs) else "UNKNOWN",
                stability =
                    if (connected) {
                        deriveClockStability(SendspinNative.nativeIsTimeSynced(handle), it.clockUpdateCount)
                    } else {
                        "UNSTABLE"
                    },
            )
        }
        if (!connected) {
            try {
                output.pause()
            } catch (_: Exception) {
            }
        }
    }

    override fun isNetworkReady(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(tag, "isNetworkReady check failed; assuming ready", e)
            true
        }
    }

    companion object {
        private const val PRODUCT_NAME = "SendSpin Android"
        private const val SOFTWARE_VERSION = "1.7"
        // Platform pipeline delay is tracked via notify_audio_played feedback, not fixed_delay.
        private const val BASELINE_FIXED_DELAY_US = 0
        private const val AUDIO_BUFFER_CAPACITY = 2_000_000L
        private const val FEEDBACK_INTERVAL_MS = 5L
        private const val DIAGNOSTICS_INTERVAL_MS = 250L
        /** Roles compiled into the native client (player + metadata). */
        private const val ACTIVE_ROLES = "player, metadata"
    }

    private fun deriveNetworkQuality(offsetUncertaintyUs: Long): String =
        when {
            offsetUncertaintyUs <= 0L -> "UNKNOWN"
            offsetUncertaintyUs < 1_000L -> "GOOD"
            offsetUncertaintyUs < 5_000L -> "FAIR"
            else -> "POOR"
        }

    private fun deriveClockStability(timeSynced: Boolean, clockUpdateCount: Int): String {
        if (!timeSynced || clockUpdateCount == 0) return "UNSTABLE"
        val anchorMs = firstTimeSyncedAtMs
        if (anchorMs == 0L) return "UNSTABLE"
        val elapsedMs = System.currentTimeMillis() - anchorMs
        return when {
            elapsedMs >= 5_000L -> "STABLE"
            elapsedMs >= 1_000L -> "CONVERGING"
            else -> "UNSTABLE"
        }
    }
}
