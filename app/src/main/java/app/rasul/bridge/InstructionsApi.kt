package app.rasul.bridge

import app.rasul.R
import app.rasul.data.HermesError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

const val MAX_INSTRUCTIONS_BYTES = 20_000

@Serializable
data class InstructionsDocument(val text: String, val revision: String)

@Serializable
private data class InstructionsSaved(val revision: String)

@Serializable
private data class InstructionsFailure(val error: String)

internal fun instructionsRevision(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

enum class InstructionsError(val message: Int) {
    Conflict(R.string.instructions_conflict),
    UnsafePath(R.string.instructions_unsafe_path),
    TooLarge(R.string.instructions_too_large),
    NotUtf8(R.string.instructions_not_utf8),
    InvalidRequest(R.string.instructions_invalid),
    ProfileMissing(R.string.instructions_profile_missing),
    RateLimited(R.string.instructions_rate_limited),
    Unavailable(R.string.instructions_unavailable),
    Unsupported(R.string.instructions_unsupported),
    InvalidResponse(R.string.instructions_invalid_response),
    BridgeRemoved(R.string.instructions_bridge_removed),
    HeadersTooLarge(R.string.instructions_headers_too_large),
    InvalidHeader(R.string.instructions_invalid_header),
    DuplicateHeader(R.string.instructions_duplicate_header),
    UnsupportedFraming(R.string.instructions_unsupported_framing),
    InvalidLength(R.string.instructions_invalid_length),
    BodyTooLarge(R.string.instructions_body_too_large),
    DeviceRevoked(R.string.bridge_revoked),
    DeviceKeyRepair(R.string.bridge_key_repair),
    Timeout(R.string.bridge_timeout),
    LinkDropped(R.string.bridge_link_dropped),
    Secure(R.string.bridge_secure_failed),
    AddressUnknown(R.string.bridge_address_unknown),
    Refused(R.string.bridge_refused),
    Unreachable(R.string.bridge_unreachable),
}

class InstructionsException(val error: InstructionsError) : Exception()

internal fun instructionsError(status: Int, body: String): InstructionsError {
    val token = try { profileJson.decodeFromString<InstructionsFailure>(body).error } catch (_: Exception) { null }
    return when (token) {
        "instructions_conflict" -> InstructionsError.Conflict
        "instructions_unsafe_path" -> InstructionsError.UnsafePath
        "instructions_too_large" -> InstructionsError.TooLarge
        "instructions_not_utf8" -> InstructionsError.NotUtf8
        "instructions_invalid", "Invalid request" -> InstructionsError.InvalidRequest
        "instructions_profile_missing" -> InstructionsError.ProfileMissing
        "instructions_rate_limited" -> InstructionsError.RateLimited
        "instructions_unavailable" -> InstructionsError.Unavailable
        "Route not found" -> InstructionsError.Unsupported
        "Registered device certificate required" -> InstructionsError.DeviceRevoked
        "Headers too large" -> InstructionsError.HeadersTooLarge
        "Invalid header" -> InstructionsError.InvalidHeader
        "Duplicate header" -> InstructionsError.DuplicateHeader
        "Unsupported framing" -> InstructionsError.UnsupportedFraming
        "Invalid content length" -> InstructionsError.InvalidLength
        "Request body too large" -> InstructionsError.BodyTooLarge
        "Upstream unavailable" -> InstructionsError.Unreachable
        else -> when (status) {
            409 -> InstructionsError.Conflict
            403 -> InstructionsError.DeviceRevoked
            413 -> InstructionsError.TooLarge
            429 -> InstructionsError.RateLimited
            404 -> InstructionsError.Unsupported
            503 -> InstructionsError.Unavailable
            else -> InstructionsError.InvalidResponse
        }
    }
}

internal fun instructionsTransportError(error: Exception): InstructionsError = when (bridgeError(error)) {
    HermesError.DeviceRevoked -> InstructionsError.DeviceRevoked
    HermesError.DeviceKeyRepair -> InstructionsError.DeviceKeyRepair
    HermesError.BridgeTimeout -> InstructionsError.Timeout
    HermesError.BridgeLinkDropped -> InstructionsError.LinkDropped
    HermesError.BridgeSecure -> InstructionsError.Secure
    HermesError.BridgeAddressUnknown -> InstructionsError.AddressUnknown
    HermesError.BridgeRefused -> InstructionsError.Refused
    else -> InstructionsError.Unreachable
}

interface InstructionsApi {
    suspend fun load(): InstructionsDocument
    suspend fun save(document: InstructionsDocument): String
}

class BridgeInstructionsApi(private val client: OkHttpClient, profileId: String) : InstructionsApi {
    init { require(Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(profileId)) }
    private val url = BridgeTransport.origin.newBuilder().addPathSegments("v1/profiles")
        .addPathSegment(profileId).addPathSegment("instructions").build()

    private suspend fun <T> send(document: InstructionsDocument? = null, decode: (String) -> T): T {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (document != null) put(profileJson.encodeToString(document).toRequestBody("application/json; charset=utf-8".toMediaType())) }
            .build()
        return try {
            client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code != 200) throw InstructionsException(instructionsError(response.code, text))
                try { decode(text) }
                catch (_: Exception) { throw InstructionsException(InstructionsError.InvalidResponse) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw InstructionsException(instructionsTransportError(e)) }
    }

    override suspend fun load(): InstructionsDocument = send { text ->
        profileJson.decodeFromString<InstructionsDocument>(text).also {
            require(it.text.toByteArray(Charsets.UTF_8).size <= MAX_INSTRUCTIONS_BYTES)
            require(it.revision == instructionsRevision(it.text))
        }
    }

    override suspend fun save(document: InstructionsDocument): String = send(document) { text ->
        profileJson.decodeFromString<InstructionsSaved>(text).revision.also {
            require(it == instructionsRevision(document.text))
        }
    }
}
