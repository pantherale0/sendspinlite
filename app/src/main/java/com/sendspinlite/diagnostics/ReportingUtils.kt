package com.sendspinlite.diagnostics

/** Returns the last [n] lines of [text] joined by newlines. */
internal fun lastLines(
    text: String,
    n: Int,
): String = text.lines().takeLast(n).joinToString("\n")
