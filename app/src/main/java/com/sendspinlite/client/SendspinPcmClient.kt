package com.sendspinlite.client

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.sendspinlite.network.PortChecker
import com.sendspinlite.playback.AudioJitterBuffer
import com.sendspinlite.playback.PcmAudioOutput
import com.sendspinlite.playback.PlaybackDiagnostics
import com.sendspinlite.playback.PlaybackSpeedController
import com.sendspinlite.playback.SendspinAudioWarmup
import com.sendspinlite.protocol.SendspinPayloadFactory
import com.sendspinlite.protocol.SendspinProtocolHandler
import com.sendspinlite.protocol.SendspinProtocolListener
import com.sendspinlite.sync.ClockSync
import com.sendspinlite.system.SendspinSystemUtils
import java.util.concurrent.atomic.AtomicBoolean

class SendspinPcmClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val context: android.content.Context,
) : SendspinProtocolListener {
    private val _diagnostics = MutableStateFlow(ClientDiagnostics())
    val diagnostics: StateFlow<ClientDiagnostics> = _diagnostics.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    private fun updateDiagnostics(block: (ClientDiagnostics) -> ClientDiagnostics) {
        _diagnostics.update(block)
    }

    private fun emitEvent(event: ClientEvent) {
        _events.tryEmit(event)
    }

    private val tag = "SendspinPcmClient"

    companion object {
        private const val MAX_CLOCK_UNCERTAINTY_FOR_START_US = 50_000L
        private const val MAX_RTT_FOR_START_US = 2_000_000L
        private const val RTT_AUDIO_CUT_INFLATE_DIVISOR = 2000L
        private const val RTT_AUDIO_CUT_INFLATE_MAX_MS = 500L

        // Playout loop scheduling constants
        private const val MIN_BUFFER_MS = -20L
        private const val MAX_START_AHEAD_MS = 150L
        private const val RESYNC_MIN_BUFFER_MS = -10L
        private const val RESYNC_MAX_START_AHEAD_MS = 90L
        private const val LATE_DROP_US = 200_000L
        private const val DROP_LATE_US = 250_000L
        private const val TARGET_LATE_US = 100_000L
        private const val RESTART_DROP_TRIGGER_AHEAD_MS = -60L
        private const val RESTART_MIN_AHEAD_MS = -30L
        private const val RESTART_MIN_QUEUED = 6
        private const val FORCE_START_AFTER_LOOPS = 80
        private const val PRESTART_BACKLOG_CHUNK_THRESHOLD = 60

        // Shared OkHttpClient to avoid leaking thread pools and connection pools on reconnect
        private val sharedOkHttp =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ws: WebSocket? = null

    private val isConnected = AtomicBoolean(false)
    private var handshakeComplete: Boolean = false

    // Detect low-memory devices to disable expensive features
    private val isLowMemoryDevice = SendspinSystemUtils.checkIsLowMemoryDevice(context, tag)

    private val clock = ClockSync()
    private val jitter = AudioJitterBuffer(clock)
    private val output: PcmAudioOutput = PcmAudioOutput()
    private val speedController = PlaybackSpeedController(output, jitter)
    private val protocolHandler = SendspinProtocolHandler(tag, this)

    private var timeLoopJob: Job? = null
    private var playoutJob: Job? = null
    private var statsJob: Job? = null
    private var speedAdjustmentJob: Job? = null
    private var watchdogJob: Job? = null

    private var codec: String = ""
    private var sampleRate: Int = 48000
    private var channels: Int = 2
    private var bitDepth: Int = 16

    // Watchdog/Health check tracking
    @Volatile
    private var lastPlaybackHeartbeatMs: Long = 0L

    @Volatile
    private var lastStatsHeartbeatMs: Long = 0L

    @Volatile
    private var lastMessageReceivedMs: Long = 0L

    private var playAtServerUs: Long = Long.MIN_VALUE

    // Pipeline delay offset (µs). Compensates for Android audio pipeline latency + codec decode latency.
    // For multi-device sync, tighter offset compensation improves inter-device synchronization.
    // -50ms balances responsiveness with reliable buffer management across varying network conditions
    // Reduced from -75ms to prevent negative buffer-ahead at startup which causes unnecessary frame drops
    @Volatile
    private var playoutOffsetUs: Long = -50_000L // Default -50ms

    // Fine-grained adjustment for network/device-specific latency variations
    // Allows per-connection tuning without requiring app restart
    @Volatile
    private var playoutOffsetAdjustmentUs: Long = 0L // Neutral by default; avoid systematic early playout

    // Static delay (µs) per Sendspin spec: compensates for external speaker/amplifier delay.
    // Range: 0-5000ms (0 = audio exits port at the server timestamp).
    // Reported in client/state as static_delay_ms; server can change it via set_static_delay command.
    // Persisted across reboots and reconnections.
    @Volatile
    private var staticDelayUs: Long = 0L

    // Track last sent error state to prevent spam
    private var lastErrorStateSent: Long = 0L
    private val errorStateThrottleMs = 1000L // Only send error state once per second

    // Track whether a stream has ended to avoid spurious error messages
    @Volatile
    private var streamEnded: Boolean = false

    // Track last chunk timestamp to detect discontinuities (skips)
    private var lastChunkServerTimestampUs: Long = Long.MIN_VALUE
    private val discontinuityThresholdUs = 500_000L // 500ms = significant skip threshold

    // Flag to track if we're in "discontinuity recovery mode" (just after skip/seek)
    // In this mode, use chunk count only for startup, ignore time-based buffer calculations
    @Volatile
    private var inDiscontinuityMode: Boolean = false
    private val discontinuityModeTimeoutMs = 1000L // Exit mode after 1 second of normal chunks

    // Throttle UI updates to prevent excessive recomposition on low-memory devices
    @Volatile
    private var lastUiUpdateUs: Long = 0L
    private val uiUpdateThrottleMs =
        if (isLowMemoryDevice) 250L else 100L // Balanced throttle: 10 updates/sec (normal), 4 updates/sec (low-mem)

    // Statistics counters
    private var audibleSyncCount: Long = 0 // Incremented when audible sync (catch-up) occurs
    private var kalmanErrorCount: Long = 0 // Incremented when Kalman filter detects anomalies
    private var audioScheduleDebugCount = 0 // Debug counter for audio scheduling logs
    private var lastRestartCatchupLogMs: Long = 0L

    // Loop setup state variables
    private var lateRestartLoops: Int = 0
    private var restartBackoffMs: Long = 200L
    private var nextStartAttemptUs: Long = 0L

    // When Android audioserver dies/restarts, force a short "snap-to-live" phase
    // to avoid staying 100-300ms behind other clients after local output recovery.
    @Volatile
    private var forceResyncMode: Boolean = false
    private var forceResyncUntilUs: Long = 0L
    private var lastForceResyncLogMs: Long = 0L

    @Volatile
    private var playbackRecoveryStatus: String = PlaybackDiagnostics.STATUS_IDLE

    @Volatile
    private var lastRecoveryEvent: String = ""

    @Volatile
    private var lastPublishedServerLatenessMs: Long = 0L

    // Audio cut and resync configuration for handling late drops
    // When audio falls too far behind after packet loss, cut and resync instead of relying on slow playback speed
    private val audioOutOfSyncThresholdMs = 150L // If behind by >150ms, trigger audio cut
    private val audioDropDetectionMs = 50L // If we detect drops and buffer drops below this, prepare for cut
    private val audioResyncTargetMs = 80L // Target buffer depth after resync
    private var lastAudioCutMs: Long = 0L // Track when we last cut audio to avoid thrashing

    // Startup protection: avoid aggressive cuts immediately after output start.
    @Volatile
    private var outputStartedAtUs: Long = 0L
    private val startupCutGraceUs = 2_500_000L

    fun getAudibleSyncCount(): Long = audibleSyncCount

    fun getKalmanErrorCount(): Long = kalmanErrorCount

    /**
     * Set the static delay in milliseconds (Sendspin spec: static_delay_ms).
     * This compensates for external device delay (speakers, amplifiers).
     * Range: 0-5000ms. Persisted across reboots and reconnections.
     */
    fun setStaticDelayMs(ms: Long) {
        val clamped = ms.coerceIn(0L, 5000L)
        staticDelayUs = clamped * 1000L
        Log.i(tag, "staticDelay=${clamped}ms")
    }

    /**
     * Set per-device playout offset adjustment for fine-grained sync tuning.
     * Use this to compensate for device-specific latency variations when multiple
     * devices are synchronized. Typical values: -20ms to +20ms.
     *
     * @param ms Adjustment in milliseconds (clamped to ±100ms)
     */
    fun setPlayoutOffsetAdjustmentMs(ms: Long) {
        val clamped = ms.coerceIn(-100L, 100L)
        playoutOffsetAdjustmentUs = clamped * 1000L
        Log.i(tag, "playoutOffsetAdjustment=${clamped}ms (for multi-device sync tuning)")
    }
    suspend fun connect() {
        val req = Request.Builder().url(wsUrl).build()

        // Initialize UI with actual system volume before connecting
        val actualVolume = SendspinSystemUtils.getActualSystemVolume(context, tag)
        updateDiagnostics {
            it.copy(
                status = "connecting...",
                connected = false,
                groupVolume = actualVolume,
            )
        }

        // Extract host and port from URL for port checking
        try {
            val url = java.net.URL(wsUrl)
            val host = url.host ?: "localhost"
            val port =
                if (url.port == -1) {
                    if (wsUrl.startsWith("wss://")) 443 else 80
                } else {
                    url.port
                }

            // Check if port is open before attempting WebSocket connection
            Log.i(tag, "Performing pre-connection port check on $host:$port")
            val portCheckResult = PortChecker.checkPort(host, port)

            when (portCheckResult) {
                is PortChecker.PortCheckResult.PortOpen -> {
                    Log.i(tag, "Port check passed, proceeding with WebSocket connection")
                    updateDiagnostics { it.copy(status = "port_open") }
                }

                is PortChecker.PortCheckResult.PortClosed -> {
                    Log.w(tag, "Port is closed - server may be offline or component upgrading")
                    updateDiagnostics {
                        it.copy(
                            status = "failure: port_closed (server upgrading?)",
                            connected = false,
                        )
                    }
                    teardown("port_closed")
                    return
                }

                is PortChecker.PortCheckResult.ServerUnreachable -> {
                    Log.e(tag, "Server unreachable: ${portCheckResult.error}")
                    updateDiagnostics {
                        it.copy(
                            status = "failure: server_unreachable",
                            connected = false,
                        )
                    }
                    teardown("server_unreachable")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error during port check, proceeding with connection anyway: ${e.message}")
        }
        output.checkAudioCapabilities(context)
        ws =
            sharedOkHttp.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        Log.i(tag, "WS open")
                        isConnected.set(true)
                        handshakeComplete = false
                        lastMessageReceivedMs = System.currentTimeMillis()
                        updateDiagnostics { it.copy(status = "ws_open", connected = true) }
                        sendClientHello()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        lastMessageReceivedMs = System.currentTimeMillis()
                        protocolHandler.handleText(text)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) = handleBinary(bytes.toByteArray())

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.w(tag, "WS closed code=$code reason=$reason")
                        teardown("closed: $reason")
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.e(tag, "WS failure: ${t.message}", t)
                        teardown("failure: ${t.message}")
                    }
                },
            )
    }

    fun close(reason: String) {
        try {
            sendClientGoodbye(reason)
        } catch (_: Throwable) {
        }
        ws?.close(1000, reason)
        teardown("client_close:$reason")
    }

    private fun teardown(status: String) {
        isConnected.set(false)
        handshakeComplete = false
        lastMessageReceivedMs = 0L

        // Stop the playback loop FIRST to prevent further writes to audio buffer
        playoutJob?.cancel()
        playoutJob = null

        timeLoopJob?.cancel()
        timeLoopJob = null
        statsJob?.cancel()
        statsJob = null
        speedAdjustmentJob?.cancel()
        speedAdjustmentJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        // Stop audio output - this prevents buffered data from continuing to play
        output.stop()
        jitter.clear()
        playAtServerUs = Long.MIN_VALUE
        lastChunkServerTimestampUs = Long.MIN_VALUE
        inDiscontinuityMode = false
        audioScheduleDebugCount = 0
        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE
        lastRecoveryEvent = ""
        lastPublishedServerLatenessMs = 0L
        forceResyncMode = false
        forceResyncUntilUs = 0L

        // Reset loop control properties
        lateRestartLoops = 0
        restartBackoffMs = 200L
        nextStartAttemptUs = 0L
        speedController.reset()

        publishTeardownUi(status)
    }

    private fun publishTeardownUi(status: String) {
        updateDiagnostics {
            it.copy(
                status = status,
                connected = false,
                activeRoles = "",
                streamDesc = "",
                playbackState = "",
                groupName = "",
                queuedChunks = 0,
                bufferAheadMs = 0,
                audioOutputStarted = false,
                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE,
                lastRecoveryEvent = "",
                clockReadyForPlayback = false,
                forceResyncActive = false,
                inDiscontinuityRecovery = false,
                lateRestartLoops = 0,
                effectiveBufferAheadMs = 0,
                serverLatenessMs = 0,
                lastAudioCutAgeMs = -1L,
                trackTitle = null,
                trackArtist = null,
                albumTitle = null,
                albumArtist = null,
                trackYear = null,
                trackNumber = null,
                trackProgress = null,
                trackDuration = null,
                playbackSpeed = null,
                repeatMode = null,
                shuffleEnabled = null,
            )
        }
    }

    fun cleanupResources() {
        try {
            close("resource_cleanup")
        } catch (e: Exception) {
            Log.w(tag, "Error during close", e)
        }

        // Block briefly to ensure playback job is fully cancelled and audio is stopped
        // This prevents buffered audio from playing after the service is destroyed
        try {
            scope.cancel()
            Thread.sleep(50) // Give audio subsystem time to stop and flush
        } catch (e: Exception) {
            Log.w(tag, "Error during cleanup/sleep", e)
        }
        Log.i(tag, "Resources cleaned up")
    }
    private fun recordRecoveryEvent(
        event: String,
        details: String,
    ) {
        val entry =
            if (details.isBlank()) {
                event
            } else {
                "$event | $details"
            }
        lastRecoveryEvent = entry.take(PlaybackDiagnostics.MAX_RECOVERY_EVENT_CHARS)
        Log.i(tag, "DIAG: $entry")
    }

    private fun isClockReadyForPlayback(): Boolean =
        clock.hasConverged() &&
            clock.getOffsetUncertaintyUs() <= MAX_CLOCK_UNCERTAINTY_FOR_START_US &&
            clock.getAverageRttUs() <= MAX_RTT_FOR_START_US

    private fun publishPlaybackDiagnostics(
        snapshot: AudioJitterBuffer.Snapshot,
        recoveryStatus: String,
        clockReady: Boolean,
        lateRestartLoops: Int = 0,
        serverLatenessMs: Long? = null,
    ) {
        playbackRecoveryStatus = recoveryStatus
        val effectiveAheadMs = snapshot.bufferAheadMs + (playoutOffsetUs / 1000L)
        val cutAgeMs =
            if (lastAudioCutMs <= 0L) {
                -1L
            } else {
                (System.currentTimeMillis() - lastAudioCutMs).coerceAtLeast(0L)
            }
        val latenessMs =
            serverLatenessMs ?: lastPublishedServerLatenessMs
        if (serverLatenessMs != null) {
            lastPublishedServerLatenessMs = serverLatenessMs
        }
        throttledUiUpdate {
            it.copy(
                audioOutputStarted = output.isStarted(),
                playbackRecoveryStatus = recoveryStatus,
                lastRecoveryEvent = lastRecoveryEvent,
                clockReadyForPlayback = clockReady,
                forceResyncActive = forceResyncMode,
                inDiscontinuityRecovery = inDiscontinuityMode,
                lateRestartLoops = lateRestartLoops,
                effectiveBufferAheadMs = effectiveAheadMs,
                estimatedOffsetMs = clock.estimatedOffsetUs() / 1000L,
                playoutOffsetMs = (playoutOffsetUs + playoutOffsetAdjustmentUs) / 1000L,
                networkJitterMs = clock.getEstimatedNetworkJitterUs() / 1000L,
                clockUpdateCount = clock.getUpdateCount(),
                driftUncertaintyPpm = clock.getDriftUncertaintyPpm(),
                driftSnr = clock.getDriftSnr(),
                serverLatenessMs = latenessMs,
                lastAudioCutAgeMs = cutAgeMs,
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
                lateDrops = snapshot.lateDrops,
                offsetUncertaintyUs = clock.getOffsetUncertaintyUs(),
                driftPpm = clock.estimatedDriftPpm(),
                rttUs = clock.getAverageRttUs(),
                audibleSyncCount = audibleSyncCount,
                kalmanErrorCount = clock.getKalmanErrorCount(),
                networkQuality = clock.getNetworkConditionQuality().toString(),
                stability = clock.getClockStability().toString(),
                connectionType = SendspinSystemUtils.getConnectionType(context, tag),
                playbackSpeedMultiplier = output.getCurrentPlaybackSpeed(),
                smoothedLatencyMs = output.getSmoothedLatencyMs(),
            )
        }
    }

    private fun throttledUiUpdate(block: (ClientDiagnostics) -> ClientDiagnostics) {
        val now = nowUs()
        if (now - lastUiUpdateUs >= uiUpdateThrottleMs * 1000L) {
            lastUiUpdateUs = now
            updateDiagnostics(block)
        }
    }

    private fun sendJson(
        type: String,
        payload: JSONObject,
    ) {
        val obj = JSONObject().put("type", type).put("payload", payload)
        val json = obj.toString()
        if (type != "client/time") {
            Log.i(
                tag,
                ">>>> SEND: $type | ${json.take(200)}${if (json.length > 200) "..." else ""}",
            )
        }
        ws?.send(json)
    }

    private fun sendClientHello() {
        val hello = SendspinPayloadFactory.buildHello(
            clientId = clientId,
            clientName = clientName,
            model = android.os.Build.MODEL,
            manufacturer = android.os.Build.MANUFACTURER,
            versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        )
        sendJson("client/hello", hello)
        updateDiagnostics { it.copy(status = "sent client/hello") }
    }

    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        Log.i(tag, "setPlayerVolume: $clamped (local control)")
        sendClientStatePlayer(volume = clamped, muted = null)

        // Update local diagnostics immediately
        updateDiagnostics { it.copy(playerVolume = clamped, playerVolumeFromServer = false) }
    }

    fun setPlayerMute(muted: Boolean) {
        Log.i(tag, "setPlayerMute: $muted (local control)")
        sendClientStatePlayer(volume = null, muted = muted)

        // Update local diagnostics immediately
        updateDiagnostics { it.copy(playerMuted = muted, playerMutedFromServer = false) }
    }

    private fun sendClientStatePlayer(
        volume: Int? = null,
        muted: Boolean? = null,
        staticDelayMs: Long? = null,
    ) {
        val payload = SendspinPayloadFactory.buildStatePlayer(volume, muted, staticDelayMs)
        sendJson("client/state", payload)
    }

    private fun sendClientGoodbye(reason: String) {
        val payload = SendspinPayloadFactory.buildGoodbye(reason)
        sendJson("client/goodbye", payload)
    }

    private fun sendClientStateSynchronized(
        volume: Int = SendspinSystemUtils.getActualSystemVolume(context, tag),
        muted: Boolean = false,
    ) {
        val currentStaticDelayMs = staticDelayUs / 1000L
        val payload = SendspinPayloadFactory.buildStateSynchronized(volume, muted, currentStaticDelayMs)
        sendJson("client/state", payload)
    }

    private fun sendClientStateError(
        volume: Int = SendspinSystemUtils.getActualSystemVolume(context, tag),
        muted: Boolean = false,
    ) {
        // Don't send error state after stream has ended
        if (streamEnded) {
            return
        }

        // Throttle error state messages to prevent spam
        val now = System.currentTimeMillis()
        if (now - lastErrorStateSent < errorStateThrottleMs) {
            return
        }
        lastErrorStateSent = now

        val currentStaticDelayMs = staticDelayUs / 1000L
        val payload = SendspinPayloadFactory.buildStateError(volume, muted, currentStaticDelayMs)
        sendJson("client/state", payload)
    }

    private fun startTimeSyncLoop() {
        timeLoopJob?.cancel()
        timeLoopJob =
            scope.launch {
                var lastFrequencyLogMs: Long = 0

                while (isActive && isConnected.get()) {
                    sendJson("client/time", JSONObject().put("client_transmitted", nowUs()))

                    // ADAPTIVE FREQUENCY based on network conditions and clock stability
                    // For multi-device sync, use more aggressive initial sync frequency
                    val nextIntervalMs = clock.getRecommendedSyncFrequencyMs()

                    // Log frequency changes for diagnostics
                    if (nextIntervalMs != lastFrequencyLogMs) {
                        val networkQuality = clock.getNetworkConditionQuality()
                        val clockStability = clock.getClockStability()
                        Log.i(
                            tag,
                            "client/time frequency: ${lastFrequencyLogMs}ms → ${nextIntervalMs}ms " +
                                "network=$networkQuality stability=$clockStability " +
                                "offset=${clock.estimatedOffsetUs() / 1000L}ms drift=${String.format("%.3f", clock.estimatedDriftPpm())}ppm",
                        )
                        lastFrequencyLogMs = nextIntervalMs
                    }

                    delay(nextIntervalMs)
                }
            }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob =
            scope.launch {
                while (isActive && isConnected.get()) {
                    val snapshot = jitter.snapshot()

                    // Signal that stats loop is alive
                    lastStatsHeartbeatMs = System.currentTimeMillis()

                    publishPlaybackDiagnostics(
                        snapshot = snapshot,
                        recoveryStatus = playbackRecoveryStatus,
                        clockReady = isClockReadyForPlayback(),
                    )

                    // Detailed logging for multi-device sync diagnostics
                    if (!isLowMemoryDevice && System.currentTimeMillis() % 9000 < 100) {
                        val offsetMs = clock.estimatedOffsetUs() / 1000.0
                        val driftPpm = clock.estimatedDriftPpm()
                        val driftUncertaintyPpm = clock.getDriftUncertaintyPpm()

                        Log.i(
                            tag,
                            "sync: offset=${String.format("%.2f", offsetMs)}ms " +
                                "drift=${String.format("%.3f", driftPpm)}ppm±${String.format("%.3f", driftUncertaintyPpm)} " +
                                "jitter=${String.format("%.0f", clock.getEstimatedNetworkJitterUs() / 1000.0)}ms " +
                                "playoutAdj=${playoutOffsetAdjustmentUs / 1000}ms " +
                                "queued=${snapshot.queuedChunks} ahead=${snapshot.bufferAheadMs}ms codec=$codec",
                        )
                    }
                    delay(1000L) // Run every second for responsive stat updates
                }
            }
    }

    private fun startPlaybackSpeedAdjustmentLoop() {
        speedAdjustmentJob?.cancel()
        speedAdjustmentJob =
            scope.launch {
                while (isActive && isConnected.get()) {
                    val needsHardCorrection = speedController.adjustSpeed(nowUs())
                    if (needsHardCorrection && output.isStarted()) {
                        Log.w(tag, "Hard correction: startup sync error > 80ms, resyncing")
                        output.stop()
                        outputStartedAtUs = 0L
                        triggerForceResync("startup_hard_correction")
                        nextStartAttemptUs = nowUs() + 100_000L // Brief delay before restart
                    }
                    // Run more frequently during startup for responsive convergence
                    val delayMs = if (outputStartedAtUs > 0L &&
                        (nowUs() - outputStartedAtUs) < 4_000_000L
                    ) 200L else 1000L
                    delay(delayMs)
                }
            }
    }

    private fun startMemoryMonitoringLoop() {
        // Only run memory monitoring on low-memory devices
        if (!isLowMemoryDevice) return

        scope.launch {
            while (isActive && isConnected.get()) {
                try {
                    val activityManager =
                        context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)

                    val availableMemMb = (memInfo?.availMem ?: 0L) / (1024 * 1024)
                    val totalMemMb = (memInfo?.totalMem ?: 0L) / (1024 * 1024)

                    // Respond to memory pressure
                    if (memInfo?.lowMemory == true) {
                        Log.w(tag, "System lowMemory flag set, trimming buffer")
                        trimAudioBufferLow()
                    } else if (availableMemMb < 50) {
                        Log.e(
                            tag,
                            "Critical memory available (${availableMemMb}MB), clearing buffer",
                        )
                        trimAudioBufferCritical()
                    } else if (availableMemMb < 100) {
                        Log.w(
                            tag,
                            "Low memory available (${availableMemMb}MB), doing moderate trim",
                        )
                        trimAudioBufferModerate()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Error during memory monitoring", e)
                }

                // Check every 5 seconds
                delay(5000L)
            }
        }
    }

    private fun startPlayoutLoop() {
        playoutJob?.cancel()
        playoutJob =
            scope.launch {
                while (isActive && isConnected.get()) {
                    // Signal that playback loop is alive
                    lastPlaybackHeartbeatMs = System.currentTimeMillis()

                    if (!output.isStarted()) {
                        val started = runPreStartSetup()
                        if (!started) continue
                    }

                    handleActivePlayout()
                }
            }
    }

    private suspend fun runPreStartSetup(): Boolean {
        val snapshot = jitter.snapshot()
        val nowLocalUs = nowUs()
        if (nextStartAttemptUs > nowLocalUs) {
            val waitMs = ((nextStartAttemptUs - nowLocalUs) / 1000L).coerceAtLeast(10L)
            publishPlaybackDiagnostics(
                snapshot = snapshot,
                recoveryStatus = PlaybackDiagnostics.STATUS_START_BACKOFF,
                clockReady = isClockReadyForPlayback(),
                lateRestartLoops = lateRestartLoops,
            )
            delay(waitMs)
            return false
        }

        if (codec != "pcm") {
            delay(50)
            return false
        }

        // One-time warmup baseline: run on first launch and after app updates.
        SendspinAudioWarmup.runIfNeeded(context, output, tag)

        val clockReadyForPlayback = isClockReadyForPlayback()

        if (!clockReadyForPlayback) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_WAITING_CLOCK
            // Poor WiFi can produce multi-second RTT bursts; starting before the
            // filter converges causes false lateness, audio-cut thrash, and backlog growth.
            if (snapshot.queuedChunks > 0) {
                val dropped = jitter.dropWhileLate(nowUs(), 100_000L)
                if (dropped > 0) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastRestartCatchupLogMs >= 1000L) {
                        lastRestartCatchupLogMs = nowMs
                        recordRecoveryEvent(
                            PlaybackDiagnostics.STATUS_WAITING_CLOCK,
                            "dropped=$dropped " +
                                "uncertainty=${clock.getOffsetUncertaintyUs() / 1000}ms " +
                                "rtt=${clock.getAverageRttUs() / 1000}ms " +
                                "converged=${clock.hasConverged()}",
                        )
                    }
                }
            }
            publishPlaybackDiagnostics(
                snapshot = jitter.snapshot(),
                recoveryStatus = PlaybackDiagnostics.STATUS_WAITING_CLOCK,
                clockReady = false,
                lateRestartLoops = lateRestartLoops,
            )
            delay(50)
            return false
        }

        // Prevent deadlock when head is late (negative ahead) by dropping late chunks now.
        if (snapshot.queuedChunks > 0 && snapshot.bufferAheadMs < RESTART_DROP_TRIGGER_AHEAD_MS) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_CATCHUP
            val targetAheadMs = if (forceResyncMode) RESYNC_MIN_BUFFER_MS else MIN_BUFFER_MS
            // Always trim to the start window; lateness-based drop can leave
            // the head outside force-resync (-10..90ms).
            val dropped = jitter.dropUntilHeadAheadAtLeast(nowUs(), targetAheadMs)
            if (dropped > 0) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastRestartCatchupLogMs >= 1000L) {
                    lastRestartCatchupLogMs = nowMs
                    recordRecoveryEvent(
                        PlaybackDiagnostics.STATUS_PRESTART_CATCHUP,
                        "dropped=$dropped ahead=${snapshot.bufferAheadMs}ms " +
                            "queued=${snapshot.queuedChunks}",
                    )
                }
            }
        }

        val snap2 = jitter.snapshot()

        if (forceResyncMode) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_FORCE_RESYNC_PRESTART
            // While recovering from audioserver death, drop stale audio more aggressively
            // so we rejoin the live timeline quickly.
            val targetAheadMs = RESYNC_MIN_BUFFER_MS
            val dropped = jitter.dropUntilHeadAheadAtLeast(nowUs(), targetAheadMs)
            if (dropped > 0) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastForceResyncLogMs >= 1000L) {
                    lastForceResyncLogMs = nowMs
                    recordRecoveryEvent(
                        PlaybackDiagnostics.STATUS_FORCE_RESYNC_PRESTART,
                        "dropped=$dropped ahead=${snap2.bufferAheadMs}ms queued=${snap2.queuedChunks}",
                    )
                }
            }
        }

        // Large prestart backlog with a nearly-live head: trim to the start window in one pass.
        if (snap2.queuedChunks >= PRESTART_BACKLOG_CHUNK_THRESHOLD && snap2.bufferAheadMs < RESTART_MIN_AHEAD_MS) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_BACKLOG_TRIM
            val targetAheadMs = if (forceResyncMode) RESYNC_MIN_BUFFER_MS else MIN_BUFFER_MS
            val trimmed = jitter.dropUntilHeadAheadAtLeast(nowUs(), targetAheadMs)
            if (trimmed > 0) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastRestartCatchupLogMs >= 1000L) {
                    lastRestartCatchupLogMs = nowMs
                    recordRecoveryEvent(
                        PlaybackDiagnostics.STATUS_PRESTART_BACKLOG_TRIM,
                        "dropped=$trimmed queued=${snap2.queuedChunks} ahead=${snap2.bufferAheadMs}ms",
                    )
                }
            }
        }

        val snapForStart = jitter.snapshot()

        // Calculate effective buffer ahead accounting for playout offset
        // Chunks will actually be needed playoutOffsetUs in the future (negative offset = sooner)
        val effectiveBufferAheadMs = snapForStart.bufferAheadMs + (playoutOffsetUs / 1000L)

        val startMinMs = if (forceResyncMode) RESYNC_MIN_BUFFER_MS else MIN_BUFFER_MS
        val startMaxMs = if (forceResyncMode) RESYNC_MAX_START_AHEAD_MS else MAX_START_AHEAD_MS
        val discontinuityStartMinMs = -80L
        val discontinuityStartMaxMs = 250L
        val activeStartMinMs =
            if (inDiscontinuityMode) discontinuityStartMinMs else startMinMs
        val activeStartMaxMs =
            if (inDiscontinuityMode) discontinuityStartMaxMs else startMaxMs

        // Start window is based on raw ahead; effective offset is handled in scheduling.
        val canStartNormally =
            snapForStart.queuedChunks >= RESTART_MIN_QUEUED &&
                snapForStart.bufferAheadMs in activeStartMinMs..activeStartMaxMs

        // Recovery path: when network handoff leaves us perpetually late, don't deadlock.
        // Start anyway after ~1s of late restarts and let catch-up/drop logic recover.
        if (!canStartNormally && snapForStart.queuedChunks >= RESTART_MIN_QUEUED && snapForStart.bufferAheadMs < RESTART_MIN_AHEAD_MS) {
            lateRestartLoops++
        } else if (canStartNormally) {
            // Do not reset the counter when ahead oscillates around restartMinAheadMs
            // while still outside the normal start window — that prevented force-late-start.
            lateRestartLoops = 0
        }

        // If we remain late for a while, allow bounded late-start recovery instead of
        // endless prestart dropping (which can deadlock playback on constrained devices).
        val canStartLateRecovery =
            lateRestartLoops >= 20 && snapForStart.bufferAheadMs >= -50L

        val forceLateStart =
            lateRestartLoops >= FORCE_START_AFTER_LOOPS && snapForStart.bufferAheadMs >= -40L
        val canStart = canStartNormally || canStartLateRecovery || forceLateStart

        if (canStart) {
            if ((forceLateStart || canStartLateRecovery) && !canStartNormally) {
                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_LATE_START_RECOVERY
                recordRecoveryEvent(
                    PlaybackDiagnostics.STATUS_LATE_START_RECOVERY,
                    "ahead=${snapForStart.bufferAheadMs}ms effective=$effectiveBufferAheadMs ms " +
                        "queued=${snapForStart.queuedChunks} loops=$lateRestartLoops",
                )
                // If we're more than 100ms behind on forced late-start, trigger resync to snap to live
                // instead of waiting for slow catch-up via playback speed adjustment
                if (effectiveBufferAheadMs < -100) {
                    triggerForceResync("late_start_too_far_behind")
                    nextStartAttemptUs = nowUs() + 50_000L
                    return false
                }
            }
            output.start(sampleRate, channels, bitDepth)
            if (!output.isStarted()) {
                triggerForceResync("start_failed")
                outputStartedAtUs = 0L
                val backoffNowUs = nowUs()
                nextStartAttemptUs = backoffNowUs + (restartBackoffMs * 1000L)
                Log.w(tag, "Audio output failed to start; backing off ${restartBackoffMs}ms")
                restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(3000L)
                return false
            }
            outputStartedAtUs = nowUs()
            speedController.notifyOutputStarted(outputStartedAtUs)
            if (inDiscontinuityMode) {
                inDiscontinuityMode = false
            }
            sendClientStateSynchronized()
            lateRestartLoops = 0
            restartBackoffMs = 200L
            nextStartAttemptUs = 0L
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PLAYING
            recordRecoveryEvent(
                PlaybackDiagnostics.STATUS_PLAYING,
                "sr=$sampleRate ch=$channels codec=$codec buffered=${snapForStart.bufferAheadMs}ms " +
                    "effective=$effectiveBufferAheadMs ms",
            )
            publishPlaybackDiagnostics(
                snapshot = jitter.snapshot(),
                recoveryStatus = PlaybackDiagnostics.STATUS_PLAYING,
                clockReady = true,
            )
            return true
        } else {
            publishPlaybackDiagnostics(
                snapshot = snapForStart,
                recoveryStatus = playbackRecoveryStatus,
                clockReady = clockReadyForPlayback,
                lateRestartLoops = lateRestartLoops,
            )
            delay(10)
            return false
        }
    }

    private suspend fun handleActivePlayout() {
        val chunk = jitter.pollPlayable(nowUs(), LATE_DROP_US)
        if (chunk == null) {
            if (jitter.isEmpty()) {
                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_UNDERRUN
            }
            publishPlaybackDiagnostics(
                snapshot = jitter.snapshot(),
                recoveryStatus = playbackRecoveryStatus,
                clockReady = true,
                serverLatenessMs = lastPublishedServerLatenessMs,
            )
            delay(2)
            return
        }
        val pcmData = chunk.pcmData

        if (pcmData.isEmpty()) {
            Log.w(tag, "Empty PCM data after decode")
            return
        }

        val effectiveServerTsUs =
            if (playAtServerUs != Long.MIN_VALUE) {
                maxOf(
                    chunk.serverTimestampUs,
                    playAtServerUs,
                )
            } else {
                chunk.serverTimestampUs
            }

        val nowUsForSchedule = nowUs()
        val sinceOutputStartUs =
            if (outputStartedAtUs > 0L) {
                (nowUsForSchedule - outputStartedAtUs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        // Calculate effective playout offset: pipeline delay + device adjustment
        // Subtract staticDelayUs to schedule audio earlier, compensating for external device delay
        // No startup ramp — apply full playout offset from the first frame for instant group sync
        val totalPlayoutOffsetUs = playoutOffsetUs + playoutOffsetAdjustmentUs - staticDelayUs

        // Convert server timestamp to client time using Kalman filter offset (same client "now" as earlyUs)
        val localPlayUs =
            clock.convertServerToClient(effectiveServerTsUs, nowUsForSchedule) + totalPlayoutOffsetUs
        val now = nowUsForSchedule
        val earlyUs = localPlayUs - now

        // Server-time lateness: how many ms the chunk is past its server timestamp.
        // Positive = late; negative = ahead of server time.
        val serverLatenessMs = -(earlyUs - totalPlayoutOffsetUs) / 1000L

        if (forceResyncMode) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_FORCE_RESYNC_RUNTIME
            // If still late during forced resync, drop to near-live instead of gradually drifting.
            if (serverLatenessMs > 80L) {
                val dropped = jitter.dropWhileLate(nowUs(), 30_000L)
                if (dropped > 0) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastForceResyncLogMs >= 1000L) {
                        lastForceResyncLogMs = nowMs
                        Log.w(tag, "force-resync runtime: dropped=$dropped serverLate=${serverLatenessMs}ms early=${earlyUs / 1000}ms")
                    }
                }
                return
            }

            // Exit resync mode once the chunk is near (or ahead of) server time, or timeout expires.
            if (serverLatenessMs < 40L || nowUs() >= forceResyncUntilUs) {
                forceResyncMode = false
                forceResyncUntilUs = 0L
                recordRecoveryEvent(
                    "force_resync_complete",
                    "serverLate=${serverLatenessMs}ms early=${earlyUs / 1000}ms",
                )
            }
        }

        // Aggressive audio cut when audio falls too far behind server time.
        val nowMs = System.currentTimeMillis()
        val timeSinceLastCutMs = nowMs - lastAudioCutMs
        val startupCutGraceActive = sinceOutputStartUs < startupCutGraceUs
        val effectiveOutOfSyncThresholdMs =
            if (startupCutGraceActive) (audioOutOfSyncThresholdMs + 120L) else audioOutOfSyncThresholdMs
        val rttInflatedThresholdMs =
            if (clock.getAverageRttUs() > MAX_RTT_FOR_START_US) {
                effectiveOutOfSyncThresholdMs +
                    (clock.getAverageRttUs() / RTT_AUDIO_CUT_INFLATE_DIVISOR)
                        .coerceAtMost(RTT_AUDIO_CUT_INFLATE_MAX_MS)
            } else {
                effectiveOutOfSyncThresholdMs
            }

        if (!forceResyncMode && serverLatenessMs > rttInflatedThresholdMs && timeSinceLastCutMs > 500L) {
            val snapBeforeCut = jitter.snapshot()
            val lateDropsCount = snapBeforeCut.lateDrops
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_AUDIO_CUT
            recordRecoveryEvent(
                PlaybackDiagnostics.STATUS_AUDIO_CUT,
                "serverLate=${serverLatenessMs}ms threshold=$rttInflatedThresholdMs ms " +
                    "lateDrops=$lateDropsCount queued=${snapBeforeCut.queuedChunks}",
            )
            output.stop()
            outputStartedAtUs = 0L
            triggerForceResync("audio_out_of_sync_late_drop")
            lastAudioCutMs = nowMs
            val backoffNowUs = nowUs()
            nextStartAttemptUs = backoffNowUs + 500_000L // Delay 500ms before restart attempt
            return
        }

        // Debug: log first few scheduling attempts
        if (audioScheduleDebugCount < 3) {
            audioScheduleDebugCount++
            Log.d("SendspinPcmClient", "AudioSchedule: serverTs=$effectiveServerTsUs localPlay=$localPlayUs now=$now early=${earlyUs / 1000}ms serverLate=${serverLatenessMs}ms playout=${totalPlayoutOffsetUs / 1000}ms")
        }

        // If we're behind by a lot in server time, drop chunks to catch up (audible effect).
        val dropLateThresholdMs = DROP_LATE_US / 1000L
        if (serverLatenessMs > dropLateThresholdMs) {
            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_CATCHUP_DROP
            var dropped = 1
            val maxDrops = 50 // Prevent unbounded dropping that causes ANR
            val catchupStart = nowUs()
            val targetLateThresholdMs = TARGET_LATE_US / 1000L
            while (dropped < maxDrops) {
                val next = jitter.pollPlayable(nowUs(), Long.MAX_VALUE) ?: break
                val nextPcm = next.pcmData

                // Apply same latency compensation during catch-up as during normal playback
                val nextTotalPlayoutOffsetUs = playoutOffsetUs + playoutOffsetAdjustmentUs - staticDelayUs
                val scheduleNowUs = nowUs()
                val nextLocalPlayUs =
                    clock.convertServerToClient(next.serverTimestampUs, scheduleNowUs) +
                        nextTotalPlayoutOffsetUs
                val nextEarlyUs = nextLocalPlayUs - scheduleNowUs
                // Server-time lateness for the candidate chunk
                val nextServerLatenessMs = -(nextEarlyUs - nextTotalPlayoutOffsetUs) / 1000L
                dropped++
                if (nextServerLatenessMs <= targetLateThresholdMs) {
                    if (nextPcm.isNotEmpty()) {
                        val ok = output.writePcm(nextPcm)
                        if (!ok) {
                            Log.w(tag, "catch-up write failed; restarting output")
                            output.stop()
                            triggerForceResync("catchup_write_failed")
                            val backoffNowUs = nowUs()
                            nextStartAttemptUs = backoffNowUs + (restartBackoffMs * 1000L)
                            restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(3000L)
                            break
                        }
                    }
                    break
                }
                // Yield frequently (every 2 drops) to prevent ANR and allow other threads
                if (dropped % 2 == 0) {
                    yield()
                }
            }
            if (dropped > 1) {
                audibleSyncCount++
                recordRecoveryEvent(
                    PlaybackDiagnostics.STATUS_CATCHUP_DROP,
                    "dropped=$dropped durationMs=${(nowUs() - catchupStart) / 1000} " +
                        "serverLate=${serverLatenessMs}ms audibleSyncs=$audibleSyncCount",
                )
            }
            publishPlaybackDiagnostics(
                snapshot = jitter.snapshot(),
                recoveryStatus = playbackRecoveryStatus,
                clockReady = true,
                serverLatenessMs = serverLatenessMs,
            )
            return
        }

        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PLAYING
        publishPlaybackDiagnostics(
            snapshot = jitter.snapshot(),
            recoveryStatus = PlaybackDiagnostics.STATUS_PLAYING,
            clockReady = true,
            serverLatenessMs = serverLatenessMs,
        )

        // Sleep until we're exactly one pipeline-latency before the intended play time.
        val pipelineLatencyUs = output.getEstimatedPipelineLatencyUs()
        if (earlyUs > pipelineLatencyUs) {
            delay((earlyUs - pipelineLatencyUs) / 1000)
        }

        val ok = output.writePcm(pcmData)
        if (!ok) {
            Log.w(tag, "PCM write failed; restarting output")
            output.stop()
            outputStartedAtUs = 0L
            triggerForceResync("pcm_write_failed")
            val backoffNowUs = nowUs()
            nextStartAttemptUs = backoffNowUs + (restartBackoffMs * 1000L)
            restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(3000L)
            return
        }
    }

    override fun onHelloReceived(activeRoles: String, hasController: Boolean, hasMetadata: Boolean) {
        handshakeComplete = true
        updateDiagnostics {
            it.copy(
                status = "server/hello",
                activeRoles = activeRoles,
                hasController = hasController,
                hasMetadata = hasMetadata,
            )
        }
        startTimeSyncLoop()
        startPlayoutLoop()
        startStatsLoop()
        startPlaybackSpeedAdjustmentLoop()
        startMemoryMonitoringLoop()
        startWatchdogLoop() // Monitor playback loop health

        // Send initial state with actual Android volume
        val volumePercent = SendspinSystemUtils.getActualSystemVolume(context, tag)
        sendClientStateSynchronized(volume = volumePercent, muted = false)
    }

    override fun onTimeSync(clientTx: Long, sRecv: Long, sTx: Long) {
        val clientRx = nowUs()
        val rtt = clientRx - clientTx
        Log.d(tag, "server/time: T1=$clientTx T2=$sRecv T3=$sTx T4=$clientRx rtt=${rtt}us")
        clock.onServerTime(clientTx, clientRx, sRecv, sTx)
    }

    override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, playAtUs: Long) {
        streamEnded = false // Reset flag when new stream starts
        lastChunkServerTimestampUs = Long.MIN_VALUE // Reset discontinuity detector
        inDiscontinuityMode = false
        audioScheduleDebugCount = 0
        playoutOffsetUs = -50_000L // Reset to default on new stream
        playoutOffsetAdjustmentUs = 0L // Clear any accumulated adjustment too
        outputStartedAtUs = 0L

        this.codec = codec
        this.sampleRate = sampleRate
        this.channels = channels
        this.bitDepth = bitDepth
        this.playAtServerUs = playAtUs

        updateDiagnostics {
            it.copy(
                status = "stream/start",
                streamDesc = "$codec ${sampleRate / 1000}kHz ${channels}ch ${bitDepth}bit",
            )
        }

        // Use pause() to clear buffer for new stream, preserving track for resume/reuse
        output.pause()
        jitter.clear()
    }

    override fun onStreamClear() {
        jitter.clear()
    }

    override fun onStreamEnd() {
        streamEnded = true
        // Pause instead of stop to allow reuse
        output.pause()
        outputStartedAtUs = 0L
        jitter.clear()
        playAtServerUs = Long.MIN_VALUE
        updateDiagnostics { it.copy(status = "stream/end", streamDesc = "") }
    }

    override fun onGroupUpdate(playbackState: String, groupName: String) {
        updateDiagnostics { it.copy(playbackState = playbackState, groupName = groupName) }
    }

    override fun onServerState(controllerVolume: Int?, controllerMuted: Boolean?, supportedCommands: Set<String>?, metadata: JSONObject?) {
        if (controllerVolume != null || controllerMuted != null || supportedCommands != null) {
            updateDiagnostics {
                it.copy(
                    groupVolume = controllerVolume ?: it.groupVolume,
                    groupMuted = controllerMuted ?: it.groupMuted,
                    supportedCommands = supportedCommands ?: it.supportedCommands,
                )
            }
        }
        metadata?.let {
            handleMetadataUpdate(it)
        }
    }

    override fun onVolumeCommand(volume: Int) {
        Log.i(tag, "server/command volume: $volume (server commanded)")
        updateDiagnostics {
            it.copy(
                playerVolume = volume,
                playerVolumeFromServer = true,
            )
        }
        emitEvent(ClientEvent.ServerVolumeChanged(volume))
        // Echo back in state
        sendClientStatePlayer(volume = volume, muted = null)
    }

    override fun onMuteCommand(muted: Boolean) {
        Log.i(tag, "server/command mute: $muted (server commanded)")
        updateDiagnostics {
            it.copy(
                playerMuted = muted,
                playerMutedFromServer = true,
            )
        }
        emitEvent(ClientEvent.ServerMutedChanged(muted))
        // Echo back in state
        sendClientStatePlayer(volume = null, muted = muted)
    }

    override fun onStaticDelayCommand(delayMs: Long) {
        Log.i(tag, "server/command set_static_delay: ${delayMs}ms (server commanded)")
        staticDelayUs = delayMs * 1000L
        updateDiagnostics {
            it.copy(
                staticDelayMs = delayMs,
                staticDelayMsFromServer = true,
            )
        }
        emitEvent(ClientEvent.ServerStaticDelayChanged(delayMs))
        // Echo back in state
        sendClientStatePlayer(staticDelayMs = delayMs)
    }

    private fun handleBinary(data: ByteArray) {
        lastMessageReceivedMs = System.currentTimeMillis()
        if (!handshakeComplete) return
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF

        when (type) {
            4 -> {
                // Audio chunk (player role)
                if (codec != "pcm") return
                if (data.size < 1 + 8 + 1) return

                val tsServerUs = readInt64BE(data, 1)
                val encodedData = data.copyOfRange(1 + 8, data.size)

                // Detect stream discontinuity (skip/seek)
                if (lastChunkServerTimestampUs != Long.MIN_VALUE) {
                    val timestampJumpUs = tsServerUs - lastChunkServerTimestampUs
                    if (kotlin.math.abs(timestampJumpUs) > discontinuityThresholdUs) {
                        Log.w(
                            tag,
                            "Stream discontinuity detected: jump=${timestampJumpUs / 1000}ms, clearing buffer and entering discontinuity recovery mode",
                        )
                        jitter.clear()
                        inDiscontinuityMode = true
                        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_DISCONTINUITY
                        recordRecoveryEvent(
                            PlaybackDiagnostics.STATUS_DISCONTINUITY,
                            "jump=${timestampJumpUs / 1000}ms",
                        )
                        triggerForceResync("stream_discontinuity")
                    }
                }

                lastChunkServerTimestampUs = tsServerUs
                jitter.offer(tsServerUs, encodedData)
            }
        }
    }

    private fun readInt64BE(
        buf: ByteArray,
        off: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFFL)
        return v
    }

    private fun nowUs(): Long = System.nanoTime() / 1000L

    private fun triggerForceResync(reason: String) {
        forceResyncMode = true
        forceResyncUntilUs = nowUs() + 3_000_000L
        // If we had a historical anchor, discard it and follow current server timestamps.
        playAtServerUs = Long.MIN_VALUE

        val dropped = jitter.dropWhileLate(nowUs(), 40_000L)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastForceResyncLogMs >= 1000L) {
            lastForceResyncLogMs = nowMs
            recordRecoveryEvent("force_resync", "reason=$reason dropped=$dropped")
        }
    }

    // Memory pressure management
    private fun trimAudioBuffer(targetPercent: Double, minChunks: Int, level: String) {
        val currentSize = jitter.size()
        val targetSize = (currentSize * targetPercent).toInt().coerceAtMost(300).coerceAtLeast(minChunks)
        Log.w(tag, "$level memory trim: reducing buffer from $currentSize to $targetSize chunks")
        jitter.trimTo(targetSize)
        val snapshot = jitter.snapshot()
        updateDiagnostics {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
            )
        }
    }

    fun trimAudioBufferCritical() = trimAudioBuffer(1.0 / 3.0, 150, "CRITICAL")
    fun trimAudioBufferModerate() = trimAudioBuffer(0.5, 200, "MODERATE")
    fun trimAudioBufferLow() = trimAudioBuffer(0.5, 200, "LOW")

    /**
     * Watchdog loop - detects if playback or stats threads are hung/blocked
     * Runs every 5 seconds and triggers recovery if no heartbeat detected
     */
    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob =
            scope.launch {
                while (isActive && isConnected.get()) {
                    delay(5000) // Check every 5 seconds

                    val now = System.currentTimeMillis()
                    val playbackTimeout = 10_000L // 10 seconds of no heartbeat = hung
                    val statsTimeout = 10_000L

                    val playbackDead = (now - lastPlaybackHeartbeatMs) > playbackTimeout
                    val statsDead = (now - lastStatsHeartbeatMs) > statsTimeout

                    if (playbackDead || statsDead) {
                        val msg =
                            buildString {
                                append("Watchdog: ")
                                if (playbackDead) append("playback hung ")
                                if (statsDead) append("stats hung")
                            }
                        Log.e(tag, msg)
                        // Signal the caller to trigger recovery
                        sendClientStateError()
                    }
                }
            }
    }

    /**
     * Health check - called by service to determine if client is functioning
     * Returns true if playback/stats loops are active and the server has sent a message recently.
     * A long silence from the server (> 3 min) while the handshake is complete indicates the
     * underlying TCP connection has gone silently dead (e.g. battery optimisation, Doze mode,
     * router reboot) without triggering an OkHttp onFailure/onClosed callback.
     */
    fun isHealthy(): Boolean {
        if (!isConnected.get()) return false
        if (!handshakeComplete) return false

        val now = System.currentTimeMillis()
        val heartbeatTimeout = 10_000L // 10 seconds of no internal heartbeat = hung loop
        // Once the handshake is complete the time-sync loop sends client/time every ≤2 s and
        // the server echoes server/time back. Three minutes of silence is a reliable signal
        // that the connection is dead even though OkHttp has not (yet) reported a failure.
        val messageTimeout = 3 * 60 * 1000L // 3 minutes

        val playbackOk = (now - lastPlaybackHeartbeatMs) <= heartbeatTimeout
        val statsOk = (now - lastStatsHeartbeatMs) <= heartbeatTimeout
        val messageOk = lastMessageReceivedMs > 0L && (now - lastMessageReceivedMs) <= messageTimeout

        if (!messageOk) {
            val silenceSeconds = if (lastMessageReceivedMs > 0L) (now - lastMessageReceivedMs) / 1000 else -1L
            Log.w(tag, "isHealthy: no server message for ${silenceSeconds}s — connection may be silently dead")
        }

        return playbackOk && statsOk && messageOk
    }

    private fun handleMetadataUpdate(metadata: JSONObject) {
        updateDiagnostics { currentState ->
            var newState = currentState

            if (metadata.has("timestamp")) {
                newState = newState.copy(metadataTimestamp = metadata.getLong("timestamp"))
            }

            if (metadata.has("title")) {
                newState = newState.copy(
                    trackTitle = if (metadata.isNull("title")) null else metadata.getString("title")
                )
            }

            if (metadata.has("artist")) {
                newState = newState.copy(
                    trackArtist = if (metadata.isNull("artist")) null else metadata.getString("artist")
                )
            }

            if (metadata.has("album")) {
                newState = newState.copy(
                    albumTitle = if (metadata.isNull("album")) null else metadata.getString("album")
                )
            }

            if (metadata.has("album_artist")) {
                newState = newState.copy(
                    albumArtist = if (metadata.isNull("album_artist")) null else metadata.getString("album_artist")
                )
            }

            if (metadata.has("year")) {
                newState = newState.copy(
                    trackYear = if (metadata.isNull("year")) null else metadata.getInt("year")
                )
            }

            if (metadata.has("track")) {
                newState = newState.copy(
                    trackNumber = if (metadata.isNull("track")) null else metadata.getInt("track")
                )
            }

            if (metadata.has("progress")) {
                val progress = metadata.optJSONObject("progress")
                newState = if (progress != null) {
                    newState.copy(
                        trackProgress = progress.getLong("track_progress"),
                        trackDuration = progress.getLong("track_duration"),
                        playbackSpeed = progress.getInt("playback_speed"),
                    )
                } else {
                    newState.copy(
                        trackProgress = null,
                        trackDuration = null,
                        playbackSpeed = null,
                    )
                }
            }

            if (metadata.has("repeat")) {
                newState = newState.copy(
                    repeatMode = if (metadata.isNull("repeat")) null else metadata.getString("repeat")
                )
            }

            if (metadata.has("shuffle")) {
                newState = newState.copy(
                    shuffleEnabled = if (metadata.isNull("shuffle")) null else metadata.getBoolean("shuffle")
                )
            }

            newState
        }

        Log.i(tag, "Metadata update received")
    }
}
