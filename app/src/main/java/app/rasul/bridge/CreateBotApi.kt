package app.rasul.bridge

import app.rasul.R
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class ProfileTemplate(val id: String, val label: String)

@Serializable
data class CreateBotRequest(@SerialName("request_id") val requestId: String, val name: String, val template: String)

@Serializable
private data class CreateBotAccepted(@SerialName("operation_id") val operationId: String)

@Serializable
data class CreateBotOperation(val state: String, @SerialName("profile_id") val profileId: String? = null, val error: String? = null)

@Serializable
private data class CreateBotErrorBody(val error: String)

fun validBotName(name: String): Boolean = Regex("[a-z0-9-]{2,32}").matches(name) && name != "default"

enum class CreateBotError(val message: Int) {
    NameTaken(R.string.create_name_taken),
    InvalidName(R.string.create_invalid_name),
    TemplateUnknown(R.string.create_template_unknown),
    TemplateUnavailable(R.string.create_template_unavailable),
    RateLimited(R.string.create_rate_limited),
    NotReady(R.string.bridge_profile_not_ready),
    DeviceRevoked(R.string.bridge_revoked),
    DeviceKeyRepair(R.string.bridge_key_repair),
    Timeout(R.string.bridge_timeout),
    LinkDropped(R.string.bridge_link_dropped),
    Secure(R.string.bridge_secure_failed),
    AddressUnknown(R.string.bridge_address_unknown),
    Refused(R.string.bridge_refused),
    Unreachable(R.string.bridge_unreachable),
    InvalidRequest(R.string.create_invalid_request),
    InvalidRequestId(R.string.create_invalid_request_id),
    RequestConflict(R.string.create_request_conflict),
    OperationUnknown(R.string.create_operation_unknown),
    CreationFailed(R.string.create_failed),
    OutcomeUncertain(R.string.create_uncertain),
    InvalidResponse(R.string.create_invalid_response),
    RouteMissing(R.string.create_route_missing),
    HeadersTooLarge(R.string.create_headers_too_large),
    InvalidHeader(R.string.create_invalid_header),
    DuplicateHeader(R.string.create_duplicate_header),
    UnsupportedFraming(R.string.create_unsupported_framing),
    InvalidLength(R.string.create_invalid_length),
    BodyTooLarge(R.string.create_body_too_large),
    AuthenticationUnavailable(R.string.create_auth_unavailable),
    BridgeRemoved(R.string.create_bridge_removed),
}

class CreateBotException(val error: CreateBotError) : Exception()

internal fun createTransportError(failure: Throwable): CreateBotError = when (bridgeError(failure)) {
    app.rasul.data.HermesError.DeviceRevoked -> CreateBotError.DeviceRevoked
    app.rasul.data.HermesError.DeviceKeyRepair -> CreateBotError.DeviceKeyRepair
    app.rasul.data.HermesError.BridgeTimeout -> CreateBotError.Timeout
    app.rasul.data.HermesError.BridgeLinkDropped -> CreateBotError.LinkDropped
    app.rasul.data.HermesError.BridgeSecure -> CreateBotError.Secure
    app.rasul.data.HermesError.BridgeAddressUnknown -> CreateBotError.AddressUnknown
    app.rasul.data.HermesError.BridgeRefused -> CreateBotError.Refused
    else -> CreateBotError.Unreachable
}

internal fun createBotError(status: Int, body: String): CreateBotError {
    if (status == 403) return CreateBotError.DeviceRevoked
    if (status == 429) return CreateBotError.RateLimited
    if (status == 503) return CreateBotError.NotReady
    val token = try { profileJson.decodeFromString<CreateBotErrorBody>(body).error } catch (_: Exception) { null }
    return when (token) {
        "Invalid profile name" -> CreateBotError.InvalidName
        "Profile name already exists or is reserved" -> CreateBotError.NameTaken
        "Unknown template" -> CreateBotError.TemplateUnknown
        "Template source is unavailable" -> CreateBotError.TemplateUnavailable
        "Invalid profile request", "Invalid request" -> CreateBotError.InvalidRequest
        "A canonical request UUID is required" -> CreateBotError.InvalidRequestId
        "Request UUID was used for a different operation" -> CreateBotError.RequestConflict
        "Unknown operation" -> CreateBotError.OperationUnknown
        "Route not found" -> CreateBotError.RouteMissing
        "Headers too large" -> CreateBotError.HeadersTooLarge
        "Invalid header" -> CreateBotError.InvalidHeader
        "Duplicate header" -> CreateBotError.DuplicateHeader
        "Unsupported framing" -> CreateBotError.UnsupportedFraming
        "Invalid content length" -> CreateBotError.InvalidLength
        "Request body too large" -> CreateBotError.BodyTooLarge
        "Profile key needs local initialization" -> CreateBotError.NotReady
        "Registered device certificate required" -> CreateBotError.DeviceRevoked
        "Upstream unavailable" -> CreateBotError.Unreachable
        else -> if (status >= 500) CreateBotError.Unreachable else CreateBotError.InvalidResponse
    }
}

internal fun parseTemplates(body: String): List<ProfileTemplate> = profileJson.decodeFromString<List<ProfileTemplate>>(body).also {
    require(it.map { t -> t.id }.distinct().size == it.size)
    require(it.all { t -> Regex("[a-z0-9-]{2,32}").matches(t.id) })
}

internal fun parseOperation(body: String): CreateBotOperation = profileJson.decodeFromString<CreateBotOperation>(body).also {
    require(when (it.state) {
        "running" -> true
        "done" -> it.profileId?.let(::validBotName) == true
        "failed" -> it.error in setOf("Profile creation failed; inspect locally", "Outcome uncertain; inspect profiles locally")
        else -> false
    })
}

interface CreateBotApi {
    suspend fun templates(): List<ProfileTemplate>
    suspend fun create(request: CreateBotRequest): String
    suspend fun operation(id: String): CreateBotOperation
}

class BridgeCreateBotApi(private val client: OkHttpClient) : CreateBotApi {
    private suspend fun <T> send(path: String, expected: Int, body: String? = null, decode: (String) -> T): T {
        val request = Request.Builder().url(BridgeTransport.origin.newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json")
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }.build()
        return try {
            client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code != expected) throw CreateBotException(createBotError(response.code, text))
                try { decode(text) } catch (_: Exception) { throw CreateBotException(CreateBotError.InvalidResponse) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw CreateBotException(createTransportError(e)) }
    }
    override suspend fun templates() = send("v1/profile-templates", 200, decode = ::parseTemplates)
    override suspend fun create(request: CreateBotRequest) = send("v1/profiles", 202, profileJson.encodeToString(request)) {
        profileJson.decodeFromString<CreateBotAccepted>(it).operationId.also { id -> require(Regex("[0-9a-f]{32}").matches(id)) }
    }
    override suspend fun operation(id: String): CreateBotOperation {
        require(Regex("[0-9a-f]{32}").matches(id))
        return send("v1/operations/$id", 200, decode = ::parseOperation)
    }
}
