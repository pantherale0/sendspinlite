package com.sendspinlite.service

import android.app.*
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.sendspinlite.client.ClientDiagnostics
import com.sendspinlite.client.ClientEvent
import com.sendspinlite.client.SendspinNativeClient
import com.sendspinlite.diagnostics.DiagnosticsDelta
import com.sendspinlite.network.ReconnectPolicy
import com.sendspinlite.system.AppMemoryPolicy
import com.sendspinlite.ui.MainActivity
import com.sendspinlite.ui.PlayerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile

class SendspinService : Service() {
    private val tag = "SendspinService"
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val auxiliaryLock = Any()

    private val mediaSessionCallback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                dispatchTransportCommand("play")
            }

            override fun onPause() {
                dispatchTransportCommand("pause")
            }

            override fun onSkipToNext() {
                dispatchTransportCommand("next")
            }

            override fun onSkipToPrevious() {
                dispatchTransportCommand("previous")
            }

            override fun onStop() {
                dispatchTransportCommand("stop")
            }
        }

    private var auxiliaryStarted = false
    private var uiStateCollectorsJob: Job? = null

    /** When false, diagnostics from the client only update notification/session essentials (kiosk path). */
    @Volatile
    var uiMirrorEnabled: Boolean = false

    private var client: SendspinNativeClient? = null
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val _uiState = MutableStateFlow(PlayerViewModel.UiState())
    val uiState: StateFlow<PlayerViewModel.UiState> = _uiState

    // Detect low-memory devices to disable expensive features (initialized in onCreate)
    private var isLowMemoryDevice = false
    private var isTV = false

    // Reconnection retry tracking
    private var reconnectJob: Job? = null
    private var reconnectRetryCount = 0

    // Unlimited reconnection attempts - will keep retrying until manually disconnected
    private var connectingStartedAtMs: Long = 0L
    private var connectWatchdogJob: Job? = null

    // Per-connection flow collectors. These MUST be cancelled on every disconnect/reconnect,
    // otherwise each connect() leaks two coroutines that keep the previous SendspinNativeClient
    // (and its buffers/audio output) reachable forever. Relying solely on a takeWhile() guard
    // is insufficient: once `client` is reassigned the old client's flows stop emitting, so the
    // guard never re-evaluates and the collector stays suspended, pinning the dead client in
    // memory. Under the boot path (background service, repeated health-recovery reconnects) this
    // accumulates until the heap is exhausted (OOM).
    private var diagnosticsJob: Job? = null
    private var eventsJob: Job? = null

    // Connection drop tracking
    private var hasEstablishedConnection = false
    private var dropCountedForCurrentOutage = false

    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L // Less than 2GB total RAM
            if (lowMemory) {
                Log.i(tag, "Low-memory device detected: disabling artwork and action buttons")
            }
            lowMemory
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device memory", e)
            false
        }
    }

    private fun checkIsTV(): Boolean {
        return try {
            val uiMode = resources.configuration.uiMode
            val isTV = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
            if (isTV) {
                Log.i(tag, "TV device detected: using simplified UI and auto-discovery")
            }
            isTV
        } catch (e: Exception) {
            Log.w(tag, "Failed to check device type", e)
            false
        }
    }

    // Track notification state to avoid redundant updates
    private var lastNotificationState: NotificationState? = null

    // Track if service was started from boot context to handle Android 12+ restrictions
    private var startedFromBoot: Boolean = false

    // Network connectivity receiver for auto-reconnect
    private var connectivityReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkState: Boolean = false

    @Volatile
    private var lastStarvationReconnectMs: Long = 0L

    private var volumeChangeReceiver: BroadcastReceiver? = null

    @Volatile
    private var lastServerVolumeSetMs: Long = 0L
    private val volumeSuppressWindowMs: Long = 500L

    @Volatile
    private var isDucked: Boolean = false

    /** Independent app software volume (0–100); composed into AudioTrack gain. */
    @Volatile
    private var unDuckedBaseVolume: Int = 100

    /** Temporary duck multiplier (1.0 = unducked). */
    @Volatile
    private var currentDuckMultiplier: Float = 1.0f

    private var duckRampJob: Job? = null
    private var autoUnduckJob: Job? = null

    // Track the last connection URL to prevent duplicate connections
    private var lastConnectUrl: String? = null
    private var lastConnectTime: Long = 0

    // Store reference to unregister in onDestroy and prevent leak
    private var memoryTrimCallback: android.content.ComponentCallbacks2? = null

    private data class NotificationState(
        val trackTitle: String?,
        val trackArtist: String?,
        val playbackState: String?,
        val hasController: Boolean,
        val supportedCommands: Set<String>,
        // Using Any to avoid bitmap comparison issues
        val artworkBitmap: Any?,
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sendspin_playback"

        const val ACTION_DUCK = "com.sendspinlite.ACTION_DUCK"
        const val ACTION_UNDUCK = "com.sendspinlite.ACTION_UNDUCK"
        const val ACTION_SET_APP_VOLUME = "com.sendspinlite.ACTION_SET_APP_VOLUME"
        const val ACTION_TOGGLE_DUCK = "com.sendspinlite.ACTION_TOGGLE_DUCK"

        const val EXTRA_DUCK_PERCENT = "DUCK_PERCENT"
        const val EXTRA_PERCENT = "PERCENT"
        const val EXTRA_RAMP_MS = "RAMP_MS"
        const val EXTRA_FADE_MS = "FADE_MS"
        const val EXTRA_DURATION_MS = "DURATION_MS"
        const val EXTRA_VOLUME = "VOLUME"

        fun startService(
            context: Context,
            wsUrl: String,
            clientId: String,
            clientName: String,
        ) {
            val intent =
                Intent(context, SendspinService::class.java).apply {
                    putExtra("wsUrl", wsUrl)
                    putExtra("clientId", clientId)
                    putExtra("clientName", clientName)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, SendspinService::class.java))
        }
    }

    fun setUiDiagnosticsMirrorEnabled(enabled: Boolean) {
        uiMirrorEnabled = enabled
        Log.i(tag, "UI diagnostics mirror ${if (enabled) "enabled" else "disabled"}")
    }

    inner class LocalBinder : Binder() {
        fun getService(): SendspinService = this@SendspinService

        fun setUiMirrorEnabled(enabled: Boolean) {
            this@SendspinService.setUiDiagnosticsMirrorEnabled(enabled)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service created")

        // Initialize low-memory detection now that context is ready
        isTV = checkIsTV()
        isLowMemoryDevice = AppMemoryPolicy.isLeanDevice(this)

        // Initialize UI state with current system media volume. Player mute is app-level
        // (AudioTrack gain) and must not be derived from STREAM_MUSIC mute, or we would
        // treat another app's stream mute as our own.
        val initialVolume = getSystemMediaVolume()
        _uiState.value =
            _uiState.value.copy(
                playerVolume = initialVolume,
                playerMuted = false,
            )

        createNotificationChannel()

        // Acquire wake lock to keep CPU running during playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SendspinService::WakeLock",
            ).apply {
                setReferenceCounted(false)
            }

        // Initialize WifiLock to keep WiFi radio active and prevent low-power DTIM sleep during playback
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SendspinService::WifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "SendspinService::WifiLock")
            }.apply {
                setReferenceCounted(true)
            }

        // Memory trim must be registered early so LMK pressure is handled during cold start.
        memoryTrimCallback = createMemoryTrimCallback()
        registerComponentCallbacks(memoryTrimCallback)

        // Duck/unduck intents from other apps (HA, Tasker, etc.) require a context-registered
        // exported receiver — manifest registration alone does not receive custom implicit
        // broadcasts on modern targetSdk.
        registerIntentReceiver()
    }

    /**
     * Network receivers, MediaSession, and state collectors are deferred until the first
     * [connect] to keep sticky-restart / LMK recovery paths as light as possible.
     *
     * MediaSessionCompat is not thread-safe; all session work runs on the main looper.
     */
    private fun ensureAuxiliaryStarted() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureAuxiliaryStartedOnMain()
        } else {
            runBlocking(Dispatchers.Main.immediate) { ensureAuxiliaryStartedOnMain() }
        }
    }

    private fun ensureAuxiliaryStartedOnMain() {
        synchronized(auxiliaryLock) {
            if (auxiliaryStarted) {
                if (mediaSession == null) {
                    mediaSession = createMediaSession()
                    updateMediaSessionStateOnMain()
                    updateNotification()
                }
                return
            }

            Log.i(tag, "Starting deferred service components")

            registerNetworkReceiver()
            registerNetworkCallback()
            registerVolumeChangeReceiver()

            mediaSession = createMediaSession()

            uiStateCollectorsJob?.cancel()
            uiStateCollectorsJob =
                scope.launch {
                    _uiState.collect { state ->
                        val shouldHoldWakeLock = state.connected && state.playbackState == "playing"
                        wakeLock?.let { lock ->
                            if (shouldHoldWakeLock) {
                                if (!lock.isHeld) {
                                    lock.acquire()
                                    Log.i(tag, "WakeLock acquired dynamically (playing)")
                                }
                            } else {
                                if (lock.isHeld) {
                                    lock.release()
                                    Log.i(tag, "WakeLock released dynamically (not playing)")
                                }
                            }
                        }

                        val shouldHoldWifiLock = state.connected && state.playbackState == "playing"
                        wifiLock?.let { lock ->
                            if (shouldHoldWifiLock) {
                                if (!lock.isHeld) {
                                    lock.acquire()
                                    Log.i(tag, "WifiLock acquired dynamically (playing)")
                                }
                            } else {
                                if (lock.isHeld) {
                                    lock.release()
                                    Log.i(tag, "WifiLock released dynamically (not playing)")
                                }
                            }
                        }

                        if (ReconnectPolicy.shouldAutoReconnect(state.status) && reconnectJob == null) {
                            Log.i(tag, "Connection lost (${state.status}), starting auto-reconnect")
                            startAutoReconnect(state.wsUrl, state.clientId, state.clientName)
                        }

                        updateMediaSessionState()
                    }
                }

            auxiliaryStarted = true
            updateMediaSessionStateOnMain()
            updateNotification()
        }
    }

    private fun createMediaSession(): MediaSessionCompat =
        MediaSessionCompat(this, "SendspinMediaSession").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }.also {
            Log.i(tag, "MediaSession created and activated")
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.i(tag, "Service started")
        // Detect if this is from a boot receiver (no connection parameters provided)
        val fromBoot = intent?.getStringExtra("fromBoot") == "1"

        // Track that this service instance started from boot
        if (fromBoot) {
            startedFromBoot = true
            Log.i(tag, "Service started from boot context")
        }

        // Fulfill startForegroundService contract immediately to prevent ANR
        startForegroundWithRetry(fromBoot = startedFromBoot || fromBoot)

        if (intent == null) {
            reconnectFromSavedCredentialsIfIdle()
            return START_STICKY
        }

        val action = intent.action
        if (action != null) {
            when (action) {
                ACTION_DUCK -> {
                    val percent =
                        intent.getIntExtra(EXTRA_DUCK_PERCENT, intent.getIntExtra(EXTRA_PERCENT, 20))
                    val rampMs =
                        intent.getLongExtra(EXTRA_RAMP_MS, intent.getLongExtra(EXTRA_FADE_MS, 200L))
                    val durationMs =
                        if (intent.hasExtra(EXTRA_DURATION_MS)) {
                            intent.getLongExtra(EXTRA_DURATION_MS, -1L)
                        } else {
                            null
                        }
                    duckAudio(
                        duckPercent = percent,
                        rampMs = rampMs,
                        durationMs = if (durationMs != null && durationMs > 0) durationMs else null,
                    )
                    return START_STICKY
                }
                ACTION_UNDUCK -> {
                    val rampMs =
                        intent.getLongExtra(EXTRA_RAMP_MS, intent.getLongExtra(EXTRA_FADE_MS, 400L))
                    unduckAudio(rampMs = rampMs)
                    return START_STICKY
                }
                ACTION_SET_APP_VOLUME -> {
                    val volume =
                        intent.getIntExtra(EXTRA_VOLUME, intent.getIntExtra(EXTRA_PERCENT, 100))
                    setAppVolume(volume)
                    return START_STICKY
                }
                ACTION_TOGGLE_DUCK -> {
                    val percent =
                        intent.getIntExtra(EXTRA_DUCK_PERCENT, intent.getIntExtra(EXTRA_PERCENT, 20))
                    val rampMs =
                        intent.getLongExtra(EXTRA_RAMP_MS, intent.getLongExtra(EXTRA_FADE_MS, 200L))
                    toggleDuck(duckPercent = percent, rampMs = rampMs)
                    return START_STICKY
                }
            }
        }

        val mediaAction = intent.getStringExtra("media_action")
        if (mediaAction != null) {
            dispatchTransportCommand(mediaAction)
            return START_STICKY
        }

        val wsUrl = intent.getStringExtra("wsUrl")
        val clientId = intent.getStringExtra("clientId")
        val clientName = intent.getStringExtra("clientName")

        if (wsUrl != null && clientId != null && clientName != null) {
            // Prevent duplicate connections within 1 second
            val currentTime = System.currentTimeMillis()
            if (lastConnectUrl == wsUrl && (currentTime - lastConnectTime) < 1000) {
                Log.d(tag, "Ignoring duplicate connection request to $wsUrl")
                return START_STICKY // Still return STICKY in case service is killed
            }

            lastConnectUrl = wsUrl
            lastConnectTime = currentTime

            // Disconnect existing connection before creating new one
            if (client != null) {
                Log.i(tag, "Disconnecting existing client before reconnecting")
                disconnect(keepForeground = true)
            }

            connect(wsUrl, clientId, clientName, fromBoot = fromBoot)
        }

        // START_STICKY ensures system restarts service if it's killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        Log.i(tag, "Service destroyed")
        disconnect()

        mediaSession?.release()
        mediaSession = null

        // Unregister memory trim callbacks to prevent leaking this service
        memoryTrimCallback?.let { unregisterComponentCallbacks(it) }
        memoryTrimCallback = null

        unregisterIntentReceiver()

        // Unregister deferred components only if they were started
        if (auxiliaryStarted) {
            unregisterNetworkReceiver()
            unregisterNetworkCallback()
            unregisterVolumeChangeReceiver()
            uiStateCollectorsJob?.cancel()
            uiStateCollectorsJob = null
        }

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        // Release wifi lock
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wifiLock = null

        duckRampJob?.cancel()
        duckRampJob = null
        autoUnduckJob?.cancel()
        autoUnduckJob = null

        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Sendspin Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Sendspin music playback - keeps service running"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val state = _uiState.value

        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Always show track info if available, fallback to status
        val title = if (state.trackTitle.isNullOrBlank()) "Sendspin Player" else state.trackTitle
        val subtitle =
            if (state.trackArtist.isNullOrBlank()) {
                when {
                    state.connected && state.trackTitle != null -> "Now Playing"
                    state.connected -> "Connected"
                    else -> "Not connected"
                }
            } else {
                state.trackArtist
            }

        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)

        // Add album art only on non-low-memory devices
        if (!isLowMemoryDevice) {
            state.artworkBitmap?.let { bitmap ->
                builder.setLargeIcon(bitmap)
            }
        }

        // Track which action indices we add
        val actionIndices = mutableListOf<Int>()

        // Add media controls only on non-low-memory devices
        if (!isLowMemoryDevice && state.hasController) {
            // Previous
            if (state.supportedCommands.contains("previous")) {
                val prevIntent = createMediaActionIntent("previous")
                builder.addAction(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevIntent,
                )
                actionIndices.add(actionIndices.size)
            }

            // Play/Pause
            if (state.playbackState == "playing" && state.supportedCommands.contains("pause")) {
                val pauseIntent = createMediaActionIntent("pause")
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pauseIntent,
                )
                actionIndices.add(actionIndices.size)
            } else if (state.supportedCommands.contains("play")) {
                val playIntent = createMediaActionIntent("play")
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Play",
                    playIntent,
                )
                actionIndices.add(actionIndices.size)
            }

            // Next
            if (state.supportedCommands.contains("next")) {
                val nextIntent = createMediaActionIntent("next")
                builder.addAction(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextIntent,
                )
                actionIndices.add(actionIndices.size)
            }
        }

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(*actionIndices.toIntArray()),
            )
        }

        return builder.build()
    }

    private fun updateMediaSessionState() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateMediaSessionStateOnMain()
        } else {
            mainHandler.post { updateMediaSessionStateOnMain() }
        }
    }

    private fun updateMediaSessionStateOnMain() {
        val session = mediaSession ?: return
        val state = _uiState.value
        val playState =
            when (state.playbackState) {
                "playing" -> PlaybackStateCompat.STATE_PLAYING
                "stopped" -> PlaybackStateCompat.STATE_STOPPED
                "paused" -> PlaybackStateCompat.STATE_PAUSED
                else -> {
                    if (state.connected) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_NONE
                }
            }

        val transportActions = buildTransportActions(state.supportedCommands)

        val playbackState =
            PlaybackStateCompat.Builder()
                .setState(playState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(transportActions)
                .build()
        session.setPlaybackState(playbackState)

        val metadata =
            MediaMetadataCompat.Builder().apply {
                state.trackTitle?.let { putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
                state.trackArtist?.let { putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
                state.albumTitle?.let { putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            }.build()
        session.setMetadata(metadata)
    }

    private fun buildTransportActions(commands: Set<String>): Long {
        var actions = 0L
        if ("play" in commands) actions = actions or PlaybackStateCompat.ACTION_PLAY
        if ("pause" in commands) actions = actions or PlaybackStateCompat.ACTION_PAUSE
        if ("next" in commands) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if ("previous" in commands) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if ("stop" in commands) actions = actions or PlaybackStateCompat.ACTION_STOP
        return actions
    }

    private fun dispatchTransportCommand(command: String) {
        val supported = _uiState.value.supportedCommands
        if (supported.isNotEmpty() && command !in supported) {
            Log.i(tag, "Ignoring unsupported transport command: $command")
            return
        }
        val sent = client?.sendTransportCommand(command) == true
        Log.i(tag, "Transport command '$command' dispatched (sent=$sent)")
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent =
            Intent(this, SendspinService::class.java).apply {
                putExtra("media_action", action)
            }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification() {
        val state = _uiState.value
        val currentState =
            NotificationState(
                trackTitle = state.trackTitle,
                trackArtist = state.trackArtist,
                playbackState = state.playbackState,
                hasController = state.hasController,
                supportedCommands = state.supportedCommands,
                artworkBitmap = state.artworkBitmap,
            )

        // Only update notification if something relevant changed
        if (lastNotificationState != currentState) {
            lastNotificationState = currentState
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    fun connect(
        wsUrl: String,
        clientId: String,
        clientName: String,
        fromBoot: Boolean = false,
        fromAutoReconnect: Boolean = false,
    ) {
        ensureAuxiliaryStarted()

        if (!fromAutoReconnect && shouldIgnoreDuplicateConnect(wsUrl, clientId)) {
            Log.i(tag, "Already connected/connecting to this server, ignoring duplicate connect request")
            return
        }

        // Explicit user/UI connect cancels any in-flight auto-reconnect loop.
        // Auto-reconnect attempts must preserve the loop or a server restart that lasts
        // longer than the first attempt leaves the app stuck until force-close.
        if (!fromAutoReconnect) {
            reconnectRetryCount = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }

        disconnect(keepForeground = true, cancelReconnect = !fromAutoReconnect)
        beginClientConnection(wsUrl, clientId, clientName, fromBoot)
    }

    private fun shouldIgnoreDuplicateConnect(
        wsUrl: String,
        clientId: String,
    ): Boolean {
        val state = _uiState.value
        if (client == null || state.wsUrl != wsUrl || state.clientId != clientId) {
            return false
        }
        if (state.connected) {
            return true
        }
        return ReconnectPolicy.isActivelyConnecting(
            status = state.status,
            connected = state.connected,
            connectingStartedAtMs = connectingStartedAtMs,
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun beginClientConnection(
        wsUrl: String,
        clientId: String,
        clientName: String,
        fromBoot: Boolean,
    ) {
        // Acquire wifi lock when connecting
        wifiLock?.acquire()

        // Start foreground service with retry logic for Android 12+ BOOT_COMPLETED restrictions
        // Use startedFromBoot flag to track if service was initially started from boot context
        startForegroundWithRetry(fromBoot = startedFromBoot || fromBoot)

        connectingStartedAtMs = System.currentTimeMillis()
        _uiState.value =
            _uiState.value.copy(
                wsUrl = wsUrl,
                clientId = clientId,
                clientName = clientName,
                status = "connecting...",
                connected = true,
            )

        // Load persisted static delay from SharedPreferences (needed when starting from boot
        // since the ViewModel is not running to initialize _uiState with the saved value)
        val prefs = getSharedPreferences("SendspinPlayerPrefs", Context.MODE_PRIVATE)
        val savedStaticDelayMs = prefs.getLong("static_delay_ms", 0L).coerceIn(0L, 5000L)
        if (savedStaticDelayMs != _uiState.value.staticDelayMs) {
            _uiState.value = _uiState.value.copy(staticDelayMs = savedStaticDelayMs)
        }

        val activeClient =
            SendspinNativeClient(
                wsUrl = wsUrl,
                clientId = clientId,
                clientName = clientName,
                context = this,
            )
        client = activeClient
        // Let the native library drive the WiFi high-performance lock during sync bursts/streaming.
        activeClient.onRequestHighPerformance = { wifiLock?.acquire() }
        activeClient.onReleaseHighPerformance = {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        // Refresh STREAM_MUSIC now — stale uiState volume (or a prior server echo of 0) must not
        // be what we push on the first client/state after connect/reconnect.
        val currentVolume = getSystemMediaVolume()
        _uiState.value =
            _uiState.value.copy(
                playerVolume = currentVolume,
                playerVolumeFromServer = false,
            )
        activeClient.setStaticDelayMs(_uiState.value.staticDelayMs)
        activeClient.setPlayerVolume(currentVolume)
        activeClient.setPlayerMute(_uiState.value.playerMuted)
        // Re-apply local duck / app-volume gain on the new AudioTrack (protocol volume untouched).
        applyEffectiveVolume()

        attachClientCollectors(activeClient)
        startHealthMonitoring()

        scope.launch {
            client?.connect()
        }
        startConnectWatchdog(activeClient)
    }

    private fun attachClientCollectors(activeClient: SendspinNativeClient) {
        // Listen to client diagnostics Flow.
        // Cancel any previous collector first so a reconnect cannot leak the prior client.
        diagnosticsJob?.cancel()
        diagnosticsJob =
            scope.launch {
                activeClient.diagnostics
                    .takeWhile { client === activeClient }
                    .collect { diag ->
                        applyDiagnosticsFromClient(diag)
                    }
            }

        // Listen to server events (Volume, Mute, Delay changes).
        // Cancel any previous collector first so a reconnect cannot leak the prior client.
        eventsJob?.cancel()
        eventsJob =
            scope.launch {
                activeClient.events
                    .takeWhile { client === activeClient }
                    .collect { event ->
                        handleClientEvent(event)
                    }
            }
    }

    private fun handleClientEvent(event: ClientEvent) {
        when (event) {
            is ClientEvent.ServerVolumeChanged -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val systemVolume = (event.volume * maxVolume / 100)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, systemVolume, 0)
                Log.i(tag, "Applied server volume command: ${event.volume}% (systemVolume=$systemVolume)")
                markServerVolumeSet()

                _uiState.value =
                    _uiState.value.copy(
                        playerVolume = event.volume,
                        playerVolumeFromServer = false,
                    )
            }
            is ClientEvent.ServerMutedChanged -> {
                // Mute already applied as AudioTrack gain in SendspinNativeClient.onMuteChanged.
                // Do not touch STREAM_MUSIC — that would silence concurrent media apps.
                Log.i(tag, "Server mute reflected in UI: muted=${event.muted}")
                markServerVolumeSet()

                _uiState.value =
                    _uiState.value.copy(
                        playerMuted = event.muted,
                        playerMutedFromServer = false,
                    )
            }
            is ClientEvent.ServerStaticDelayChanged -> {
                val prefs = getSharedPreferences("SendspinPlayerPrefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("static_delay_ms", event.delayMs).apply()
                Log.i(tag, "Persisted server-commanded static delay: ${event.delayMs}ms")

                _uiState.value =
                    _uiState.value.copy(
                        staticDelayMs = event.delayMs,
                        staticDelayMsFromServer = false,
                    )
            }
            is ClientEvent.PlaybackStarvation -> {
                handlePlaybackStarvation(event)
            }
        }
    }

    /**
     * If a connect attempt never reaches ws_open (common while a Sendspin server is restarting),
     * native code never emits a closed:/failure: edge. Surface a failure so auto-reconnect
     * can keep retrying instead of wedging on "connecting".
     */
    private fun startConnectWatchdog(activeClient: SendspinNativeClient) {
        connectWatchdogJob?.cancel()
        connectWatchdogJob =
            scope.launch {
                delay(ReconnectPolicy.CONNECT_TIMEOUT_MS)
                if (client !== activeClient) return@launch
                val state = _uiState.value
                if (ReconnectPolicy.isConnectedOpen(state.status, state.connected)) {
                    return@launch
                }
                if (!state.status.startsWith("connecting") && !state.status.startsWith("failure:")) {
                    // Already closed/disconnected by another path.
                    if (!ReconnectPolicy.shouldAutoReconnect(state.status)) return@launch
                }
                Log.w(tag, "Connect timed out (status=${state.status}); marking failure for reconnect")
                activeClient.markConnectFailed("connect_timeout")
                if (client === activeClient) {
                    _uiState.value =
                        _uiState.value.copy(
                            connected = false,
                            status = ReconnectPolicy.FAILURE_CONNECT_TIMEOUT,
                        )
                }
            }
    }

    private fun startForegroundWithRetry(
        retryCount: Int = 0,
        fromBoot: Boolean = false,
    ) {
        // Android 12+ restricts BOOT_COMPLETED receivers from starting mediaPlayback foreground services
        // The boot receiver now uses startService() instead, so this only handles non-boot contexts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fromBoot) {
            Log.i(tag, "Skipping foreground service start from boot context on Android 12+ (background service mode)")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.i(tag, "Foreground service started successfully")
        } catch (e: Exception) {
            // May still fail if called from certain restricted contexts
            // Retry after a short delay
            if (retryCount < 5) {
                Log.w(tag, "Failed to start foreground service, retrying... (attempt ${retryCount + 1})", e)
                scope.launch {
                    delay(1000L * (retryCount + 1))
                    startForegroundWithRetry(retryCount + 1, fromBoot = fromBoot)
                }
            } else {
                Log.e(tag, "Failed to start foreground service after retries", e)
            }
        }
    }

    fun disconnect(
        reason: String = "user_disconnect",
        keepForeground: Boolean = false,
        cancelReconnect: Boolean = true,
    ) {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
        connectingStartedAtMs = 0L

        try {
            client?.close(reason)
        } catch (e: Exception) {
            Log.e(tag, "Error closing client with reason $reason", e)
        }

        try {
            client?.cleanupResources()
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning up client resources", e)
        }

        client = null

        // Cancel the per-connection flow collectors so they release the client they captured.
        // Without this they remain suspended on the (now silent) flows and pin the client graph
        // in memory across reconnects, leaking until the heap is exhausted.
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        eventsJob?.cancel()
        eventsJob = null

        // Stop health monitoring when disconnecting
        stopHealthMonitoring()

        // Cancel any pending reconnect attempts (skipped when tearing down for an auto-reconnect attempt)
        if (cancelReconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectRetryCount = 0
        }

        // Release wake lock and wifi lock when disconnecting
        if (!keepForeground) {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        }

        _uiState.value = _uiState.value.copy(connected = false, status = "disconnected")
        updateNotification()

        if (!keepForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startAutoReconnect(
        wsUrl: String,
        clientId: String,
        clientName: String,
    ) {
        // Cancel any existing reconnect job
        reconnectJob?.cancel()
        reconnectRetryCount = 0

        reconnectJob =
            scope.launch {
                var keepTrying = true
                while (isActive && keepTrying) {
                    val currentStatus = _uiState.value.status
                    val delayMs = ReconnectPolicy.reconnectDelayMs(currentStatus, reconnectRetryCount)

                    reconnectRetryCount++
                    Log.i(tag, "Reconnect attempt $reconnectRetryCount, waiting ${delayMs}ms")

                    delay(delayMs)

                    val beforeAttempt = _uiState.value
                    if (!ReconnectPolicy.shouldContinueReconnectLoop(beforeAttempt.status, beforeAttempt.connected)) {
                        Log.i(tag, "Stopping auto-reconnect (status=${beforeAttempt.status})")
                        keepTrying = false
                    } else {
                        Log.i(tag, "Attempting auto-reconnect (attempt $reconnectRetryCount)")
                        connect(
                            wsUrl = wsUrl,
                            clientId = clientId,
                            clientName = clientName,
                            fromBoot = false,
                            fromAutoReconnect = true,
                        )
                        keepTrying =
                            handleReconnectAttemptOutcome(
                                awaitReconnectAttemptOutcome(),
                                wsUrl,
                                clientId,
                                clientName,
                            )
                    }
                }

                reconnectJob = null
            }
    }

    private fun handleReconnectAttemptOutcome(
        outcome: ReconnectAttemptOutcome,
        wsUrl: String,
        clientId: String,
        clientName: String,
    ): Boolean =
        when (outcome) {
            ReconnectAttemptOutcome.CONNECTED -> {
                Log.i(tag, "Auto-reconnect succeeded on attempt $reconnectRetryCount")
                false
            }
            ReconnectAttemptOutcome.STOPPED -> {
                Log.i(tag, "Auto-reconnect stopped (user disconnect or terminal status)")
                false
            }
            ReconnectAttemptOutcome.FAILED -> {
                Log.w(tag, "Auto-reconnect attempt $reconnectRetryCount failed; will retry")
                markFailedReconnectAttempt(wsUrl, clientId, clientName)
                true
            }
        }

    private fun markFailedReconnectAttempt(
        wsUrl: String,
        clientId: String,
        clientName: String,
    ) {
        // Tear down the failed client so the next attempt starts clean, without
        // cancelling this reconnect loop.
        if (client == null || _uiState.value.connected) return
        disconnect(
            reason = "connect_timeout",
            keepForeground = true,
            cancelReconnect = false,
        )
        _uiState.value =
            _uiState.value.copy(
                wsUrl = wsUrl,
                clientId = clientId,
                clientName = clientName,
                connected = false,
                status = ReconnectPolicy.FAILURE_CONNECT_TIMEOUT,
            )
    }

    private enum class ReconnectAttemptOutcome {
        CONNECTED,
        FAILED,
        STOPPED,
    }

    /**
     * Waits until the in-flight connect reaches ws_open, fails/times out, or the user disconnects.
     */
    private suspend fun awaitReconnectAttemptOutcome(): ReconnectAttemptOutcome {
        val deadline = System.currentTimeMillis() + ReconnectPolicy.CONNECT_TIMEOUT_MS
        var outcome: ReconnectAttemptOutcome? = null
        while (outcome == null && currentCoroutineContext().isActive) {
            val state = _uiState.value
            outcome =
                when {
                    ReconnectPolicy.isConnectedOpen(state.status, state.connected) ->
                        ReconnectAttemptOutcome.CONNECTED
                    ReconnectPolicy.isTerminalDisconnect(state.status) ->
                        ReconnectAttemptOutcome.STOPPED
                    ReconnectPolicy.isReconnectFailureStatus(state.status) ->
                        ReconnectAttemptOutcome.FAILED
                    System.currentTimeMillis() >= deadline ->
                        timeoutReconnectAttempt(state.status)
                    else -> null
                }
            if (outcome == null) {
                delay(ReconnectPolicy.RECONNECT_POLL_MS)
            }
        }
        return outcome ?: ReconnectAttemptOutcome.STOPPED
    }

    private fun timeoutReconnectAttempt(status: String): ReconnectAttemptOutcome {
        val latest = _uiState.value
        if (ReconnectPolicy.isConnectedOpen(latest.status, latest.connected)) {
            return ReconnectAttemptOutcome.CONNECTED
        }
        // Optimistic connected=true during "connecting..." must not count as success.
        Log.w(tag, "Reconnect attempt timed out (status=$status)")
        client?.markConnectFailed("connect_timeout")
        _uiState.value =
            _uiState.value.copy(
                connected = false,
                status = ReconnectPolicy.FAILURE_CONNECT_TIMEOUT,
            )
        return ReconnectAttemptOutcome.FAILED
    }

    fun setStaticDelayMs(ms: Long) {
        val clamped = ms.coerceIn(0L, 5000L)
        _uiState.value = _uiState.value.copy(staticDelayMs = clamped)
        client?.setStaticDelayMs(clamped)
    }

    // Player (local device) volume controls — protocol / STREAM_MUSIC sync only.
    fun setPlayerVolume(volume: Int) {
        client?.setPlayerVolume(volume)
    }

    fun setPlayerMute(muted: Boolean) {
        client?.setPlayerMute(muted)
    }

    /**
     * Duck playback to [duckPercent]% residual level via AudioTrack gain.
     * Does not change protocol player volume or system STREAM_MUSIC.
     */
    fun duckAudio(
        duckPercent: Int = 20,
        rampMs: Long = 200L,
        durationMs: Long? = null,
    ) {
        val targetMultiplier = duckPercent.coerceIn(0, 100) / 100.0f
        isDucked = true
        autoUnduckJob?.cancel()

        rampDuckMultiplier(targetMultiplier, rampMs)

        if (durationMs != null && durationMs > 0) {
            autoUnduckJob =
                scope.launch {
                    delay(durationMs)
                    unduckAudio(rampMs)
                }
        }
    }

    /** Restore unducked AudioTrack gain. */
    fun unduckAudio(rampMs: Long = 400L) {
        isDucked = false
        autoUnduckJob?.cancel()
        autoUnduckJob = null
        rampDuckMultiplier(1.0f, rampMs)
    }

    fun toggleDuck(
        duckPercent: Int = 20,
        rampMs: Long = 200L,
    ) {
        if (isDucked) {
            unduckAudio(rampMs)
        } else {
            duckAudio(duckPercent, rampMs)
        }
    }

    /**
     * Set independent app software volume (0–100). Applied as AudioTrack gain;
     * does not change protocol player volume or system STREAM_MUSIC.
     */
    fun setAppVolume(volume: Int) {
        unDuckedBaseVolume = volume.coerceIn(0, 100)
        applyEffectiveVolume()
    }

    private fun rampDuckMultiplier(
        targetMultiplier: Float,
        durationMs: Long,
    ) {
        duckRampJob?.cancel()
        if (durationMs <= 0) {
            currentDuckMultiplier = targetMultiplier
            applyEffectiveVolume()
            return
        }
        duckRampJob =
            scope.launch(Dispatchers.Default) {
                val startMultiplier = currentDuckMultiplier
                val stepMs = 20L
                val totalSteps = (durationMs / stepMs).coerceAtLeast(1L)
                for (step in 1..totalSteps) {
                    val progress = step.toFloat() / totalSteps.toFloat()
                    currentDuckMultiplier =
                        startMultiplier + (targetMultiplier - startMultiplier) * progress
                    applyEffectiveVolume()
                    delay(stepMs)
                }
                currentDuckMultiplier = targetMultiplier
                applyEffectiveVolume()
            }
    }

    private fun applyEffectiveVolume() {
        client?.setAppVolumeGain(unDuckedBaseVolume / 100.0f)
        client?.setDuckGain(currentDuckMultiplier)
    }

    fun clearPlayerVolumeFlag() {
        _uiState.value = _uiState.value.copy(playerVolumeFromServer = false)
    }

    fun clearPlayerMutedFlag() {
        _uiState.value = _uiState.value.copy(playerMutedFromServer = false)
    }

    fun markServerVolumeSet() {
        lastServerVolumeSetMs = System.currentTimeMillis()
    }

    // Network connectivity monitoring
    private fun registerNetworkReceiver() {
        connectivityReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val isCurrentlyConnected = isNetworkAvailable()

                    // Only act on state changes
                    if (isCurrentlyConnected != lastNetworkState) {
                        lastNetworkState = isCurrentlyConnected

                        if (isCurrentlyConnected) {
                            Log.i(tag, "Network restored, checking connection")
                            // Only attempt reconnect if we're NOT already connected or connecting
                            val currentState = _uiState.value
                            if ((currentState.status == "network_lost" || currentState.status.startsWith("failure:") || currentState.status.startsWith("closed:")) &&
                                !currentState.wsUrl.isBlank() &&
                                !currentState.clientId.isBlank() &&
                                !currentState.clientName.isBlank()
                            ) {
                                Log.i(tag, "Network restored, attempting auto-reconnect")
                                // Reconnect with existing parameters
                                scope.launch {
                                    // Give network a moment to stabilize
                                    delay(1000)
                                    // Verify that the current state is still suitable for reconnection
                                    // (e.g. the user hasn't explicitly disconnected/reconnected in the meantime)
                                    val freshState = _uiState.value
                                    if (freshState.status == "network_lost" || freshState.status.startsWith("failure:") || freshState.status.startsWith("closed:")) {
                                        connect(freshState.wsUrl, freshState.clientId, freshState.clientName, fromBoot = false)
                                    }
                                }
                            }
                        } else {
                            Log.i(tag, "Network lost")
                            updateUiState { it.copy(status = "network_lost", connected = false) }
                        }
                    }
                }
            }

        try {
            @Suppress("DEPRECATION")
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(connectivityReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(connectivityReceiver, filter)
            }

            // Initialize network state
            lastNetworkState = isNetworkAvailable()
            Log.i(tag, "Network receiver registered. Current state: $lastNetworkState")
        } catch (e: Exception) {
            Log.w(tag, "Failed to register network receiver", e)
        }
    }

    private fun unregisterNetworkReceiver() {
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i(tag, "Network receiver unregistered")
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister network receiver", e)
            }
        }
        connectivityReceiver = null
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLosing(
                        network: Network,
                        maxMsToLive: Int,
                    ) {
                        if (_uiState.value.playbackState != "playing") return
                        Log.i(tag, "Default network losing (maxMsToLive=${maxMsToLive}ms) during playback")
                        client?.notifyLinkDegrading(maxMsToLive)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        if (_uiState.value.playbackState != "playing") return
                        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            client?.clearLinkDegraded()
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.i(tag, "Default network lost")
                        client?.notifyLinkDegrading(0)
                    }
                }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
            Log.i(tag, "Network callback registered")
        } catch (e: Exception) {
            Log.w(tag, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { callback ->
                cm?.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to unregister network callback", e)
        }
        networkCallback = null
    }

    private fun handlePlaybackStarvation(event: ClientEvent.PlaybackStarvation) {
        val now = System.currentTimeMillis()
        if (now - lastStarvationReconnectMs < com.sendspinlite.playback.PlaybackDiagnostics.STARVATION_RECONNECT_COOLDOWN_MS) {
            Log.i(tag, "Ignoring playback starvation (cooldown): $event")
            return
        }

        val state = _uiState.value
        if (state.wsUrl.isBlank() || state.clientId.isBlank() || state.clientName.isBlank()) {
            Log.w(tag, "Playback starvation but connection params missing; skipping reconnect")
            return
        }

        lastStarvationReconnectMs = now
        Log.w(
            tag,
            "Playback starvation — forcing reconnect (msSinceWrite=${event.msSinceLastWrite}, " +
                "queueMs=${event.outputQueueMs})",
        )

        scope.launch {
            try {
                disconnect("playback_starvation", keepForeground = true)
                delay(400)
                val fresh = _uiState.value
                if (fresh.wsUrl.isNotBlank() && fresh.clientId.isNotBlank()) {
                    connect(fresh.wsUrl, fresh.clientId, fresh.clientName, fromBoot = false)
                }
            } catch (e: Exception) {
                Log.e(tag, "Starvation reconnect failed", e)
            }
        }
    }

    private fun reconnectFromSavedCredentialsIfIdle() {
        val existing = client
        if (existing != null) {
            if (existing.diagnostics.value.connected) {
                return
            }
            Log.i(tag, "Stale disconnected client on service restart — cleaning up before reconnect")
            disconnect(keepForeground = true)
        }

        val prefs = getSharedPreferences("SendspinPlayerPrefs", Context.MODE_PRIVATE)
        val wsUrl = prefs.getString("ws_url", null)?.takeIf { it.isNotBlank() } ?: return
        val clientId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: return
        val clientName = "${Build.MANUFACTURER} ${Build.MODEL}"

        Log.i(tag, "Sticky service restart — reconnecting from saved credentials")
        scope.launch {
            ensureAuxiliaryStarted()
            connect(wsUrl, clientId, clientName, fromBoot = true)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                (networkInfo?.isConnectedOrConnecting == true)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error checking network availability", e)
            false
        }
    }

    private fun getSystemMediaVolume(): Int {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            (currentVolume * 100 / maxVolume).coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(tag, "Failed to get system media volume", e)
            100
        }
    }

    private fun updateUiState(block: (PlayerViewModel.UiState) -> PlayerViewModel.UiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun registerVolumeChangeReceiver() {
        volumeChangeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        if (streamType == android.media.AudioManager.STREAM_MUSIC) {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastServerVolumeSetMs < volumeSuppressWindowMs) {
                                Log.d(tag, "Volume change suppressed (server-initiated)")
                                return
                            }
                            Log.d(tag, "Volume changed, updating UI state and syncing to server")
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            val volumePercent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)

                            updateUiState { it.copy(playerVolume = volumePercent) }

                            // Sync the volume change back to the server
                            client?.setPlayerVolume(volumePercent)
                        }
                    }
                }
            }

        try {
            val filter =
                IntentFilter().apply {
                    addAction("android.media.VOLUME_CHANGED_ACTION")
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires explicit RECEIVER_EXPORTED flag
                registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                // Android 7-11: use legacy registration (default is exported)
                @Suppress("DEPRECATION")
                registerReceiver(volumeChangeReceiver, filter)
            }
            Log.i(tag, "Volume change receiver registered")
        } catch (e: Exception) {
            Log.w(tag, "Failed to register volume change receiver", e)
        }
    }

    private fun unregisterVolumeChangeReceiver() {
        volumeChangeReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i(tag, "Volume change receiver unregistered")
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister volume change receiver", e)
            }
        }
        volumeChangeReceiver = null
    }

    private var sendspinIntentReceiverRegistered = false
    private var sendspinIntentReceiver: SendspinIntentReceiver? = null

    private fun registerIntentReceiver() {
        if (sendspinIntentReceiverRegistered) return
        val receiver = SendspinIntentReceiver()
        sendspinIntentReceiver = receiver
        try {
            val filter =
                IntentFilter().apply {
                    addAction(ACTION_DUCK)
                    addAction(ACTION_UNDUCK)
                    addAction(ACTION_SET_APP_VOLUME)
                    addAction(ACTION_TOGGLE_DUCK)
                }
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            sendspinIntentReceiverRegistered = true
            Log.i(tag, "SendspinIntentReceiver registered dynamically for duck/unduck actions")
        } catch (e: Exception) {
            Log.w(tag, "Failed to register SendspinIntentReceiver dynamically", e)
        }
    }

    private fun unregisterIntentReceiver() {
        if (!sendspinIntentReceiverRegistered) return
        sendspinIntentReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i(tag, "SendspinIntentReceiver unregistered")
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister SendspinIntentReceiver", e)
            }
        }
        sendspinIntentReceiver = null
        sendspinIntentReceiverRegistered = false
    }

    private fun applyDiagnosticsFromClient(diag: ClientDiagnostics) {
        val previousState = _uiState.value
        val newState =
            if (uiMirrorEnabled) {
                if (!DiagnosticsDelta.fullMirrorChanged(previousState, diag)) {
                    return
                }
                mirrorFullUiState(previousState, diag)
            } else {
                if (!DiagnosticsDelta.serviceEssentialsChanged(previousState, diag)) {
                    return
                }
                mirrorServiceEssentials(previousState, diag)
            }

        if (ReconnectPolicy.isConnectedOpen(diag.status, diag.connected)) {
            connectingStartedAtMs = 0L
            connectWatchdogJob?.cancel()
            connectWatchdogJob = null
        }

        val tracked = trackConnectionDrops(previousState, newState)
        if (tracked == previousState) {
            return
        }
        _uiState.value = tracked
        updateNotification()
    }

    private fun mirrorServiceEssentials(
        previous: PlayerViewModel.UiState,
        diag: ClientDiagnostics,
    ): PlayerViewModel.UiState =
        previous.copy(
            status = diag.status,
            connected = diag.connected,
            playbackState = diag.playbackState,
            trackTitle = diag.trackTitle,
            trackArtist = diag.trackArtist,
            groupName = diag.groupName,
            hasController = diag.hasController,
            supportedCommands = diag.supportedCommands,
            artworkBitmap = diag.artworkBitmap,
        )

    private fun mirrorFullUiState(
        previous: PlayerViewModel.UiState,
        diag: ClientDiagnostics,
    ): PlayerViewModel.UiState =
        previous.copy(
            status = diag.status,
            connected = diag.connected,
            activeRoles = diag.activeRoles,
            playbackState = diag.playbackState,
            groupName = diag.groupName,
            streamDesc = diag.streamDesc,
            offsetUncertaintyUs = diag.offsetUncertaintyUs,
            driftPpm = diag.driftPpm,
            driftUncertaintyPpm = diag.driftUncertaintyPpm,
            driftSnr = diag.driftSnr,
            rttUs = diag.rttUs,
            networkQuality = diag.networkQuality,
            stability = diag.stability,
            connectionType = diag.connectionType,
            queuedChunks = diag.queuedChunks,
            bufferAheadMs = diag.bufferAheadMs,
            lateDrops = diag.lateDrops,
            audibleSyncCount = diag.audibleSyncCount,
            kalmanErrorCount = diag.kalmanErrorCount,
            groupVolume = diag.groupVolume,
            groupMuted = diag.groupMuted,
            supportedCommands = diag.supportedCommands,
            playbackSpeedMultiplier = diag.playbackSpeedMultiplier,
            smoothedLatencyMs = diag.smoothedLatencyMs,
            audioOutputStarted = diag.audioOutputStarted,
            playbackRecoveryStatus = diag.playbackRecoveryStatus,
            lastRecoveryEvent = diag.lastRecoveryEvent,
            clockReadyForPlayback = diag.clockReadyForPlayback,
            forceResyncActive = diag.forceResyncActive,
            inDiscontinuityRecovery = diag.inDiscontinuityRecovery,
            lateRestartLoops = diag.lateRestartLoops,
            effectiveBufferAheadMs = diag.effectiveBufferAheadMs,
            estimatedOffsetMs = diag.estimatedOffsetMs,
            playoutOffsetMs = diag.playoutOffsetMs,
            networkJitterMs = diag.networkJitterMs,
            clockUpdateCount = diag.clockUpdateCount,
            serverLatenessMs = diag.serverLatenessMs,
            lastAudioCutAgeMs = diag.lastAudioCutAgeMs,
            metadataTimestamp = diag.metadataTimestamp,
            trackTitle = diag.trackTitle,
            trackArtist = diag.trackArtist,
            albumTitle = diag.albumTitle,
            albumArtist = diag.albumArtist,
            trackYear = diag.trackYear,
            trackNumber = diag.trackNumber,
            artworkUrl = diag.artworkUrl,
            artworkBitmap = diag.artworkBitmap,
            trackProgress = diag.trackProgress,
            trackDuration = diag.trackDuration,
            playbackSpeed = diag.playbackSpeed,
            repeatMode = diag.repeatMode,
            shuffleEnabled = diag.shuffleEnabled,
            playerVolume = diag.playerVolume,
            playerVolumeFromServer = diag.playerVolumeFromServer,
            playerMuted = diag.playerMuted,
            playerMutedFromServer = diag.playerMutedFromServer,
            staticDelayMs = diag.staticDelayMs,
            staticDelayMsFromServer = diag.staticDelayMsFromServer,
            hasMetadata = diag.hasMetadata,
            hasController = diag.hasController,
            isLowMemoryDevice = diag.isLowMemoryDevice,
        )

    private fun trackConnectionDrops(
        previousState: PlayerViewModel.UiState,
        newState: PlayerViewModel.UiState,
    ): PlayerViewModel.UiState {
        if (newState.connected && newState.status == "ws_open") {
            hasEstablishedConnection = true
            dropCountedForCurrentOutage = false
        }

        val unexpectedDisconnect =
            hasEstablishedConnection &&
                previousState.connected &&
                !newState.connected &&
                (newState.status.startsWith("failure:") || newState.status.startsWith("closed:"))

        if (unexpectedDisconnect && !dropCountedForCurrentOutage) {
            dropCountedForCurrentOutage = true
            Log.w(
                tag,
                "Unexpected connection drop detected. totalDrops=${previousState.connectionDrops + 1}, status=${newState.status}",
            )
            return newState.copy(connectionDrops = previousState.connectionDrops + 1)
        }

        return newState
    }

    private fun createMemoryTrimCallback(): ComponentCallbacks2 {
        return object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                        Log.e(tag, "CRITICAL memory pressure: clearing audio buffer and pausing playback")
                        // Critical memory - clear buffers and pause, but keep connection alive
                        client?.trimAudioBufferCritical()
                    }
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                        Log.w(tag, "MODERATE memory pressure: reducing audio buffer")
                        // Moderate pressure - reduce buffer size
                        client?.trimAudioBufferModerate()
                    }
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        Log.w(tag, "LOW memory pressure: trimming audio buffer")
                        // Low memory - trim but keep playing if possible
                        client?.trimAudioBufferLow()
                    }
                    else -> {
                        // Other trim levels (background app, etc.) - mostly ignored for foreground service
                        Log.d(tag, "Memory trim level: $level")
                    }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // Handle configuration changes if needed
            }

            override fun onLowMemory() {
                Log.e(tag, "Critical low memory callback from system!")
                // This is called when system is in critical state
                client?.trimAudioBufferCritical()
            }
        }
    }

    /**
     * Health monitoring - ensures client thread never gets stuck
     * Checks playback loop health every 5 seconds and triggers recovery if hung
     */
    private var healthMonitorJob: Job? = null

    private fun startHealthMonitoring() {
        healthMonitorJob?.cancel()
        healthMonitorJob =
            scope.launch {
                while (isActive) {
                    try {
                        delay(5000) // Check every 5 seconds

                        if (!isServiceHealthy()) {
                            Log.w(tag, "Service health check failed - triggering recovery")
                            recoverService()
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error in health monitor", e)
                    }
                }
            }
    }

    private fun isServiceHealthy(): Boolean {
        // Auto-reconnect owns recovery while it is running; health recovery would cancel the loop.
        val activeClient = if (reconnectJob != null) null else client
        return activeClient?.isHealthy() ?: (reconnectJob != null)
    }

    private fun recoverService() {
        scope.launch {
            try {
                Log.i(tag, "Starting service recovery...")
                disconnect("health_recovery", keepForeground = true)
                delay(1000)

                val wsUrl = _uiState.value.wsUrl
                val clientId = _uiState.value.clientId
                val clientName = _uiState.value.clientName

                if (wsUrl.isNotBlank() && clientId.isNotBlank()) {
                    Log.i(tag, "Reconnecting after recovery...")
                    connect(wsUrl, clientId, clientName, fromBoot = false)
                }
            } catch (e: Exception) {
                Log.e(tag, "Service recovery failed", e)
            }
        }
    }

    private fun stopHealthMonitoring() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }
}
