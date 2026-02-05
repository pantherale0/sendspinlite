package com.sendspinlite

import android.app.*
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.content.Context
import android.os.Binder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SendspinService : Service() {
    private val tag = "SendspinService"
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var client: SendspinPcmClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _uiState = MutableStateFlow(PlayerViewModel.UiState())
    val uiState: StateFlow<PlayerViewModel.UiState> = _uiState

    // Detect low-memory devices to disable expensive features (initialized in onCreate)
    private var isLowMemoryDevice = false
    private var isTV = false
    
    // Reconnection retry tracking
    private var reconnectJob: Job? = null
    private var reconnectRetryCount = 0
    // Unlimited reconnection attempts - will keep retrying until manually disconnected
    
    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val lowMemory = memInfo?.totalMem ?: 0L < 2_000_000_000L  // Less than 2GB total RAM
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
    private var lastNetworkState: Boolean = false
    
    private var volumeChangeReceiver: BroadcastReceiver? = null
    
    // Track the last connection URL to prevent duplicate connections
    private var lastConnectUrl: String? = null
    private var lastConnectTime: Long = 0

    private data class NotificationState(
        val trackTitle: String?,
        val trackArtist: String?,
        val playbackState: String?,
        val hasController: Boolean,
        val supportedCommands: Set<String>,
        val artworkBitmap: Any? // Using Any to avoid bitmap comparison issues
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sendspin_playback"

        fun startService(context: Context, wsUrl: String, clientId: String, clientName: String) {
            val intent = Intent(context, SendspinService::class.java).apply {
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

    inner class LocalBinder : Binder() {
        fun getService(): SendspinService = this@SendspinService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service created")
        
        // Initialize low-memory detection now that context is ready
        isTV = checkIsTV()
        isLowMemoryDevice = checkIsLowMemoryDevice()
        
        createNotificationChannel()

        // Acquire wake lock to keep CPU running during playback
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SendspinService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Register network connectivity receiver
        registerNetworkReceiver()
        
        // Register volume change receiver for background volume monitoring
        registerVolumeChangeReceiver()

        // Register memory trim callbacks to respond to system memory pressure
        registerComponentCallbacks(createMemoryTrimCallback())

        // Monitor connection state and auto-reconnect on failures
        scope.launch {
            _uiState.collect { state ->
                // If connection failed and we're not already retrying, start auto-reconnect
                if (state.status.startsWith("failure:") && reconnectJob == null) {
                    Log.i(tag, "Connection failed, starting auto-reconnect")
                    startAutoReconnect(state.wsUrl, state.clientId, state.clientName)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "Service started")
        // Detect if this is from a boot receiver (no connection parameters provided)
        val fromBoot = intent?.getStringExtra("fromBoot") == "1"
        
        // Track that this service instance started from boot
        if (fromBoot) {
            startedFromBoot = true
            Log.i(tag, "Service started from boot context")
        }

        intent?.let {
            val wsUrl = it.getStringExtra("wsUrl") ?: return@let
            val clientId = it.getStringExtra("clientId") ?: return@let
            val clientName = it.getStringExtra("clientName") ?: return@let

            // Prevent duplicate connections within 1 second
            val currentTime = System.currentTimeMillis()
            if (lastConnectUrl == wsUrl && (currentTime - lastConnectTime) < 1000) {
                Log.d(tag, "Ignoring duplicate connection request to $wsUrl")
                return START_STICKY  // Still return STICKY in case service is killed
            }
            
            lastConnectUrl = wsUrl
            lastConnectTime = currentTime

            // Disconnect existing connection before creating new one
            if (client != null) {
                Log.i(tag, "Disconnecting existing client before reconnecting")
                disconnect()
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

        // Unregister network connectivity receiver
        unregisterNetworkReceiver()
        
        // Unregister volume change receiver
        unregisterVolumeChangeReceiver()

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sendspin Playback",
                NotificationManager.IMPORTANCE_LOW
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

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Always show track info if available, fallback to status
        val title = if (state.trackTitle.isNullOrBlank()) "Sendspin Player" else state.trackTitle
        val subtitle = if (state.trackArtist.isNullOrBlank()) {
            when {
                state.connected && state.trackTitle != null -> "Now Playing"
                state.connected -> "Connected"
                else -> "Not connected"
            }
        } else {
            state.trackArtist
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
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
                    prevIntent
                )
                actionIndices.add(actionIndices.size)
            }

            // Play/Pause
            if (state.playbackState == "playing" && state.supportedCommands.contains("pause")) {
                val pauseIntent = createMediaActionIntent("pause")
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pauseIntent
                )
                actionIndices.add(actionIndices.size)
            } else if (state.supportedCommands.contains("play")) {
                val playIntent = createMediaActionIntent("play")
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Play",
                    playIntent
                )
                actionIndices.add(actionIndices.size)
            }

            // Next
            if (state.supportedCommands.contains("next")) {
                val nextIntent = createMediaActionIntent("next")
                builder.addAction(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextIntent
                )
                actionIndices.add(actionIndices.size)
            }
        }

        return builder.build()
    }

    private fun createMediaActionIntent(action: String): PendingIntent {
        val intent = Intent(this, SendspinService::class.java).apply {
            putExtra("media_action", action)
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val state = _uiState.value
        val currentState = NotificationState(
            trackTitle = state.trackTitle,
            trackArtist = state.trackArtist,
            playbackState = state.playbackState,
            hasController = state.hasController,
            supportedCommands = state.supportedCommands,
            artworkBitmap = state.artworkBitmap
        )

        // Only update notification if something relevant changed
        if (lastNotificationState != currentState) {
            lastNotificationState = currentState
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    fun connect(wsUrl: String, clientId: String, clientName: String, fromBoot: Boolean = false) {
        // Don't create a new connection if we're already connected or connecting to the same server
        if (client != null &&
            _uiState.value.wsUrl == wsUrl &&
            _uiState.value.clientId == clientId &&
            (_uiState.value.connected || _uiState.value.status.startsWith("connecting"))) {
            Log.i(tag, "Already connected/connecting to this server, ignoring duplicate connect request")
            return
        }

        // Reset reconnect retry counter when user explicitly calls connect()
        // This allows recovery after hitting the max retry limit
        reconnectRetryCount = 0
        reconnectJob?.cancel()
        reconnectJob = null

        disconnect()

        // Acquire wake lock when connecting
        wakeLock?.acquire()

        // Start foreground service with retry logic for Android 12+ BOOT_COMPLETED restrictions
        // Use startedFromBoot flag to track if service was initially started from boot context
        startForegroundWithRetry(fromBoot = startedFromBoot || fromBoot)

        _uiState.value = _uiState.value.copy(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            status = "connecting...",
            connected = true
        )

        client = SendspinPcmClient(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            context = this,
            onUiUpdate = { patch ->
                _uiState.value = patch(_uiState.value)
                updateNotification()
            }
        ).also {
            it.setPlayoutOffsetMs(_uiState.value.playoutOffsetMs)
        }

        // Start health monitoring when connecting
        startHealthMonitoring()

        scope.launch {
            client?.connect()
        }
    }

    private fun startForegroundWithRetry(retryCount: Int = 0, fromBoot: Boolean = false) {
        // Android 12+ restricts BOOT_COMPLETED receivers from starting mediaPlayback foreground services
        // The boot receiver now uses startService() instead, so this only handles non-boot contexts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fromBoot) {
            Log.i(tag, "Skipping foreground service start from boot context on Android 12+ (background service mode)")
            return
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
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

    fun disconnect() {
        try {
            client?.close("user_disconnect")
        } catch (e: Exception) {
            Log.e(tag, "Error closing client", e)
        }
        
        try {
            client?.cleanupResources()
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning up client resources", e)
        }
        
        client = null
        
        // Stop health monitoring when disconnecting
        stopHealthMonitoring()
        
        // Cancel any pending reconnect attempts
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectRetryCount = 0

        // Release wake lock when disconnecting
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        _uiState.value = _uiState.value.copy(connected = false, status = "disconnected")
        updateNotification()
    }

    private fun startAutoReconnect(wsUrl: String, clientId: String, clientName: String) {
        // Cancel any existing reconnect job
        reconnectJob?.cancel()
        reconnectRetryCount = 0
        
        reconnectJob = scope.launch {
            while (isActive) {
                val currentStatus = _uiState.value.status
                
                // If port is closed, assume server is being upgraded - use longer wait
                val delayMs = if (currentStatus.contains("port_closed")) {
                    // Server likely upgrading - wait 30 seconds between attempts
                    Log.i(tag, "Port closed (server upgrading?), using extended retry delay")
                    30_000L
                } else {
                    // Normal exponential backoff for other failures
                    (1000L * Math.pow(2.0, reconnectRetryCount.toDouble())).toLong().coerceAtMost(60000L)
                }
                
                reconnectRetryCount++
                Log.i(tag, "Reconnect attempt $reconnectRetryCount, waiting ${delayMs}ms")
                
                delay(delayMs)
                
                // Check if we still want to reconnect (haven't been manually disconnected)
                if (_uiState.value.status.startsWith("failure:") || _uiState.value.status.startsWith("closed:")) {
                    Log.i(tag, "Attempting auto-reconnect (attempt $reconnectRetryCount)")
                    connect(wsUrl, clientId, clientName, fromBoot = false)
                } else {
                    // Connection was restored or user disconnected, stop retrying
                    break
                }
            }
            
            reconnectJob = null
        }
    }

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        _uiState.value = _uiState.value.copy(playoutOffsetMs = clamped)
        client?.setPlayoutOffsetMs(clamped)
    }

    fun setEnableOpusCodec(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enableOpusCodec = enabled)
        client?.setEnableOpusCodec(enabled)
    }

    // Player (local device) volume controls
    fun setPlayerVolume(volume: Int) {
        client?.setPlayerVolume(volume)
    }

    fun setPlayerMute(muted: Boolean) {
        client?.setPlayerMute(muted)
    }

    fun clearPlayerVolumeFlag() {
        _uiState.value = _uiState.value.copy(playerVolumeFromServer = false)
    }

    fun clearPlayerMutedFlag() {
        _uiState.value = _uiState.value.copy(playerMutedFromServer = false)
    }

    // Network connectivity monitoring
    private fun registerNetworkReceiver() {
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val isCurrentlyConnected = isNetworkAvailable()
                
                // Only act on state changes
                if (isCurrentlyConnected != lastNetworkState) {
                    lastNetworkState = isCurrentlyConnected
                    
                    if (isCurrentlyConnected) {
                        Log.i(tag, "Network restored, checking connection")
                        // Only attempt reconnect if we're NOT already connected or connecting
                        val currentState = _uiState.value
                        if ((currentState.status.startsWith("failure:") || !currentState.connected) &&
                            !currentState.wsUrl.isBlank() && 
                            !currentState.clientId.isBlank() && 
                            !currentState.clientName.isBlank()) {
                            Log.i(tag, "Network restored, attempting auto-reconnect")
                            // Reconnect with existing parameters
                            scope.launch {
                                // Give network a moment to stabilize
                                delay(1000)
                                connect(currentState.wsUrl, currentState.clientId, currentState.clientName, fromBoot = false)
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

    private fun updateUiState(block: (PlayerViewModel.UiState) -> PlayerViewModel.UiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun registerVolumeChangeReceiver() {
        volumeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (streamType == android.media.AudioManager.STREAM_MUSIC) {
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
            val filter = IntentFilter().apply {
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
        healthMonitorJob = scope.launch {
            while (isActive) {
                try {
                    delay(5000)  // Check every 5 seconds
                    
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
        val client = this.client ?: return false
        return client.isHealthy()
    }
    
    private suspend fun recoverService() {
        try {
            Log.i(tag, "Starting service recovery...")
            client?.close("health_recovery")
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

    private fun stopHealthMonitoring() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }
}