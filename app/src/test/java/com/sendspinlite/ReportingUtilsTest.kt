package com.sendspinlite

import com.google.common.truth.Truth.assertThat
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
}
