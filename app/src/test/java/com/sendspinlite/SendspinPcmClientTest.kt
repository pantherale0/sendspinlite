package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import com.sendspinlite.client.SendspinPcmClient
import org.junit.Test

class SendspinPcmClientTest {
    @Test
    fun summarizeFailureForLog_usesTypeWhenMessageIsMissing() {
        val result = SendspinPcmClient.summarizeFailureForLog(RuntimeException())

        assertThat(result).isEqualTo("RuntimeException")
    }

    @Test
    fun summarizeFailureForLog_truncatesLongMessages() {
        val result = SendspinPcmClient.summarizeFailureForLog(RuntimeException("x".repeat(300)))

        assertThat(result).isEqualTo("RuntimeException: ${"x".repeat(200)}...")
    }

    @Test
    fun summarizeFailureForLog_doesNotIncludeStackTraceFrames() {
        val throwable =
            RuntimeException("socket closed").apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("okhttp3.internal.ws.RealWebSocket", "failWebSocket", "RealWebSocket.kt", 592),
                        StackTraceElement("com.sendspinlite.client.SendspinPcmClient", "onFailure", "SendspinPcmClient.kt", 329),
                    )
            }

        val result = SendspinPcmClient.summarizeFailureForLog(throwable)

        assertThat(result).isEqualTo("RuntimeException: socket closed")
        assertThat(result).doesNotContain("RealWebSocket")
        assertThat(result).doesNotContain("SendspinPcmClient.kt")
    }
}
