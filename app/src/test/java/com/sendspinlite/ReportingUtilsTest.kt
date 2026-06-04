package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import com.sendspinlite.diagnostics.lastLines
import com.sendspinlite.diagnostics.throwableSummary
import org.junit.Test

class ReportingUtilsTest {
    @Test
    fun lastLines_returnsTailLinesWhenInputHasMoreLines() {
        val input =
            """
            one
            two
            three
            four
            """.trimIndent()

        val result = lastLines(input, 2)

        assertThat(result).isEqualTo("three\nfour")
    }

    @Test
    fun lastLines_returnsEntireTextWhenNExceedsLineCount() {
        val input =
            """
            one
            two
            """.trimIndent()

        val result = lastLines(input, 10)

        assertThat(result).isEqualTo("one\ntwo")
    }

    @Test
    fun throwableSummary_includesTypeAndBoundedFirstLineWithoutStackTrace() {
        val throwable =
            IllegalStateException(
                "first line is longer than the limit\nsecond line should not be included",
            ).apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("com.sendspinlite.Example", "call", "Example.kt", 42),
                    )
            }

        val result = throwableSummary(throwable, maxMessageChars = 19)

        assertThat(result).isEqualTo("IllegalStateException: first line is longe")
        assertThat(result).doesNotContain("Example.kt")
        assertThat(result).doesNotContain("second line")
    }
}
