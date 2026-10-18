package app.rasul.sessions

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.REMOTE_PHOTO_OMITTED
import app.rasul.data.RunInputLimits
import app.rasul.data.SentAttachment
import app.rasul.data.dataUrlJpegThumb
import app.rasul.data.isTextFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** One row of GET /api/sessions (server `_session_response`). Unknown keys are ignored. */
@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    val source: String? = null,
    val model: String? = null,
    @SerialName("started_at") val startedAt: JsonElement? = null,
    @SerialName("last_active") val lastActive: JsonElement? = null,
    @SerialName("ended_at") val endedAt: JsonElement? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val preview: String? = null,
) {
    /** A session without `ended_at` is still open. */
    val isActive: Boolean get() = endedAt == null || endedAt is JsonNull
    val isCronRun: Boolean get() = isCronSessionSource(source)
}

@Serializable
data class SessionListDto(val data: List<SessionDto>)

@Serializable
data class SessionOneDto(val session: SessionDto)

@Serializable
private data class SessionDeletedDto(
    @SerialName("object") val objectType: String? = null,
    val id: String,
    val deleted: Boolean,
)

/** One message of GET /api/sessions/{id}/messages. `content` is a string or a list of parts. */
@Serializable
data class SessionMessageDto(
    val id: JsonElement? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: JsonElement? = null,
) {
    /** Plain text of the message: the string, or the `text` of every text part. */
    fun text(): String = when (val c = content) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
        else -> ""
    }

    /**
     * What a history row shows as its text. A file part ("name:\nbody", the shape a staged text
     * file is sent as) keeps a short preview: the name is already on the file chip.
     */
    fun displayText(): String = when (val c = content) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.mapNotNull {
            ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(::filePreview)
        }.joinToString("\n")
        else -> ""
    }

    /** Image and file parts of a parts-list `content`, as displayable attachments (api_server.py:493 shape). */
    fun attachments(): List<SentAttachment> = when (val c = content) {
        is JsonArray -> c.mapNotNull { part ->
            val o = part as? JsonObject ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "image_url", "input_image" -> {
                    val url = when (val ref = o["image_url"]) {
                        is JsonObject -> (ref["url"] as? JsonPrimitive)?.contentOrNull
                        is JsonPrimitive -> ref.contentOrNull
                        else -> null
                    }
                    url?.takeIf { it.isNotBlank() }?.let(SentAttachment::RemotePhoto)
                }
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull
                    ?.let { t -> fileName(t)?.let(SentAttachment::NamedFile) }
                else -> null
            }
        }
        else -> emptyList()
    }
}

/** `name:` prefix line of a sent file part (runInput builds `"$name:\n$text"`). */
private val FILE_PART_NAME = Regex("^([^\\n]{1,200}):\\n")

/** The name a text part starts with, when it has one of the text-file extensions the picker accepts. */
private fun fileName(text: String): String? =
    FILE_PART_NAME.find(text)?.groupValues?.get(1)?.takeIf { isTextFile(null, it) }

/** A file part keeps a bounded preview after its name line; anything else is plain text. */
private fun filePreview(part: String): String =
    fileName(part)?.let { part.substringAfter('\n').take(FILE_PREVIEW_CHARS) } ?: part

private const val FILE_PREVIEW_CHARS = 600

@Serializable
data class SessionMessagesDto(val data: List<SessionMessageDto>)

internal fun isCronSessionSource(source: String?): Boolean = source?.equals("cron", ignoreCase = true) == true

interface SessionsApi {
    suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>>
}

interface SessionsManagementApi {
    suspend fun list(target: HermesTarget, limit: Int = 50): HermesResult<List<SessionDto>>
    suspend fun rename(target: HermesTarget, sessionId: String, title: String): HermesResult<SessionDto>
    suspend fun delete(target: HermesTarget, sessionId: String): HermesResult<Unit>
}

