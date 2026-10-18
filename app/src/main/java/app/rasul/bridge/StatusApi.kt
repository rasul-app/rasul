package app.rasul.bridge

import app.rasul.data.HermesTarget
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// GET /v1/status (bridge README "Self-check"): flags and counts only, never addresses, keys or paths.

@Serializable
data class StatusProfileDto(
    val id: String,
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    @SerialName("gateway_reachable") val gatewayReachable: Boolean = false,
)

@Serializable
data class StatusHermesDto(
    val version: String? = null,
    @SerialName("active_agents") val activeAgents: Int? = null,
)

@Serializable
data class StatusUrlsDto(
    val lan: Int = 0,
    val tailscale: Int = 0,
    @SerialName("public") val publicUrls: Int = 0,
)

@Serializable
data class BridgeStatusDto(
    @SerialName("bridge_version") val bridgeVersion: String? = null,
    @SerialName("hermes_home_found") val hermesHomeFound: Boolean = false,
    val profiles: List<StatusProfileDto> = emptyList(),
    val hermes: StatusHermesDto? = null,
    @SerialName("dashboard_found") val dashboardFound: Boolean = false,
    /** "timeout" means the dashboard probe timed out, so [dashboardFound] false is not conclusive. */
    @SerialName("dashboard_check") val dashboardCheck: String? = null,
    @SerialName("approvals_listener") val approvalsListener: Boolean = false,
    val urls: StatusUrlsDto? = null,
) {
    val dashboardTimedOut: Boolean get() = !dashboardFound && dashboardCheck == "timeout"
}

interface BridgeStatusApi {
    suspend fun status(): BridgeStatusDto
}

/** The status route of the bridge a target belongs to, or null for a direct connection. */
fun HermesTarget.statusApi(): BridgeStatusApi? = bridge?.let { HttpBridgeStatusApi(it.client) }

class HttpBridgeStatusApi(private val client: OkHttpClient) : BridgeStatusApi {
    override suspend fun status(): BridgeStatusDto {
        val request = Request.Builder()
            .url(BridgeTransport.origin.newBuilder().addPathSegments("v1/status").build())
            .header("Accept", "application/json")
            .build()
        return try {
            client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code != 200) throw DesktopException(desktopError(response.code, text))
                try {
                    json.decodeFromString<BridgeStatusDto>(text)
                } catch (_: Exception) {
                    throw DesktopException(DesktopError.InvalidResponse)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: DesktopException) { throw e }
        catch (e: IOException) { throw DesktopException(desktopTransportError(e)) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
