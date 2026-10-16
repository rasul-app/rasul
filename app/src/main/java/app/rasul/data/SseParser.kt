package app.rasul.data

import okio.BufferedSource

/** One Server-Sent Events frame. [data] joins multiple `data:` lines with `\n`. */
data class SseFrame(val id: String?, val event: String?, val data: String)

/**
 * Blocking SSE line parser (no library). Comment lines (`:` first) are skipped, a single space after
 * the colon is dropped, and a frame without any `data:` line is not emitted. Ends when the source ends;
 * a half-written last frame (no blank line) is dropped.
 */
fun BufferedSource.sseFrames(): Sequence<SseFrame> = sequence {
    var id: String? = null
    var event: String? = null
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = readUtf8Line() ?: break
        if (line.isEmpty()) {
            if (hasData) yield(SseFrame(id, event, data.toString()))
            id = null; event = null; data.setLength(0); hasData = false
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
        }
    }
}
