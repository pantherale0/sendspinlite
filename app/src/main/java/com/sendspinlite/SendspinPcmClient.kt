package com.sendspinlite

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.util.ArrayDeque
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SendspinPcmClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val onUiUpdate: ((PlayerViewModel.UiState) -> PlayerViewModel.UiState) -> Unit,
    private val context: android.content.Context,
) {
    private val tag = "SendspinPcmClient"

    companion object {
        private const val PREFS_NAME = "SendspinPlayerPrefs"
        private const val KEY_AUDIO_WARMUP_LAST_VERSION_CODE = "audio_warmup_last_version_code"
        private const val KEY_AUDIO_WARMUP_BASELINE_LATENCY_US = "audio_warmup_baseline_latency_us"
        private const val KEY_AUDIO_WARMUP_BASELINE_FORMAT = "audio_warmup_baseline_format"
        private const val KEY_AUDIO_WARMUP_BASELINE_TIMESTAMP_MS = "audio_warmup_baseline_timestamp_ms"
        private const val MAX_CLOCK_UNCERTAINTY_FOR_START_US = 50_000L
        private const val MAX_RTT_FOR_START_US = 2_000_000L
        private const val RTT_AUDIO_CUT_INFLATE_DIVISOR = 2000L
        private const val RTT_AUDIO_CUT_INFLATE_MAX_MS = 500L

        /** Compressed bytes the server may buffer ahead (limits startup burst). */
        private const val CLIENT_BUFFER_CAPACITY_BYTES = 96_000

        /** Pending Opus frames awaiting decode; drop oldest when full to stay near live edge. */
        private const val MAX_OPUS_PENDING_FRAMES = 2400

        private const val OPUS_WARM_DECODE_MAX_US = 20_000L
        private const val OPUS_PENDING_STALE_DROP_US = 150_000L
        private const val OPUS_POST_DECODE_STALE_DROP_US = 150_000L
        private const val OPUS_BATCH_BUDGET_COLD_US = 2_000_000L
        private const val OPUS_BATCH_BUDGET_LARGE_US = 100_000L
        private const val OPUS_BATCH_BUDGET_NORMAL_US = 25_000L
        private const val OPUS_PENDING_LARGE_THRESHOLD = 200
        private const val OPUS_JIT_PREWARM_MAX_ITERATIONS = 24
        private const val OPUS_JIT_WARMUP_MIN_ITERATIONS = 4
        private const val OPUS_STREAM_WARMUP_MAX_ITERATIONS = 32
        private const val OPUS_STARTUP_AUDIO_CUT_GRACE_US = 4_000_000L
        private const val MAX_SCHEDULED_PREFETCH_AHEAD_MS = 120_000L
        private const val SCHEDULED_PREFETCH_MAX_WAIT_MS = 30_000L
        private const val MIN_BUFFERED_PREFETCH_CHUNKS = 40
        private const val MIN_WARM_START_QUEUED = 12
        private const val WARM_START_MAX_LATE_MS = 400L

        // Shared OkHttpClient to avoid leaking thread pools and connection pools on reconnect
        private val sharedOkHttp =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

        /** Process-wide Concentus JIT warmup — cold JVM first decode is ~3s without this. */
        private val opusJitPrewarmInFlight = AtomicBoolean(false)
        private val opusJitPrewarmComplete = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ws: WebSocket? = null

    private val isConnected = AtomicBoolean(false)
    private var handshakeComplete: Boolean = false

    // Detect low-memory devices to disable expensive features
    private val isLowMemoryDevice = checkIsLowMemoryDevice()

    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager =
                context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L // Less than 2GB total RAM
            if (lowMemory) {
                Log.i(tag, "Low-memory device detected: disabling metadata and action buttons")
            }
            lowMemory
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device memory", e)
            false
        }
    }

    private fun getConnectionType(): String {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    when {
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                        else -> "Other"
                    }
                } else {
                    "Unknown"
                }
            } else {
                "Disconnected"
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to get connection type", e)
            "Unknown"
        }
    }

    private fun getActualSystemVolume(): Int {
        return try {
            val audioManager =
                context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume =
                audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            (currentVolume * 100 / maxVolume).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(tag, "Failed to get system volume", e)
            100 // Default to max if we can't read
        }
    }

    private val clock = ClockSync()
    private val jitter = AudioJitterBuffer(clock)
    private val output: PcmAudioOutput = PcmAudioOutput()

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

    @Volatile
    private var opusDecoder: OpusDecoder? = null

    private var opusDecodeThread: HandlerThread? = null
    private var opusDecodeHandler: Handler? = null
    private val opusPending = ArrayDeque<Pair<Long, ByteArray>>()
    private val opusPendingLock = Any()
    private var opusDecodeErrorLogs = 0
    private var prestartLateRestartLoops = 0
    private var prestartFarAheadSinceMs = 0L

    private var playAtServerUs: Long = Long.MIN_VALUE

    private val warmupInProgress = AtomicBoolean(false)

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

    // Track codec decode latency to adjust playoutOffsetUs dynamically
    private var decodeLatencyUs: Long = 0L // Running average of decode time
    private val decodeLatencySamples = ArrayDeque<Long>(30)
    private val maxDecodeLatencySamples = 30 // Keep rolling average of last 30 frames
    private var opusWarmupDone: Boolean = false

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
    private var lastUiUpdateUs: Long = 0L
    private val uiUpdateThrottleMs =
        if (isLowMemoryDevice) 250L else 100L // Balanced throttle: 10 updates/sec (normal), 4 updates/sec (low-mem)

    // Statistics counters
    private var audibleSyncCount: Long = 0 // Incremented when audible sync (catch-up) occurs
    private var kalmanErrorCount: Long = 0 // Incremented when Kalman filter detects anomalies
    private var audioScheduleDebugCount = 0 // Debug counter for audio scheduling logs
    private var lastRestartCatchupLogMs: Long = 0L

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

    // Startup protection: avoid aggressive cuts immediately after output start,
    // and ramp playout offset in to prevent launching too far behind.
    @Volatile
    private var outputStartedAtUs: Long = 0L
    private val startupCutGraceUs = 2_500_000L
    private val startupPlayoutOffsetRampUs = 2_000_000L

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
        val actualVolume = getActualSystemVolume()
        onUiUpdate {
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
                    onUiUpdate { it.copy(status = "port_open") }
                }

                is PortChecker.PortCheckResult.PortClosed -> {
                    Log.w(tag, "Port is closed - server may be offline or component upgrading")
                    onUiUpdate {
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
                    onUiUpdate {
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
                        prewarmOpusJitInBackground()
                        onUiUpdate { it.copy(status = "ws_open", connected = true) }
                        sendClientHello()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) = handleText(text)

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

        stopOpusDecodeThread()

        // Stop audio output - this prevents buffered data from continuing to play
        output.stop()
        jitter.clear()
        opusDecoder = null
        decodeLatencyUs = 0L
        decodeLatencySamples.clear()
        playAtServerUs = Long.MIN_VALUE
        lastChunkServerTimestampUs = Long.MIN_VALUE
        inDiscontinuityMode = false
        audioScheduleDebugCount = 0
        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE
        lastRecoveryEvent = ""
        lastPublishedServerLatenessMs = 0L
        forceResyncMode = false
        forceResyncUntilUs = 0L
        publishTeardownUi(status)
    }

    private fun publishTeardownUi(status: String) {
        onUiUpdate {
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

        // Clear decode latency samples to free memory
        decodeLatencySamples.clear()

        Log.i(tag, "Resources cleaned up")
    }

    /**
     * Measure and track decode latency for compensation.
     * Updates decodeLatencyUs with rolling average of codec decode times.
     * This ensures playback timing accounts for actual codec processing delay.
     */
    private fun recordDecodeLatency(decodeTimeUs: Long) {
        decodeLatencySamples.addLast(decodeTimeUs)
        while (decodeLatencySamples.size > maxDecodeLatencySamples) {
            decodeLatencySamples.removeFirst()
        }

        // Calculate moving average
        decodeLatencyUs = decodeLatencySamples.average().toLong()
    }

    private fun isOpusDecoderWarm(): Boolean {
        if (decodeLatencySamples.size < 3) return false
        return decodeLatencySamples.toList().takeLast(3).all { sample ->
            sample in 1..OPUS_WARM_DECODE_MAX_US
        }
    }

    /** JIT-compile Concentus on a background thread so first playback decode is fast. */
    private fun prewarmOpusJitInBackground() {
        if (!opusJitPrewarmInFlight.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    val packet = OpusDecoder.createWarmupPacket(48_000, 2)
                    val decoder = OpusDecoder(48_000, 2)
                    val warmupStart = nowUs()
                    var lastDecodeUs = 0L
                    var iterations = 0
                    while (
                        iterations < OPUS_JIT_PREWARM_MAX_ITERATIONS &&
                            (iterations < OPUS_JIT_WARMUP_MIN_ITERATIONS || lastDecodeUs > OPUS_WARM_DECODE_MAX_US)
                    ) {
                        val t0 = nowUs()
                        decoder.decode(packet)
                        lastDecodeUs = nowUs() - t0
                        iterations++
                    }
                    opusJitPrewarmComplete.set(true)
                    Log.i(
                        tag,
                        "Opus JIT prewarm at connect: lastDecodeUs=$lastDecodeUs " +
                            "totalUs=${nowUs() - warmupStart}",
                    )
                } catch (e: Exception) {
                    opusJitPrewarmInFlight.set(false)
                    Log.w(tag, "Opus JIT prewarm at connect failed", e)
                }
            },
            "OpusJitPrewarm",
        ).apply {
            priority = Process.THREAD_PRIORITY_BACKGROUND
            start()
        }
    }

    /** JIT-compile Concentus on the live decoder using the head ingress packet (never synthetic). */
    private fun warmupOpusDecoderFromPending() {
        val decoder = opusDecoder
        if (decoder == null || isOpusDecoderWarm()) return
        var lastDecodeUs = 0L
        var iterations = 0
        while (
            iterations < OPUS_STREAM_WARMUP_MAX_ITERATIONS &&
                (iterations < OPUS_JIT_WARMUP_MIN_ITERATIONS || lastDecodeUs > OPUS_WARM_DECODE_MAX_US)
        ) {
            val packet =
                synchronized(opusPendingLock) {
                    opusPending.peekFirst()?.second
                } ?: break
            val t0 = nowUs()
            decoder.decode(packet)
            lastDecodeUs = nowUs() - t0
            recordDecodeLatency(lastDecodeUs)
            iterations++
        }
    }

    private fun ensureOpusDecoderWarmedForDrain() {
        if (isOpusDecoderWarm()) return
        if (synchronized(opusPendingLock) { opusPending.isEmpty() }) return
        warmupOpusDecoderFromPending()
    }

    private fun opusBatchBudgetUs(): Long =
        synchronized(opusPendingLock) {
            when {
                !isOpusDecoderWarm() -> OPUS_BATCH_BUDGET_COLD_US
                opusPending.size > OPUS_PENDING_LARGE_THRESHOLD -> OPUS_BATCH_BUDGET_LARGE_US
                else -> OPUS_BATCH_BUDGET_NORMAL_US
            }
        }

    private fun pollNextOpusPending(staleDropUs: Long): Pair<Long, ByteArray>? =
        synchronized(opusPendingLock) {
            val nowServerUs = clock.convertClientToServer(nowUs())
            while (opusPending.size > 1) {
                val head = opusPending.peekFirst() ?: break
                if (nowServerUs - head.first > staleDropUs) {
                    opusPending.removeFirst()
                } else {
                    break
                }
            }
            if (opusPending.isEmpty()) null else opusPending.removeFirst()
        }

    private fun offerDecodedOpusToJitter(
        serverTsUs: Long,
        pcm: ByteArray,
        decoderWarm: Boolean,
    ) {
        if (pcm.isEmpty()) {
            if (opusDecodeErrorLogs < 8) {
                opusDecodeErrorLogs++
                Log.w(tag, "Opus decode empty ts=$serverTsUs")
            }
            return
        }
        if (!streamEnded) {
            val nowServerUs = clock.convertClientToServer(nowUs())
            val notStale =
                !decoderWarm || nowServerUs - serverTsUs <= OPUS_POST_DECODE_STALE_DROP_US
            if (notStale) {
                jitter.offer(serverTsUs, pcm)
            }
        }
    }

    private fun hasPendingOpusFrames(): Boolean =
        synchronized(opusPendingLock) { opusPending.isNotEmpty() }

    private fun ensureOpusDecodeThread() {
        if (opusDecodeThread?.isAlive == true) return
        val thread =
            HandlerThread("OpusDecode", Process.THREAD_PRIORITY_AUDIO).apply {
                start()
            }
        opusDecodeThread = thread
        opusDecodeHandler = Handler(thread.looper)
    }

    private fun stopOpusDecodeThread() {
        synchronized(opusPendingLock) { opusPending.clear() }
        opusDecodeHandler = null
        opusDecodeThread?.quitSafely()
        opusDecodeThread = null
    }

    private val opusDrainRunnable =
        Runnable {
            drainOpusPendingQueue()
        }

    private fun enqueueOpusChunk(
        serverTsUs: Long,
        encoded: ByteArray,
    ) {
        if (streamEnded) return
        ensureOpusDecodeThread()
        synchronized(opusPendingLock) {
            while (opusPending.size >= MAX_OPUS_PENDING_FRAMES) {
                opusPending.removeFirst()
            }
            opusPending.addLast(serverTsUs to encoded)
        }
        opusDecodeHandler?.removeCallbacks(opusDrainRunnable)
        opusDecodeHandler?.post(opusDrainRunnable)
    }

    private fun drainOpusPendingQueue() {
        if (streamEnded) return
        ensureOpusDecoderWarmedForDrain()
        if (!isOpusDecoderWarm()) return

        val batchStartUs = nowUs()
        val batchBudgetUs = opusBatchBudgetUs()
        while (nowUs() - batchStartUs < batchBudgetUs && !streamEnded) {
            val decoderWarm = isOpusDecoderWarm()
            val pendingStaleDropUs = if (decoderWarm) OPUS_PENDING_STALE_DROP_US else Long.MAX_VALUE
            val item = pollNextOpusPending(pendingStaleDropUs) ?: break
            val decoder = opusDecoder ?: continue
            val decodeStart = nowUs()
            val pcm = decoder.decode(item.second)
            recordDecodeLatency(nowUs() - decodeStart)
            offerDecodedOpusToJitter(item.first, pcm, decoderWarm)
            if (!hasPendingOpusFrames()) break
        }
    }

    private fun shouldEnterScheduledPrefetchWait(
        snap: AudioJitterBuffer.Snapshot,
        activeStartMaxMs: Long,
        restartMinQueued: Int,
        scheduledPrefetchTimedOut: Boolean,
        minBufferedPrefetchChunks: Int,
    ): Boolean {
        if (
            isBufferedPrefetchReady(snap, activeStartMaxMs, minBufferedPrefetchChunks) ||
            shouldDeferScheduledWaitForOpusWarmup(snap, minBufferedPrefetchChunks)
        ) {
            return false
        }
        return snap.queuedChunks >= restartMinQueued &&
            snap.bufferAheadMs > activeStartMaxMs &&
            snap.bufferAheadMs <= MAX_SCHEDULED_PREFETCH_AHEAD_MS &&
            !scheduledPrefetchTimedOut
    }

    private fun shouldIncrementLateRestartLoops(
        skipOpusLateRestartWhileWarming: Boolean,
        canStartNormally: Boolean,
        snap: AudioJitterBuffer.Snapshot,
        restartMinQueued: Int,
        restartMinAheadMs: Long,
    ): Boolean =
        !skipOpusLateRestartWhileWarming &&
            !canStartNormally &&
            snap.queuedChunks >= restartMinQueued &&
            snap.bufferAheadMs < restartMinAheadMs

    private fun isBufferedPrefetchReady(
        snap: AudioJitterBuffer.Snapshot,
        activeStartMaxMs: Long,
        minBufferedPrefetchChunks: Int,
    ): Boolean {
        if (!isLowMemoryDevice || codec != "opus") return false
        return snap.queuedChunks >= minBufferedPrefetchChunks &&
            snap.bufferAheadMs > activeStartMaxMs &&
            snap.bufferAheadMs <= MAX_SCHEDULED_PREFETCH_AHEAD_MS
    }

    private fun shouldDeferScheduledWaitForOpusWarmup(
        snap: AudioJitterBuffer.Snapshot,
        minBufferedPrefetchChunks: Int,
    ): Boolean {
        if (!isLowMemoryDevice || codec != "opus") return false
        return snap.queuedChunks < minBufferedPrefetchChunks ||
            !isOpusDecoderWarm() ||
            hasPendingOpusFrames()
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
        if (output.isStarted()) {
            output.getEstimatedPipelineLatencyUs()
        }
        val smoothedLatencyMs = output.getSmoothedLatencyMs()
        throttledUiUpdate {
            it.copy(
                audioOutputStarted = output.isStarted(),
                smoothedLatencyMs = smoothedLatencyMs,
                playbackRecoveryStatus = recoveryStatus,
                lastRecoveryEvent = lastRecoveryEvent,
                clockReadyForPlayback = clockReady,
                forceResyncActive = forceResyncMode,
                inDiscontinuityRecovery = inDiscontinuityMode,
                lateRestartLoops = lateRestartLoops,
                effectiveBufferAheadMs = effectiveAheadMs,
                estimatedOffsetMs = clock.estimatedOffsetUs() / 1000L,
                decodeLatencyMs = decodeLatencyUs / 1000L,
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
            )
        }
    }

    private fun throttledUiUpdate(block: (PlayerViewModel.UiState) -> PlayerViewModel.UiState) {
        val now = nowUs()
        if (now - lastUiUpdateUs >= uiUpdateThrottleMs * 1000L) {
            lastUiUpdateUs = now
            onUiUpdate(block)
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

    private fun buildPlayerSupportObject(): JSONObject {
        val supportedFormats = JSONArray()

        // PCM first (preferred on constrained devices); Opus offered as alternative.
        for (sampleRate in listOf(48000, 44100)) {
            for (bitDepth in listOf(16, 24, 32)) {
                supportedFormats
                    .put(
                        JSONObject().put("codec", "pcm").put("channels", 2)
                            .put("sample_rate", sampleRate).put("bit_depth", bitDepth),
                    )
            }
        }

        supportedFormats
            .put(
                JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 48000)
                    .put("bit_depth", 16),
            )
            .put(
                JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 44100)
                    .put("bit_depth", 16),
            )

        val supportedCommands = JSONArray().put("volume").put("mute")

        return JSONObject()
            .put("supported_formats", supportedFormats)
            .put("buffer_capacity", CLIENT_BUFFER_CAPACITY_BYTES)
            .put("supported_commands", supportedCommands)
    }

    private fun sendClientHello() {
        val hello =
            JSONObject()
                .put("client_id", clientId)
                .put("name", clientName)
                .put("version", 1)
                .put(
                    "device_info",
                    JSONObject()
                        .put("product_name", android.os.Build.MODEL)
                        .put("manufacturer", android.os.Build.MANUFACTURER)
                        .put(
                            "software_version",
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                        ),
                )
                .put(
                    "supported_roles",
                    JSONArray()
                        .put("player@v1"),
                )

        val playerSupport = buildPlayerSupportObject()
        hello.put("player@v1_support", playerSupport)
        hello.put("player_support", playerSupport) // Legacy field for compatibility

        sendJson("client/hello", hello)
        onUiUpdate { it.copy(status = "sent client/hello") }
    }

    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        Log.i(tag, "setPlayerVolume: $clamped (local control)")
        sendClientStatePlayer(volume = clamped, muted = null)

        // Update local state immediately without triggering server updates
        onUiUpdate { it.copy(playerVolume = clamped, playerVolumeFromServer = false) }
    }

    fun setPlayerMute(muted: Boolean) {
        Log.i(tag, "setPlayerMute: $muted (local control)")
        sendClientStatePlayer(volume = null, muted = muted)

        // Update local state immediately without triggering server updates
        onUiUpdate { it.copy(playerMuted = muted, playerMutedFromServer = false) }
    }

    private fun sendClientStatePlayer(
        volume: Int? = null,
        muted: Boolean? = null,
        staticDelayMs: Long? = null,
    ) {
        val player = JSONObject()
        volume?.let { player.put("volume", it) }
        muted?.let { player.put("muted", it) }
        staticDelayMs?.let { player.put("static_delay_ms", it) }
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientGoodbye(reason: String) {
        sendJson("client/goodbye", JSONObject().put("reason", reason))
    }

    private fun sendClientStateSynchronized(
        volume: Int = getActualSystemVolume(),
        muted: Boolean = false,
    ) {
        val currentStaticDelayMs = staticDelayUs / 1000L
        val player =
            JSONObject()
                .put("volume", volume)
                .put("muted", muted)
                .put("static_delay_ms", currentStaticDelayMs)
                .put("supported_commands", JSONArray().put("set_static_delay"))
        val payload =
            JSONObject()
                .put("state", "synchronized")
                .put("player", player)
        sendJson("client/state", payload)
    }

    private fun sendClientStateError(
        volume: Int = getActualSystemVolume(),
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
        val player =
            JSONObject()
                .put("volume", volume)
                .put("muted", muted)
                .put("static_delay_ms", currentStaticDelayMs)
        val payload =
            JSONObject()
                .put("state", "error")
                .put("player", player)
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
                    // Connection stats bypass playout diagnostics throttle (playout hammers throttledUiUpdate).
                    val networkQuality = clock.getNetworkConditionQuality().toString()
                    val stability = clock.getClockStability().toString()
                    val connectionType = getConnectionType()
                    onUiUpdate {
                        it.copy(
                            networkQuality = networkQuality,
                            stability = stability,
                            connectionType = connectionType,
                            playbackSpeedMultiplier = output.getCurrentPlaybackSpeed(),
                        )
                    }

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
                var currentSpeed = 1.0f
                var emaBufferAheadMs = Double.NaN
                var lastSuccessfulSpeedUs = 0L

                while (isActive && isConnected.get()) {
                    if (output.isStarted()) {
                        val snapshot = jitter.snapshot()
                        val rawAheadMs = snapshot.bufferAheadMs.toDouble()

                        // EMA smoothing: α=0.3 → ~3-sample window; filters single-tick noise
                        emaBufferAheadMs =
                            if (emaBufferAheadMs.isNaN()) {
                                rawAheadMs
                            } else {
                                0.3 * rawAheadMs + 0.7 * emaBufferAheadMs
                            }

                        // Target buffer-ahead = AudioTrack pipeline latency.
                        val targetAheadMs = output.getEstimatedPipelineLatencyUs() / 1000.0
                        val bufferErrorMs = emaBufferAheadMs - targetAheadMs

                        // Gentle proportional control (Python-like cadence/strength).
                        // Positive error => too far ahead => slow down (<1.0)
                        // Negative error => behind => speed up (>1.0)
                        val kP = 0.00005
                        var desiredSpeed = (1.0 - (kP * bufferErrorMs)).coerceIn(0.998, 1.002)

                        // Wider deadband prevents audible hunt around target.
                        if (kotlin.math.abs(bufferErrorMs) < 12.0) {
                            desiredSpeed = 1.0
                        }

                        // Quantize to 0.001x to reduce rapid tiny parameter churn.
                        desiredSpeed = kotlin.math.round(desiredSpeed * 1000.0) / 1000.0

                        // Rate-limit speed adjustments to once per second to avoid thrashing on low-end devices
                        val desiredSpeedF = desiredSpeed.toFloat()
                        if (kotlin.math.abs(desiredSpeedF - currentSpeed) > 0.0001f) {
                            val nowUs = nowUs()
                            if (nowUs - lastSuccessfulSpeedUs >= 1_000_000L) {
                                output.setPlaybackSpeed(desiredSpeedF)
                                currentSpeed = desiredSpeedF
                                lastSuccessfulSpeedUs = nowUs
                            }
                        }
                    } else {
                        // Reset state when output stops so we start fresh on next stream
                        currentSpeed = 1.0f
                        emaBufferAheadMs = Double.NaN
                        lastSuccessfulSpeedUs = 0L
                    }
                    // Faster cadence (1s instead of 1.5s) allows quicker response to buffer changes
                    // while still being gentle enough for low-end devices
                    delay(1000L)
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

    private fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve app version code, falling back to BuildConfig", e)
            BuildConfig.VERSION_CODE.toLong()
        }
    }

    private suspend fun runAudioWarmupIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersionCode = getCurrentVersionCode()
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

    private fun touchPlaybackHeartbeat() {
        lastPlaybackHeartbeatMs = System.currentTimeMillis()
    }

    /** Long playout sleeps must not trip the 10s health watchdog. */
    private suspend fun delayKeepingPlaybackAlive(durationMs: Long) {
        if (durationMs <= 0L) return
        val stepMs = 500L
        var remaining = durationMs
        while (remaining > 0) {
            val slice = minOf(stepMs, remaining)
            delay(slice)
            touchPlaybackHeartbeat()
            remaining -= slice
        }
    }

    private fun startPlayoutLoop() {
        playoutJob?.cancel()
        playoutJob =
            scope.launch(Dispatchers.Default) {
                val minBufferMs = -20L // allow modestly late starts; catch-up logic will recover
                val maxStartAheadMs = 150L // Increased from 120ms to allow more buffer accumulation before starting
                val resyncMinBufferMs = -10L
                val resyncMaxStartAheadMs = 90L

                // Normal "too-late" drop once we're running.
                val lateDropUs = 200_000L

                // Make offset changes audible by catching up (dropping) or slowing down (waiting).
                val dropLateUs = 250_000L
                val targetLateUs = 100_000L

                // NEW: if output is stopped and the queue head is very late, we must drop until near-now,
                // otherwise bufferAheadMs stays negative and we never restart (queue grows forever).
                val restartDropTriggerAheadMs = -60L // pre-drop once head is meaningfully late
                val restartMinAheadMs = -30L // consider late-start recovery once head is below this
                val restartMinQueued =
                    4 // allow recovery to begin sooner on constrained devices
                val forceStartAfterLoops = 80 // ~0.8s with 10ms retry delay
                val prestartBacklogChunkThreshold = 60 // trim aggressively when prestart queue grows
                // Music Assistant may schedule audio far in the future; wait in prestart (with
                // heartbeat) until server time catches up instead of blocking playout for tens of seconds.
                val scheduledPrefetchMaxWaitMs = SCHEDULED_PREFETCH_MAX_WAIT_MS
                val minBufferedPrefetchChunks = MIN_BUFFERED_PREFETCH_CHUNKS
                val minWarmStartQueued = MIN_WARM_START_QUEUED
                val warmStartMaxLateMs = WARM_START_MAX_LATE_MS

                var restartBackoffMs = 200L
                var nextStartAttemptUs = 0L

                while (isActive && isConnected.get()) {
                    if (streamEnded && !output.isStarted()) {
                        if (playbackRecoveryStatus != PlaybackDiagnostics.STATUS_IDLE) {
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE
                        }
                        publishPlaybackDiagnostics(
                            snapshot = jitter.snapshot(),
                            recoveryStatus = PlaybackDiagnostics.STATUS_IDLE,
                            clockReady = isClockReadyForPlayback(),
                        )
                        delay(50)
                        continue
                    }

                    val snapshot = jitter.snapshot()

                    touchPlaybackHeartbeat()

                    if (!output.isStarted()) {
                        val nowLocalUs = nowUs()
                        if (nextStartAttemptUs > nowLocalUs) {
                            val waitMs = ((nextStartAttemptUs - nowLocalUs) / 1000L).coerceAtLeast(10L)
                            publishPlaybackDiagnostics(
                                snapshot = snapshot,
                                recoveryStatus = PlaybackDiagnostics.STATUS_START_BACKOFF,
                                clockReady = isClockReadyForPlayback(),
                                lateRestartLoops = prestartLateRestartLoops,
                            )
                            delay(waitMs)
                            continue
                        }

                        if (codec != "pcm" && codec != "opus") {
                            delay(50)
                            continue
                        }

                        // One-time warmup baseline: run on first launch and after app updates.
                        runAudioWarmupIfNeeded()

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
                                lateRestartLoops = prestartLateRestartLoops,
                            )
                            delay(50)
                            continue
                        }

                        // Skip catchup while Opus JIT is compiling or the prestart buffer is still building.
                        val skipOpusCatchupWhileWarming =
                            codec == "opus" &&
                                (
                                    !isOpusDecoderWarm() ||
                                    synchronized(opusPendingLock) { opusPending.isNotEmpty() } ||
                                    (
                                        isLowMemoryDevice &&
                                            snapshot.queuedChunks < minBufferedPrefetchChunks
                                    )
                                )
                        if (
                            !skipOpusCatchupWhileWarming &&
                            snapshot.queuedChunks > 0 &&
                            snapshot.bufferAheadMs < restartDropTriggerAheadMs
                        ) {
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_CATCHUP
                            val targetAheadMs = if (forceResyncMode) resyncMinBufferMs else minBufferMs
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
                            val targetAheadMs = resyncMinBufferMs
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
                        if (snap2.queuedChunks >= prestartBacklogChunkThreshold && snap2.bufferAheadMs < restartMinAheadMs) {
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_BACKLOG_TRIM
                            val targetAheadMs = if (forceResyncMode) resyncMinBufferMs else minBufferMs
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

                        var snapForStart = jitter.snapshot()

                        // Calculate effective buffer ahead accounting for playout offset
                        // Chunks will actually be needed playoutOffsetUs in the future (negative offset = sooner)
                        val effectiveBufferAheadMs = snapForStart.bufferAheadMs + (playoutOffsetUs / 1000L)

                        val startMinMs = if (forceResyncMode) resyncMinBufferMs else minBufferMs
                        val startMaxMs = if (forceResyncMode) resyncMaxStartAheadMs else maxStartAheadMs
                        val discontinuityStartMinMs = -80L
                        val discontinuityStartMaxMs = 250L
                        val activeStartMinMs =
                            if (inDiscontinuityMode) discontinuityStartMinMs else startMinMs
                        val activeStartMaxMs =
                            if (inDiscontinuityMode) discontinuityStartMaxMs else startMaxMs

                        if (snapForStart.bufferAheadMs <= activeStartMaxMs) {
                            prestartFarAheadSinceMs = 0L
                        }

                        val nowMsPrestart = System.currentTimeMillis()
                        val scheduledPrefetchWaitedMs =
                            if (prestartFarAheadSinceMs > 0L) {
                                nowMsPrestart - prestartFarAheadSinceMs
                            } else {
                                0L
                            }
                        val scheduledPrefetchTimedOut =
                            prestartFarAheadSinceMs > 0L &&
                                scheduledPrefetchWaitedMs >= scheduledPrefetchMaxWaitMs

                        // Scheduled prefetch: keep decoding while server clock catches the head timestamp.
                        if (
                            shouldEnterScheduledPrefetchWait(
                                snap = snapForStart,
                                activeStartMaxMs = activeStartMaxMs,
                                restartMinQueued = restartMinQueued,
                                scheduledPrefetchTimedOut = scheduledPrefetchTimedOut,
                                minBufferedPrefetchChunks = minBufferedPrefetchChunks,
                            )
                        ) {
                            if (prestartFarAheadSinceMs == 0L) {
                                prestartFarAheadSinceMs = nowMsPrestart
                            }
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_SCHEDULED_WAIT
                            publishPlaybackDiagnostics(
                                snapshot = snapForStart,
                                recoveryStatus = playbackRecoveryStatus,
                                clockReady = clockReadyForPlayback,
                                lateRestartLoops = prestartLateRestartLoops,
                            )
                            delay(50)
                            continue
                        }

                        // Start window is based on raw ahead; effective offset is handled in scheduling.
                        val canStartNormally =
                            snapForStart.queuedChunks >= restartMinQueued &&
                                snapForStart.bufferAheadMs in activeStartMinMs..activeStartMaxMs

                        val skipOpusLateRestartWhileWarming =
                            codec == "opus" &&
                                (snapForStart.queuedChunks < prestartBacklogChunkThreshold ||
                                    synchronized(opusPendingLock) { opusPending.isNotEmpty() })

                        // Recovery path: when network handoff leaves us perpetually late, don't deadlock.
                        // Start anyway after ~1s of late restarts and let catch-up/drop logic recover.
                        if (
                            shouldIncrementLateRestartLoops(
                                skipOpusLateRestartWhileWarming = skipOpusLateRestartWhileWarming,
                                canStartNormally = canStartNormally,
                                snap = snapForStart,
                                restartMinQueued = restartMinQueued,
                                restartMinAheadMs = restartMinAheadMs,
                            )
                        ) {
                            prestartLateRestartLoops++
                        } else if (canStartNormally) {
                            // Do not reset the counter when ahead oscillates around restartMinAheadMs
                            // while still outside the normal start window — that prevented force-late-start.
                            prestartLateRestartLoops = 0
                        }

                        // If we remain late for a while, allow bounded late-start recovery instead of
                        // endless prestart dropping (which can deadlock playback on constrained devices).
                        val canStartLateRecovery =
                            prestartLateRestartLoops >= 20 &&
                                snapForStart.queuedChunks >= restartMinQueued &&
                                snapForStart.bufferAheadMs >= -50L

                        val forceLateStart =
                            prestartLateRestartLoops >= forceStartAfterLoops &&
                                snapForStart.queuedChunks >= restartMinQueued &&
                                snapForStart.bufferAheadMs >= -40L

                        val canStartBufferedPrefetch =
                            isBufferedPrefetchReady(
                                snapForStart,
                                activeStartMaxMs,
                                minBufferedPrefetchChunks,
                            )

                        val canStartOpusWarmLowMem =
                            isLowMemoryDevice &&
                            codec == "opus" &&
                            isOpusDecoderWarm() &&
                            snapForStart.queuedChunks >= minWarmStartQueued &&
                            snapForStart.bufferAheadMs >= -warmStartMaxLateMs &&
                            snapForStart.bufferAheadMs <= activeStartMaxMs + 50L

                        val canStartScheduledPrefetch =
                            snapForStart.queuedChunks >= restartMinQueued &&
                                snapForStart.bufferAheadMs > activeStartMaxMs &&
                                snapForStart.bufferAheadMs <= MAX_SCHEDULED_PREFETCH_AHEAD_MS &&
                                scheduledPrefetchTimedOut &&
                                !canStartBufferedPrefetch

                        val canStart =
                            canStartNormally ||
                                canStartLateRecovery ||
                                forceLateStart ||
                                canStartOpusWarmLowMem ||
                                canStartBufferedPrefetch ||
                                canStartScheduledPrefetch

                        if (streamEnded) {
                            delay(50)
                            continue
                        }

                        if (canStart) {
                            prestartFarAheadSinceMs = 0L
                            if (canStartOpusWarmLowMem && !canStartNormally) {
                                val pullForwardUs =
                                    if (snapForStart.bufferAheadMs < 0L) {
                                        (-snapForStart.bufferAheadMs + 50L) * 1000L
                                    } else {
                                        0L
                                    }
                                playoutOffsetAdjustmentUs = -pullForwardUs
                                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START
                                recordRecoveryEvent(
                                    PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START,
                                    "mode=warm_lowmem ahead=${snapForStart.bufferAheadMs}ms " +
                                        "pullForwardMs=${pullForwardUs / 1000} queued=${snapForStart.queuedChunks} " +
                                        "decodeAvgUs=$decodeLatencyUs",
                                )
                            } else if (canStartBufferedPrefetch && !canStartNormally) {
                                val pullForwardUs =
                                    ((snapForStart.bufferAheadMs - activeStartMaxMs).coerceAtLeast(0L)) * 1000L
                                playoutOffsetAdjustmentUs = -pullForwardUs
                                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START
                                recordRecoveryEvent(
                                    PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START,
                                    "mode=lowmem_pull_forward ahead=${snapForStart.bufferAheadMs}ms " +
                                        "pullForwardMs=${pullForwardUs / 1000} queued=${snapForStart.queuedChunks}",
                                )
                            } else if (canStartScheduledPrefetch && !canStartNormally) {
                                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START
                                recordRecoveryEvent(
                                    PlaybackDiagnostics.STATUS_PRESTART_PREFETCH_START,
                                    "mode=scheduled_timeout ahead=${snapForStart.bufferAheadMs}ms " +
                                        "queued=${snapForStart.queuedChunks} waitedMs=$scheduledPrefetchWaitedMs",
                                )
                            } else if ((forceLateStart || canStartLateRecovery) && !canStartNormally) {
                                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_LATE_START_RECOVERY
                                recordRecoveryEvent(
                                    PlaybackDiagnostics.STATUS_LATE_START_RECOVERY,
                                    "ahead=${snapForStart.bufferAheadMs}ms effective=$effectiveBufferAheadMs ms " +
                                        "queued=${snapForStart.queuedChunks} loops=$prestartLateRestartLoops",
                                )
                                // If we're more than 100ms behind on forced late-start, trigger resync to snap to live
                                // instead of waiting for slow catch-up via playback speed adjustment
                                if (effectiveBufferAheadMs < -100) {
                                    triggerForceResync("late_start_too_far_behind")
                                    nextStartAttemptUs = nowUs() + 50_000L
                                    continue
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
                                continue
                            }
                            outputStartedAtUs = nowUs()
                            if (inDiscontinuityMode) {
                                inDiscontinuityMode = false
                            }

                            sendClientStateSynchronized()
                            prestartLateRestartLoops = 0
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
                        } else {
                            publishPlaybackDiagnostics(
                                snapshot = snapForStart,
                                recoveryStatus = playbackRecoveryStatus,
                                clockReady = clockReadyForPlayback,
                                lateRestartLoops = prestartLateRestartLoops,
                            )
                            delay(10)
                            continue
                        }
                    }

                    val chunk = jitter.pollPlayable(nowUs(), lateDropUs)
                    if (chunk == null) {
                        if (jitter.isEmpty() && !streamEnded) {
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_UNDERRUN
                        }
                        publishPlaybackDiagnostics(
                            snapshot = jitter.snapshot(),
                            recoveryStatus = playbackRecoveryStatus,
                            clockReady = true,
                            serverLatenessMs = lastPublishedServerLatenessMs,
                        )
                        delay(2)
                        continue
                    }

                    val pcmData = chunk.pcmData
                    if (pcmData.isEmpty()) {
                        continue
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

                    // Calculate effective playout offset: pipeline delay + measured codec decode latency + device adjustment
                    // Subtract staticDelayUs to schedule audio earlier, compensating for external device delay
                    val startupRampFactor =
                        when {
                            sinceOutputStartUs == Long.MAX_VALUE -> 1.0
                            sinceOutputStartUs >= startupPlayoutOffsetRampUs -> 1.0
                            else -> sinceOutputStartUs.toDouble() / startupPlayoutOffsetRampUs.toDouble()
                        }
                    val rampedBasePlayoutOffsetUs = (playoutOffsetUs.toDouble() * startupRampFactor).toLong()
                    // Decode runs before jitter (ingress path); only pipeline offset applies at playout.
                    val totalPlayoutOffsetUs = rampedBasePlayoutOffsetUs + playoutOffsetAdjustmentUs - staticDelayUs

                    // Convert server timestamp to client time using Kalman filter offset (same client "now" as earlyUs)
                    val localPlayUs =
                        clock.convertServerToClient(effectiveServerTsUs, nowUsForSchedule) + totalPlayoutOffsetUs
                    val now = nowUsForSchedule
                    val earlyUs = localPlayUs - now

                    // Server-time lateness: how many ms the chunk is past its server timestamp.
                    // Positive = late; negative = ahead of server time.
                    //
                    // Derivation:
                    //   earlyUs = localPlayUs - now
                    //           = (clock.convertServerToClient(serverTs) + totalPlayoutOffsetUs) - now
                    //   → earlyUs - totalPlayoutOffsetUs = clock.convertServerToClient(serverTs) - now
                    //   → -(earlyUs - totalPlayoutOffsetUs) = now - clock.convertServerToClient(serverTs)
                    //                                       ≈ now_server - serverTs   (offset ≈ 0)
                    //
                    // This is independent of staticDelayUs and playoutOffsetUs, so threshold
                    // checks based on it work correctly even when the server has applied a large
                    // static delay (e.g. 754 ms on Echo Show 8) that would otherwise make earlyUs
                    // permanently very negative and trigger false audio-cut / catch-up events.
                    val serverLatenessMs = -(earlyUs - totalPlayoutOffsetUs) / 1000L

                    if (forceResyncMode) {
                        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_FORCE_RESYNC_RUNTIME
                        // If still late during forced resync, drop to near-live instead of gradually drifting.
                        // Compare against server-time lateness so that devices with large staticDelayMs
                        // or pipeline offset don't spuriously discard all buffered audio.
                        if (serverLatenessMs > 80L) {
                            val dropped = jitter.dropWhileLate(nowUs(), 30_000L)
                            if (dropped > 0) {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastForceResyncLogMs >= 1000L) {
                                    lastForceResyncLogMs = nowMs
                                    Log.w(tag, "force-resync runtime: dropped=$dropped serverLate=${serverLatenessMs}ms early=${earlyUs / 1000}ms")
                                }
                            }
                            continue
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
                    // Uses server-time lateness instead of earlyMs so that a large staticDelayMs
                    // (e.g. 754 ms set by the server for Echo Show 8) does not cause continuous
                    // false-positive cuts that produce the buffer-drain / drop cycle.
                    val nowMs = System.currentTimeMillis()
                    val timeSinceLastCutMs = nowMs - lastAudioCutMs
                    val startupCutGraceActive = sinceOutputStartUs < startupCutGraceUs
                    val opusStartupGraceActive =
                        codec == "opus" && sinceOutputStartUs < OPUS_STARTUP_AUDIO_CUT_GRACE_US
                    val effectiveOutOfSyncThresholdMs =
                        when {
                            opusStartupGraceActive -> audioOutOfSyncThresholdMs + 500L
                            startupCutGraceActive -> audioOutOfSyncThresholdMs + 120L
                            else -> audioOutOfSyncThresholdMs
                        }
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
                        continue
                    }

                    // Debug: log first few scheduling attempts
                    if (audioScheduleDebugCount < 3) {
                        audioScheduleDebugCount++
                        Log.d("SendspinPcmClient", "AudioSchedule: serverTs=$effectiveServerTsUs localPlay=$localPlayUs now=$now early=${earlyUs / 1000}ms serverLate=${serverLatenessMs}ms playout=${totalPlayoutOffsetUs / 1000}ms")
                    }

                    // If we're behind by a lot in server time, drop chunks to catch up (audible effect).
                    // Use server-time lateness so a large staticDelayMs does not trigger spurious catch-up.
                    val dropLateThresholdMs = dropLateUs / 1000L
                    if (serverLatenessMs > dropLateThresholdMs) {
                        playbackRecoveryStatus = PlaybackDiagnostics.STATUS_CATCHUP_DROP
                        var dropped = 1
                        val maxDrops = 50 // Prevent unbounded dropping that causes ANR
                        val catchupStart = nowUs()
                        val targetLateThresholdMs = targetLateUs / 1000L
                        while (dropped < maxDrops) {
                            val next = jitter.pollPlayable(nowUs(), Long.MAX_VALUE) ?: break
                            val nextPcm = next.pcmData
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
                        continue
                    }

                    playbackRecoveryStatus = PlaybackDiagnostics.STATUS_PLAYING
                    publishPlaybackDiagnostics(
                        snapshot = jitter.snapshot(),
                        recoveryStatus = PlaybackDiagnostics.STATUS_PLAYING,
                        clockReady = true,
                        serverLatenessMs = serverLatenessMs,
                    )

                    // Sleep until we're exactly one pipeline-latency before the intended play time.
                    // This ensures data reaches the speaker at the correct moment regardless of buffer depth.
                    val pipelineLatencyUs = output.getEstimatedPipelineLatencyUs()
                    if (earlyUs > pipelineLatencyUs) {
                        delayKeepingPlaybackAlive((earlyUs - pipelineLatencyUs) / 1000)
                    }

                    if (streamEnded) {
                        delay(10)
                        continue
                    }

                    val ok = output.writePcm(pcmData)
                    if (!ok) {
                        if (streamEnded) {
                            delay(10)
                            continue
                        }
                        Log.w(tag, "PCM write failed; restarting output")
                        output.stop()
                        outputStartedAtUs = 0L
                        triggerForceResync("pcm_write_failed")
                        val backoffNowUs = nowUs()
                        nextStartAttemptUs = backoffNowUs + (restartBackoffMs * 1000L)
                        restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(3000L)
                        continue
                    }
                }
            }
    }

    private fun handleText(text: String) {
        lastMessageReceivedMs = System.currentTimeMillis()
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            if (type != "server/time") {
                Log.i(
                    tag,
                    "<<<< RECV: $type | ${text.take(200)}${if (text.length > 200) "..." else ""}",
                )
            }

            when (type) {
                "server/hello" -> {
                    handshakeComplete = true
                    val activeRoles =
                        payload.optJSONArray("active_roles")?.let { arr ->
                            (0 until arr.length()).joinToString(",") { arr.getString(it) }
                        } ?: ""

                    val hasController = activeRoles.contains("controller")
                    val hasMetadata = activeRoles.contains("metadata")
                    Log.i(
                        tag,
                        "Active roles: $activeRoles, hasController: $hasController, hasMetadata: $hasMetadata",
                    )

                    onUiUpdate {
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
                    val volumePercent = getActualSystemVolume()
                    sendClientStateSynchronized(volume = volumePercent, muted = false)
                }

                "server/time" -> {
                    // Per Sendspin spec: all timestamps are monotonic microseconds.
                    // Server uses its own monotonic clock; client uses System.nanoTime()/1000.
                    // No epoch or unit conversion is needed -- the Kalman filter tracks
                    // the offset between the two monotonic clocks directly.
                    val clientTx = payload.getLong("client_transmitted")
                    val sRecv = payload.getLong("server_received")
                    val sTx = payload.getLong("server_transmitted")
                    val clientRx = nowUs()

                    val rtt = clientRx - clientTx
                    Log.d(tag, "server/time: T1=$clientTx T2=$sRecv T3=$sTx T4=$clientRx rtt=${rtt}us")

                    clock.onServerTime(clientTx, clientRx, sRecv, sTx)
                }

                "stream/start" -> {
                    streamEnded = false // Reset flag when new stream starts
                    prestartLateRestartLoops = 0
                    prestartFarAheadSinceMs = 0L
                    opusWarmupDone = false
                    lastChunkServerTimestampUs = Long.MIN_VALUE // Reset discontinuity detector
                    inDiscontinuityMode = false
                    audioScheduleDebugCount = 0
                    playoutOffsetUs = -50_000L // Reset to default on new stream
                    playoutOffsetAdjustmentUs = 0L // Clear any accumulated adjustment too
                    outputStartedAtUs = 0L

                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        stopOpusDecodeThread()
                        opusDecodeErrorLogs = 0
                        codec = player.optString("codec", codec)
                        val streamSampleRate = player.optInt("sample_rate", sampleRate)
                        val streamChannels = player.optInt("channels", channels)
                        bitDepth = player.optInt("bit_depth", bitDepth)
                        if (codec == "opus") {
                            // Decoder output is always 16-bit PCM; AudioTrack must match.
                            bitDepth = 16
                            try {
                                val existing = opusDecoder
                                val reusingDecoder =
                                    existing != null &&
                                        existing.sampleRate == streamSampleRate &&
                                        existing.channels == streamChannels
                                if (reusingDecoder) {
                                    existing.reset()
                                } else {
                                    opusDecoder = OpusDecoder(streamSampleRate, streamChannels)
                                    decodeLatencyUs = 0L
                                    decodeLatencySamples.clear()
                                }
                                sampleRate = streamSampleRate
                                channels = streamChannels
                                opusWarmupDone = true
                                ensureOpusDecodeThread()
                                opusDecodeHandler?.post { drainOpusPendingQueue() }
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to create Opus decoder", e)
                                opusDecoder = null
                                sendClientStateError()
                            }
                        } else {
                            opusDecoder = null
                        }
                        playAtServerUs =
                            if (player.has("play_at")) {
                                player.optLong(
                                    "play_at",
                                    Long.MIN_VALUE,
                                )
                            } else {
                                Long.MIN_VALUE
                            }

                        onUiUpdate {
                            it.copy(
                                status = "stream/start",
                                streamDesc = "$codec ${sampleRate / 1000}kHz ${channels}ch ${bitDepth}bit",
                            )
                        }

                        // Use pause() to clear buffer for new stream, preserving track for resume/reuse
                        output.pause()

                        jitter.clear()
                    }
                }

                "stream/clear" -> {
                    jitter.clear()
                    opusDecoder?.reset()
                }

                "stream/end" -> {
                    streamEnded = true
                    prestartLateRestartLoops = 0
                    prestartFarAheadSinceMs = 0L
                    playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE
                    lastRecoveryEvent = ""
                    stopOpusDecodeThread()
                    // Pause instead of stop to allow reuse
                    output.pause()
                    outputStartedAtUs = 0L
                    jitter.clear()
                    opusDecoder = null
                    playAtServerUs = Long.MIN_VALUE
                    publishPlaybackDiagnostics(
                        snapshot = jitter.snapshot(),
                        recoveryStatus = PlaybackDiagnostics.STATUS_IDLE,
                        clockReady = isClockReadyForPlayback(),
                    )
                    onUiUpdate {
                        it.copy(
                            status = "stream/end",
                            streamDesc = "",
                            playbackRecoveryStatus = PlaybackDiagnostics.STATUS_IDLE,
                        )
                    }
                }

                "group/update" -> {
                    val playbackState = payload.optString("playback_state", "")
                    val groupName = payload.optString("group_name", "")
                    onUiUpdate { it.copy(playbackState = playbackState, groupName = groupName) }
                }

                "server/state" -> {
                    val controller = payload.optJSONObject("controller")
                    if (controller != null) {
                        val volume = controller.optInt("volume", 100)
                        val muted = controller.optBoolean("muted", false)
                        val supportedCommands =
                            controller.optJSONArray("supported_commands")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }.toSet()
                            } ?: emptySet()

                        onUiUpdate {
                            it.copy(
                                groupVolume = volume,
                                groupMuted = muted,
                                supportedCommands = supportedCommands,
                            )
                        }
                    }

                    // Handle metadata updates - ONLY update fields that are present
                    val metadata = payload.optJSONObject("metadata")
                    if (metadata != null) {
                        onUiUpdate { currentState ->
                            var newState = currentState

                            if (metadata.has("timestamp")) {
                                newState =
                                    newState.copy(metadataTimestamp = metadata.getLong("timestamp"))
                            }

                            if (metadata.has("title")) {
                                newState =
                                    newState.copy(
                                        trackTitle =
                                            if (metadata.isNull("title")) {
                                                null
                                            } else {
                                                metadata.getString(
                                                    "title",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("artist")) {
                                newState =
                                    newState.copy(
                                        trackArtist =
                                            if (metadata.isNull("artist")) {
                                                null
                                            } else {
                                                metadata.getString(
                                                    "artist",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("album")) {
                                newState =
                                    newState.copy(
                                        albumTitle =
                                            if (metadata.isNull("album")) {
                                                null
                                            } else {
                                                metadata.getString(
                                                    "album",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("album_artist")) {
                                newState =
                                    newState.copy(
                                        albumArtist =
                                            if (metadata.isNull("album_artist")) {
                                                null
                                            } else {
                                                metadata.getString(
                                                    "album_artist",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("year")) {
                                newState =
                                    newState.copy(
                                        trackYear =
                                            if (metadata.isNull("year")) {
                                                null
                                            } else {
                                                metadata.getInt(
                                                    "year",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("track")) {
                                newState =
                                    newState.copy(
                                        trackNumber =
                                            if (metadata.isNull("track")) {
                                                null
                                            } else {
                                                metadata.getInt(
                                                    "track",
                                                )
                                            },
                                    )
                            }

                            // Parse progress object
                            if (metadata.has("progress")) {
                                val progress = metadata.optJSONObject("progress")
                                if (progress != null) {
                                    newState =
                                        newState.copy(
                                            trackProgress = progress.getLong("track_progress"),
                                            trackDuration = progress.getLong("track_duration"),
                                            playbackSpeed = progress.getInt("playback_speed"),
                                        )
                                } else {
                                    // progress is null - clear it
                                    newState =
                                        newState.copy(
                                            trackProgress = null,
                                            trackDuration = null,
                                            playbackSpeed = null,
                                        )
                                }
                            }

                            if (metadata.has("repeat")) {
                                newState =
                                    newState.copy(
                                        repeatMode =
                                            if (metadata.isNull("repeat")) {
                                                null
                                            } else {
                                                metadata.getString(
                                                    "repeat",
                                                )
                                            },
                                    )
                            }

                            if (metadata.has("shuffle")) {
                                newState =
                                    newState.copy(
                                        shuffleEnabled =
                                            if (metadata.isNull("shuffle")) {
                                                null
                                            } else {
                                                metadata.getBoolean(
                                                    "shuffle",
                                                )
                                            },
                                    )
                            }

                            newState
                        }

                        Log.i(tag, "Metadata update received")
                    }
                }

                "server/command" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        val command = player.optString("command", "")
                        when (command) {
                            "volume" -> {
                                val volume = player.optInt("volume", 100)
                                Log.i(tag, "server/command volume: $volume (server commanded)")
                                // Update UI state AND notify the onUiUpdate callback so ViewModel can set system volume
                                onUiUpdate {
                                    it.copy(
                                        playerVolume = volume,
                                        playerVolumeFromServer = true,
                                    )
                                }
                                // Echo back in state
                                sendClientStatePlayer(volume = volume, muted = null)
                            }

                            "mute" -> {
                                val muted = player.optBoolean("mute", false)
                                Log.i(tag, "server/command mute: $muted (server commanded)")
                                // Update UI state AND notify the onUiUpdate callback
                                onUiUpdate {
                                    it.copy(
                                        playerMuted = muted,
                                        playerMutedFromServer = true,
                                    )
                                }
                                // Echo back in state
                                sendClientStatePlayer(volume = null, muted = muted)
                            }

                            "set_static_delay" -> {
                                val newDelayMs =
                                    player.optLong("static_delay_ms", 0L)
                                        .coerceIn(0L, 5000L)
                                Log.i(tag, "server/command set_static_delay: ${newDelayMs}ms (server commanded)")
                                staticDelayUs = newDelayMs * 1000L
                                // Notify service to persist the new delay
                                onUiUpdate {
                                    it.copy(
                                        staticDelayMs = newDelayMs,
                                        staticDelayMsFromServer = true,
                                    )
                                }
                                // Echo back in state
                                sendClientStatePlayer(staticDelayMs = newDelayMs)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Bad JSON: ${t.message}", t)
        }
    }

    private fun handleBinary(data: ByteArray) {
        lastMessageReceivedMs = System.currentTimeMillis()
        if (!handshakeComplete) return
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF

        when (type) {
            4 -> {
                // Audio chunk (player role)
                if (codec != "pcm" && codec != "opus") return
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

                if (codec == "opus") {
                    if (opusDecoder == null) {
                        return
                    }
                    enqueueOpusChunk(tsServerUs, encodedData)
                } else {
                    jitter.offer(tsServerUs, encodedData)
                }
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
    fun trimAudioBufferCritical() {
        // For LAN: even in critical memory, maintain minimum 150ms buffer (150 chunks)
        // Clearing completely causes playback errors
        val currentSize = jitter.size()
        val targetSize = (currentSize / 3).coerceAtMost(200).coerceAtLeast(150)
        Log.e(
            tag,
            "CRITICAL memory trim: reducing buffer from $currentSize to $targetSize chunks (minimum 150 for LAN stability)",
        )
        jitter.trimTo(targetSize)
        stopOpusDecodeThread()
        opusDecoder = null
        // Update UI to reflect buffer state
        val snapshot = jitter.snapshot()
        onUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
            )
        }
    }

    fun trimAudioBufferModerate() {
        val currentSize = jitter.size()
        // For LAN: keep at least 200 chunks (20ms) even under memory pressure
        val targetSize = (currentSize / 2).coerceAtMost(300).coerceAtLeast(200)
        Log.w(tag, "MODERATE memory trim: reducing buffer from $currentSize to $targetSize chunks")
        jitter.trimTo(targetSize)
        val snapshot = jitter.snapshot()
        onUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
            )
        }
    }

    fun trimAudioBufferLow() {
        val currentSize = jitter.size()
        // For LAN: keep at least 200 chunks (20ms) to avoid streaming issues
        // This is still aggressive but won't cause playback glitches
        val targetSize = (currentSize / 2).coerceAtMost(300).coerceAtLeast(200)
        Log.w(
            tag,
            "LOW memory trim: reducing buffer from $currentSize to $targetSize chunks (minimum 200 for stable playback)",
        )
        jitter.trimTo(targetSize)
        val snapshot = jitter.snapshot()
        onUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
            )
        }
    }

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
}
