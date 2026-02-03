package com.sendspinlite

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
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
    private val context: android.content.Context
) {
    private val tag = "SendspinPcmClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)  // Increased from 10s to 30s to be more tolerant
        .build()

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
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L  // Less than 2GB total RAM
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
            100  // Default to max if we can't read
        }
    }

    private val clock = ClockSync()
    private val jitter = AudioJitterBuffer(clock)
    private val output: PcmAudioOutput = PcmAudioOutput()

    private var timeLoopJob: Job? = null
    private var playoutJob: Job? = null
    private var statsJob: Job? = null
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
    private var enableOpusCodec: Boolean = false

    private var opusDecoder: OpusDecoder? = null

    private var playAtServerUs: Long = Long.MIN_VALUE

    // Pipeline delay offset (µs). Compensates for Android audio pipeline latency + codec decode latency.
    // -75ms balances responsiveness with reliable buffer management across varying network conditions
    @Volatile
    private var playoutOffsetUs: Long = -75_000L  // Default -75ms

    // Track codec decode latency to adjust playoutOffsetUs dynamically
    private var decodeLatencyUs: Long = 0L  // Running average of decode time
    private val decodeLatencySamples = mutableListOf<Long>()
    private val maxDecodeLatencySamples = 30  // Keep rolling average of last 30 frames

    // Track last sent error state to prevent spam
    private var lastErrorStateSent: Long = 0L
    private val errorStateThrottleMs = 1000L  // Only send error state once per second

    // Track whether a stream has ended to avoid spurious error messages
    @Volatile
    private var streamEnded: Boolean = false

    // Track last chunk timestamp to detect discontinuities (skips)
    private var lastChunkServerTimestampUs: Long = Long.MIN_VALUE
    private val discontinuityThresholdUs = 500_000L  // 500ms = significant skip threshold

    // Flag to track if we're in "discontinuity recovery mode" (just after skip/seek)
    // In this mode, use chunk count only for startup, ignore time-based buffer calculations
    @Volatile
    private var inDiscontinuityMode: Boolean = false
    private val discontinuityModeTimeoutMs = 1000L  // Exit mode after 1 second of normal chunks

    // Throttle UI updates to prevent excessive recomposition on low-memory devices
    private var lastUiUpdateUs: Long = 0L
    private val uiUpdateThrottleMs =
        if (isLowMemoryDevice) 250L else 100L  // Balanced throttle: 10 updates/sec (normal), 4 updates/sec (low-mem)

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        playoutOffsetUs = clamped * 1000L
        Log.i(tag, "playoutOffset=${clamped}ms")
    }

    fun setEnableOpusCodec(enabled: Boolean) {
        enableOpusCodec = enabled
        Log.i(tag, "enableOpusCodec=$enabled")
    }

    suspend fun connect() {
        val req = Request.Builder().url(wsUrl).build()

        // Initialize UI with actual system volume before connecting
        val actualVolume = getActualSystemVolume()
        onUiUpdate {
            it.copy(
                status = "connecting...",
                connected = false,
                groupVolume = actualVolume
            )
        }

        // Extract host and port from URL for port checking
        try {
            val url = java.net.URL(wsUrl)
            val host = url.host ?: "localhost"
            val port = if (url.port == -1) {
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
                            connected = false
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
                            connected = false
                        )
                    }
                    teardown("server_unreachable")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error during port check, proceeding with connection anyway: ${e.message}")
        }

        ws = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WS open")
                isConnected.set(true)
                handshakeComplete = false
                onUiUpdate { it.copy(status = "ws_open", connected = true) }
                sendClientHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handleBinary(bytes.toByteArray())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(tag, "WS closed code=$code reason=$reason")
                teardown("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS failure: ${t.message}", t)
                teardown("failure: ${t.message}")
            }
        })
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

        // Stop the playback loop FIRST to prevent further writes to audio buffer
        playoutJob?.cancel()
        playoutJob = null

        timeLoopJob?.cancel(); timeLoopJob = null
        statsJob?.cancel(); statsJob = null
        watchdogJob?.cancel(); watchdogJob = null

        // Stop audio output - this prevents buffered data from continuing to play
        output.stop()
        jitter.clear()
        opusDecoder = null
        playAtServerUs = Long.MIN_VALUE

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
                // Clear metadata
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
                shuffleEnabled = null
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
            Thread.sleep(50)  // Give audio subsystem time to stop and flush
        } catch (e: Exception) {
            Log.w(tag, "Error during cleanup/sleep", e)
        }

        try {
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.w(tag, "Error shutting down OkHttpClient", e)
        }

        Log.i(tag, "Resources cleaned up")
    }

    /**
     * Measure and track decode latency for compensation.
     * Updates decodeLatencyUs with rolling average of codec decode times.
     * This ensures playback timing accounts for actual codec processing delay.
     */
    private fun recordDecodeLatency(decodeTimeUs: Long) {
        decodeLatencySamples.add(decodeTimeUs)
        if (decodeLatencySamples.size > maxDecodeLatencySamples) {
            decodeLatencySamples.removeAt(0)
        }

        // Calculate moving average
        decodeLatencyUs = decodeLatencySamples.average().toLong()
    }

    private fun throttledUiUpdate(block: (PlayerViewModel.UiState) -> PlayerViewModel.UiState) {
        val now = nowUs()
        if (now - lastUiUpdateUs >= uiUpdateThrottleMs * 1000L) {
            lastUiUpdateUs = now
            onUiUpdate(block)
        }
    }

    private fun sendJson(type: String, payload: JSONObject) {
        val obj = JSONObject().put("type", type).put("payload", payload)
        val json = obj.toString()
        if (type != "client/time") Log.i(
            tag,
            ">>>> SEND: $type | ${json.take(200)}${if (json.length > 200) "..." else ""}"
        )
        ws?.send(json)
    }

    private fun buildPlayerSupportObject(): JSONObject {
        val supportedFormats = JSONArray()

        // Only include Opus if explicitly enabled by user
        if (enableOpusCodec) {
            supportedFormats
                .put(
                    JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 48000)
                        .put("bit_depth", 16)
                )
                .put(
                    JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 44100)
                        .put("bit_depth", 16)
                )
        }

        // Always include PCM with supported bit depths (16, 24, 32)
        for (sampleRate in listOf(48000, 44100)) {
            for (bitDepth in listOf(16, 24, 32)) {
                supportedFormats
                    .put(
                        JSONObject().put("codec", "pcm").put("channels", 2)
                            .put("sample_rate", sampleRate).put("bit_depth", bitDepth)
                    )
            }
        }

        val supportedCommands = JSONArray().put("volume").put("mute")

        return JSONObject()
            .put("supported_formats", supportedFormats)
            .put("buffer_capacity", 2_000_000)
            .put("supported_commands", supportedCommands)
    }

    private fun sendClientHello() {
        val hello = JSONObject()
            .put("client_id", clientId)
            .put("name", clientName)
            .put("version", 1)
            .put(
                "device_info", JSONObject()
                    .put("product_name", android.os.Build.MODEL)
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                    .put(
                        "software_version",
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    )
            )
            .put(
                "supported_roles", JSONArray()
                    .put("player@v1")
            )

        val playerSupport = buildPlayerSupportObject()
        hello.put("player@v1_support", playerSupport)
        hello.put("player_support", playerSupport)  // Legacy field for compatibility

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

    private fun sendClientStatePlayer(volume: Int? = null, muted: Boolean? = null) {
        val player = JSONObject().put("state", "synchronized")
        volume?.let { player.put("volume", it) }
        muted?.let { player.put("muted", it) }
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientGoodbye(reason: String) {
        sendJson("client/goodbye", JSONObject().put("reason", reason))
    }

    private fun sendClientStateSynchronized(volume: Int = 100, muted: Boolean = false) {
        val player =
            JSONObject().put("state", "synchronized").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientStateError(volume: Int = 100, muted: Boolean = true) {
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

        val player = JSONObject().put("state", "error").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun startTimeSyncLoop() {
        timeLoopJob?.cancel()
        timeLoopJob = scope.launch {
            var lastFrequencyLogMs: Long = 0

            while (isActive && isConnected.get()) {
                sendJson("client/time", JSONObject().put("client_transmitted", nowUs()))

                // ADAPTIVE FREQUENCY based on network conditions and clock stability
                val nextIntervalMs = clock.getRecommendedSyncFrequencyMs()

                // Log frequency changes for diagnostics
                if (nextIntervalMs != lastFrequencyLogMs) {
                    val networkQuality = clock.getNetworkConditionQuality()
                    val clockStability = clock.getClockStability()
                    Log.i(
                        tag,
                        "client/time frequency: ${lastFrequencyLogMs}ms → ${nextIntervalMs}ms " +
                                "network=$networkQuality stability=$clockStability "
                    )
                    lastFrequencyLogMs = nextIntervalMs
                }

                delay(nextIntervalMs)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isConnected.get()) {
                val snapshot = jitter.snapshot()

                // Signal that stats loop is alive
                lastStatsHeartbeatMs = System.currentTimeMillis()

                // Update UI with clock sync details - runs every 1 second for responsive updates
                throttledUiUpdate {
                    it.copy(
                        offsetUs = clock.estimatedOffsetUs(),
                        driftPpm = clock.estimatedDriftPpm(),
                        networkQuality = clock.getNetworkConditionQuality().toString(),
                        stability = clock.getClockStability().toString(),
                        connectionType = getConnectionType()
                    )
                }

                // Minimal logging to reduce memory pressure - skip formatted strings
                if (!isLowMemoryDevice && System.currentTimeMillis() % 9000 < 100) {
                    Log.i(
                        tag,
                        "stats: offset=${clock.estimatedOffsetUs()}us drift=${(clock.estimatedDriftPpm() * 1000).toLong() / 1000}ppm " +
                                "queued=${snapshot.queuedChunks} ahead~=${snapshot.bufferAheadMs}ms codec=$codec"
                    )
                }
                delay(1000L)  // Run every second for responsive stat updates
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
                            "Critical memory available (${availableMemMb}MB), clearing buffer"
                        )
                        trimAudioBufferCritical()
                    } else if (availableMemMb < 100) {
                        Log.w(
                            tag,
                            "Low memory available (${availableMemMb}MB), doing moderate trim"
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
        playoutJob = scope.launch {
            val minBufferMs = 50L  // Wait for 50ms buffer before starting playback

            // Normal "too-late" drop once we're running.
            val lateDropUs = 50_000L

            // Make offset changes audible by catching up (dropping) or slowing down (waiting).
            val dropLateUs = 80_000L
            val targetLateUs = 20_000L
            val maxEarlySleepMs = 50L

            // NEW: if output is stopped and the queue head is very late, we must drop until near-now,
            // otherwise bufferAheadMs stays negative and we never restart (queue grows forever).
            val restartKeepWithinUs = 20_000L      // bring head within 20ms late
            val restartMinAheadMs = -20L           // allow small negative ahead at start
            val restartMinQueued =
                1              // if we have any data after dropping, we can start

            while (isActive && isConnected.get()) {
                val snapshot = jitter.snapshot()

                // Signal that playback loop is alive
                lastPlaybackHeartbeatMs = System.currentTimeMillis()

                // Throttle UI updates to prevent excessive recomposition on low-memory devices
                throttledUiUpdate {
                    it.copy(
                        queuedChunks = snapshot.queuedChunks,
                        bufferAheadMs = snapshot.bufferAheadMs,
                        lateDrops = snapshot.lateDrops,
                        offsetUs = clock.estimatedOffsetUs(),
                        driftPpm = clock.estimatedDriftPpm()
                    )
                }

                if (!output.isStarted()) {
                    if (codec != "pcm" && codec != "opus") {
                        delay(50)
                        continue
                    }

                    // NEW: prevent deadlock when head is late (negative ahead) by dropping late chunks now.
                    if (snapshot.queuedChunks > 0 && snapshot.bufferAheadMs < restartMinAheadMs) {
                        val dropped = jitter.dropWhileLate(nowUs(), restartKeepWithinUs)
                        if (dropped > 0) {
                            Log.w(
                                tag,
                                "restart-catchup: dropped=$dropped head was late (ahead~${snapshot.bufferAheadMs}ms)"
                            )
                        }
                    }

                    val snap2 = jitter.snapshot()

                    // Calculate effective buffer ahead accounting for playout offset
                    // Chunks will actually be needed playoutOffsetUs in the future (negative offset = sooner)
                    val effectiveBufferAheadMs = snap2.bufferAheadMs + (playoutOffsetUs / 1000L)

                    // Progressive startup based on chunk count
                    // With many chunks queued (after skip), start earlier to prevent excessive buffering
                    // Each chunk at 48kHz stereo ~21ms, so:
                    // - 10 chunks = ~210ms, 20 chunks = ~420ms, 30 chunks = ~630ms, etc.
                    val canStart = when {
                        snap2.queuedChunks < restartMinQueued -> false

                        // Light buffering: wait for time-based threshold
                        snap2.queuedChunks < 15 ->
                            effectiveBufferAheadMs >= minBufferMs || effectiveBufferAheadMs >= restartMinAheadMs

                        // Moderate buffering (15-30 chunks = 300-630ms): start more eagerly
                        snap2.queuedChunks < 30 ->
                            effectiveBufferAheadMs >= 75L || snap2.bufferAheadMs >= 100L

                        // Heavy buffering (30-60 chunks = 630ms-1.2s): very aggressive
                        snap2.queuedChunks < 60 ->
                            effectiveBufferAheadMs >= 50L || snap2.bufferAheadMs >= 75L

                        // Excessive buffering (60+ chunks): start immediately
                        else -> true
                    }

                    if (canStart) {
                        output.start(sampleRate, channels, bitDepth)

                        // Initialize Opus decoder if needed
                        if (codec == "opus") {
                            opusDecoder = try {
                                OpusDecoder(sampleRate, channels)
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to create Opus decoder", e)
                                sendClientStateError()
                                delay(100)
                                continue
                            }
                        }

                        sendClientStateSynchronized()
                        Log.i(
                            tag,
                            "Audio output started sr=$sampleRate ch=$channels bd=$bitDepth codec=$codec (buffered=${snap2.bufferAheadMs}ms)"
                        )
                    } else {
                        delay(10)
                        continue
                    }
                }

                val chunk = jitter.pollPlayable(nowUs(), lateDropUs)
                if (chunk == null) {
                    if (jitter.isEmpty()) {
                        // Throttle error state messages to prevent spam (already throttled in sendClientStateError)
                        sendClientStateError()
                        output.flushSilence(20)
                    }
                    delay(2)
                    continue
                }

                // Decode if Opus, measuring latency
                val pcmData = if (codec == "opus") {
                    val decodeStart = nowUs()
                    val decoded = opusDecoder?.decode(chunk.pcmData) ?: ByteArray(0)
                    recordDecodeLatency(nowUs() - decodeStart)
                    decoded
                } else {
                    chunk.pcmData
                }

                if (pcmData.isEmpty()) {
                    Log.w(tag, "Empty PCM data after decode")
                    continue
                }

                val effectiveServerTsUs =
                    if (playAtServerUs != Long.MIN_VALUE) maxOf(
                        chunk.serverTimestampUs,
                        playAtServerUs
                    )
                    else chunk.serverTimestampUs

                // Calculate effective playout offset: pipeline delay + measured codec decode latency
                val totalPlayoutOffsetUs = playoutOffsetUs - decodeLatencyUs

                // Convert server timestamp to client time using Kalman filter offset
                val localPlayUs =
                    clock.convertServerToClient(effectiveServerTsUs) + totalPlayoutOffsetUs
                val now = nowUs()
                val earlyUs = localPlayUs - now

                // If we're behind by a lot, drop chunks to catch up (audible effect).
                if (earlyUs < -dropLateUs) {
                    var dropped = 1
                    val maxDrops = 50  // Prevent unbounded dropping that causes ANR
                    val catchupStart = nowUs()
                    while (dropped < maxDrops) {
                        val next = jitter.pollPlayable(nowUs(), Long.MAX_VALUE) ?: break
                        val nextPcm = if (codec == "opus") {
                            val decodeStart = nowUs()
                            val decoded = opusDecoder?.decode(next.pcmData) ?: ByteArray(0)
                            recordDecodeLatency(nowUs() - decodeStart)
                            decoded
                        } else {
                            next.pcmData
                        }

                        // Apply same decode latency compensation during catch-up as during normal playback
                        val nextTotalPlayoutOffsetUs = playoutOffsetUs - decodeLatencyUs
                        val nextLocalPlayUs =
                            clock.convertServerToClient(next.serverTimestampUs) + nextTotalPlayoutOffsetUs
                        val nextEarlyUs = nextLocalPlayUs - nowUs()
                        dropped++
                        if (nextEarlyUs >= -targetLateUs) {
                            if (nextPcm.isNotEmpty()) {
                                output.writePcm(nextPcm)
                            }
                            break
                        }
                        // Yield frequently (every 2 drops) to prevent ANR and allow other threads
                        if (dropped % 2 == 0) {
                            yield()
                        }
                    }
                    if (dropped > 1) {
                        Log.w(
                            tag,
                            "catch-up: dropped=$dropped (${(nowUs() - catchupStart) / 1000}ms) playoutOffset=${playoutOffsetUs / 1000}ms"
                        )
                    }
                    continue
                }

                if (earlyUs > 5_000) {
                    delay((earlyUs / 1000).coerceAtMost(maxEarlySleepMs))
                }

                output.writePcm(pcmData)
            }
        }
    }

    private fun handleText(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            if (type != "server/time") Log.i(
                tag,
                "<<<< RECV: $type | ${text.take(200)}${if (text.length > 200) "..." else ""}"
            )

            when (type) {
                "server/hello" -> {
                    handshakeComplete = true
                    val activeRoles = payload.optJSONArray("active_roles")?.let { arr ->
                        (0 until arr.length()).joinToString(",") { arr.getString(it) }
                    } ?: ""

                    val hasController = activeRoles.contains("controller")
                    val hasMetadata = activeRoles.contains("metadata")
                    Log.i(
                        tag,
                        "Active roles: $activeRoles, hasController: $hasController, hasMetadata: $hasMetadata"
                    )

                    onUiUpdate {
                        it.copy(
                            status = "server/hello",
                            activeRoles = activeRoles,
                            hasController = hasController,
                            hasMetadata = hasMetadata
                        )
                    }
                    startTimeSyncLoop()
                    startPlayoutLoop()
                    startStatsLoop()
                    startMemoryMonitoringLoop()
                    startWatchdogLoop()  // Monitor playback loop health

                    // Send initial state with actual Android volume
                    val volumePercent = getActualSystemVolume()
                    sendClientStateSynchronized(volume = volumePercent, muted = false)
                }

                "server/time" -> {
                    val clientTx = payload.getLong("client_transmitted")
                    val sRecv = payload.getLong("server_received")
                    val sTx = payload.getLong("server_transmitted")
                    val clientRx = nowUs()
                    clock.onServerTime(clientTx, clientRx, sRecv, sTx)
                }

                "stream/start" -> {
                    streamEnded = false  // Reset flag when new stream starts
                    lastChunkServerTimestampUs = Long.MIN_VALUE  // Reset discontinuity detector
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        codec = player.optString("codec", codec)
                        sampleRate = player.optInt("sample_rate", sampleRate)
                        channels = player.optInt("channels", channels)
                        bitDepth = player.optInt("bit_depth", bitDepth)
                        playAtServerUs =
                            if (player.has("play_at")) player.optLong(
                                "play_at",
                                Long.MIN_VALUE
                            ) else Long.MIN_VALUE

                        onUiUpdate {
                            it.copy(
                                status = "stream/start",
                                streamDesc = "$codec ${sampleRate}Hz ${channels}ch ${bitDepth}bit"
                            )
                        }

                        output.stop()
                        jitter.clear()
                        opusDecoder = null
                    }
                }

                "stream/clear" -> {
                    jitter.clear()
                    opusDecoder?.reset()
                }

                "stream/end" -> {
                    streamEnded = true
                    output.stop()
                    jitter.clear()
                    opusDecoder = null
                    playAtServerUs = Long.MIN_VALUE
                    onUiUpdate { it.copy(status = "stream/end", streamDesc = "") }
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
                                supportedCommands = supportedCommands
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
                                newState = newState.copy(
                                    trackTitle = if (metadata.isNull("title")) null else metadata.getString(
                                        "title"
                                    )
                                )
                            }

                            if (metadata.has("artist")) {
                                newState = newState.copy(
                                    trackArtist = if (metadata.isNull("artist")) null else metadata.getString(
                                        "artist"
                                    )
                                )
                            }

                            if (metadata.has("album")) {
                                newState = newState.copy(
                                    albumTitle = if (metadata.isNull("album")) null else metadata.getString(
                                        "album"
                                    )
                                )
                            }

                            if (metadata.has("album_artist")) {
                                newState = newState.copy(
                                    albumArtist = if (metadata.isNull("album_artist")) null else metadata.getString(
                                        "album_artist"
                                    )
                                )
                            }

                            if (metadata.has("year")) {
                                newState = newState.copy(
                                    trackYear = if (metadata.isNull("year")) null else metadata.getInt(
                                        "year"
                                    )
                                )
                            }

                            if (metadata.has("track")) {
                                newState = newState.copy(
                                    trackNumber = if (metadata.isNull("track")) null else metadata.getInt(
                                        "track"
                                    )
                                )
                            }

                            // Parse progress object
                            if (metadata.has("progress")) {
                                val progress = metadata.optJSONObject("progress")
                                if (progress != null) {
                                    newState = newState.copy(
                                        trackProgress = progress.getLong("track_progress"),
                                        trackDuration = progress.getLong("track_duration"),
                                        playbackSpeed = progress.getInt("playback_speed")
                                    )
                                } else {
                                    // progress is null - clear it
                                    newState = newState.copy(
                                        trackProgress = null,
                                        trackDuration = null,
                                        playbackSpeed = null
                                    )
                                }
                            }

                            if (metadata.has("repeat")) {
                                newState = newState.copy(
                                    repeatMode = if (metadata.isNull("repeat")) null else metadata.getString(
                                        "repeat"
                                    )
                                )
                            }

                            if (metadata.has("shuffle")) {
                                newState = newState.copy(
                                    shuffleEnabled = if (metadata.isNull("shuffle")) null else metadata.getBoolean(
                                        "shuffle"
                                    )
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
                                        playerVolumeFromServer = true
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
                                        playerMutedFromServer = true
                                    )
                                }
                                // Echo back in state
                                sendClientStatePlayer(volume = null, muted = muted)
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
                            "Stream discontinuity detected: jump=${timestampJumpUs / 1000}ms, clearing buffer and entering discontinuity recovery mode"
                        )
                        jitter.clear()
                        inDiscontinuityMode = true
                    }
                }

                lastChunkServerTimestampUs = tsServerUs
                jitter.offer(tsServerUs, encodedData)
            }
        }
    }

    private fun readInt64BE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFFL)
        return v
    }

    private fun nowUs(): Long = System.nanoTime() / 1000L

    // Memory pressure management
    fun trimAudioBufferCritical() {
        // For LAN: even in critical memory, maintain minimum 150ms buffer (150 chunks)
        // Clearing completely causes playback errors
        val currentSize = jitter.size()
        val targetSize = (currentSize / 3).coerceAtMost(200).coerceAtLeast(150)
        Log.e(
            tag,
            "CRITICAL memory trim: reducing buffer from $currentSize to $targetSize chunks (minimum 150 for LAN stability)"
        )
        jitter.trimTo(targetSize)
        opusDecoder = null  // Still free decoder
        // Update UI to reflect buffer state
        val snapshot = jitter.snapshot()
        onUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs
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
                bufferAheadMs = snapshot.bufferAheadMs
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
            "LOW memory trim: reducing buffer from $currentSize to $targetSize chunks (minimum 200 for stable playback)"
        )
        jitter.trimTo(targetSize)
        val snapshot = jitter.snapshot()
        onUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs
            )
        }
    }

    /**
     * Watchdog loop - detects if playback or stats threads are hung/blocked
     * Runs every 5 seconds and triggers recovery if no heartbeat detected
     */
    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(5000)  // Check every 5 seconds

                val now = System.currentTimeMillis()
                val playbackTimeout = 10_000L  // 10 seconds of no heartbeat = hung
                val statsTimeout = 10_000L

                val playbackDead = (now - lastPlaybackHeartbeatMs) > playbackTimeout
                val statsDead = (now - lastStatsHeartbeatMs) > statsTimeout

                if (playbackDead || statsDead) {
                    val msg = buildString {
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
     * Returns true if both playback and stats loops are active (heartbeating)
     */
    fun isHealthy(): Boolean {
        if (!isConnected.get()) return false
        if (!handshakeComplete) return false

        val now = System.currentTimeMillis()
        val playbackTimeout = 10_000L  // 10 seconds of no heartbeat = unhealthy
        val statsTimeout = 10_000L

        val playbackOk = (now - lastPlaybackHeartbeatMs) <= playbackTimeout
        val statsOk = (now - lastStatsHeartbeatMs) <= statsTimeout

        return playbackOk && statsOk
    }
}