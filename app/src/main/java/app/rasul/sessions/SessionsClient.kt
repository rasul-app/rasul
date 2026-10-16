package app.rasul.sessions

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
}

@Serializable
data class SessionListDto(val data: List<SessionDto>)

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
}

@Serializable
data class SessionMessagesDto(val data: List<SessionMessageDto>)

interface SessionsApi {
    suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>>
}

class SessionsClient(private val client: HermesClient) : SessionsApi {
    private val json = Json { ignoreUnknownKeys = true }

    /** GET /api/sessions (newest activity first, server-side). */
    suspend fun list(target: HermesTarget, limit: Int = 50): HermesResult<List<SessionDto>> =
        decode("api/sessions", client.send(target, "api/sessions", query = mapOf("limit" to limit.toString()))) {
            json.decodeFromString<SessionListDto>(it).data
        }

    /** GET /api/sessions/{id}/messages (live transcript, images as placeholders). */
    override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.NotFound)
        return decode(
            "api/sessions/{id}/messages",
            client.send(target, "api/sessions/$sessionId/messages", query = mapOf("inline_images" to "false")),
        ) { json.decodeFromString<SessionMessagesDto>(it).data }
    }

    private fun <T> decode(what: String, r: HermesResult<String>, f: (String) -> T): HermesResult<T> =
        when (r) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> try {
                HermesResult.Ok(f(r.value))
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Incompatible(listOf(what)))
            }
        }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_.:-]{1,200}")
    }
}
