package app.rasul.files

import java.util.Locale

/** What a received file looks like in the chat. Chosen from the file name's extension only. */
enum class FileKind { Pdf, Document, Sheet, Archive, Text, Image, Audio, Video, Other }

/** A `rasul-file://<token>` link from the bridge. [name] and [sizeText] come from the link text. */
data class FileLink(val token: String, val name: String, val sizeText: String?)

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    data class File(val link: FileLink) : MessagePart

    /** `[file not available: name]` from the bridge: shown muted, no action. [name] is null for the bare form. */
    data class Unavailable(val name: String?) : MessagePart
}

object FileLinks {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_NAME_BYTES = 200

    /**
     * Notification text: `[name (size)](rasul-file://token)` becomes `name (size)`, so no token leaves the chat.
     * A link still arriving while the reply streams is dropped whole, and format characters are removed.
     */
    fun plainText(text: String): String =
        link.replace(partialLink.replace(text, "")) { it.groupValues[1] }
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }

    // Exactly 43 token characters then ")": a longer or shorter token never matches. A leading "!" (image syntax) is excluded in code.
    private val link = Regex("""\[([^\]\n]{1,300})]\(rasul-file://([A-Za-z0-9_-]{43})\)""")
    // The end of a reply that stops inside a link: `[label](`, `[label](rasul-fi` or `[label](rasul-file://` plus part of a token.
    private val partialLink = Regex(
        """\[[^\]\n]{1,300}]\((?:""" + (0..12).joinToString("|") { Regex.escape("rasul-file://".take(it)) } + """|rasul-file://[A-Za-z0-9_-]{0,43})$""",
    )
    private val unavailable = Regex("""\[file not available(?:: ([^\]\n]{0,300}))?]""")
    private val sizeSuffix = Regex(""" \((\d+(?:\.\d+)? (?:B|KB|MB))\)$""")

    fun hasFileLink(text: String): Boolean = text.contains("rasul-file:") || text.contains("[file not available")

    /** Splits assistant text into plain text, file cards and "not available" notes. Anything else stays text. */
    fun split(text: String): List<MessagePart> {
        if (!hasFileLink(text)) return listOf(MessagePart.Text(text))
        data class Hit(val range: IntRange, val part: MessagePart)
        val hits = mutableListOf<Hit>()
        for (m in link.findAll(text)) {
            if (m.range.first > 0 && text[m.range.first - 1] == '!') continue
            val label = m.groupValues[1]
            val size = sizeSuffix.find(label)
            val name = cleanFormat((if (size != null) label.substring(0, size.range.first) else label).trim())
            hits += Hit(m.range, MessagePart.File(FileLink(m.groupValues[2], name, size?.groupValues?.get(1))))
        }
        for (m in unavailable.findAll(text)) {
            if (hits.any { it.range.first <= m.range.last && m.range.first <= it.range.last }) continue
            hits += Hit(m.range, MessagePart.Unavailable(m.groups[1]?.value?.takeIf { it.isNotBlank() }))
        }
        if (hits.isEmpty()) return listOf(MessagePart.Text(text))
        val parts = mutableListOf<MessagePart>()
        var at = 0
        for (hit in hits.sortedBy { it.range.first }) {
            if (hit.range.first > at) parts += MessagePart.Text(text.substring(at, hit.range.first))
            parts += hit.part
            at = hit.range.last + 1
        }
        if (at < text.length) parts += MessagePart.Text(text.substring(at))
        // Whitespace between cards is layout, not content.
        return parts.filterNot { it is MessagePart.Text && it.text.isBlank() }
    }

    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    private val docs = setOf("doc", "docx", "odt", "rtf", "ppt", "pptx", "odp")
    private val sheets = setOf("xls", "xlsx", "ods", "csv", "tsv")
    private val texts = setOf("txt", "md", "log", "json", "yaml", "yml", "ini", "toml", "py", "kt", "java", "c", "cpp", "h", "sh", "sql", "html", "htm", "xml", "js", "css")
    private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic")
    private val audio = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
    private val video = setOf("mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String): FileKind {
        val e = extension(name)
        return when {
            e == "pdf" -> FileKind.Pdf
            e in docs -> FileKind.Document
            e in sheets -> FileKind.Sheet
            e in archives -> FileKind.Archive
            e in images -> FileKind.Image
            e in audio -> FileKind.Audio
            e in video -> FileKind.Video
            e in texts -> FileKind.Text
            else -> FileKind.Other
        }
    }

    /** Types that can run script when rendered. They are never opened in the app or a WebView: Save and Share only. */
    private val activeContent = setOf("html", "htm", "xhtml", "svg", "xml", "js", "mjs", "apk", "apks", "xapk", "aab")

    fun isActiveContent(name: String): Boolean = extension(name) in activeContent

    /** Files that decode safely to a small thumbnail with the platform decoder. */
    fun hasThumbnail(name: String): Boolean = extension(name) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** Media types that are markup or script, or an app installer: never handed to a viewer. */
    fun neverRender(mime: String): Boolean =
        mime.endsWith("xml") || mime.contains("javascript") || mime.contains("ecmascript") ||
            mime in setOf("text/html", "multipart/related", "message/rfc822", "application/vnd.android.package-archive")

    /** Format characters (bidi controls, zero-width, BOM) can make a name look like another type; they become "_". */
    fun cleanFormat(raw: String): String =
        raw.map { if (Character.getType(it) == Character.FORMAT.toInt()) '_' else it }.joinToString("")

    /** The type handed to the system viewer, picker and share sheet. */
    fun mimeFor(contentType: String?, name: String): String {
        if (isActiveContent(name)) return "application/octet-stream"
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (declared.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) && declared != "application/octet-stream") {
            return if (neverRender(declared)) "application/octet-stream" else declared
        }
        return when (extension(name)) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    /** A name that is safe as one file name: no separators, control characters or leading dots; at most 120 characters, extension kept. */
    fun sanitiseName(raw: String): String {
        var s = cleanFormat(raw).map { c -> if (c == '/' || c == '\\' || c == ':' || c.isISOControl()) '_' else c }.joinToString("")
        s = s.trim().trimStart('.').trim()
        if (s.isEmpty() || s.all { it == '_' }) return "file"
        if (s.length > 120 || s.toByteArray().size > MAX_NAME_BYTES) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
            val stem = s.substring(0, s.length - ext.length)
            val room = MAX_NAME_BYTES - ext.toByteArray().size
            var end = minOf(stem.length, 120 - ext.length)
            while (end > 0 && (stem.substring(0, end).toByteArray().size > room || stem[end - 1].isHighSurrogate())) end--
            s = stem.substring(0, end) + ext
        }
        return s
    }
}
