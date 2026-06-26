package com.sendspinlite

import com.sendspinlite.client.ClientDiagnostics
import com.sendspinlite.diagnostics.DiagnosticsDelta
import com.sendspinlite.ui.PlayerViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsDeltaTest {
    @Test
    fun hotPublishChanged_detectsBufferAheadChange() {
        val current = ClientDiagnostics(bufferAheadMs = 100L)
        assertTrue(
            DiagnosticsDelta.hotPublishChanged(
                current = current,
                status = current.status,
                connected = current.connected,
                timeSynced = current.clockReadyForPlayback,
                outputStarted = current.audioOutputStarted,
                outputQueueMs = 200L,
                queuedChunks = current.queuedChunks,
                latencyMs = current.smoothedLatencyMs,
                connectionType = current.connectionType,
                networkQuality = current.networkQuality,
                stability = current.stability,
                staticDelayMs = current.staticDelayMs,
                trackProgress = current.trackProgress,
                trackDuration = current.trackDuration,
            ),
        )
    }

    @Test
    fun hotPublishChanged_skipsWhenUnchanged() {
        val current =
            ClientDiagnostics(
                status = "ws_open",
                connected = true,
                bufferAheadMs = 120L,
                effectiveBufferAheadMs = 120L,
                queuedChunks = 2,
            )
        assertFalse(
            DiagnosticsDelta.hotPublishChanged(
                current = current,
                status = "ws_open",
                connected = true,
                timeSynced = current.clockReadyForPlayback,
                outputStarted = current.audioOutputStarted,
                outputQueueMs = 120L,
                queuedChunks = 2,
                latencyMs = current.smoothedLatencyMs,
                connectionType = current.connectionType,
                networkQuality = current.networkQuality,
                stability = current.stability,
                staticDelayMs = current.staticDelayMs,
                trackProgress = current.trackProgress,
                trackDuration = current.trackDuration,
            ),
        )
    }

    @Test
    fun serviceEssentialsChanged_ignoresBufferMetrics() {
        val ui = PlayerViewModel.UiState(status = "ws_open", connected = true, playbackState = "playing")
        val diag =
            ClientDiagnostics(
                status = "ws_open",
                connected = true,
                playbackState = "playing",
                bufferAheadMs = 999L,
                queuedChunks = 99,
            )
        assertFalse(DiagnosticsDelta.serviceEssentialsChanged(ui, diag))
    }

    @Test
    fun fullMirrorChanged_detectsBufferMetrics() {
        val ui = PlayerViewModel.UiState(status = "ws_open", connected = true, playbackState = "playing")
        val diag =
            ClientDiagnostics(
                status = "ws_open",
                connected = true,
                playbackState = "playing",
                bufferAheadMs = 999L,
            )
        assertTrue(DiagnosticsDelta.fullMirrorChanged(ui, diag))
    }
}
