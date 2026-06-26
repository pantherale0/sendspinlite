package com.sendspinlite.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.sendspinlite.diagnostics.CachedDiagnosticLabels
import com.sendspinlite.diagnostics.DiagnosticsDelta
import com.sendspinlite.playback.PcmAudioOutput
import com.sendspinlite.playback.PlaybackDiagnostics
import com.sendspinlite.playback.PlaybackHealthRules
import com.sendspinlite.system.AppMemoryPolicy
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

    private val leanDevice = AppMemoryPolicy.isLeanDevice(context)

    private val _events =
        MutableSharedFlow<ClientEvent>(
            extraBufferCapacity = AppMemoryPolicy.eventBufferCapacity(leanDevice),
        )
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    /** Wired by the service to drive the WiFi high-performance lock. */
    @Volatile
    var onRequestHighPerformance: () -> Unit = {}

    @Volatile
    var onReleaseHighPerformance: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val output =
        PcmAudioOutput().also {
            it.setLeanMode(leanDevice)
        }

    private val diagnosticsIntervalMs = AppMemoryPolicy.diagnosticsIntervalMs(leanDevice)
    private val healthCheckIntervalMs = AppMemoryPolicy.healthCheckIntervalMs(leanDevice)
    private val cachedLabels = CachedDiagnosticLabels(context, tag)

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

    @Volatile
    private var lastDiagnosticsUpdateMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastSuccessfulAudioWriteMs: Long = 0L

    @Volatile
    private var linkDegraded: Boolean = false

    @Volatile
    private var lastStreamSampleRate: Int = 0

    @Volatile
    private var lastStreamChannels: Int = 0

    @Volatile
    private var lastStreamBitDepth: Int = 0

    private val starvationReported = AtomicBoolean(false)

    private var feedbackJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var healthJob: Job? = null

    init {
        updateDiagnostics {
            it.copy(
                hasMetadata = true,
                activeRoles = ACTIVE_ROLES,
                isLowMemoryDevice = leanDevice,
                connectionType = SendspinSystemUtils.getConnectionType(context, tag),
            )
        }
    }

    private fun ensureNativeHandleLocked(): Boolean {
        if (handle != 0L) return true
        handle =
            SendspinNative.nativeCreate(
                callbacks = this,
                clientId = clientId,
                name = clientName,
                productName = PRODUCT_NAME,
                manufacturer = Build.MANUFACTURER ?: "Android",
                softwareVersion = SOFTWARE_VERSION,
                fixedDelayUs = BASELINE_FIXED_DELAY_US,
                audioBufferCapacity = AppMemoryPolicy.nativeRingBufferBytes(leanDevice),
                initialStaticDelayMs = 0,
            )
        if (handle == 0L) {
            Log.e(tag, "nativeCreate failed")
            return false
        }
        Log.i(
            tag,
            "Native client created (lean=$leanDevice, ringBuffer=" +
                "${AppMemoryPolicy.nativeRingBufferBytes(leanDevice)} bytes)",
        )
        return true
    }

    suspend fun connect() {
        status = "connecting"
        updateDiagnostics { it.copy(status = status) }

        val connected =
            withContext(Dispatchers.IO) {
                synchronized(handleLock) {
                    if (!ensureNativeHandleLocked()) {
                        return@withContext false
                    }
                    SendspinNative.nativeStart(handle)
                    SendspinNative.nativeConnect(handle, wsUrl)
                    true
                }
            }
        if (!connected || handle == 0L || !kotlin.coroutines.coroutineContext.isActive) return

        started.set(true)
        starvationReported.set(false)
        lastSuccessfulAudioWriteMs = System.currentTimeMillis()
        startFeedbackLoop()
        startDiagnosticsLoop()
        startHealthMonitor()
    }

    fun close(reason: String) {
        started.set(false)
        feedbackJob?.cancel()
        diagnosticsJob?.cancel()
        healthJob?.cancel()
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
        started.set(false)
        feedbackJob?.cancel()
        feedbackJob = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        healthJob?.cancel()
        healthJob = null
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

    // Release Kotlin-visible footprint on memory pressure. Native ring buffer size is fixed at
    // create time; lean devices use a smaller capacity via [AppMemoryPolicy].
    fun trimAudioBufferCritical() {
        Log.w(tag, "trimAudioBufferCritical: reducing playback footprint")
        val queueMs = if (output.isStarted()) output.getOutputQueueMs() else 0L
        if (output.isStarted() && queueMs < 100L) {
            output.pause()
        }
        trimDiagnosticsFootprint(aggressive = true)
    }

    fun trimAudioBufferModerate() {
        Log.i(tag, "trimAudioBufferModerate: clearing non-essential diagnostics")
        trimDiagnosticsFootprint(aggressive = false)
    }

    fun trimAudioBufferLow() {
        trimDiagnosticsFootprint(aggressive = false)
    }

    private fun trimDiagnosticsFootprint(aggressive: Boolean) {
        updateDiagnostics {
            it.copy(
                artworkBitmap = null,
                artworkUrl = null,
                lastRecoveryEvent = if (aggressive) "" else it.lastRecoveryEvent,
            )
        }
    }

    /** Called when the OS signals the active network link is degrading (e.g. Wi-Fi roam). */
    fun notifyLinkDegrading(maxMsToLive: Int) {
        linkDegraded = true
        Log.w(tag, "Network link degrading (maxMsToLive=${maxMsToLive}ms); tightening starvation thresholds")
    }

    fun clearLinkDegraded() {
        if (linkDegraded) {
            linkDegraded = false
            Log.i(tag, "Network link stable; restoring normal starvation thresholds")
        }
    }

    fun isHealthy(): Boolean {
        if (!started.get()) return true
        val elapsed = System.currentTimeMillis() - lastDiagnosticsUpdateMs
        return elapsed < 10000L
    }

    /**
     * Runs [block] with the native handle while holding [handleLock].
     * Returns null when the handle has been destroyed.
     */
    private inline fun <T> withHandle(block: (Long) -> T): T? {
        synchronized(handleLock) {
            val h = handle
            if (h == 0L) return null
            return block(h)
        }
    }

    // ========================================================================
    // Feedback + diagnostics loops
    // ========================================================================

    private fun startFeedbackLoop() {
        feedbackJob?.cancel()
        feedbackJob =
            scope.launch {
                while (isActive && started.get()) {
                    if (output.isStarted()) {
                        val nowUs = SendspinNative.nativeMonotonicTimeUs()
                        val progress = output.getPlaybackProgress(nowUs)
                        if (progress != null && progress.framesPlayed > 0) {
                            withHandle { h ->
                                if (started.get()) {
                                    SendspinNative.nativeNotifyAudioPlayed(
                                        h,
                                        progress.framesPlayed.toInt(),
                                        progress.finishTimestampUs,
                                    )
                                }
                            }
                        }
                    }
                    delay(FEEDBACK_INTERVAL_MS)
                }
            }
    }

    private fun startDiagnosticsLoop() {
        diagnosticsJob?.cancel()
        diagnosticsJob =
            scope.launch {
                while (isActive && started.get()) {
                    val snapshot =
                        withHandle { h ->
                            if (!started.get()) return@withHandle null
                            DiagnosticsSnapshot(
                                connected = SendspinNative.nativeIsConnected(h),
                                timeSynced = SendspinNative.nativeIsTimeSynced(h),
                                progressMs = SendspinNative.nativeGetTrackProgressMs(h),
                                durationMs = SendspinNative.nativeGetTrackDurationMs(h),
                                staticDelayMs = SendspinNative.nativeGetStaticDelayMs(h).toLong(),
                            )
                        }
                    if (snapshot != null) {
                        publishDiagnostics(snapshot)
                    }
                    delay(diagnosticsIntervalMs)
                }
            }
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob =
            scope.launch {
                while (isActive && started.get()) {
                    checkPlaybackHealth()
                    delay(healthCheckIntervalMs)
                }
            }
    }

    private fun checkPlaybackHealth() {
        val diag = _diagnostics.value
        if (diag.playbackState != "playing") {
            starvationReported.set(false)
            return
        }

        maybeRecoverAudioTrack()

        if (!diag.audioOutputStarted) return

        val now = System.currentTimeMillis()
        val msSinceWrite =
            if (lastSuccessfulAudioWriteMs > 0L) {
                now - lastSuccessfulAudioWriteMs
            } else {
                -1L
            }
        val outputQueueMs = output.getOutputQueueMs()

        if (
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = diag.playbackState,
                audioOutputStarted = diag.audioOutputStarted,
                connected = diag.connected,
                msSinceLastWrite = msSinceWrite,
                outputQueueMs = outputQueueMs,
                linkDegraded = linkDegraded,
            )
        ) {
            if (starvationReported.compareAndSet(false, true)) {
                Log.w(
                    tag,
                    "Playback starvation detected: msSinceWrite=$msSinceWrite queueMs=$outputQueueMs " +
                        "connected=${diag.connected} linkDegraded=$linkDegraded",
                )
                updateDiagnostics {
                    it.copy(
                        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_STARVATION_RECONNECT,
                        lastRecoveryEvent =
                            "starvation msSinceWrite=$msSinceWrite queueMs=$outputQueueMs " +
                                "connected=${diag.connected}",
                    )
                }
                _events.tryEmit(ClientEvent.PlaybackStarvation(msSinceWrite, outputQueueMs))
            }
        } else if (msSinceWrite in 0 until PlaybackDiagnostics.STARVATION_NO_WRITE_MS) {
            starvationReported.set(false)
        }
    }

    private fun maybeRecoverAudioTrack() {
        if (lastStreamSampleRate <= 0) return
        if (output.isStarted()) return

        Log.w(
            tag,
            "AudioTrack not started during playback; recreating sr=$lastStreamSampleRate " +
                "ch=$lastStreamChannels bd=$lastStreamBitDepth",
        )
        output.start(lastStreamSampleRate, lastStreamChannels, lastStreamBitDepth)
        output.syncPlaybackFeedbackBaseline()
        lastSuccessfulAudioWriteMs = System.currentTimeMillis()
        updateDiagnostics {
            it.copy(
                audioOutputStarted = output.isStarted(),
                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_UNDERRUN,
                lastRecoveryEvent = "audiotrack_recreate",
            )
        }
    }

    private fun publishDiagnostics(snapshot: DiagnosticsSnapshot) {
        lastDiagnosticsUpdateMs = System.currentTimeMillis()
        val outputStarted = output.isStarted()
        val outputQueueMs = if (outputStarted) output.getOutputQueueMs() else 0L
        val latencyMs = if (outputStarted) output.getSmoothedLatencyMs() else 0.0
        val queuedChunks =
            if (outputQueueMs > 0L) {
                ((outputQueueMs + CHUNK_MS - 1L) / CHUNK_MS).toInt().coerceAtLeast(1)
            } else {
                0
            }

        val current = _diagnostics.value
        val connectionType = cachedLabels.connectionType()
        val networkQuality = cachedLabels.networkQuality(current.offsetUncertaintyUs)
        val stability =
            cachedLabels.clockStability(
                timeSynced = snapshot.timeSynced,
                clockUpdateCount = current.clockUpdateCount,
                firstTimeSyncedAtMs = firstTimeSyncedAtMs,
            )
        val trackProgress =
            if (snapshot.durationMs > 0) snapshot.progressMs.toLong() else current.trackProgress
        val trackDuration =
            if (snapshot.durationMs > 0) snapshot.durationMs.toLong() else current.trackDuration

        if (
            !DiagnosticsDelta.hotPublishChanged(
                current = current,
                status = status,
                connected = snapshot.connected,
                timeSynced = snapshot.timeSynced,
                outputStarted = outputStarted,
                outputQueueMs = outputQueueMs,
                queuedChunks = queuedChunks,
                latencyMs = latencyMs,
                connectionType = connectionType,
                networkQuality = networkQuality,
                stability = stability,
                staticDelayMs = snapshot.staticDelayMs,
                trackProgress = trackProgress,
                trackDuration = trackDuration,
            )
        ) {
            return
        }

        updateDiagnostics {
            it.copy(
                status = status,
                connected = snapshot.connected,
                activeRoles = if (snapshot.connected) ACTIVE_ROLES else "",
                clockReadyForPlayback = snapshot.timeSynced,
                audioOutputStarted = outputStarted,
                smoothedLatencyMs = latencyMs,
                queuedChunks = queuedChunks,
                bufferAheadMs = outputQueueMs,
                effectiveBufferAheadMs = outputQueueMs,
                staticDelayMs = snapshot.staticDelayMs,
                connectionType = connectionType,
                networkQuality = networkQuality,
                stability = stability,
                trackProgress = trackProgress,
                trackDuration = trackDuration,
            )
        }
    }

    private data class DiagnosticsSnapshot(
        val connected: Boolean,
        val timeSynced: Boolean,
        val progressMs: Int,
        val durationMs: Int,
        val staticDelayMs: Long,
    )

    private fun updateDiagnostics(block: (ClientDiagnostics) -> ClientDiagnostics) {
        _diagnostics.update { current ->
            val next = block(current)
            if (next == current) current else next
        }
    }

    // ========================================================================
    // SendspinNativeCallbacks (from native threads)
    // ========================================================================

    override fun onAudioWrite(
        buffer: ByteBuffer,
        length: Int,
        timeoutMs: Int,
    ): Int {
        if (!output.isStarted()) {
            maybeRecoverAudioTrack()
            if (!output.isStarted()) return 0
        }
        val written = output.writePcm(buffer, length, timeoutMs)
        if (written > 0) {
            lastSuccessfulAudioWriteMs = System.currentTimeMillis()
            linkDegraded = false
            starvationReported.set(false)
        }
        return written
    }

    override fun onStreamStart(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ) {
        Log.i(tag, "Stream start sr=$sampleRate ch=$channels bd=$bitDepth")
        lastStreamSampleRate = sampleRate
        lastStreamChannels = channels
        lastStreamBitDepth = bitDepth
        lastSuccessfulAudioWriteMs = System.currentTimeMillis()
        starvationReported.set(false)
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

    override fun onGroupUpdate(
        playbackState: String?,
        groupId: String?,
        groupName: String?,
    ) {
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
                rttUs = uncertaintyUs * 2,
                clockUpdateCount = count,
                networkQuality = cachedLabels.networkQuality(uncertaintyUs),
                stability =
                    cachedLabels.clockStability(
                        timeSynced = true,
                        clockUpdateCount = count,
                        firstTimeSyncedAtMs = firstTimeSyncedAtMs,
                    ),
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

    override fun onConnectionState(
        status: String,
        connected: Boolean,
    ) {
        this.status = status
        if (!connected) {
            firstTimeSyncedAtMs = 0L
        }
        cachedLabels.invalidateConnectionType()
        updateDiagnostics {
            it.copy(
                status = status,
                connected = connected,
                activeRoles = if (connected) ACTIVE_ROLES else "",
                connectionType = if (connected) cachedLabels.connectionType() else it.connectionType,
                networkQuality =
                    if (connected) cachedLabels.networkQuality(it.offsetUncertaintyUs) else "UNKNOWN",
                stability =
                    if (connected) {
                        cachedLabels.clockStability(
                            timeSynced = it.clockReadyForPlayback,
                            clockUpdateCount = it.clockUpdateCount,
                            firstTimeSyncedAtMs = firstTimeSyncedAtMs,
                        )
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
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
        private const val FEEDBACK_INTERVAL_MS = 5L

        /** Nominal chunk duration used for output-queue → chunk count UI mapping. */
        private const val CHUNK_MS = 20L

        /** Roles compiled into the native client (player + metadata). */
        private const val ACTIVE_ROLES = "player, metadata"
    }
}
