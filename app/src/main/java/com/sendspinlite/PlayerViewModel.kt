package com.sendspinlite

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioManager
import android.os.IBinder
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PREFS_NAME = "SendspinPlayerPrefs"
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_PLAYOUT_OFFSET_MS = "playout_offset_ms"
        private const val KEY_ENABLE_OPUS_CODEC = "enable_opus_codec"
        private const val DEFAULT_CLIENT_NAME = "Android Player"
        private const val DEFAULT_PLAYOUT_OFFSET_MS = 0L
        private const val DEFAULT_ENABLE_OPUS_CODEC = false
    }

    private val sharedPrefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val deviceId: String = getOrCreateDeviceId()

    data class UiState(
        val wsUrl: String = "",
        val clientId: String = "android-player",
        val clientName: String = "Android Player",
        val connected: Boolean = false,
        val status: String = "idle",
        val activeRoles: String = "",
        val playbackState: String = "",
        val groupName: String = "",
        val streamDesc: String = "",
        val offsetUs: Long = 0,
        val driftPpm: Double = 0.0,
        val driftUncertaintyPpm: Double = 0.0,
        val driftSnr: Double = 0.0,
        val rttUs: Long = 0,
        val networkQuality: String = "UNKNOWN",
        val stability: String = "UNKNOWN",
        val connectionType: String = "UNKNOWN",
        val queuedChunks: Int = 0,
        val bufferAheadMs: Long = 0,
        val lateDrops: Long = 0,
        val playoutOffsetMs: Long = 0,
        val enableOpusCodec: Boolean = DEFAULT_ENABLE_OPUS_CODEC,
        val hasController: Boolean = false,
        val groupVolume: Int = 100,
        val groupMuted: Boolean = false,
        val supportedCommands: Set<String> = emptySet(),
        val playerVolume: Int = 100,
        val playerMuted: Boolean = false,
        val playerVolumeFromServer: Boolean = false,
        val playerMutedFromServer: Boolean = false,
        val isLowMemoryDevice: Boolean = false,
        val isTV: Boolean = false,
        val discoveryTimeoutExpired: Boolean = false,
        // Metadata properties
        val hasMetadata: Boolean = false,
        val hasArtwork: Boolean = false,
        val metadataTimestamp: Long? = null,
        val trackTitle: String? = null,
        val trackArtist: String? = null,
        val albumTitle: String? = null,
        val albumArtist: String? = null,
        val trackYear: Int? = null,
        val trackNumber: Int? = null,
        val artworkUrl: String? = null,
        val artworkBitmap: android.graphics.Bitmap? = null,
        val trackProgress: Long? = null,
        val trackDuration: Long? = null,
        val playbackSpeed: Int? = null,
        val repeatMode: String? = null,
        val shuffleEnabled: Boolean? = null
    )

    private val _ui = MutableStateFlow(UiState(isLowMemoryDevice = checkIsLowMemoryDevice(), playerVolume = getSystemMediaVolume(), isTV = checkIsTV()))
    val ui: StateFlow<UiState> = _ui
    
    
    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            memInfo?.totalMem ?: 0L < 1_500_000_000L  // Less than 1.5GB total RAM
        } catch (e: Exception) {
            false
        }
    }

    private fun checkIsTV(): Boolean {
        return try {
            val uiMode = getApplication<Application>().resources.configuration.uiMode
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (e: Exception) {
            false
        }
    }

    private fun getSystemMediaVolume(): Int {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            (currentVolume * 100 / maxVolume).coerceIn(0, 100)
        } catch (e: Exception) {
            100
        }
    }

    private fun getOrCreateDeviceId(): String {
        var deviceId = sharedPrefs.getString("device_id", null)
        if (deviceId == null) {
            // Use hardware device identifier that's unique per device and persists across reinstalls
            // ANDROID_ID is the standard way for regular apps to get a stable device identifier
            deviceId = Settings.Secure.getString(getApplication<Application>().contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            sharedPrefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private val serviceDiscovery = ServiceDiscovery(app)
    val discoveredServers: StateFlow<List<DiscoveredServer>> = serviceDiscovery.discoveredServers

    private var service: SendspinService? = null
    private var serviceBound = false

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SendspinService.LocalBinder
            service = localBinder?.getService()
            serviceBound = true

            service?.let { svc ->
                viewModelScope.launch {
                    svc.uiState.collect { serviceState ->
                        // Handle server-commanded volume changes
                        if (serviceState.playerVolumeFromServer) {
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val systemVolume = (serviceState.playerVolume * maxVolume / 100)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVolume, 0)
                            svc.markServerVolumeSet()

                            // Clear the flag in the service
                            svc.clearPlayerVolumeFlag()
                        }

                        // Handle server-commanded mute changes
                        if (serviceState.playerMutedFromServer) {
                            if (serviceState.playerMuted) {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                            }

                            svc.markServerVolumeSet()

                            // Clear the flag in the service
                            svc.clearPlayerMutedFlag()
                        }

                        // Only update player volume from service if it's from the server
                        // This prevents service state from overwriting local UI volume
                        if (serviceState.playerVolumeFromServer || _ui.value.playerVolume == 100) {
                            _ui.value = serviceState
                        } else {
                            // Keep local volume, update everything else
                            _ui.value = serviceState.copy(
                                playerVolume = _ui.value.playerVolume,
                                playerVolumeFromServer = false
                            )
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
        }
    }

    init {
        // Load saved settings from SharedPreferences
        val savedWsUrl = sharedPrefs.getString(KEY_WS_URL, null)
        val savedPlayoutOffsetMs = sharedPrefs.getLong(KEY_PLAYOUT_OFFSET_MS, DEFAULT_PLAYOUT_OFFSET_MS)
        val savedEnableOpusCodec = sharedPrefs.getBoolean(KEY_ENABLE_OPUS_CODEC, DEFAULT_ENABLE_OPUS_CODEC)
        
        // Initialize with saved URL and Opus codec preference if available
        _ui.value = _ui.value.copy(
            wsUrl = savedWsUrl ?: "",
            clientId = deviceId,
            clientName = DEFAULT_CLIENT_NAME,
            enableOpusCodec = savedEnableOpusCodec
        )
        
        updateAndroidVolumeState()

        // If we have a saved server URL, connect to it immediately
        if (!savedWsUrl.isNullOrBlank()) {
            connect(savedWsUrl)
        }

        // Start mDNS service discovery
        serviceDiscovery.startDiscovery()
        
        // Set a 5-second timeout for discovery
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000) // 5 seconds
            // Mark that discovery timeout has expired (this allows fallback to manual entry)
            _ui.value = _ui.value.copy(discoveryTimeoutExpired = true)
        }
        
        // Monitor discovered servers and auto-connect to the first one
        viewModelScope.launch {
            serviceDiscovery.discoveredServers.collect { servers ->
                val currentState = _ui.value
                // Auto-connect to the first discovered server if not already connected
                if (!currentState.connected && servers.isNotEmpty()) {
                    val firstServer = servers.first()
                    
                    // Connect to first discovered server
                    connect(firstServer.url)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceDiscovery.stopDiscovery()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    fun connect(wsUrl: String) {
        // Save settings to SharedPreferences
        sharedPrefs.edit().apply {
            putString(KEY_WS_URL, wsUrl)
            apply()
        }
        
        // Bind to service before starting it
        if (!serviceBound) {
            val intent = Intent(getApplication(), SendspinService::class.java)
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        SendspinService.startService(getApplication(), wsUrl, deviceId, DEFAULT_CLIENT_NAME)
    }

    fun selectDiscoveredServer(server: DiscoveredServer) {
        _ui.value = _ui.value.copy(wsUrl = server.url)
    }

    fun disconnect() {
        SendspinService.stopService(getApplication())
        
        // Unbind from service when disconnecting
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
            service = null
        }
        
        _ui.value = _ui.value.copy(connected = false, status = "disconnected")
    }

    fun setPlayoutOffsetMs(ms: Long) {
        // Save to SharedPreferences for persistence
        sharedPrefs.edit().putLong(KEY_PLAYOUT_OFFSET_MS, ms).apply()
        service?.setPlayoutOffsetMs(ms)
    }

    fun setEnableOpusCodec(enabled: Boolean) {
        // Save to SharedPreferences for persistence
        sharedPrefs.edit().putBoolean(KEY_ENABLE_OPUS_CODEC, enabled).apply()
        service?.setEnableOpusCodec(enabled)
    }

    fun updateAndroidVolumeState() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)
        _ui.value = _ui.value.copy(playerVolume = volumePercent)
    }
}