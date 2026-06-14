package com.sendspinlite

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sendspinlite.client.SendspinNativeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the native client lifecycle against the real libsendspin_jni on-device: create the
 * native client, read its initial diagnostics, then destroy it. Verifies the JNI bridge loads
 * and the create/destroy path does not crash.
 */
@RunWith(AndroidJUnit4::class)
class SendspinNativeClientTest {
    @Test
    fun createAndDestroy_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = SendspinNativeClient(
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
