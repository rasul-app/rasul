package app.rasul.jobs

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One cron job (server `cron/jobs.py`). Times are ISO strings; unknown keys are ignored. */
@Serializable
data class JobDto(
    val id: String,
    val name: String? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean? = null,
    val state: String? = null,
    @SerialName("next_run_at") val nextRunAt: JsonElement? = null,
    @SerialName("last_run_at") val lastRunAt: JsonElement? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    val schedule: JsonElement? = null,
    val prompt: String? = null,
) {
    /** Editable schedule text from the server's cron expression or stored string. */
    val scheduleInput: String
        get() = when (val value = schedule) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonObject -> listOf("expr", "run_at", "value", "display").firstNotNullOfOrNull { key ->
                (value[key] as? JsonPrimitive)?.contentOrNull
            }.orEmpty()
            else -> scheduleDisplay.orEmpty()
        }.ifBlank { scheduleDisplay.orEmpty() }

    /**
     * Mirrors cron/jobs.py `effective_job_state` (:545-555): completed/error are terminal and kept whatever
     * `enabled` says (a finished job is stored enabled=false, state="completed", :1627-1629); otherwise `enabled`
     * is authoritative, and only without it does the stored state decide.
     */
    val jobState: JobState
        get() = when {
            state == "completed" -> JobState.Completed
            state == "error" -> JobState.Error
            enabled == false -> JobState.Paused
            enabled == true -> JobState.Scheduled
            state == "paused" -> JobState.Paused
            else -> JobState.Scheduled
        }

    val isPaused: Boolean get() = jobState == JobState.Paused
}

enum class JobState {
    Scheduled,
    Paused,

    /** Terminal (cron/jobs.py:560): cannot be resumed or run. */
    Completed,
    Error,
    ;

    val isTerminal: Boolean get() = this == Completed || this == Error
}

@Serializable
data class JobListDto(val jobs: List<JobDto>)

@Serializable
data class JobOneDto(val job: JobDto)

interface JobsApi {
    suspend fun create(target: HermesTarget, name: String, schedule: String, prompt: String): HermesResult<JobDto>
    suspend fun update(target: HermesTarget, id: String, name: String? = null, schedule: String? = null, prompt: String? = null): HermesResult<JobDto>
    suspend fun delete(target: HermesTarget, id: String): HermesResult<Unit>
    suspend fun list(target: HermesTarget): HermesResult<List<JobDto>>
    suspend fun pause(target: HermesTarget, id: String): HermesResult<JobDto>
    suspend fun resume(target: HermesTarget, id: String): HermesResult<JobDto>
    suspend fun runNow(target: HermesTarget, id: String): HermesResult<JobDto>
}

class JobsClient(private val client: HermesClient) : JobsApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(
        target: HermesTarget,
        name: String,
        schedule: String,
        prompt: String,
    ): HermesResult<JobDto> = decode("api/jobs", client.send(
        target,
        "api/jobs",
        body = jobBody(name, schedule, prompt),
        httpMethod = "POST",
    )) { json.decodeFromString<JobOneDto>(it).job }

    override suspend fun update(
        target: HermesTarget,
        id: String,
        name: String?,
        schedule: String?,
        prompt: String?,
    ): HermesResult<JobDto> {
        if (!SAFE_ID.matches(id)) return HermesResult.Err(HermesError.NotFound)
        return decode("api/jobs/{job_id}", client.send(
            target,
            "api/jobs/$id",
            body = jobBody(name, schedule, prompt),
            httpMethod = "PATCH",
        )) { json.decodeFromString<JobOneDto>(it).job }
    }

    override suspend fun delete(target: HermesTarget, id: String): HermesResult<Unit> {
        if (!SAFE_ID.matches(id)) return HermesResult.Err(HermesError.NotFound)
        return when (val r = client.send(target, "api/jobs/$id", httpMethod = "DELETE")) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> try {
                if (json.decodeFromString<JobDeletedDto>(r.value).ok) HermesResult.Ok(Unit)
                else HermesResult.Err(HermesError.ResourceNotFound)
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Incompatible(listOf("api/jobs/{job_id}")))
            }
        }
    }

    /** GET /api/jobs?include_disabled=true (paused jobs are disabled, we want them listed). */
    override suspend fun list(target: HermesTarget): HermesResult<List<JobDto>> =
        decode("api/jobs", client.send(target, "api/jobs", query = mapOf("include_disabled" to "true"))) {
            json.decodeFromString<JobListDto>(it).jobs
        }

    override suspend fun pause(target: HermesTarget, id: String): HermesResult<JobDto> = act(target, id, "pause")

    override suspend fun resume(target: HermesTarget, id: String): HermesResult<JobDto> = act(target, id, "resume")

    /** POST /api/jobs/{id}/run: schedules an immediate run; returns the updated job. */
    override suspend fun runNow(target: HermesTarget, id: String): HermesResult<JobDto> = act(target, id, "run")

    private suspend fun act(target: HermesTarget, id: String, verb: String): HermesResult<JobDto> {
        if (!SAFE_ID.matches(id)) return HermesResult.Err(HermesError.NotFound)
        return decode("api/jobs/{id}/$verb", client.send(target, "api/jobs/$id/$verb", post = true)) {
            json.decodeFromString<JobOneDto>(it).job
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

    private fun jobBody(name: String?, schedule: String?, prompt: String?): String = buildJsonObject {
        name?.let { put("name", it) }
        schedule?.let { put("schedule", it) }
        prompt?.let { put("prompt", it) }
    }.toString()

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_.:-]{1,200}")
    }
}

@Serializable
private data class JobDeletedDto(val ok: Boolean)
