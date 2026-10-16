package app.rasul.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Events on GET /v1/runs/{id}/events. The wire frame has no `event:` line: the name is the `event` key
 * of the JSON in `data:` (server `_run_event`, api_server_runs.py:177-178) and the SSE `id:` is the seq
 * (`_write_event`, api_server_runs.py:1100-1103).
 */
sealed interface RunEvent {
    /** `message.delta` (api_server_runs.py:925). */
    data class MessageDelta(val delta: String) : RunEvent

    /** `message.interim` (api_server_runs.py:935). */
    data class MessageInterim(val text: String, val alreadyStreamed: Boolean) : RunEvent

    /** `tool.started` (api_server_runs.py:94). */
    data class ToolStarted(val tool: String, val preview: String?) : RunEvent

    /** `tool.completed` (api_server_runs.py:95-96, preview added at :333-335). */
    data class ToolCompleted(val tool: String, val durationSeconds: Double?, val error: Boolean, val preview: String?) : RunEvent

    /** `reasoning.available` (api_server_runs.py:97). */
    data class Reasoning(val text: String) : RunEvent

    /** `subagent.start` / `subagent.complete` (api_server_runs.py:337-346). */
    data class Subagent(val started: Boolean, val goal: String?, val summary: String?, val status: String?) : RunEvent

    /** `approval.request` (api_server.py:113-126; command/description from tools/approval.py:838-841). */
    data class ApprovalRequest(
        val requestId: String?,
        val command: String,
        val description: String?,
        val choices: List<String>,
    ) : RunEvent

    /** `approval.responded` (api_server_runs.py:1213); `request_id` is present when the answer carried one (:1208-1214). */
    data class ApprovalResponded(val choice: String, val requestId: String? = null) : RunEvent

    /** `run.steered` (api_server_runs.py:1249). */
    data object Steered : RunEvent

    /** `run.stopping` is only a status on the server (api_server_runs.py:1266); kept for forward compatibility. */
    data object Stopping : RunEvent

    /** `replay.truncated` (api_server_runs.py:1118): events before [oldestRetainedSeq] are gone. */
    data class ReplayTruncated(val oldestRetainedSeq: Long?) : RunEvent

    /** `run.completed` (api_server_runs.py:978 via _finish): final answer is [output]. */
    data class Completed(val output: String, override val pendingSteer: String? = null) : RunEvent

    /** `run.failed` (api_server_runs.py:972-985). */
    data class Failed(val error: String?, override val pendingSteer: String? = null) : RunEvent

    /** `run.cancelled` (api_server_runs.py:967, 976). */
    data class Cancelled(override val pendingSteer: String? = null) : RunEvent

    /** `run.interrupted` (api_server_runs.py:294, 413; gateway shutdown). */
    data class Interrupted(val error: String?, override val pendingSteer: String? = null) : RunEvent

    /** Anything the app does not know. Never an error. */
    data class Unknown(val name: String, val raw: String) : RunEvent

    /** Steer text the server never delivered; rides on terminal events and statuses (api_server_runs.py:197-199). */
    val pendingSteer: String? get() = null

    val isTerminal: Boolean
        get() = this is Completed || this is Failed || this is Cancelled || this is Interrupted
}

/** A parsed event with its SSE `id:` (the server's seq) when present. */
data class RunFrame(val seq: Long?, val event: RunEvent)

/** A response from POST /v1/runs (202 `{"run_id","status","replayed"}`, api_server_runs.py:466-467). */
data class StartedRun(val runId: String)

/**
 * GET /v1/runs/{id} reply (api_server_runs.py:1057-1062; fields set in `_set_run_status`, :258-266: `status`, plus
 * `output` / `error` on terminal runs). Terminal statuses: completed, failed, cancelled, interrupted
 * (TERMINAL_STATUSES, api_server_run_idempotency.py:19); anything else (queued, running, waiting_for_approval,
 * stopping...) is still going.
 */
data class RunStatus(
    val status: String,
    val output: String?,
    val error: String?,
    val pendingSteer: String? = null,
    /** The open approval a waiting run carries (api_server_runs.py:857); the status may omit it, so merge add-only. */
    val approval: RunEvent.ApprovalRequest? = null,
) {
    /** The event a client would have seen had it been connected; null while the run is still going. */
    fun terminalEvent(): RunEvent? = when (status) {
        "completed" -> RunEvent.Completed(output.orEmpty(), pendingSteer)
        "failed" -> RunEvent.Failed(error, pendingSteer)
        "cancelled" -> RunEvent.Cancelled(pendingSteer)
        "interrupted" -> RunEvent.Interrupted(error, pendingSteer)
        else -> null
    }
}

/** Approval choices the server accepts (api_server_runs.py:1190-1193). */
enum class ApprovalChoice(val wire: String) { Once("once"), Deny("deny") }

private val eventJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** `pending_steer`: a string, or a list of strings joined by newlines; blank = none. */
internal fun JsonObject.steer(): String? = when (val v = this["pending_steer"]) {
    is JsonPrimitive -> v.takeIf { it.isString }?.contentOrNull
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
    else -> null
}?.takeIf { it.isNotBlank() }

fun parseRunFrame(frame: SseFrame): RunFrame {
    val seq = frame.id?.trim()?.toLongOrNull()
    val obj: JsonObject? = try {
        eventJson.parseToJsonElement(frame.data).jsonObject
    } catch (e: Exception) {
        null
    }
    val name = obj?.str("event") ?: frame.event ?: "unknown"
    if (obj == null) return RunFrame(seq, RunEvent.Unknown(name, frame.data))
    val event: RunEvent = when (name) {
        "message.delta" -> RunEvent.MessageDelta(obj.str("delta").orEmpty())
        "message.interim" -> RunEvent.MessageInterim(
            obj.str("text").orEmpty(), (obj["already_streamed"] as? JsonPrimitive)?.booleanOrNull == true)
        "tool.started" -> RunEvent.ToolStarted(obj.str("tool").orEmpty(), obj.str("preview"))
        "tool.completed" -> RunEvent.ToolCompleted(
            obj.str("tool").orEmpty(), (obj["duration"] as? JsonPrimitive)?.doubleOrNull,
            (obj["error"] as? JsonPrimitive)?.booleanOrNull == true, obj.str("preview"))
        "reasoning.available" -> RunEvent.Reasoning(obj.str("text").orEmpty())
        "subagent.start", "subagent.complete" ->
            RunEvent.Subagent(name == "subagent.start", obj.str("goal"), obj.str("summary"), obj.str("status"))
        "approval.request" -> RunEvent.ApprovalRequest(
            requestId = obj.str("request_id"),
            command = obj.str("command").orEmpty(),
            description = obj.str("description"),
            choices = (obj["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
        "approval.responded" -> RunEvent.ApprovalResponded(obj.str("choice").orEmpty(), obj.str("request_id"))
        "run.steered" -> RunEvent.Steered
        "run.stopping" -> RunEvent.Stopping
        "replay.truncated" -> RunEvent.ReplayTruncated((obj["oldest_retained_seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
        "run.completed" -> RunEvent.Completed(obj.str("output").orEmpty(), obj.steer())
        "run.failed" -> RunEvent.Failed(obj.str("error"), obj.steer())
        "run.cancelled" -> RunEvent.Cancelled(obj.steer())
        "run.interrupted" -> RunEvent.Interrupted(obj.str("error"), obj.steer())
        else -> RunEvent.Unknown(name, frame.data)
    }
    return RunFrame(seq, event)
}

