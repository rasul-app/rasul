package app.rasul.chat

import androidx.annotation.StringRes
import app.rasul.R
import app.rasul.data.RunEvent

/** What the running task is doing right now, as far as the events received so far say. */
sealed interface RunPhase {
    /** The send was accepted; no event has arrived yet. */
    data object Received : RunPhase
    data object Thinking : RunPhase
    data class Tool(val name: String, val preview: String?) : RunPhase
    data object WaitingApproval : RunPhase
    data object Writing : RunPhase
    data class Reconnecting(val attempt: Int, val max: Int) : RunPhase
}

/** One tool call seen on the event stream. [finishedAt] is null while it runs. */
data class RunStep(
    val tool: String,
    val preview: String?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMs: Long? = null,
    val failed: Boolean = false,
) {
    val running: Boolean get() = finishedAt == null
}

sealed interface RunNote {
    /** A tool has been running for [minutes] without finishing. */
    data class LongTool(val minutes: Int) : RunNote

    /** No event for [minutes]. */
    data class Stalled(val minutes: Int) : RunNote
}

/**
 * Everything the progress card shows, built only from the run events the app receives.
 * [steps] keeps the last [MAX_STEPS] tool calls; [stepCount] counts every finished one.
 * [restored] marks a run found again after leaving the chat: its start time and earlier steps are unknown
 * until the event replay supplies them.
 */
data class RunProgress(
    val receivedAt: Long,
    val phase: RunPhase = RunPhase.Received,
    val steps: List<RunStep> = emptyList(),
    val stepCount: Int = 0,
    val lastEventAt: Long = receivedAt,
    val steeredAt: Long? = null,
    val restored: Boolean = false,
    /** The phase to return to once the stream is back. */
    val resume: RunPhase? = null,
) {
    /** Any event arrived. */
    fun alive(now: Long): RunProgress {
        if (phase !is RunPhase.Reconnecting) return copy(lastEventAt = now)
        return copy(lastEventAt = now, phase = resume ?: RunPhase.Thinking, resume = null)
    }

    fun toolStarted(tool: String, preview: String?, now: Long): RunProgress {
        val step = RunStep(tool, cleanPreview(preview), now)
        return copy(steps = (steps + step).takeLast(MAX_STEPS), phase = RunPhase.Tool(tool, step.preview))
    }

    fun toolCompleted(tool: String, durationSeconds: Double?, error: Boolean, preview: String?, now: Long): RunProgress {
        // Parallel calls of one tool: the same preview names the call; else the oldest open one, which started first.
        val cleaned = cleanPreview(preview)
        val at = steps.indexOfFirst { it.running && it.tool == tool && cleaned != null && it.preview == cleaned }
            .takeIf { it >= 0 } ?: steps.indexOfFirst { it.running && it.tool == tool }
        val spoken = durationSeconds?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
        val done = if (at >= 0) {
            val open = steps[at]
            open.copy(
                preview = open.preview ?: cleanPreview(preview),
                finishedAt = now, durationMs = spoken ?: (now - open.startedAt).coerceAtLeast(0), failed = error,
            )
        } else {
            // Completion of a call whose start was not seen (a replay that began later): it still happened.
            RunStep(tool, cleanPreview(preview), now - (spoken ?: 0), now, spoken, error)
        }
        val list = if (at >= 0) steps.toMutableList().also { it[at] = done } else steps + done
        return copy(steps = list.takeLast(MAX_STEPS), stepCount = stepCount + 1).settled()
    }

    fun thinking(): RunProgress = if (steps.any { it.running }) this else copy(phase = RunPhase.Thinking)

    fun writing(): RunProgress = copy(phase = RunPhase.Writing)

    fun approval(): RunProgress = copy(phase = RunPhase.WaitingApproval)

    /** The last approval was answered or is gone. */
    fun approvalCleared(): RunProgress = if (phase == RunPhase.WaitingApproval) settled() else this

    fun reconnecting(attempt: Int, max: Int): RunProgress =
        copy(phase = RunPhase.Reconnecting(attempt, max), resume = resume ?: phase.takeUnless { it is RunPhase.Reconnecting })

    fun steered(now: Long): RunProgress = copy(steeredAt = now)

    /** Back to what the open steps say: the newest running tool, else thinking. */
    private fun settled(): RunProgress {
        val open = steps.lastOrNull { it.running }
        return copy(phase = if (open != null) RunPhase.Tool(open.tool, open.preview) else RunPhase.Thinking)
    }

    /** The label of the newest finished step; null before any step finished. */
    val lastDone: RunStep? get() = steps.lastOrNull { !it.running }

    val running: RunStep? get() = steps.lastOrNull { it.running }

    fun steerNoticeAt(now: Long): Boolean = steeredAt?.let { now - it in 0 until STEER_NOTICE_MS } == true

    fun notes(now: Long, watchStall: Boolean): List<RunNote> = buildList {
        running?.let {
            val age = now - it.startedAt
            if (age >= LONG_TOOL_MS) add(RunNote.LongTool((age / 60_000).toInt()))
        }
        val quiet = now - lastEventAt
        val waiting = phase == RunPhase.WaitingApproval || phase is RunPhase.Reconnecting
        // A running tool has its own note; "no activity" would say the same thing twice.
        if (watchStall && !waiting && running == null && quiet >= STALL_MS) add(RunNote.Stalled((quiet / 60_000).toInt()))
    }

    companion object {
        const val MAX_STEPS = 20
        const val PREVIEW_MAX = 80
        const val LONG_TOOL_MS = 2 * 60_000L
        const val STALL_MS = 3 * 60_000L
        const val STEER_NOTICE_MS = 4_000L

        /** A run found again after leaving the chat: only "still going" is known. */
        fun restored(now: Long) = RunProgress(receivedAt = now, phase = RunPhase.Thinking, restored = true)
    }
}

