package com.sendspinlite.playback

/**
 * Pure rules for detecting playback starvation (e.g. Wi-Fi roam stalls the WebSocket while
 * AudioTrack drains). Kept separate from Android I/O so it can be unit-tested.
 */
object PlaybackHealthRules {
    /**
     * Returns true when the output queue is nearly empty and no PCM has arrived recently —
     * typical just before AudioFlinger BUFFER TIMEOUT during a network stall.
     */
    fun isOutputQueueCriticallyLow(
        outputQueueMs: Long,
        msSinceLastWrite: Long,
    ): Boolean =
        outputQueueMs <= PlaybackDiagnostics.STARVATION_LOW_BUFFER_MS &&
            msSinceLastWrite >= PlaybackDiagnostics.STARVATION_LOW_BUFFER_GRACE_MS

    /**
     * Returns true when playback should be treated as starved and a reconnect is warranted.
     */
    fun shouldReportStarvation(
        playbackState: String,
        audioOutputStarted: Boolean,
        connected: Boolean,
        msSinceLastWrite: Long,
        outputQueueMs: Long,
        linkDegraded: Boolean,
    ): Boolean {
        if (playbackState != "playing" || !audioOutputStarted) return false
        if (msSinceLastWrite < 0L) return false

        val noWriteThreshold =
            if (linkDegraded) {
                PlaybackDiagnostics.STARVATION_NO_WRITE_DEGRADED_MS
            } else {
                PlaybackDiagnostics.STARVATION_NO_WRITE_MS
            }

        if (msSinceLastWrite >= noWriteThreshold) {
            return true
        }

        if (isOutputQueueCriticallyLow(outputQueueMs, msSinceLastWrite)) {
            return true
        }

        // Socket may still show connected while TCP is wedged after roam.
        if (connected && msSinceLastWrite >= PlaybackDiagnostics.STARVATION_CONNECTED_STALL_MS) {
            return true
        }

        return false
    }
}
