package app.rasul.sessions

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which per-chat session routes the server advertises in GET /v1/capabilities `features`. */
data class SessionControlSupport(val fork: Boolean = false)

/** The new session returned by POST /api/sessions/{id}/fork (201 `{"session": {...}}`). */
data class ForkedSession(val id: String, val title: String?)

/** What the chat header needs from GET /api/sessions/{id}. */
data class SessionInfo(val title: String?, val parentSessionId: String?)

/** Fork for one chat. [None] is used where no server is wired (previews, old tests). */
interface ChatSessionControl {
    /** Never fails: a server that cannot be asked, or does not advertise a feature, is unsupported. */
    suspend fun support(target: HermesTarget): SessionControlSupport
    suspend fun fork(target: HermesTarget, sessionId: String): HermesResult<ForkedSession>
    suspend fun info(target: HermesTarget, sessionId: String): HermesResult<SessionInfo>

    object None : ChatSessionControl {
        override suspend fun support(target: HermesTarget) = SessionControlSupport()
        override suspend fun fork(target: HermesTarget, sessionId: String): HermesResult<ForkedSession> = HermesResult.Err(HermesError.NotFound)
        override suspend fun info(target: HermesTarget, sessionId: String): HermesResult<SessionInfo> = HermesResult.Err(HermesError.NotFound)
    }
}

@Serializable
private data class ForkReplyDto(val session: ForkedSessionDto)

@Serializable
private data class ForkedSessionDto(val id: String, val title: String? = null)

@Serializable
private data class InfoReplyDto(val session: InfoDto)

@Serializable
private data class InfoDto(val title: String? = null, @SerialName("parent_session_id") val parentSessionId: String? = null)

class SessionControlClient(private val client: HermesClient) : ChatSessionControl {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun support(target: HermesTarget): SessionControlSupport =
        when (val caps = client.capabilities(target)) {
            is HermesResult.Err -> SessionControlSupport()
            is HermesResult.Ok -> caps.value.enabledFeatures().let {
                SessionControlSupport(fork = "session_fork" in it)
            }
        }

    /** POST /api/sessions/{id}/fork with an empty object: the server names the fork (api_server.py:3335-3340). */
    override suspend fun fork(target: HermesTarget, sessionId: String): HermesResult<ForkedSession> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.ResourceNotFound)
        return decode("api/sessions/{session_id}/fork", client.send(target, "api/sessions/$sessionId/fork", httpMethod = "POST")) {
            val session = json.decodeFromString<ForkReplyDto>(it).session
            require(SAFE_ID.matches(session.id) && session.id != sessionId)
            ForkedSession(session.id, session.title)
        }
    }

    override suspend fun info(target: HermesTarget, sessionId: String): HermesResult<SessionInfo> {
        if (!SAFE_ID.matches(sessionId)) return HermesResult.Err(HermesError.ResourceNotFound)
        return decode("api/sessions/{session_id}", client.send(target, "api/sessions/$sessionId", httpMethod = "GET")) {
            json.decodeFromString<InfoReplyDto>(it).session.let { s -> SessionInfo(s.title, s.parentSessionId) }
        }
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
