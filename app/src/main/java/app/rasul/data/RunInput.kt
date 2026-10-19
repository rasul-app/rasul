package app.rasul.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Wire limits for chat attachments on POST /v1/runs. */
object RunInputLimits {
    /** The photo picker allows at most this many photos per send. */
    const val MAX_PHOTOS = 4

    /** Long edge after downscaling. */
    const val PHOTO_LONG_EDGE = 1600

    const val PHOTO_JPEG_QUALITY = 80

    /** The last encode pass shrinks the long edge to this when the quality steps alone are not enough. */
    const val PHOTO_FALLBACK_LONG_EDGE = 1280

    /**
     * Raw bytes for one encoded photo. Base64 grows ~4/3, so a photo at this cap keeps its data
     * URL under ~3/4 of the body budget: the per-photo refusal and the total-size refusal can
     * never contradict each other (a single photo this small cannot make the body too big).
     */
    const val MAX_PHOTO_BYTES = 520 * 1024

    /** A text file above this many UTF-8 bytes is refused. */
    const val MAX_FILE_BYTES = 100 * 1024

    /**
     * The serialized body must stay under this. The bridge rejects request bodies over 1 MiB
     * (bridge/rasul_bridge/limits.py MAX_BODY; Hermes allows 10 MB, api_server.py
     * MAX_REQUEST_BYTES) — ~74 KiB of headroom covers headers/framing and the
     * `session_id`/`provider`/`model` keys beside `input`.
     */
    const val MAX_RUN_BODY_BYTES = 950 * 1024

    /** Thumbnail long edge for composer chips and sent bubbles. */
    const val THUMB_LONG_EDGE = 512

    const val THUMB_JPEG_QUALITY = 75

    /** History keeps at most this many photo thumbnails; older image parts show a "Photo" label. */
    const val HISTORY_PHOTO_THUMBS = 24

    /** A history photo is re-encoded down to this long edge / quality before it is kept in memory. */
    const val HISTORY_THUMB_LONG_EDGE = 256
    const val HISTORY_THUMB_JPEG_QUALITY = 70

    /**
     * The raw-byte budget one photo gets when [photoCount] photos ride in one request: the photos'
     * base64 strings share three quarters of the body cap (base64 is ~4/3 of raw bytes), so a
     * photo inside its share can never push the total over it.
     */
    fun photoBudget(photoCount: Int): Int =
        minOf(MAX_PHOTO_BYTES, MAX_RUN_BODY_BYTES * 9 / (16 * photoCount.coerceAtLeast(1)))
}

private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "text", "md", "markdown", "json", "csv", "tsv", "log", "kt", "kts", "py", "java",
    "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "gradle", "sh", "bash",
    "c", "h", "cpp", "cc", "hpp", "js", "jsx", "ts", "tsx", "html", "htm", "css", "sql",
    "rb", "go", "rs", "swift", "php", "dart", "lua", "r", "pl", "pm", "scss", "less",
)

/** Non-`text/...` MIME types that still carry plain text. */
private val TEXT_FILE_MIMES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-javascript",
    "application/x-yaml", "application/toml", "application/x-sh", "application/sql",
)

/** True when [mimeType] (nullable) or the [fileName] extension marks a small text file. */
fun isTextFile(mimeType: String?, fileName: String?): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (mime != null && (mime.startsWith("text/") || mime in TEXT_FILE_MIMES)) return true
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    return ext != null && ext in TEXT_FILE_EXTENSIONS
}

/** What the composer+server allow for one send. */
sealed interface RunInputPlan {
    /** No attachments staged: `input` stays a plain string (unchanged wire shape). */
    data object TextOnly : RunInputPlan

    /** Attachment [parts] and the finished `input` element for [app.rasul.data.RunsApi.startRun]. */
    data class WithParts(val parts: List<JsonObject>, val input: JsonElement) : RunInputPlan

    /** The serialized request would exceed [RunInputLimits.MAX_RUN_BODY_BYTES]. */
    data object TooLarge : RunInputPlan
}

/** One staged attachment -> one canonical content part (api_server.py:464-498 shape). */
private fun PendingAttachment.toPart(): JsonObject = when (this) {
    is PendingAttachment.Photo -> buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
        })
    }
    is PendingAttachment.TextFile -> buildJsonObject {
        put("type", "text")
        put("text", "$name:\n$text")
    }
    // Bridge files travel as ids in `rasul_files`, never as a part of `input`.
    is PendingAttachment.UploadFile -> throw IllegalArgumentException("A bridge file has no input part")
}

/**
 * The `input` value for POST /v1/runs. Text-only stays a plain string. With [parts] it is a
 * one-message list whose `content` is the optional caption plus the attachment parts —
 * api_server_runs.py:650-656 reads `input[-1].content`, and conversation_loop.py appends it
 * verbatim as `{"role":"user","content":...}`.
 */
fun runInputElement(text: String, parts: List<JsonObject>): JsonElement =
    if (parts.isEmpty()) {
        JsonPrimitive(text)
    } else {
        buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    if (text.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                    parts.forEach(::add)
                })
            })
        }
    }

/** The serialized `{"input": ...}` size as it would be posted (plus keys beside `input`). */
private fun runBodyBytes(input: JsonElement): Int =
    buildJsonObject { put("input", input) }.toString().encodeToByteArray().size + 1024

/**
 * Build the send plan: empty [attachments] -> [RunInputPlan.TextOnly]; otherwise the parts and the
 * finished `input` element (built once, ready to post), with a total-size check against the
 * bridge body cap. Nothing is cleared or sent here.
 */
fun planRunInput(text: String, attachments: List<PendingAttachment>): RunInputPlan {
    if (attachments.isEmpty()) return RunInputPlan.TextOnly
    val parts = attachments.filterNot { it is PendingAttachment.UploadFile }.map { it.toPart() }
    val input = runInputElement(text, parts)
    return if (runBodyBytes(input) <= RunInputLimits.MAX_RUN_BODY_BYTES) {
        RunInputPlan.WithParts(parts, input)
    } else {
        RunInputPlan.TooLarge
    }
}
