package com.sendspinlite.playback

/**
 * Playback-starvation rules without Android I/O. A Wi-Fi roam can stall the WebSocket
 * while AudioTrack drains.
 */
object PlaybackHealthRules {
    /**
     * Returns true when the output queue is nearly empty and PCM writes have stalled.
     * This can precede AudioFlinger BUFFER TIMEOUT during a network stall.
     */
    fun isOutputQueueCriticallyLow(
        outputQueueMs: Long,
        msSinceLastWrite: Long,
    ): Boolean =
        outputQueueMs <= PlaybackDiagnostics.STARVATION_LOW_BUFFER_MS &&
            msSinceLastWrite >= PlaybackDiagnostics.STARVATION_LOW_BUFFER_GRACE_MS

    /**
     * Returns true when a playback stall warrants a reconnect.
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
