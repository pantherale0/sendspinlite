package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioIssueReporterTest {
    @Test
    fun buildReport_includesPlaybackRecoveryAndDiagnosticSections() {
        val uiState =
            PlayerViewModel.UiState(
                playbackRecoveryStatus = PlaybackDiagnostics.STATUS_FORCE_RESYNC_PRESTART,
                audioOutputStarted = false,
                playbackState = "playing",
                clockReadyForPlayback = false,
                lastRecoveryEvent = "force_resync | reason=audio_out_of_sync_late_drop dropped=3",
                queuedChunks = 120,
                bufferAheadMs = -40,
                lateDrops = 200,
            )

        val report = AudioIssueReporter.buildReport(uiState)

        assertThat(report).contains("=== PLAYBACK RECOVERY ===")
        assertThat(report).contains("Recovery Status   : force_resync_prestart")
        assertThat(report).contains("=== PLAYOUT TIMING ===")
        assertThat(report).contains("=== DIAGNOSTIC HINTS ===")
        assertThat(report).contains("Clock not ready for playback")
    }

    @Test
    fun formatDiagnosticHints_flagsPlayingWithoutLocalOutput() {
        val hints =
            AudioIssueReporter.formatDiagnosticHints(
                PlayerViewModel.UiState(
                    playbackState = "playing",
                    audioOutputStarted = false,
                ),
            )

        assertThat(hints).contains("AudioTrack is not started")
    }
}
