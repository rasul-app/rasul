package app.rasul.bridge

import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.data.HermesTarget
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Shapes follow docs/bridge-desktop.md: every route sits under /v1/profiles/{id}/desktop/ and the
// bridge answers errors as {"error": "desktop_..."}.

/** One past or running run of a job. [startedAt]/[endedAt] are epoch seconds and may be null. */
@Serializable
data class JobRunDto(
    val id: String,
    val title: String? = null,
    @SerialName("started_at") val startedAt: JsonElement? = null,
    @SerialName("ended_at") val endedAt: JsonElement? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    val preview: String? = null,
) {
    fun startedEpoch(): Long? = startedAt.epochSeconds()
    fun endedEpoch(): Long? = endedAt.epochSeconds()
}

private fun JsonElement?.epochSeconds(): Long? =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

@Serializable
private data class JobRunsDto(val runs: List<JobRunDto> = emptyList())

/** A desktop skill with its switch state; [essential] is present only when the backend supplies it. */
@Serializable
data class DesktopSkillDto(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean = true,
    val essential: Boolean? = null,
) {
    val isEssential: Boolean get() = essential == true
}

@Serializable
private data class DesktopSkillsDto(val skills: List<DesktopSkillDto> = emptyList())

@Serializable
data class SkillToggleResult(val name: String, val enabled: Boolean)

@Serializable
data class CatalogSkillDto(
    val identifier: String,
    val name: String,
    val description: String? = null,
    val installed: Boolean = false,
)

@Serializable
private data class SkillCatalogDto(val skills: List<CatalogSkillDto> = emptyList())

@Serializable
private data class InstallReceipt(val identifier: String, val state: String)

@Serializable
data class McpServerDto(
    val name: String,
    val transport: String? = null,
    val enabled: Boolean? = null,
    val source: String? = null,
    val plugin: String? = null,
)

@Serializable
private data class McpListDto(val servers: List<McpServerDto> = emptyList())

/** Counters/cost are null when the backend aggregate is null; token numbers can arrive as fractions. */
@Serializable
data class UsageDayDto(
    val day: String? = null,
    @SerialName("input_tokens") val inputTokens: Double? = null,
    @SerialName("output_tokens") val outputTokens: Double? = null,
    @SerialName("cache_read_tokens") val cacheReadTokens: Double? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Double? = null,
    @SerialName("estimated_cost") val estimatedCost: Double? = null,
    val sessions: Double? = null,
    @SerialName("api_calls") val apiCalls: Double? = null,
)

@Serializable
data class UsageTotalsDto(
    @SerialName("input_tokens") val inputTokens: Double? = null,
    @SerialName("output_tokens") val outputTokens: Double? = null,
    @SerialName("cache_read_tokens") val cacheReadTokens: Double? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Double? = null,
    @SerialName("estimated_cost") val estimatedCost: Double? = null,
    val sessions: Double? = null,
    @SerialName("api_calls") val apiCalls: Double? = null,
)

@Serializable
data class UsageReportDto(
    val daily: List<UsageDayDto> = emptyList(),
    val totals: UsageTotalsDto? = null,
    @SerialName("period_days") val periodDays: Int? = null,
)

enum class DesktopError(val message: Int) {
    Unavailable(R.string.desktop_unavailable),
    DesktopFailed(R.string.desktop_error),
    RateLimited(R.string.desktop_rate_limited),
    InvalidRequest(R.string.desktop_invalid),
    InvalidResponse(R.string.desktop_invalid_response),
    NotFound(R.string.skills_update_bridge),
    ProfileNotReady(R.string.bridge_profile_not_ready),
    DeviceRevoked(R.string.bridge_revoked),
    DeviceKeyRepair(R.string.bridge_key_repair),
    Timeout(R.string.bridge_timeout),
    Unreachable(R.string.bridge_unreachable),
    /** Local state, never a bridge token: the catalog entry went away between read and install confirm. */
    CatalogChanged(R.string.skill_catalog_changed),
}

class DesktopException(val error: DesktopError) : Exception()

