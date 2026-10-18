package app.rasul.bridge

import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.data.sseFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal val APPROVAL_PROFILE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
internal val APPROVAL_REQUEST_ID = Regex("[0-9a-f]{32}")
internal val APPROVAL_CHOICES = listOf("once", "session", "always", "deny")

/** One pending approval item, exactly the shape of GET .../approvals (docs/bridge-approvals.md:54-68). Display text only. */
@Serializable
data class ApprovalItemDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("session_key") val sessionKey: String,
    val profile: String,
    val surface: String,
    val command: String,
    val description: String,
    @SerialName("allow_session") val allowSession: Boolean,
    @SerialName("allow_permanent") val allowPermanent: Boolean,
    @SerialName("expires_at") val expiresAt: Double,
)

@Serializable
private data class ApprovalListDto(val approvals: List<ApprovalItemDto>)

@Serializable
private data class ApprovalAnswerBodyDto(val choice: String)

/** POST answer success body: `{"request_id":"...","state":"answered"}` (docs/bridge-approvals.md:79-80). */
@Serializable
data class ApprovalAnsweredDto(@SerialName("request_id") val requestId: String, val state: String)

/** `new`/`resolved` event data carries only identifiers (docs/bridge-approvals.md:88-100). */
@Serializable
private data class ApprovalRefDto(val profile: String, @SerialName("request_id") val requestId: String)

@Serializable
private data class ApprovalsFailureDto(val error: String)

/** Specific causes; each server code maps to its contract message (docs/bridge-approvals.md:114-122). */
enum class ApprovalsError(val message: Int) {
    Invalid(R.string.approvals_err_invalid),
    InvalidChoice(R.string.approvals_err_invalid_choice),
    Unauthorized(R.string.approvals_err_unauthorized),
    NotFound(R.string.approvals_err_not_found),
    Conflict(R.string.approvals_err_conflict),
    Expired(R.string.approvals_err_expired),
    RateLimited(R.string.approvals_err_rate_limited),

    /** The bridge has no approvals route (feature not set up or older version). */
    Unsupported(R.string.approvals_unsupported),
    InvalidResponse(R.string.approvals_invalid_response),
    BridgeRemoved(R.string.approvals_bridge_removed),

    /** The profile itself is gone or not ready on the PC (the bridge is still paired). */
    BotUnavailable(R.string.approvals_bot_unavailable),
    DeviceRevoked(R.string.bridge_revoked),
    DeviceKeyRepair(R.string.bridge_key_repair),
    Timeout(R.string.bridge_timeout),
    Unreachable(R.string.bridge_unreachable),
}

class ApprovalsException(val error: ApprovalsError) : Exception()

internal fun approvalsError(status: Int, body: String): ApprovalsError {
    val token = try { approvalsJson.decodeFromString<ApprovalsFailureDto>(body).error } catch (_: Exception) { null }
    return when (token) {
        "approvals_invalid" -> ApprovalsError.Invalid
        "approvals_invalid_choice" -> ApprovalsError.InvalidChoice
        "approvals_unauthorized" -> ApprovalsError.Unauthorized
        "approvals_not_found" -> ApprovalsError.NotFound
        "approvals_conflict" -> ApprovalsError.Conflict
        "approvals_expired" -> ApprovalsError.Expired
        "approvals_rate_limited" -> ApprovalsError.RateLimited
        "Route not found" -> ApprovalsError.Unsupported
        "Registered device certificate required" -> ApprovalsError.DeviceRevoked
        else -> when (status) {
            400 -> ApprovalsError.Invalid
            401 -> ApprovalsError.Unauthorized
            403 -> ApprovalsError.DeviceRevoked
            404 -> ApprovalsError.NotFound
            409 -> ApprovalsError.Conflict
            410 -> ApprovalsError.Expired
            429 -> ApprovalsError.RateLimited
            else -> ApprovalsError.InvalidResponse
        }
    }
}

internal fun HermesError.approvalsError(): ApprovalsError = when (this) {
    HermesError.DeviceRevoked -> ApprovalsError.DeviceRevoked
    HermesError.DeviceKeyRepair -> ApprovalsError.DeviceKeyRepair
    HermesError.BridgeTimeout -> ApprovalsError.Timeout
    else -> ApprovalsError.Unreachable
}

internal fun approvalsTransportError(error: Exception): ApprovalsError = bridgeError(error).approvalsError()

/** The read side: a list plus an event stream, for one profile or for every profile of a bridge. */
interface ApprovalsFeedApi {
    suspend fun list(): List<ApprovalItemDto>

    /** SSE frames: one `snapshot` on connect, then `new`/`resolved` ids. Ends when the link drops. */
    fun events(): Flow<ApprovalsEvent>
}

interface ApprovalsApi : ApprovalsFeedApi {
    suspend fun answer(requestId: String, choice: String): ApprovalAnsweredDto
}

