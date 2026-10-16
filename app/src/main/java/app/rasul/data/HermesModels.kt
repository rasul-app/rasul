package app.rasul.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Shape of GET /v1/capabilities (reference doc, "GET /v1/capabilities"). Unknown keys are ignored. */
@Serializable
data class CapabilitiesDto(
    @SerialName("object") val objectType: String? = null,
    val platform: String? = null,
    val model: String? = null,
    val auth: AuthDto? = null,
    val features: Map<String, JsonElement> = emptyMap(),
) {
    /** Names of features whose value is exactly `true`. */
    fun enabledFeatures(): Set<String> =
        features.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }.keys

    companion object {
        const val OBJECT_TYPE = "hermes.api_server.capabilities"

        /** What the later slices (runs, live events, stop) need from the server. */
        val REQUIRED_FEATURES = listOf("run_submission", "run_events_sse", "run_stop")
    }
}

@Serializable
data class AuthDto(val type: String? = null, val required: Boolean? = null)

/** Specific failure causes. No generic case on purpose. */
sealed interface HermesError {
    data object BridgeUnreachable : HermesError
    data object DeviceRevoked : HermesError
    data object DeviceKeyRepair : HermesError
    data object BridgeTimeout : HermesError
    data object ProfileNotReady : HermesError
    data object BridgeResponseInvalid : HermesError
    data object Unauthorized : HermesError
    data object NotFound : HermesError

    /** The profile route is fine but the job/session asked for does not exist (server `{"error": "... not found"}`). */
    data object ResourceNotFound : HermesError

    /** The active connection changed (switched, edited or deleted) since the data on screen was loaded. */
    data object ConnectionChanged : HermesError

    /** Phone storage or the saved connection list could not be read. */
    data class Storage(val detail: String) : HermesError
    data class ProfileUnknown(val profile: String) : HermesError
    data class Unreachable(val detail: String) : HermesError
    data class Incompatible(val missing: List<String>) : HermesError
    data class Server(val code: Int) : HermesError
    data class InvalidAddress(val detail: String) : HermesError

    /** Plain http to a host outside the allowed local/Tailscale ranges. */
    data object AddressRejected : HermesError

    /** The API key has characters that cannot go in an HTTP header. The key is never echoed. */
    data object InvalidKey : HermesError

    /** The server answered with a redirect; redirects are never followed. */
    data class Redirected(val code: Int) : HermesError
}

sealed interface HermesResult<out T> {
    data class Ok<T>(val value: T) : HermesResult<T>
    data class Err(val error: HermesError) : HermesResult<Nothing>
}

/** Where and how to reach one Hermes API server. */
data class HermesTarget(val baseUrl: String, val profile: String?, val apiKey: String, val bridge: app.rasul.bridge.BridgeEndpoint? = null)
