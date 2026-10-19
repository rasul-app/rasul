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

@Serializable
data class McpToggleResult(val name: String, val enabled: Boolean)

@Serializable
data class McpToolDto(val name: String, val description: String? = null)

/** [error] is present only when [ok] is false; counts of prompts/resources are optional. */
@Serializable
data class McpTestDto(
    val name: String,
    val ok: Boolean,
    val tools: List<McpToolDto> = emptyList(),
    val prompts: Int? = null,
    val resources: Int? = null,
    val error: String? = null,
)

/** [installable] is false when the entry needs setup on the PC (credentials, build step) or is already installed. */
@Serializable
data class McpCatalogEntryDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val installable: Boolean = false,
)

@Serializable
private data class McpCatalogDto(val entries: List<McpCatalogEntryDto> = emptyList())

@Serializable
private data class McpInstallReceipt(val id: String, val state: String)

@Serializable
data class HermesChangeDto(val title: String)

@Serializable
data class HermesUpdateResultDto(
    val ok: Boolean = false,
    val from: String? = null,
    val to: String? = null,
    val message: String? = null,
)

/** The status object of GET/POST/DELETE hermes/update (docs/bridge-desktop.md "Hermes update"). */
@Serializable
data class HermesUpdateDto(
    val current: String? = null,
    val latest: String? = null,
    /** Null when Hermes knows an update exists but cannot count the commits. */
    val behind: Int? = null,
    @SerialName("can_apply") val canApply: Boolean = false,
    val reason: String? = null,
    val changes: List<HermesChangeDto> = emptyList(),
    val state: String = "idle",
    @SerialName("active_agents") val activeAgents: Int? = null,
    @SerialName("queued_at") val queuedAt: String? = null,
    val result: HermesUpdateResultDto? = null,
) {
    /** True when the PC is behind, or behind by an unknown amount and the bridge can apply the update. */
    val updateAvailable: Boolean get() = (behind ?: 0) > 0 || (behind == null && canApply)
    val isQueued: Boolean get() = state == STATE_QUEUED
    val isRunning: Boolean get() = state == STATE_RUNNING

    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        const val MODE_WHEN_IDLE = "when_idle"
        const val MODE_NOW = "now"
    }
}

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

/** The answer of GET logs (docs/bridge-desktop.md "Logs"): [lines] are oldest first and already redacted on the PC. */
@Serializable
data class LogsDto(val file: String = "", val lines: List<String> = emptyList())

/** The log files the bridge serves, as the `file` query value. */
enum class LogFile(val query: String) { Errors("errors"), Agent("agent"), Gateway("gateway") }

/** The `level` query values; [All] is the default and shows every line. */
enum class LogLevel(val query: String) { All("ALL"), Error("ERROR"), Warning("WARNING"), Info("INFO") }

const val LOG_LINES_MAX = 200

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

    // Hermes update tokens (docs/bridge-desktop.md "Hermes update"); each has its own message.
    BridgeElevated(R.string.update_err_bridge_elevated),
    DashboardElevated(R.string.update_err_dashboard_elevated),
    CannotVerifyDashboard(R.string.update_err_cannot_verify),
    DashboardNotRunning(R.string.update_err_dashboard_not_running),
    UpToDate(R.string.update_err_up_to_date),
    UpdateRateLimited(R.string.hupd_err_rate_limited),
    UpdateInProgress(R.string.update_err_in_progress),
    UpdateRunning(R.string.update_err_running),
    NothingQueued(R.string.update_err_nothing_queued),
    UpdateNotStarted(R.string.update_err_not_started),
    CheckFailed(R.string.update_err_check_failed),
    NotApplicable(R.string.update_err_not_applicable),

    /** The selected log file does not exist yet on the PC (token `logs_not_found`). */
    LogsNotFound(R.string.logs_err_not_found),

    /** The MCP routes have tighter per-minute limits (test 3, install 1), so their 429 reads differently. */
    McpRateLimited(R.string.mcp_err_rate_limited),
}

/** Server tokens of the update routes, also used for the `reason` field of the status object. */
internal val UPDATE_TOKENS: Map<String, DesktopError> = mapOf(
    "bridge_elevated" to DesktopError.BridgeElevated,
    "dashboard_elevated" to DesktopError.DashboardElevated,
    "cannot_verify_dashboard_rights" to DesktopError.CannotVerifyDashboard,
    "dashboard_not_running" to DesktopError.DashboardNotRunning,
    "up_to_date" to DesktopError.UpToDate,
    "update_rate_limited" to DesktopError.UpdateRateLimited,
    "update_in_progress" to DesktopError.UpdateInProgress,
    "update_running" to DesktopError.UpdateRunning,
    "nothing_queued" to DesktopError.NothingQueued,
    "update_not_started" to DesktopError.UpdateNotStarted,
    "check_failed" to DesktopError.CheckFailed,
    "not_applicable" to DesktopError.NotApplicable,
)

