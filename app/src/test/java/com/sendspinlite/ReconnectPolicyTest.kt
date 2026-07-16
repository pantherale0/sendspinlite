package com.sendspinlite

import com.sendspinlite.network.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun shouldAutoReconnect_whenConnectionLost() {
        assertTrue(ReconnectPolicy.shouldAutoReconnect("closed:connection_lost"))
        assertTrue(ReconnectPolicy.shouldAutoReconnect("failure:connect_timeout"))
        assertTrue(ReconnectPolicy.shouldAutoReconnect("closed:playback_starvation"))
    }

    @Test
    fun shouldAutoReconnect_falseForIntentionalTeardown() {
        assertFalse(ReconnectPolicy.shouldAutoReconnect("disconnected"))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("network_lost"))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("closed:user_disconnect"))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("closed:resource_cleanup"))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("connecting"))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("connecting..."))
        assertFalse(ReconnectPolicy.shouldAutoReconnect("ws_open"))
    }

    @Test
    fun isActivelyConnecting_trueWithinTimeout() {
        val started = 1_000L
        assertTrue(
            ReconnectPolicy.isActivelyConnecting(
                status = "connecting...",
                connected = false,
                connectingStartedAtMs = started,
                nowMs = started + 1_000L,
            ),
        )
    }

    @Test
    fun isActivelyConnecting_falseAfterTimeoutSoRecoveryIsNotBlocked() {
        val started = 1_000L
        assertFalse(
            ReconnectPolicy.isActivelyConnecting(
                status = "connecting...",
                connected = false,
                connectingStartedAtMs = started,
                nowMs = started + ReconnectPolicy.CONNECT_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun isActivelyConnecting_falseWhenAlreadyConnectedOrNotConnecting() {
        assertFalse(
            ReconnectPolicy.isActivelyConnecting(
                status = "connecting...",
                connected = true,
                connectingStartedAtMs = 1L,
                nowMs = 2L,
            ),
        )
        assertFalse(
            ReconnectPolicy.isActivelyConnecting(
                status = "failure:connect_timeout",
                connected = false,
                connectingStartedAtMs = 1L,
                nowMs = 2L,
            ),
        )
    }

    @Test
    fun shouldContinueReconnectLoop_throughConnectingAndFailuresUntilOpen() {
        // Server restart scenario: first attempt leaves status stuck in connecting, then timeout.
        assertTrue(ReconnectPolicy.shouldContinueReconnectLoop("closed:connection_lost", connected = false))
        assertTrue(ReconnectPolicy.shouldContinueReconnectLoop("connecting...", connected = false))
        assertTrue(ReconnectPolicy.shouldContinueReconnectLoop("connecting", connected = false))
        assertTrue(
            ReconnectPolicy.shouldContinueReconnectLoop(
                ReconnectPolicy.FAILURE_CONNECT_TIMEOUT,
                connected = false,
            ),
        )
        assertTrue(ReconnectPolicy.shouldContinueReconnectLoop("network_lost", connected = false))

        assertFalse(ReconnectPolicy.shouldContinueReconnectLoop("ws_open", connected = true))
        assertFalse(ReconnectPolicy.shouldContinueReconnectLoop("disconnected", connected = false))
        assertFalse(ReconnectPolicy.shouldContinueReconnectLoop("closed:user_disconnect", connected = false))
        assertFalse(ReconnectPolicy.shouldContinueReconnectLoop("closed:resource_cleanup", connected = false))
    }

    @Test
    fun reconnectDelayMs_exponentialBackoffAndPortClosed() {
        assertEquals(1_000L, ReconnectPolicy.reconnectDelayMs("closed:connection_lost", retryCount = 0))
        assertEquals(2_000L, ReconnectPolicy.reconnectDelayMs("closed:connection_lost", retryCount = 1))
        assertEquals(4_000L, ReconnectPolicy.reconnectDelayMs("failure:connect_timeout", retryCount = 2))
        assertEquals(
            ReconnectPolicy.MAX_BACKOFF_MS,
            ReconnectPolicy.reconnectDelayMs("closed:connection_lost", retryCount = 20),
        )
        assertEquals(
            ReconnectPolicy.PORT_CLOSED_DELAY_MS,
            ReconnectPolicy.reconnectDelayMs("failure:port_closed", retryCount = 0),
        )
    }
}
