package io.panelassistant.android.http

/** One complete log entry, even when its message has embedded newlines, is one SSE event. */
internal fun logSseEvent(entry: String): String =
    entry.lineSequence().joinToString(separator = "\n", postfix = "\n\n") { "data: $it" }
