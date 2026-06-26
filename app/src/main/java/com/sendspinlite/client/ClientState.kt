package com.sendspinlite.client

import android.graphics.Bitmap
import com.sendspinlite.playback.PlaybackDiagnostics

/**
 * Decoupled diagnostics snapshot representing the current client playback/network state.
 * This class has no dependency on the UI layer (ViewModel).
 */
data class ClientDiagnostics(
    val status: String = "idle",
    val connected: Boolean = false,
    val connectionDrops: Int = 0,
    val activeRoles: String = "",
    val playbackState: String = "",
    val groupName: String = "",
    val streamDesc: String = "",
    val offsetUncertaintyUs: Long = 0,
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
    val audibleSyncCount: Long = 0,
    val kalmanErrorCount: Long = 0,
    val isLowMemoryDevice: Boolean = false,
    val isTV: Boolean = false,
    val hasController: Boolean = false,
    val hasMetadata: Boolean = false,
    val groupVolume: Int = 100,
    val groupMuted: Boolean = false,
    val supportedCommands: Set<String> = emptySet(),
    val playbackSpeedMultiplier: Float = 1.0f,
    val smoothedLatencyMs: Double = 0.0,
    val audioOutputStarted: Boolean = false,
    val playbackRecoveryStatus: String = PlaybackDiagnostics.STATUS_IDLE,
    val lastRecoveryEvent: String = "",
    val clockReadyForPlayback: Boolean = false,
    val forceResyncActive: Boolean = false,
    val inDiscontinuityRecovery: Boolean = false,
    val lateRestartLoops: Int = 0,
    val effectiveBufferAheadMs: Long = 0,
    val estimatedOffsetMs: Long = 0,
    val playoutOffsetMs: Long = 0,
    val networkJitterMs: Long = 0,
    val clockUpdateCount: Int = 0,
    val serverLatenessMs: Long = 0,
    val lastAudioCutAgeMs: Long = -1L,
    val playerVolume: Int = 100,
    val playerVolumeFromServer: Boolean = false,
    val playerMuted: Boolean = false,
    val playerMutedFromServer: Boolean = false,
    val staticDelayMs: Long = 0L,
    val staticDelayMsFromServer: Boolean = false,
    // Metadata properties
    val metadataTimestamp: Long? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val albumTitle: String? = null,
    val albumArtist: String? = null,
    val trackYear: Int? = null,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null,
    val artworkBitmap: Bitmap? = null,
    val trackProgress: Long? = null,
    val trackDuration: Long? = null,
    val playbackSpeed: Int? = null,
    val repeatMode: String? = null,
    val shuffleEnabled: Boolean? = null,
)

/**
 * Structured server-initiated events emitted by the client.
 */
sealed interface ClientEvent {
    data class ServerVolumeChanged(val volume: Int) : ClientEvent

    data class ServerMutedChanged(val muted: Boolean) : ClientEvent

    data class ServerStaticDelayChanged(val delayMs: Long) : ClientEvent

    /** Playback pipeline starved (network stall / AudioTrack drain); service should reconnect. */
    data class PlaybackStarvation(
        val msSinceLastWrite: Long,
        val outputQueueMs: Long,
    ) : ClientEvent
}
