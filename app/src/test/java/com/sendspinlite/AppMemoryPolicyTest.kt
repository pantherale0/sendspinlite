package com.sendspinlite

import com.sendspinlite.system.AppMemoryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMemoryPolicyTest {
    @Test
    fun leanRingBuffer_isMuchSmallerThanDefault() {
        assertTrue(AppMemoryPolicy.nativeRingBufferBytes(true) < AppMemoryPolicy.nativeRingBufferBytes(false) / 4)
    }

    @Test
    fun leanIntervals_areSlowerThanDefault() {
        assertEquals(1_000L, AppMemoryPolicy.diagnosticsIntervalMs(true))
        assertTrue(AppMemoryPolicy.diagnosticsIntervalMs(true) > AppMemoryPolicy.diagnosticsIntervalMs(false))
        assertTrue(AppMemoryPolicy.healthCheckIntervalMs(true) > AppMemoryPolicy.healthCheckIntervalMs(false))
    }

    @Test
    fun leanEventBuffer_isSmallerThanDefault() {
        assertTrue(AppMemoryPolicy.eventBufferCapacity(true) < AppMemoryPolicy.eventBufferCapacity(false))
    }

    @Test
    fun ringBufferSizes_matchConstants() {
        assertEquals(AppMemoryPolicy.RING_BUFFER_BYTES_LEAN, AppMemoryPolicy.nativeRingBufferBytes(true))
        assertEquals(AppMemoryPolicy.RING_BUFFER_BYTES_DEFAULT, AppMemoryPolicy.nativeRingBufferBytes(false))
    }
}