/** A 503 only means "open Hermes on your PC" when the body carries the desktop token; other 503s stay bot-readiness. */
internal fun desktopError(status: Int, body: String): DesktopError {
    val token = try {
        ((profileJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) { null }
    return when {
        token == "desktop_unavailable" -> DesktopError.Unavailable
        token == "desktop_error" -> DesktopError.DesktopFailed
        token == "desktop_invalid" -> DesktopError.InvalidRequest
        token == "desktop_rate_limited" -> DesktopError.RateLimited
        status == 400 -> DesktopError.InvalidRequest
        status == 401 || status == 403 -> DesktopError.DeviceRevoked
        status == 404 -> DesktopError.NotFound
        status == 429 -> DesktopError.RateLimited
        status == 502 -> DesktopError.DesktopFailed
        status == 503 -> DesktopError.ProfileNotReady
        else -> DesktopError.InvalidResponse
    }
}

internal fun desktopTransportError(error: Exception): DesktopError = when (bridgeError(error)) {
    HermesError.DeviceRevoked -> DesktopError.DeviceRevoked
    HermesError.DeviceKeyRepair -> DesktopError.DeviceKeyRepair
    HermesError.BridgeTimeout -> DesktopError.Timeout
    else -> DesktopError.Unreachable
}

/** Everything a screen can observe after a desktop call. */
internal fun desktopFailure(error: Throwable): DesktopError = when (error) {
    is DesktopException -> error.error
    is IOException -> desktopTransportError(error)
    else -> DesktopError.DesktopFailed
}

interface DesktopApi {
    suspend fun jobRuns(jobId: String, limit: Int = 20): List<JobRunDto>
    suspend fun skills(): List<DesktopSkillDto>
    suspend fun toggleSkill(name: String, enabled: Boolean): SkillToggleResult
    suspend fun skillCatalog(): List<CatalogSkillDto>

    /** The 202 receipt means spawning started, never that the install finished; re-read [skills] to observe it. */
    suspend fun installSkill(identifier: String)
    suspend fun mcpServers(): List<McpServerDto>
    suspend fun usage(days: Int): UsageReportDto
}

/** The api for one bridge target, or null when the bot is a direct connection (no desktop routes). */
fun HermesTarget.desktopApi(): DesktopApi? = bridge?.let(::BridgeDesktopApi)

class BridgeDesktopApi(private val endpoint: BridgeEndpoint) : DesktopApi {
    private fun url(path: String) = BridgeTransport.origin.newBuilder()
        .addPathSegments("v1/profiles").addPathSegment(endpoint.profileId)
        .addPathSegment("desktop").addPathSegments(path).build()

    private suspend fun <T> send(
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        decode: (String) -> T,
    ): T {
        val request = Request.Builder()
            .url(url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            endpoint.client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in 200..299) throw DesktopException(desktopError(response.code, text))
                try {
                    decode(text)
                } catch (e: DesktopException) {
                    throw e
                } catch (_: Exception) {
                    throw DesktopException(DesktopError.InvalidResponse)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: DesktopException) { throw e }
        catch (e: IOException) { throw DesktopException(desktopTransportError(e)) }
    }

    override suspend fun jobRuns(jobId: String, limit: Int): List<JobRunDto> {
        if (!JOB_ID.matches(jobId)) throw DesktopException(DesktopError.NotFound)
        return send("jobs/$jobId/runs", query = mapOf("limit" to limit.coerceIn(1, 100).toString())) {
            json.decodeFromString<JobRunsDto>(it).runs
        }
    }

    override suspend fun skills(): List<DesktopSkillDto> =
        send("skills") { json.decodeFromString<DesktopSkillsDto>(it).skills }

    override suspend fun toggleSkill(name: String, enabled: Boolean): SkillToggleResult =
        send(
            "skills/toggle", method = "PUT",
            body = buildJsonObject { put("name", name); put("enabled", enabled) }.toString(),
        ) { json.decodeFromString<SkillToggleResult>(it) }

    override suspend fun skillCatalog(): List<CatalogSkillDto> =
        send("skills/catalog") { json.decodeFromString<SkillCatalogDto>(it).skills }

    override suspend fun installSkill(identifier: String) {
        if (!IDENTIFIER.matches(identifier)) throw DesktopException(DesktopError.InvalidRequest)
        send(
            "skills/install", method = "POST",
            body = buildJsonObject { put("identifier", identifier) }.toString(),
        ) {
            val receipt = json.decodeFromString<InstallReceipt>(it)
            if (receipt.identifier != identifier || receipt.state != "started") {
                throw DesktopException(DesktopError.InvalidResponse)
            }
        }
    }

    override suspend fun mcpServers(): List<McpServerDto> =
        send("mcp") { json.decodeFromString<McpListDto>(it).servers }

    override suspend fun usage(days: Int): UsageReportDto =
        send("usage", query = mapOf("days" to days.coerceIn(1, 365).toString())) {
            json.decodeFromString<UsageReportDto>(it)
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Contract identifier rules: job ids are ASCII alphanumerics, catalog ids are slash-joined. */
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,200}")
        val IDENTIFIER = Regex("official(/[A-Za-z0-9_-]+)+")
    }
}
