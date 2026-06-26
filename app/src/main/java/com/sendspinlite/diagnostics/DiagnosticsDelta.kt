package com.sendspinlite.diagnostics

import com.sendspinlite.client.ClientDiagnostics
import com.sendspinlite.ui.PlayerViewModel

/**
 * Value-equality checks used to skip StateFlow updates and avoid allocating new
 * [ClientDiagnostics] / [PlayerViewModel.UiState] instances when nothing visible changed.
 */
object DiagnosticsDelta {
    fun hotPublishChanged(
        current: ClientDiagnostics,
        status: String,
        connected: Boolean,
        timeSynced: Boolean,
        outputStarted: Boolean,
        outputQueueMs: Long,
        queuedChunks: Int,
        latencyMs: Double,
        connectionType: String,
        networkQuality: String,
        stability: String,
        staticDelayMs: Long,
        trackProgress: Long?,
        trackDuration: Long?,
    ): Boolean =
        current.status != status ||
            current.connected != connected ||
            current.clockReadyForPlayback != timeSynced ||
            current.audioOutputStarted != outputStarted ||
            current.bufferAheadMs != outputQueueMs ||
            current.effectiveBufferAheadMs != outputQueueMs ||
            current.queuedChunks != queuedChunks ||
            current.smoothedLatencyMs != latencyMs ||
            current.connectionType != connectionType ||
            current.networkQuality != networkQuality ||
            current.stability != stability ||
            current.staticDelayMs != staticDelayMs ||
            current.trackProgress != trackProgress ||
            current.trackDuration != trackDuration

    /** Fields needed for notification, media session, and wake/wifi locks without the full UI mirror. */
    fun serviceEssentialsChanged(
        prev: PlayerViewModel.UiState,
        diag: ClientDiagnostics,
    ): Boolean =
        prev.status != diag.status ||
            prev.connected != diag.connected ||
            prev.playbackState != diag.playbackState ||
            prev.trackTitle != diag.trackTitle ||
            prev.trackArtist != diag.trackArtist ||
            prev.groupName != diag.groupName ||
            prev.hasController != diag.hasController ||
            prev.supportedCommands != diag.supportedCommands ||
            prev.artworkBitmap != diag.artworkBitmap

    fun fullMirrorChanged(
        prev: PlayerViewModel.UiState,
        diag: ClientDiagnostics,
    ): Boolean =
        serviceEssentialsChanged(prev, diag) ||
            prev.activeRoles != diag.activeRoles ||
            prev.streamDesc != diag.streamDesc ||
            prev.offsetUncertaintyUs != diag.offsetUncertaintyUs ||
            prev.driftPpm != diag.driftPpm ||
            prev.driftUncertaintyPpm != diag.driftUncertaintyPpm ||
            prev.driftSnr != diag.driftSnr ||
            prev.rttUs != diag.rttUs ||
            prev.networkQuality != diag.networkQuality ||
            prev.stability != diag.stability ||
            prev.connectionType != diag.connectionType ||
            prev.queuedChunks != diag.queuedChunks ||
            prev.bufferAheadMs != diag.bufferAheadMs ||
            prev.lateDrops != diag.lateDrops ||
            prev.audibleSyncCount != diag.audibleSyncCount ||
            prev.kalmanErrorCount != diag.kalmanErrorCount ||
            prev.groupVolume != diag.groupVolume ||
            prev.groupMuted != diag.groupMuted ||
            prev.playbackSpeedMultiplier != diag.playbackSpeedMultiplier ||
            prev.smoothedLatencyMs != diag.smoothedLatencyMs ||
            prev.audioOutputStarted != diag.audioOutputStarted ||
            prev.playbackRecoveryStatus != diag.playbackRecoveryStatus ||
            prev.lastRecoveryEvent != diag.lastRecoveryEvent ||
            prev.clockReadyForPlayback != diag.clockReadyForPlayback ||
            prev.forceResyncActive != diag.forceResyncActive ||
            prev.inDiscontinuityRecovery != diag.inDiscontinuityRecovery ||
            prev.lateRestartLoops != diag.lateRestartLoops ||
            prev.effectiveBufferAheadMs != diag.effectiveBufferAheadMs ||
            prev.estimatedOffsetMs != diag.estimatedOffsetMs ||
            prev.playoutOffsetMs != diag.playoutOffsetMs ||
            prev.networkJitterMs != diag.networkJitterMs ||
            prev.clockUpdateCount != diag.clockUpdateCount ||
            prev.serverLatenessMs != diag.serverLatenessMs ||
            prev.lastAudioCutAgeMs != diag.lastAudioCutAgeMs ||
            prev.metadataTimestamp != diag.metadataTimestamp ||
            prev.albumTitle != diag.albumTitle ||
            prev.albumArtist != diag.albumArtist ||
            prev.trackYear != diag.trackYear ||
            prev.trackNumber != diag.trackNumber ||
            prev.artworkUrl != diag.artworkUrl ||
            prev.trackProgress != diag.trackProgress ||
            prev.trackDuration != diag.trackDuration ||
            prev.playbackSpeed != diag.playbackSpeed ||
            prev.repeatMode != diag.repeatMode ||
            prev.shuffleEnabled != diag.shuffleEnabled ||
            prev.playerVolume != diag.playerVolume ||
            prev.playerVolumeFromServer != diag.playerVolumeFromServer ||
            prev.playerMuted != diag.playerMuted ||
            prev.playerMutedFromServer != diag.playerMutedFromServer ||
            prev.staticDelayMs != diag.staticDelayMs ||
            prev.staticDelayMsFromServer != diag.staticDelayMsFromServer ||
            prev.hasMetadata != diag.hasMetadata ||
            prev.isLowMemoryDevice != diag.isLowMemoryDevice
}