/** A tool preview as one plain line of at most [RunProgress.PREVIEW_MAX] characters; blank is no preview. */
fun cleanPreview(raw: String?): String? {
    val line = raw?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim().orEmpty()
    if (line.isEmpty()) return null
    return if (line.length <= RunProgress.PREVIEW_MAX) line else line.take(RunProgress.PREVIEW_MAX - 1).trimEnd() + "…"
}

enum class RunOutcome { Done, Stopped, Failed }

/** The one-line result kept under the reply after a run ended; lives in memory only. */
data class RunSummary(
    val runId: String,
    val outcome: RunOutcome,
    /** Null for a restored run, whose start is unknown. */
    val elapsedMs: Long?,
    val stepCount: Int,
    val steps: List<RunStep>,
)

internal fun outcomeOf(terminal: RunEvent?): RunOutcome = when (terminal) {
    is RunEvent.Completed -> RunOutcome.Done
    is RunEvent.Cancelled -> RunOutcome.Stopped
    else -> RunOutcome.Failed
}

@StringRes
internal fun toolLabelRes(id: String): Int? = when {
    id.isBlank() -> R.string.tool_unnamed
    id == "terminal" -> R.string.tool_terminal
    id == "process" -> R.string.tool_process
    id == "read_file" -> R.string.tool_read_file
    id == "write_file" -> R.string.tool_write_file
    id == "patch" -> R.string.tool_patch
    id == "search_files" -> R.string.tool_search_files
    id == "web_search" -> R.string.tool_web_search
    id == "web_extract" -> R.string.tool_web_extract
    id == "delegate_task" -> R.string.tool_delegate_task
    id == "execute_code" -> R.string.tool_execute_code
    id == "todo" -> R.string.tool_todo
    id == "memory" -> R.string.tool_memory
    id == "session_search" -> R.string.tool_session_search
    id == "send_message" -> R.string.tool_send_message
    id == "clarify" -> R.string.tool_clarify
    id == "vision_analyze" -> R.string.tool_vision_analyze
    id == "skill_view" -> R.string.tool_skill_view
    id == "skills_list" -> R.string.tool_skills_list
    id == "cronjob" -> R.string.tool_cronjob
    id.startsWith("browser") -> R.string.tool_browser
    id.startsWith("mcp_") -> R.string.tool_mcp
    else -> null
}
