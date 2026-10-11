package com.sendspinlite

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sendspinlite.client.SendspinNativeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Constructs the Android client wrapper, checks initial diagnostics, and releases resources.
 * This test does not connect, so it does not exercise native handle creation.
 */
@RunWith(AndroidJUnit4::class)
class SendspinNativeClientTest {
    @Test
    fun createAndDestroy_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client =
            SendspinNativeClient(
                wsUrl = "ws://127.0.0.1:8927/sendspin",
                clientId = "test-client",
                clientName = "Test Client",
                context = context,
            )
        try {
            val diagnostics = client.diagnostics.value
            assertEquals("idle", diagnostics.status)
            assertFalse(diagnostics.connected)
            assertFalse(client.isHealthy())
        } finally {
            client.cleanupResources()
        }
    }
}