sealed interface ApprovalsEvent {
    /** The complete pending list; replaces local state. */
    data class Snapshot(val items: List<ApprovalItemDto>) : ApprovalsEvent
    /** [profile] is set only on the bridge-wide stream, where one stream carries every profile. */
    data class New(val requestId: String, val profile: String? = null) : ApprovalsEvent

    /** Covers answers on the PC, withdrawal and expiry (docs/bridge-approvals.md:102). */
    data class Resolved(val requestId: String, val profile: String? = null) : ApprovalsEvent
}

internal val approvalsJson = Json { ignoreUnknownKeys = true }

internal fun decodeApprovalList(text: String): List<ApprovalItemDto> = try {
    approvalsJson.decodeFromString<ApprovalListDto>(text).approvals.also { items ->
        items.forEach {
            require(APPROVAL_REQUEST_ID.matches(it.requestId) && APPROVAL_PROFILE_ID.matches(it.profile))
            require(it.expiresAt.isFinite())
        }
    }
} catch (_: Exception) { throw ApprovalsException(ApprovalsError.InvalidResponse) }

/**
 * List and event stream over [base]. With a [profileId] they are that profile's routes; with null
 * they are the bridge-wide routes (every profile, each item and event names its profile).
 */
open class BridgeApprovalsFeed internal constructor(
    private val client: OkHttpClient,
    protected val base: HttpUrl,
    private val profileId: String?,
) : ApprovalsFeedApi {

    override suspend fun list(): List<ApprovalItemDto> {
        val request = Request.Builder().url(base).header("Accept", "application/json").build()
        return try {
            client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code != 200) throw ApprovalsException(approvalsError(response.code, text))
                decodeApprovalList(text)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw ApprovalsException(approvalsTransportError(e)) }
    }

    override fun events(): Flow<ApprovalsEvent> = channelFlow {
        val request = Request.Builder().url(base.newBuilder().addPathSegment("events").build())
            .header("Accept", "text/event-stream").build()
        try {
            client.bridgeStreamClient().bridgeResponse(request) { response ->
                val source = response.body?.source()
                if (response.code != 200 || source == null) {
                    val text = response.body?.string().orEmpty()
                    throw ApprovalsException(approvalsError(response.code, text))
                }
                for (frame in source.sseFrames()) readFrame(frame)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ApprovalsException) { throw e }
        catch (e: IOException) { throw ApprovalsException(approvalsTransportError(e)) }
    }

    private suspend fun ProducerScope<ApprovalsEvent>.readFrame(frame: app.rasul.data.SseFrame) {
        when (frame.event) {
            "snapshot" -> send(ApprovalsEvent.Snapshot(decodeApprovalList(frame.data)))
            "new" -> decodeRef(frame.data).let { send(ApprovalsEvent.New(it.requestId, it.profile.takeIf { profileId == null })) }
            "resolved" -> decodeRef(frame.data).let { send(ApprovalsEvent.Resolved(it.requestId, it.profile.takeIf { profileId == null })) }
        }
    }

    private fun decodeRef(text: String): ApprovalRefDto = try {
        approvalsJson.decodeFromString<ApprovalRefDto>(text).also {
            require(APPROVAL_REQUEST_ID.matches(it.requestId) && APPROVAL_PROFILE_ID.matches(it.profile))
            require(profileId == null || it.profile == profileId)
        }
    } catch (_: Exception) { throw ApprovalsException(ApprovalsError.InvalidResponse) }
}

private fun profileApprovalsBase(profileId: String): HttpUrl {
    require(APPROVAL_PROFILE_ID.matches(profileId))
    return BridgeTransport.origin.newBuilder()
        .addPathSegments("v1/profiles").addPathSegment(profileId).addPathSegment("approvals").build()
}

class BridgeApprovalsApi(private val client: OkHttpClient, private val profileId: String) :
    BridgeApprovalsFeed(client, profileApprovalsBase(profileId), profileId), ApprovalsApi {

    override suspend fun answer(requestId: String, choice: String): ApprovalAnsweredDto {
        require(APPROVAL_REQUEST_ID.matches(requestId))
        require(choice in APPROVAL_CHOICES)
        val url = base.newBuilder().addPathSegment(requestId).addPathSegment("answer").build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(approvalsJson.encodeToString(ApprovalAnswerBodyDto(choice))
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code != 200) throw ApprovalsException(approvalsError(response.code, text))
                try {
                    approvalsJson.decodeFromString<ApprovalAnsweredDto>(text).also {
                        require(it.requestId == requestId && it.state == "answered")
                    }
                } catch (_: Exception) { throw ApprovalsException(ApprovalsError.InvalidResponse) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw ApprovalsException(approvalsTransportError(e)) }
    }
}

/** `GET /v1/approvals` and `GET /v1/approvals/events`: one connection for every profile of a bridge. */
class BridgeWideApprovalsApi(client: OkHttpClient) :
    BridgeApprovalsFeed(client, BridgeTransport.origin.newBuilder().addPathSegments("v1/approvals").build(), null)
