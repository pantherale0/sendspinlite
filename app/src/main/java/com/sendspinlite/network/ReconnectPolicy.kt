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

    /** Poll interval while waiting for a reconnect attempt to succeed or fail. */
    const val RECONNECT_POLL_MS = 250L

    private const val BASE_BACKOFF_MS = 1_000L

    const val FAILURE_CONNECT_TIMEOUT = "failure:connect_timeout"

    /**
     * True when the client lost its server connection and should retry without user action.
     * Excludes intentional teardown (user disconnect, resource cleanup).
     */
    fun shouldAutoReconnect(status: String): Boolean =
        when {
            status == "disconnected" || status == "network_lost" -> false
            status.startsWith("failure:") -> true
            !status.startsWith("closed:") -> false
            else -> status != "closed:user_disconnect" && status != "closed:resource_cleanup"
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
    ): Boolean =
        when {
            connected -> false
            !status.startsWith("connecting") -> false
            connectingStartedAtMs <= 0L -> true
            else -> nowMs - connectingStartedAtMs < CONNECT_TIMEOUT_MS
        }

    /**
     * Whether the auto-reconnect loop should keep retrying after observing [status]/[connected].
     */
    fun shouldContinueReconnectLoop(
        status: String,
        connected: Boolean,
    ): Boolean =
        when {
            connected && (status == "ws_open" || status == "open") -> false
            status == "disconnected" -> false
            status == "closed:user_disconnect" || status == "closed:resource_cleanup" -> false
            // Keep going through connecting / failure / closed / network_lost so a server
            // restart that outlives the first attempt still recovers without force-closing the app.
            else -> true
        }

    fun reconnectDelayMs(
        status: String,
        retryCount: Int,
    ): Long {
        if (status.contains("port_closed")) {
            return PORT_CLOSED_DELAY_MS
        }
        val exponent = retryCount.coerceAtLeast(0)
        return (BASE_BACKOFF_MS * Math.pow(2.0, exponent.toDouble())).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }

    fun isConnectedOpen(
        status: String,
        connected: Boolean,
    ): Boolean = connected && (status == "ws_open" || status == "open")

    fun isTerminalDisconnect(status: String): Boolean =
        status == "disconnected" ||
            status == "closed:user_disconnect" ||
            status == "closed:resource_cleanup"

    fun isReconnectFailureStatus(status: String): Boolean =
        status.startsWith("failure:") ||
            (status.startsWith("closed:") && shouldAutoReconnect(status))
}
