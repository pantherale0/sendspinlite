package com.sendspinlite

import com.sendspinlite.playback.PlaybackDiagnostics
import com.sendspinlite.playback.PlaybackHealthRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHealthRulesTest {
    @Test
    fun shouldReportStarvation_whenNoWritesForLongPeriod() {
        assertTrue(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = PlaybackDiagnostics.STARVATION_NO_WRITE_MS,
                outputQueueMs = 200L,
                linkDegraded = false,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldReportStarvation_whenLinkDegradedAndWritesStallSooner() {
        assertTrue(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = PlaybackDiagnostics.STARVATION_NO_WRITE_DEGRADED_MS,
                outputQueueMs = 120L,
                linkDegraded = true,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldReportStarvation_whenQueueCriticallyLow() {
        assertTrue(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = PlaybackDiagnostics.STARVATION_LOW_BUFFER_GRACE_MS,
                outputQueueMs = PlaybackDiagnostics.STARVATION_LOW_BUFFER_MS,
                linkDegraded = false,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldReportStarvation_whenConnectedSocketWedged() {
        assertTrue(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = PlaybackDiagnostics.STARVATION_CONNECTED_STALL_MS,
                outputQueueMs = 80L,
                linkDegraded = false,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldNotReportStarvation_whenNotPlaying() {
        assertFalse(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "paused",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = 5_000L,
                outputQueueMs = 0L,
                linkDegraded = true,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldNotReportStarvation_whenWritesAreRecent() {
        assertFalse(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = 200L,
                outputQueueMs = 150L,
                linkDegraded = false,
                receivedAudioThisStream = true,
            ),
        )
    }

    @Test
    fun shouldNotReportLowBufferStarvation_beforeFirstPcmWriteThisStream() {
        // Matches ANDROID-2 Portal/Echo logs: stream start primes last-write time, then
        // ~500–660ms elapse with an empty AudioTrack before the first PCM frame arrives.
        assertFalse(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = 660L,
                outputQueueMs = 0L,
                linkDegraded = false,
                receivedAudioThisStream = false,
            ),
        )
    }

    @Test
    fun shouldStillReportNoWriteStarvation_beforeFirstPcmWriteThisStream() {
        assertTrue(
            PlaybackHealthRules.shouldReportStarvation(
                playbackState = "playing",
                audioOutputStarted = true,
                connected = true,
                msSinceLastWrite = PlaybackDiagnostics.STARVATION_NO_WRITE_MS,
                outputQueueMs = 0L,
                linkDegraded = false,
                receivedAudioThisStream = false,
            ),
        )
    }
}