class SessionsClient(
    private val client: HermesClient,
    /** JSON decode + history photo shrink run here, never on the caller's thread. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** data url -> small JPEG bytes, or null when it cannot be decoded (testable seam). */
    private val thumbnail: (String) -> ByteArray? = {
        dataUrlJpegThumb(it, RunInputLimits.HISTORY_THUMB_LONG_EDGE, RunInputLimits.HISTORY_THUMB_JPEG_QUALITY)
    },
) : SessionsApi, SessionsManagementApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun rename(target: HermesTarget, sessionId: String, title: String): HermesResult<SessionDto> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.NotFound)
        return decode("api/sessions/{session_id}", client.send(
            target,
            "api/sessions/$sessionId",
            body = buildJsonObject { put("title", title) }.toString(),
            httpMethod = "PATCH",
        )) { json.decodeFromString<SessionOneDto>(it).session }
    }

    override suspend fun delete(target: HermesTarget, sessionId: String): HermesResult<Unit> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.NotFound)
        return when (val r = client.send(target, "api/sessions/$sessionId", httpMethod = "DELETE")) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> try {
                val deleted = json.decodeFromString<SessionDeletedDto>(r.value)
                if (deleted.deleted && deleted.id == sessionId) HermesResult.Ok(Unit)
                else HermesResult.Err(HermesError.ResourceNotFound)
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Incompatible(listOf("api/sessions/{session_id}")))
            }
        }
    }

    /** GET /api/sessions (newest activity first, server-side). */
    override suspend fun list(target: HermesTarget, limit: Int): HermesResult<List<SessionDto>> =
        decode("api/sessions", client.send(target, "api/sessions", query = mapOf("limit" to limit.toString()))) {
            json.decodeFromString<SessionListDto>(it).data
        }

    /** GET /api/sessions/{id}/messages (live transcript, images as placeholders). */
    override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.NotFound)
        return decode(
            "api/sessions/{id}/messages",
            client.send(target, "api/sessions/$sessionId/messages", query = mapOf("inline_images" to "false")),
        ) { json.decodeFromString<SessionMessagesDto>(it).data.boundPhotoHistory() }
    }

    /**
     * History photos: keep only the newest [RunInputLimits.HISTORY_PHOTO_THUMBS] data-url images as
     * small re-encoded thumbnails; older ones keep the [REMOTE_PHOTO_OMITTED] marker the UI shows
     * as a "Photo" label. The full base64 strings are dropped here, before anything sees state.
     */
    private fun List<SessionMessageDto>.boundPhotoHistory(): List<SessionMessageDto> {
        val positions = mutableSetOf<Pair<Int, Int>>()
        forEachIndexed { mi, m ->
            (m.content as? JsonArray)?.forEachIndexed { pi, part ->
                if (dataUrlOf(part) != null) positions += mi to pi
            }
        }
        if (positions.isEmpty()) return this
        val keep = positions.toList().takeLast(RunInputLimits.HISTORY_PHOTO_THUMBS).toSet()
        return mapIndexed { mi, m ->
            val content = m.content as? JsonArray ?: return@mapIndexed m
            var changed = false
            val next = content.mapIndexed { pi, part ->
                val pos = mi to pi
                if (pos !in positions) return@mapIndexed part
                changed = true
                val shrunk = if (pos in keep) {
                    thumbnail(dataUrlOf(part)!!)
                        ?.let { "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(it) }
                } else {
                    null
                }
                rewriteImageUrl(part, shrunk ?: REMOTE_PHOTO_OMITTED)
            }
            if (changed) m.copy(content = JsonArray(next)) else m
        }
    }

    /** The `data:` url of an image part, or null (non-image part, or a plain https url). */
    private fun dataUrlOf(part: JsonElement): String? {
        val o = part as? JsonObject ?: return null
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (type != "image_url" && type != "input_image") return null
        val url = when (val ref = o["image_url"]) {
            is JsonObject -> (ref["url"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> ref.contentOrNull
            else -> null
        }
        return url?.takeIf { it.startsWith("data:") }
    }

    private fun rewriteImageUrl(part: JsonElement, url: String): JsonElement {
        val o = part as JsonObject
        val ref = o["image_url"]
        val next = when (ref) {
            is JsonObject -> JsonObject(ref + ("url" to JsonPrimitive(url)))
            else -> JsonPrimitive(url)
        }
        return JsonObject(o + ("image_url" to next))
    }

    private suspend fun <T> decode(what: String, r: HermesResult<String>, f: (String) -> T): HermesResult<T> =
        when (r) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> try {
                HermesResult.Ok(withContext(work) { f(r.value) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Incompatible(listOf(what)))
            }
        }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_.:-]{1,200}")
    }
}
