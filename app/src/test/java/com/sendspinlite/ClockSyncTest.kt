package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import com.sendspinlite.sync.ClockSync
import org.junit.Test

class ClockSyncTest {
    @Test
    fun hasConverged_becomesTrueAfterSufficientUpdates() {
        val clockSync = ClockSync.referenceFilter()

        repeat(20) { i ->
            val clientTx = 1_000_000L + (i * 100_000L)
            val clientRx = clientTx + 20_000L
            val serverRx = clientTx + 5_000L
            val serverTx = serverRx + 2_000L
            clockSync.onServerTime(
                clientTransmittedUs = clientTx,
                clientReceivedUs = clientRx,
                serverReceivedUs = serverRx,
                serverTransmittedUs = serverTx,
            )
        }

        assertThat(clockSync.getUpdateCount()).isEqualTo(20)
        assertThat(clockSync.hasConverged()).isTrue()
    }

    @Test
    fun reset_returnsClockSyncToInitialState() {
        val clockSync = ClockSync.referenceFilter()

        repeat(5) { i ->
            val clientTx = 2_000_000L + (i * 100_000L)
            val clientRx = clientTx + 20_000L
            val serverRx = clientTx + 5_000L
            val serverTx = serverRx + 2_000L
            clockSync.onServerTime(clientTx, clientRx, serverRx, serverTx)
        }

        clockSync.reset()

        assertThat(clockSync.getUpdateCount()).isEqualTo(0)
        assertThat(clockSync.estimatedOffsetUs()).isEqualTo(0)
        assertThat(clockSync.getAverageRttUs()).isEqualTo(0)
        assertThat(clockSync.getRecommendedSyncFrequencyMs()).isEqualTo(50L)
        assertThat(clockSync.hasConverged()).isFalse()
    }
}
