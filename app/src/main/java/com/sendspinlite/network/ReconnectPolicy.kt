package com.sendspinlite.network

/**
 * Pure rules for deciding when the player should auto-reconnect after a Sendspin
 * server drop (e.g. server restart). Kept free of Android I/O so it can be unit-tested.
 */
object ReconnectPolicy {
    /** How long a single connect attempt may sit in "connecting" before we treat it as failed. */
    const val CONNECT_TIMEOUT_MS = 15_000L

    /** Cap for exponential backoff between reconnect attempts. */
    const val MAX_BACKOFF_MS = 60_000L

    /** Longer pause when the server port looks closed (typical during upgrades). */
    const val PORT_CLOSED_DELAY_MS = 30_000L

    const val FAILURE_CONNECT_TIMEOUT = "failure:connect_timeout"

    /**
     * True when the client lost its server connection and should retry without user action.
     * Excludes intentional teardown (user disconnect, resource cleanup).
     */
    fun shouldAutoReconnect(status: String): Boolean {
        if (status == "disconnected" || status == "network_lost") {
            return false
        }
        if (status.startsWith("failure:")) {
            return true
        }
        if (!status.startsWith("closed:")) {
            return false
        }
        return status != "closed:user_disconnect" && status != "closed:resource_cleanup"
    }

    /**
     * True while an in-flight connect should suppress duplicate connect() calls.
     * A stale "connecting" status older than [CONNECT_TIMEOUT_MS] must not block recovery.
     */
    fun isActivelyConnecting(
        status: String,
        connected: Boolean,
        connectingStartedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (connected) return false
        if (!status.startsWith("connecting")) return false
        if (connectingStartedAtMs <= 0L) return true
        return nowMs - connectingStartedAtMs < CONNECT_TIMEOUT_MS
    }

    /**
     * Whether the auto-reconnect loop should keep retrying after observing [status]/[connected].
     */
    fun shouldContinueReconnectLoop(
        status: String,
        connected: Boolean,
    ): Boolean {
        if (connected && (status == "ws_open" || status == "open")) {
            return false
        }
        if (status == "disconnected") {
            return false
        }
        if (status == "closed:user_disconnect" || status == "closed:resource_cleanup") {
            return false
        }
        // Keep going through connecting / failure / closed / network_lost so a server
        // restart that outlives the first attempt still recovers without force-closing the app.
        return true
    }

    fun reconnectDelayMs(
        status: String,
        retryCount: Int,
    ): Long {
        if (status.contains("port_closed")) {
            return PORT_CLOSED_DELAY_MS
        }
        val exponent = retryCount.coerceAtLeast(0)
        return (1000L * Math.pow(2.0, exponent.toDouble())).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }
}
