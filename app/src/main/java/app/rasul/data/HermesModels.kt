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
    val endpoints: Map<String, CapabilityEndpointDto> = emptyMap(),
) {
    /** Names of features whose value is exactly `true`. */
    fun enabledFeatures(): Set<String> =
        features.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }.keys

    fun advertisesEndpoint(name: String): Boolean = name in endpoints

    companion object {
        const val OBJECT_TYPE = "hermes.api_server.capabilities"

        /** What the later slices (runs, live events, stop) need from the server. */
        val REQUIRED_FEATURES = listOf("run_submission", "run_events_sse", "run_stop")
    }
}

@Serializable
data class AuthDto(val type: String? = null, val required: Boolean? = null)

@Serializable
data class CapabilityEndpointDto(val method: String? = null, val path: String? = null)

@Serializable
data class SkillDto(val name: String, val description: String? = null, val category: String? = null)

@Serializable
data class SkillsListDto(
    @SerialName("object") val objectType: String? = null,
    val data: List<SkillDto>,
)

@Serializable
data class ToolsetDto(
    val name: String,
    val label: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val configured: Boolean? = null,
    val tools: List<String> = emptyList(),
)

@Serializable
data class ToolsetsListDto(
    @SerialName("object") val objectType: String? = null,
    val platform: String? = null,
    val data: List<ToolsetDto>,
)

@Serializable
data class ModelOptionsDto(
    val providers: List<ModelOptionsProviderDto>,
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class ModelOptionsProviderDto(
    val slug: String,
    val name: String,
    val models: List<String> = emptyList(),
    val limit: ModelOptionsLimitDto? = null,
    val usage: ModelOptionsUsageDto? = null,
    /** Per-model flags from `capabilities[model]` in GET /api/model/options (inventory.py `_apply_capabilities`). */
    val capabilities: Map<String, ModelCapabilityDto> = emptyMap(),
)

/** Credential-pool bench: scope "account" carries one resets_at, scope "models" carries per-model times. */
@Serializable
data class ModelOptionsLimitDto(
    val scope: String? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    val models: Map<String, String> = emptyMap(),
)

@Serializable
data class ModelOptionsUsageWindowDto(
    val label: String? = null,
    @SerialName("used_percent") val usedPercent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    val scope: String? = null,
)

@Serializable
data class ModelOptionsUsageAccountDto(
    val id: String? = null,
    val label: String? = null,
    val windows: List<ModelOptionsUsageWindowDto> = emptyList(),
    val state: String? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

/** Subscription windows: `windows` for single-account providers, `accounts` for pooled credentials. */
@Serializable
data class ModelOptionsUsageDto(
    val windows: List<ModelOptionsUsageWindowDto> = emptyList(),
    val accounts: List<ModelOptionsUsageAccountDto> = emptyList(),
)

@Serializable
data class ModelCapabilityDto(
    val fast: Boolean? = null,
    val reasoning: Boolean? = null,
)

/** Specific failure causes. No generic case on purpose. */
sealed interface HermesError {
    data object BridgeUnreachable : HermesError

    /** The saved PC speaks a newer protocol than this app can use. */
    data object NewerHost : HermesError
    data object DeviceRevoked : HermesError
    data object DeviceKeyRepair : HermesError
    data object BridgeTimeout : HermesError

    /** An established link to the bridge dropped; callers that keep retrying show it as transient. */
    data object BridgeLinkDropped : HermesError

    /** The TLS session over the link failed (handshake alert, not a plain transport loss). */
    data object BridgeSecure : HermesError

    /** The bridge's address does not resolve. */
    data object BridgeAddressUnknown : HermesError

    /** Nothing listens at the bridge's address — the connection was refused. */
    data object BridgeRefused : HermesError
    data object ProfileNotReady : HermesError
    data object BridgeResponseInvalid : HermesError
    data object Unauthorized : HermesError
    data object NotFound : HermesError

    /** The profile route is fine but the job/session asked for does not exist (server `{"error": "... not found"}`). */
    data object ResourceNotFound : HermesError

    /** The active connection changed (switched, edited or deleted) since the data on screen was loaded. */
    data object ConnectionChanged : HermesError

    /** The chat this transcript would continue is already replying on another session; nothing was written. */
    data object ChatBusy : HermesError

    /** Phone storage or the saved connection list could not be read. */
    data class Storage(val detail: String) : HermesError
    data class ProfileUnknown(val profile: String) : HermesError
    data class Unreachable(val detail: String) : HermesError
    data class Incompatible(val missing: List<String>) : HermesError
    data class Server(val code: Int, val message: String? = null, val serverCode: String? = null) : HermesError
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
data class HermesTarget(val baseUrl: String, val profile: String?, val apiKey: String, val bridge: app.rasul.bridge.BridgeEndpoint? = null, val revision: String = "0")
