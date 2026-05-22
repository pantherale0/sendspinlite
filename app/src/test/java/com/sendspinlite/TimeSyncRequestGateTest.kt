package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeSyncRequestGateTest {
    @Test
    fun shouldSendRequest_allowsOnlyOneInFlightRequest() {
        val gate = TimeSyncRequestGate(responseTimeoutMs = 2_000L)

        assertThat(gate.shouldSendRequest(clientTxUs = 100L, nowMs = 1_000L)).isTrue()
        assertThat(gate.shouldSendRequest(clientTxUs = 200L, nowMs = 1_100L)).isFalse()

        assertThat(gate.acceptResponse(clientTxUs = 100L, nowMs = 1_150L)).isTrue()
        assertThat(gate.shouldSendRequest(clientTxUs = 300L, nowMs = 1_200L)).isTrue()
    }

    @Test
    fun acceptResponse_rejectsStaleResponseWithoutClearingCurrentRequest() {
        val gate = TimeSyncRequestGate(responseTimeoutMs = 2_000L)

        assertThat(gate.shouldSendRequest(clientTxUs = 100L, nowMs = 1_000L)).isTrue()
        assertThat(gate.shouldSendRequest(clientTxUs = 200L, nowMs = 3_100L)).isTrue()

        assertThat(gate.acceptResponse(clientTxUs = 100L, nowMs = 3_150L)).isFalse()
        assertThat(gate.shouldSendRequest(clientTxUs = 300L, nowMs = 3_200L)).isFalse()
        assertThat(gate.acceptResponse(clientTxUs = 200L, nowMs = 3_250L)).isTrue()
    }

    @Test
    fun acceptResponse_rejectsExpiredResponseAndAllowsNextRequest() {
        val gate = TimeSyncRequestGate(responseTimeoutMs = 2_000L)

        assertThat(gate.shouldSendRequest(clientTxUs = 100L, nowMs = 1_000L)).isTrue()

        assertThat(gate.acceptResponse(clientTxUs = 100L, nowMs = 3_001L)).isFalse()
        assertThat(gate.shouldSendRequest(clientTxUs = 200L, nowMs = 3_010L)).isTrue()
    }

    @Test
    fun reset_clearsPendingRequest() {
        val gate = TimeSyncRequestGate(responseTimeoutMs = 2_000L)

        assertThat(gate.shouldSendRequest(clientTxUs = 100L, nowMs = 1_000L)).isTrue()
        gate.reset()

        assertThat(gate.shouldSendRequest(clientTxUs = 200L, nowMs = 1_100L)).isTrue()
    }
}