class DesktopException(val error: DesktopError) : Exception()

/** A 503 only means "open Hermes on your PC" when the body carries the desktop token; other 503s stay bot-readiness. */
internal fun desktopError(status: Int, body: String): DesktopError {
    val token = try {
        ((profileJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) { null }
    token?.let { UPDATE_TOKENS[it] }?.let { return it }
    return when {
        token == "desktop_unavailable" -> DesktopError.Unavailable
        token == "desktop_error" -> DesktopError.DesktopFailed
        token == "desktop_invalid" -> DesktopError.InvalidRequest
        token == "desktop_rate_limited" -> DesktopError.RateLimited
        token == "logs_not_found" -> DesktopError.LogsNotFound
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

    /** The newest [lines] (1-200) of one Hermes log, oldest first; filtered by [level] unless it is [LogLevel.All]. */
    suspend fun logs(file: LogFile, lines: Int = LOG_LINES_MAX, level: LogLevel = LogLevel.All): LogsDto

    suspend fun hermesUpdate(): HermesUpdateDto

    /** [mode] is [HermesUpdateDto.MODE_WHEN_IDLE] or [HermesUpdateDto.MODE_NOW]; the 202 answer is the new status. */
    suspend fun startHermesUpdate(mode: String): HermesUpdateDto
    suspend fun cancelHermesUpdate(): HermesUpdateDto
    suspend fun setMcpEnabled(name: String, enabled: Boolean): McpToggleResult
    suspend fun testMcp(name: String): McpTestDto
    suspend fun mcpCatalog(): List<McpCatalogEntryDto>

    /** The bridge re-reads the catalog itself and refuses ids that are not installable. */
    suspend fun installMcp(id: String)
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
        mcp: Boolean = false,
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
                if (response.code !in 200..299) {
                    val error = desktopError(response.code, text)
                    throw DesktopException(if (mcp && error == DesktopError.RateLimited) DesktopError.McpRateLimited else error)
                }
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

    override suspend fun logs(file: LogFile, lines: Int, level: LogLevel): LogsDto =
        send(
            "logs",
            query = mapOf("file" to file.query, "lines" to lines.coerceIn(1, LOG_LINES_MAX).toString(), "level" to level.query),
        ) { json.decodeFromString<LogsDto>(it) }

    override suspend fun hermesUpdate(): HermesUpdateDto =
        send("hermes/update") { json.decodeFromString<HermesUpdateDto>(it) }

    override suspend fun startHermesUpdate(mode: String): HermesUpdateDto {
        if (mode != HermesUpdateDto.MODE_WHEN_IDLE && mode != HermesUpdateDto.MODE_NOW) {
            throw DesktopException(DesktopError.InvalidRequest)
        }
        return send(
            "hermes/update", method = "POST",
            body = buildJsonObject { put("mode", mode) }.toString(),
        ) { json.decodeFromString<HermesUpdateDto>(it) }
    }

    override suspend fun cancelHermesUpdate(): HermesUpdateDto =
        send("hermes/update", method = "DELETE") { json.decodeFromString<HermesUpdateDto>(it) }

    override suspend fun setMcpEnabled(name: String, enabled: Boolean): McpToggleResult {
        if (!MCP_NAME.matches(name)) throw DesktopException(DesktopError.InvalidRequest)
        return send(
            "mcp/$name/enabled", method = "PUT",
            body = buildJsonObject { put("enabled", enabled) }.toString(), mcp = true,
        ) {
            json.decodeFromString<McpToggleResult>(it).also { r ->
                if (r.name != name) throw DesktopException(DesktopError.InvalidResponse)
            }
        }
    }

    override suspend fun testMcp(name: String): McpTestDto {
        if (!MCP_NAME.matches(name)) throw DesktopException(DesktopError.InvalidRequest)
        return send("mcp/$name/test", method = "POST", body = "", mcp = true) {
            json.decodeFromString<McpTestDto>(it).also { r ->
                if (r.name != name) throw DesktopException(DesktopError.InvalidResponse)
            }
        }
    }

    override suspend fun mcpCatalog(): List<McpCatalogEntryDto> =
        send("mcp/catalog", mcp = true) { json.decodeFromString<McpCatalogDto>(it).entries }

    override suspend fun installMcp(id: String) {
        if (id.isBlank() || id.length > 200) throw DesktopException(DesktopError.InvalidRequest)
        send(
            "mcp/catalog/install", method = "POST",
            body = buildJsonObject { put("id", id) }.toString(), mcp = true,
        ) {
            val receipt = json.decodeFromString<McpInstallReceipt>(it)
            if (receipt.id != id || receipt.state != "installed") throw DesktopException(DesktopError.InvalidResponse)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Contract server names: must match this before they reach a path. */
        val MCP_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

        /** Contract identifier rules: job ids are ASCII alphanumerics, catalog ids are slash-joined. */
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,200}")
        val IDENTIFIER = Regex("official(/[A-Za-z0-9_-]+)+")
    }
}
