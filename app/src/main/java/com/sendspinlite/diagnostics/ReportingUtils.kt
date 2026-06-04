package com.sendspinlite.diagnostics

/** Returns the last [n] lines of [text] joined by newlines. */
internal fun lastLines(
    text: String,
    n: Int,
): String = text.lines().takeLast(n).joinToString("\n")

/**
 * Returns bounded Throwable context without rendering the stack trace.
 *
 * Android's Log methods stringify Throwable stack traces internally, which can
 * allocate enough to crash when the process is already under memory pressure.
 */
internal fun throwableSummary(
    throwable: Throwable,
    maxMessageChars: Int = 180,
): String {
    val type = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
    val message = throwable.message?.firstLine(maxMessageChars).orEmpty()
    return if (message.isBlank()) type else "$type: $message"
}

private fun String.firstLine(maxChars: Int): String {
    val newlineIndex = indexOf('\n').let { if (it < 0) length else it }
    val endIndex = minOf(newlineIndex, maxChars.coerceAtLeast(0))
    return substring(0, endIndex)
}
