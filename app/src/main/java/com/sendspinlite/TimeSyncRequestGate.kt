package com.sendspinlite

/**
 * Keeps client/time sampling to one in-flight request so slow WebSocket replies
 * do not build a backlog of stale clock samples.
 */
internal class TimeSyncRequestGate(
    private val responseTimeoutMs: Long,
) {
    private var pendingClientTxUs: Long? = null
    private var pendingSentAtMs: Long = 0L

    @Synchronized
    fun shouldSendRequest(
        clientTxUs: Long,
        nowMs: Long,
    ): Boolean {
        val pending = pendingClientTxUs
        if (pending != null && nowMs - pendingSentAtMs <= responseTimeoutMs) {
            return false
        }

        pendingClientTxUs = clientTxUs
        pendingSentAtMs = nowMs
        return true
    }

    @Synchronized
    fun acceptResponse(
        clientTxUs: Long,
        nowMs: Long,
    ): Boolean {
        val pending = pendingClientTxUs ?: return false
        val ageMs = nowMs - pendingSentAtMs
        if (pending == clientTxUs && ageMs <= responseTimeoutMs) {
            clear()
            return true
        }

        if (pending == clientTxUs || ageMs > responseTimeoutMs) {
            clear()
        }
        return false
    }

    @Synchronized
    fun reset() {
        clear()
    }

    private fun clear() {
        pendingClientTxUs = null
        pendingSentAtMs = 0L
    }
}
