package app.rasul.chat

import app.rasul.data.BridgeProcess
import app.rasul.data.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class BgKind { Process, Helper }

enum class BgStatus { Running, Finished, Failed }

/** One background item: a terminal process the agent left running, or a helper agent it started. */
data class BgItem(
    val key: String,
    val kind: BgKind,
    /** One line, at most [BackgroundTasks.NAME_MAX] characters. */
    val name: String,
    val startedAt: Long?,
    val status: BgStatus,
    /** The agent's `proc_...` id for a process, when the event carried one. */
    val procId: String? = null,
)

/**
 * The background work of one chat, built only from the run events the app receives (the Hermes API has no list of
 * it): a `terminal` call whose result says "Background process started" is a process, a `subagent.start` is a helper.
 * Hermes cannot stop one of them, so there is nothing to stop here either.
 */
data class BackgroundTasks(
    val items: List<BgItem> = emptyList(),
    /** Started tool calls not completed yet, oldest first: a result is matched to the oldest start of its tool. */
    private val open: List<OpenCall> = emptyList(),
) {
    data class OpenCall(val tool: String, val text: String, val at: Long)

    val runningCount: Int get() = items.count { it.status == BgStatus.Running }

    fun toolStarted(tool: String, preview: String?, at: Long): BackgroundTasks =
        if (tool != TERMINAL && tool != PROCESS_MANAGE) this
        else copy(open = (open + OpenCall(tool, oneLine(preview), at)).takeLast(MAX_OPEN))

    fun toolCompleted(tool: String, error: Boolean, result: String?, at: Long): BackgroundTasks {
        if (tool != TERMINAL && tool != PROCESS_MANAGE) return this
        val index = open.indexOfFirst { it.tool == tool }
        val call = open.getOrNull(index)
        val rest = if (index >= 0) open.filterIndexed { i, _ -> i != index } else open
        val next = copy(open = rest)
        return when (tool) {
            TERMINAL -> {
                if (error) return next
                val id = result?.let { STARTED.find(it.trim())?.groupValues?.get(1) } ?: return next
                if (next.items.any { it.procId == id }) next
                else next.add(BgItem(id, BgKind.Process, call?.text?.ifEmpty { id } ?: id, call?.at ?: at, BgStatus.Running, id))
            }
            else -> next.processManaged(call?.text.orEmpty(), result)
        }
    }

    /**
     * A finished `process_manage` call. Hermes reports the outcome in the result's `status` (the `error` flag also
     * covers a harmless "not found"), so the flag is not used: poll, wait and kill end an item when the status says
     * the process is gone; `close`, `write`, `submit`, `log`, a wait that timed out and a kill that left it alive change
     * nothing. The result names its process (`session_id`) for poll and kill; a wait result does not, so the id in the
     * call text (cut to 16 characters by Hermes) is used, and only when it picks exactly one process.
     */
    private fun processManaged(callText: String, result: String?): BackgroundTasks {
        val words = callText.split(' ')
        val action = words.firstOrNull().orEmpty()
        if (action == LIST) return applyList(result)
        if (callText.isNotEmpty() && action !in SETTLING_ACTIONS) return this
        val r = ProcessResult.of(result) ?: return this
        val status = r.status?.let { ended(it, r.exitCode, r.reason) } ?: return this
        val matches = if (r.sessionId != null) items.filter { it.procId == r.sessionId } else {
            val prefix = words.getOrNull(1)?.takeIf { it.startsWith("proc_") && it.length >= MIN_PREFIX }
            if (prefix == null) emptyList() else items.filter { it.procId?.startsWith(prefix) == true }
        }
        val target = matches.singleOrNull()?.takeIf { it.status == BgStatus.Running } ?: return this
        return copy(items = items.map { if (it === target) it.copy(status = status) else it })
    }

    /** `list`: every listed process that has exited is over; a process the list leaves out says nothing. */
    private fun applyList(result: String?): BackgroundTasks {
        val listed = ProcessResult.listOf(result) ?: return this
        return copy(items = items.map { item ->
            val row = listed.firstOrNull { it.sessionId == item.procId }
            val status = row?.status?.takeIf { it == "exited" }?.let { ended(it, row.exitCode, row.reason) }
            if (status != null && item.status == BgStatus.Running) item.copy(status = status) else item
        })
    }

    fun subagent(e: RunEvent.Subagent, at: Long): BackgroundTasks {
        val startedAt = e.atMillis ?: at
        val goal = oneLine(e.goal)
        val key = e.subagentId ?: "helper:$goal"
        if (e.started) {
            val item = BgItem(key, BgKind.Helper, goal.ifEmpty { "…" }, startedAt, BgStatus.Running)
            return copy(items = items.filterNot { it.key == key }).add(item)
        }
        val ended = if (e.status?.lowercase()?.let { s -> BAD_WORDS.any { it in s } } == true) BgStatus.Failed else BgStatus.Finished
        val known = items.any { it.key == key && it.kind == BgKind.Helper }
        // A completion whose start was missed (a replay that began later) still happened.
        return if (known) finish(key, ended) else add(BgItem(key, BgKind.Helper, goal.ifEmpty { "…" }, startedAt, ended))
    }

    /** The run is over: its helpers ended with it; processes may go on and are told apart by the bridge. */
    fun runEnded(): BackgroundTasks =
        copy(items = items.map { if (it.kind == BgKind.Helper && it.status == BgStatus.Running) it.copy(status = BgStatus.Finished) else it }, open = emptyList())

    /**
     * What the Background tasks sheet lists. While the run is live: everything seen. After it: the processes only,
     * checked against the bridge's list [remote] (null = unknown): one the bridge no longer lists has finished, and
     * one it lists that this chat never saw is added as running.
     */
    fun sheetItems(runLive: Boolean, remote: List<BridgeProcess>?): List<BgItem> {
        if (runLive) return items
        val processes = items.filter { it.kind == BgKind.Process }
        if (remote == null) return processes
        val listed = remote.map { it.id }.toSet()
        val known = processes.mapNotNull { it.procId }.toSet()
        val checked = processes.map { if (it.status == BgStatus.Running && it.procId !in listed) it.copy(status = BgStatus.Finished) else it }
        val unseen = remote.filter { it.id !in known }
            .map { BgItem(it.id, BgKind.Process, oneLine(it.command).ifEmpty { it.id }, it.startedAtMillis, BgStatus.Running, it.id) }
        return checked + unseen
    }

    private fun add(item: BgItem): BackgroundTasks {
        val all = items + item
        // Keep the newest; finished ones go first when there are too many.
        val trimmed = if (all.size <= MAX_ITEMS) all else {
            val drop = all.size - MAX_ITEMS
            val gone = all.filter { it.status != BgStatus.Running }.take(drop).toSet()
            (all - gone).takeLast(MAX_ITEMS)
        }
        return copy(items = trimmed)
    }

    private fun finish(key: String, status: BgStatus): BackgroundTasks =
        copy(items = items.map { if (it.key == key || it.procId == key) it.copy(status = status) else it })

    companion object {
        const val TERMINAL = "terminal"
        const val PROCESS_MANAGE = "process_manage"
        const val NAME_MAX = 200
        private const val MAX_ITEMS = 30
        private const val MAX_OPEN = 20
        private const val LIST = "list"
        private const val MIN_PREFIX = 10
        /** The start of a background terminal result: it comes first, so a result cut at 500 characters still has it. */
        private val STARTED = Regex("""^\{"output":\s*"Background process started",\s*"session_id":\s*"(proc_[A-Za-z0-9]+)"""")
        private val SETTLING_ACTIONS = setOf("poll", "wait", "kill")
        private val BAD_WORDS = listOf("fail", "error", "interrupt", "cancel", "timeout", "timed")

        /** The end state a process result's [status] means, or null while it runs (or the call did not change it). */
        private fun ended(status: String, exitCode: Int?, reason: String?): BgStatus? = when (status) {
            "killed", "not_found" -> BgStatus.Finished
            "exited", "already_exited" ->
                if (exitCode != null && exitCode != 0 && reason != "killed") BgStatus.Failed else BgStatus.Finished
            else -> null
        }

        /** A plain single line of at most [NAME_MAX] characters; blank is empty. */
        fun oneLine(raw: String?): String {
            val line = raw?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim().orEmpty()
            return if (line.length <= NAME_MAX) line else line.take(NAME_MAX - 1).trimEnd() + "…"
        }
    }
}

/** The text of the pill above the composer: how many tasks, and which of the two sentences. */
sealed interface TasksPill {
    /** The run is live: "Working… · N running tasks". */
    data class Working(val count: Int) : TasksPill

    /** The run is over: "N tasks still running". */
    data class StillRunning(val count: Int) : TasksPill
}

/** The pill for [state], or null when nothing should show (no tasks, no bridge answer, or an old bridge). */
fun tasksPill(state: ChatState): TasksPill? =
    if (state.run != null) state.background.runningCount.takeIf { it > 0 }?.let(TasksPill::Working)
    else state.remoteProcesses?.size?.takeIf { it > 0 }?.let(TasksPill::StillRunning)

/** The fields of a `process_manage` result that matter here; [of] reads a result cut at 500 characters too. */
private class ProcessResult(val status: String?, val sessionId: String?, val exitCode: Int?, val reason: String?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val STATUS = Regex(""""status":\s*"([a-z_]+)"""")
        private val SESSION = Regex(""""session_id":\s*"(proc_[A-Za-z0-9]+)"""")
        private val EXIT = Regex(""""exit_code":\s*(-?\d+)""")
        private val REASON = Regex(""""completion_reason":\s*"([a-z_]+)"""")

        private fun obj(raw: String?): JsonObject? = runCatching { json.parseToJsonElement(raw.orEmpty().trim()) as? JsonObject }.getOrNull()

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        private fun JsonObject.result() = ProcessResult(text("status"), text("session_id"), int("exit_code"), text("completion_reason"))

        /** Complete JSON, or, when the preview was cut, the leading fields of a poll/wait/kill result; else null. */
        fun of(raw: String?): ProcessResult? {
            obj(raw)?.let { return it.result() }
            val text = raw.orEmpty().trim()
            // Only the shapes that begin with their own status or id: a cut list would show the first row's fields.
            if (!text.startsWith("""{"status"""") && !text.startsWith("""{"session_id"""")) return null
            return ProcessResult(
                STATUS.find(text)?.groupValues?.get(1), SESSION.find(text)?.groupValues?.get(1),
                EXIT.find(text)?.groupValues?.get(1)?.toIntOrNull(), REASON.find(text)?.groupValues?.get(1),
            )
        }

        /** The rows of a `list` result; only from complete JSON. */
        fun listOf(raw: String?): List<ProcessResult>? =
            (obj(raw)?.get("processes") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.result() }
    }
}
