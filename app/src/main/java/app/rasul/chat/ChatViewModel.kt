package app.rasul.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.RunEvent
import app.rasul.data.RunUpdate
import app.rasul.data.RunsApi
import app.rasul.data.followRun
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class MessageKind {
    /** Typed by the user and started a run. */
    User,

    /** Typed while a run was active: sent as steer, shown as "Added to running task". */
    UserSteer,
    Assistant,

    /** Plain notice from the app (stopped, some progress lost). */
    Info,
    Error,
}

/** [error] is set on Error messages that come from a request; the UI turns it into specific text. */
data class ChatMessage(val id: Long, val kind: MessageKind, val text: String = "", val error: ChatFailure? = null)

/** Specific causes. No generic case on purpose. */
sealed interface ChatFailure {
    data class Api(val error: HermesError) : ChatFailure

    /** Steer got 409 and the run is still going: it is not accepting messages right now (e.g. waiting for approval). */
    data object SteerRejected : ChatFailure

    /** The run itself ended badly; [detail] is the server's text, if any. */
    data class RunFailed(val detail: String?) : ChatFailure
    data class RunInterrupted(val detail: String?) : ChatFailure

    /** The saved connection was deleted, or its key can no longer be read. */
    data object ConnectionMissing : ChatFailure
    data object KeyMissing : ChatFailure
    data object SessionUnavailable : ChatFailure
    data object AnswerUnsaved : ChatFailure
}

sealed interface StatusLabel {
    data object Starting : StatusLabel
    data object Thinking : StatusLabel
    data object Writing : StatusLabel
    data class Tool(val name: String) : StatusLabel
    data object Helper : StatusLabel
    data object WaitingApproval : StatusLabel
    data object Stopping : StatusLabel
    data class Reconnecting(val attempt: Int, val max: Int) : StatusLabel

    /** Reconnects used up. The run may still be going on the server; the user can reconnect by hand. */
    data object Lost : StatusLabel

    /** The event stream is gone (events 404) but the status route says the run is still going. */
    data object LiveUpdatesUnavailable : StatusLabel
}

private fun StatusLabel.isProgress() =
    this == StatusLabel.Starting || this == StatusLabel.Thinking || this == StatusLabel.Writing ||
        this is StatusLabel.Tool || this == StatusLabel.Helper

/**
 * The live progress card. [runId] is null only while POST /v1/runs is in flight. [status] is the progress state;
 * [stopRequested] is separate so later progress events cannot erase "Stopping" (the Stop button and [label] derive from it).
 * [lastSeq] (null = nothing seen yet; the server numbers events from 0) and [draftId] belong to this run only.
 */
data class ActiveRun(
    val runId: String?,
    val startedAtMs: Long,
    val status: StatusLabel,
    val stopRequested: Boolean = false,
    val lastSeq: Long? = null,
    /** The assistant bubble currently being streamed into; null between segments (after a tool call). */
    val draftId: Long? = null,
) {
    val label: StatusLabel get() = if (stopRequested) StatusLabel.Stopping else status
}

data class ApprovalUi(val requestId: String?, val command: String, val description: String?, val canDeny: Boolean = true)

sealed interface ChatLoad {
    data object Loading : ChatLoad
    data object Ready : ChatLoad
    data class Failed(val failure: ChatFailure) : ChatLoad
}

data class ChatState(
    val load: ChatLoad = ChatLoad.Loading,
    val messages: List<ChatMessage> = emptyList(),
    val run: ActiveRun? = null,
    /** Pending approvals, oldest first, identified by request id. */
    val approvals: List<ApprovalUi> = emptyList(),
    /** Approval answers on the way (request id, "" when the server gave none): their buttons are off. */
    val answering: Set<String> = emptySet(),
    val draft: String = "",
    val historyFailure: ChatFailure? = null,
) {
    /** The one the sheet shows: the oldest still pending. */
    val approval: ApprovalUi? get() = approvals.firstOrNull()
}

/** Where a chat points: resolved once from the saved connection and its decrypted key. */
fun interface TargetLoader {
    suspend fun load(): HermesResult<HermesTarget>
}

/** What survives leaving the chat: one session id per connection+profile, plus the active run and its last event id. */
interface RunMemory {
    suspend fun sessionId(): String
    suspend fun activeRun(): String?
    suspend fun lastEventId(): Long?
    suspend fun anchor(): Anchor = Anchor.Unknown
    suspend fun setActiveRun(runId: String?, lastEventId: Long? = null, anchor: Anchor = Anchor.Unknown)
}

/** Known(null) anchors a turn to an empty transcript; Unknown supports older breadcrumbs. */
sealed interface Anchor {
    data class Known(val key: String?) : Anchor
    data object Unknown : Anchor
}

private data class HistoryRow(val key: String, val role: String?, val message: ChatMessage?)
private enum class Outcome { Answered, NoAnswer }
private data class PendingTurn(
    val anchor: Anchor,
    val restored: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val outcome: Outcome? = null,
    val finishedSequence: Long? = null,
)
private data class Notice(val anchorKey: String?, val message: ChatMessage)

class ChatViewModel(
    private val loader: TargetLoader,
    private val runs: RunsApi,
    private val sessions: SessionsApi,
    private val memory: RunMemory,
    /** Application-owned ordered writer for the active-run breadcrumb: survives leaving the chat, keeps submission order. */
    private val writer: OrderedWriter,
    private val now: () -> Long = System::currentTimeMillis,
    private val follow: (HermesTarget, String, Long?) -> Flow<RunUpdate> = { t, id, after ->
        followRun(open = { last -> runs.events(t, id, last) }, startAfter = after)
    },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private var target: HermesTarget? = null
    private var sessionId: String? = null
    private var nextId = 1L
    private var followJob: Job? = null
    private var statusJob: Job? = null
    private var historyLoaded = false
    private var missingRunWaitingForHistory: String? = null
    private var serverHistory = emptyList<HistoryRow>()
    private val historyIds = mutableMapOf<String, Long>()
    private var nextHistoryId = -1L
    private var pendingTurn: PendingTurn? = null
    private var notices = emptyList<Notice>()
    private var historySequence = 0L
    private var newestAppliedHistory = 0L
    private var preparingSend = false

    /** The key of the message whose start has not been acknowledged yet; reused when the same text is sent again. */
    private var pendingKey: Pair<String, String>? = null
    private val steerLock = Mutex()

    init {
        viewModelScope.launch {
            when (val r = loader.load()) {
                is HermesResult.Ok -> {
                    target = r.value
                    readSessionId()
                    // Send is available after resolving the target and stored run; load history before reattaching.
                    val stored = try {
                        memory.activeRun()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (stored != null) {
                        val anchor = try {
                            memory.anchor()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Anchor.Unknown
                        }
                        pendingTurn = PendingTurn(anchor, restored = true)
                    }
                    _state.update {
                        it.copy(
                            load = ChatLoad.Ready,
                            run = if (stored != null && it.run == null) {
                                ActiveRun(stored, now(), StatusLabel.Thinking)
                            } else {
                                it.run
                            },
                        )
                    }
                    sessionId?.let { loadHistory(r.value, it) }
                    if (stored != null) startStatusCheck(r.value, stored, attach = true)
                }
                is HermesResult.Err -> _state.update {
                    // The loader reports a missing connection as NotFound and an unreadable key as InvalidKey.
                    val f = when (r.error) {
                        HermesError.NotFound -> ChatFailure.ConnectionMissing
                        HermesError.InvalidKey -> ChatFailure.KeyMissing
                        else -> ChatFailure.Api(r.error)
                    }
                    it.copy(load = ChatLoad.Failed(f))
                }
            }
        }
    }

    // ---- the one rule for async work: apply a result only to the run it was started for ----

    private fun isCurrent(id: String) = _state.value.run?.runId == id

    private fun updateRun(id: String, f: (ChatState) -> ChatState) =
        _state.update { if (it.run?.runId == id) f(it) else it }

    private fun ChatState.withRun(f: (ActiveRun) -> ActiveRun) = copy(run = run?.let(f))

    private fun localKey(message: ChatMessage) = "local:${message.id}"

    /** Null means the anchor cannot be proved against this response. */
    private fun after(anchor: Anchor): List<HistoryRow>? = when (anchor) {
        Anchor.Unknown -> null
        is Anchor.Known -> if (anchor.key == null) serverHistory else {
            val index = serverHistory.indexOfFirst { it.key == anchor.key }
            if (index < 0) null else serverHistory.drop(index + 1)
        }
    }

    private fun shown(): List<Pair<String, ChatMessage>> {
        val pending = pendingTurn
        val tail = pending?.let { after(it.anchor) }
        val history = if (pending != null && !pending.restored && tail != null) {
            serverHistory.dropLast(tail.size)
        } else serverHistory
        val rows = (history.mapNotNull { row -> row.message?.let { row.key to it } } +
            pending?.messages.orEmpty().map { localKey(it) to it }).toMutableList()
        notices.forEach { notice ->
            val index = rows.indexOfFirst { it.first == notice.anchorKey }
            val position = if (notice.anchorKey == null) 0 else if (index >= 0) index + 1 else rows.size
            rows.add(position, localKey(notice.message) to notice.message)
        }
        return rows
    }

    private fun ChatState.display(): ChatState {
        val messages = shown().map { it.second }
        check(messages.map { it.id }.toSet().size == messages.size)
        return copy(messages = messages)
    }

    private fun addNotice(message: ChatMessage) {
        notices = notices + Notice(shown().lastOrNull()?.first, message)
    }

    private fun ChatState.msg(kind: MessageKind, text: String): ChatState {
        val message = ChatMessage(nextId++, kind, text)
        if (kind == MessageKind.Info || kind == MessageKind.Error) addNotice(message)
        else pendingTurn = pendingTurn?.let { it.copy(messages = it.messages + message) }
        return display()
    }

    private fun ChatState.fail(f: ChatFailure): ChatState {
        addNotice(ChatMessage(nextId++, MessageKind.Error, error = f))
        return display()
    }

    /** Preserve unresolved local text as anchored notices before starting the next turn. */
    private fun retirePending(keepMessages: Boolean) {
        val pending = pendingTurn ?: return
        val removed = pending.messages.map { localKey(it) }.toSet()
        val replacement = serverHistory.lastOrNull { it.message != null }?.key
        if (keepMessages) {
            val boundary = after(pending.anchor)?.let { serverHistory.dropLast(it.size) }
            var anchor = if (pending.restored || boundary == null) replacement
                else boundary.lastOrNull { it.message != null }?.key
            val saved = pending.messages.map { message -> Notice(anchor, message).also { anchor = localKey(message) } }
            notices = saved + notices
        } else {
            notices = notices.map { if (it.anchorKey in removed) it.copy(anchorKey = replacement) else it }
        }
        pendingTurn = null
    }

    private fun resolvePending(sequence: Long) {
        val pending = pendingTurn ?: return
        val finished = pending.finishedSequence ?: return
        val tail = after(pending.anchor)
        when (pending.outcome) {
            Outcome.Answered -> if (tail?.any { it.role == "assistant" } == true ||
                (pending.anchor == Anchor.Unknown && sequence > finished &&
                    serverHistory.lastOrNull()?.role == "assistant")
            ) retirePending(keepMessages = false)
            Outcome.NoAnswer -> if (sequence > finished) {
                retirePending(keepMessages = tail?.any { it.role == "user" } != true)
            }
            null -> Unit
        }
    }

    private suspend fun readSessionId(): String? {
        sessionId?.let { return it }
        return try {
            memory.sessionId().also { sessionId = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(historyFailure = ChatFailure.SessionUnavailable) }
            null
        }
    }

    private fun joinDraft(first: String, second: String) = if (second.isBlank()) first else if (first.isBlank()) second else "$first\n$second"

    /** Puts an unsent text back. Touches the composer only, never the run; after the run changed it goes after what was typed since. */
    private fun restoreDraft(text: String, sameRun: Boolean) =
        _state.update { it.copy(draft = if (sameRun) joinDraft(text, it.draft) else joinDraft(it.draft, text)) }

    private fun persist(runId: String?, lastEventId: Long? = null) {
        val anchor = pendingTurn?.anchor ?: Anchor.Unknown
        writer.submit { memory.setActiveRun(runId, lastEventId, anchor) }
    }

    /** Each response owns its request sequence; older responses cannot overwrite newer history. */
    fun retryHistory() {
        val t = target ?: return
        viewModelScope.launch { readSessionId()?.let { loadHistory(t, it) } }
    }

    private suspend fun loadHistory(t: HermesTarget, id: String): Boolean {
        val sequence = ++historySequence
        val result = sessions.messages(t, id)
        if (sequence < newestAppliedHistory) return false
        val messages = when (result) {
            is HermesResult.Ok -> result.value
            is HermesResult.Err -> {
                if (result.error != HermesError.NotFound && result.error != HermesError.ResourceNotFound) {
                    _state.update { it.copy(historyFailure = ChatFailure.Api(result.error)) }
                    return false
                }
                emptyList()
            }
        }
        newestAppliedHistory = sequence
        historyLoaded = true
        serverHistory = messages.mapIndexed { index, message ->
            val kind = when (message.role) {
                "user" -> MessageKind.User
                "assistant" -> MessageKind.Assistant
                else -> null
            }
            val text = message.text()
            val key = message.id?.toString() ?: "idx:$index"
            HistoryRow(key, message.role, if (kind == null || text.isBlank()) null else {
                ChatMessage(historyIds.getOrPut(key) { nextHistoryId-- }, kind, text)
            })
        }
        resolvePending(sequence)
        _state.update { it.copy(historyFailure = null).display() }
        resolveMissingRunAfterHistory()
        return true
    }

    private fun resolveMissingRunAfterHistory() {
        val id = missingRunWaitingForHistory ?: return
        if (!historyLoaded || !isCurrent(id)) return
        missingRunWaitingForHistory = null
        if (pendingTurn?.let { after(it.anchor) }?.any { it.role == "assistant" } == true) finish(id, outcome = Outcome.Answered) else finish(id) { it.fail(ChatFailure.Api(HermesError.NotFound)) }
    }

    /** The ONLY place a run is cleared. Cancels its follower and status check, returns an undelivered steer to the composer. */
    private fun finish(
        id: String,
        pendingSteer: String? = null,
        reloadHistory: Boolean = true,
        outcome: Outcome = Outcome.NoAnswer,
        change: (ChatState) -> ChatState = { it },
    ) {
        val current = _state.value
        val run = current.run ?: return
        if (run.runId != id) return
        val changed = change(current)
        pendingTurn = pendingTurn?.copy(outcome = outcome, finishedSequence = ++historySequence)
        resolvePending(newestAppliedHistory)
        followJob?.cancel()
        statusJob?.cancel()
        if (missingRunWaitingForHistory == id) missingRunWaitingForHistory = null
        var finished = changed.copy(run = null, approvals = emptyList(), answering = emptySet())
        if (!pendingSteer.isNullOrBlank()) {
            finished = finished.copy(draft = joinDraft(pendingSteer, finished.draft)).msg(MessageKind.Info, INFO_STEER_RETURNED)
        }
        _state.value = finished.display()
        persist(null)
        if (reloadHistory) {
            val t = target
            val session = sessionId
            if (t != null && session != null) {
                viewModelScope.launch { loadHistory(t, session) }
            }
        }
    }

    // ---- composer ----

    fun onDraft(text: String) {
        if (!preparingSend) _state.update { it.copy(draft = text) }
    }

    /** The user pressed send: steer when a run is active, start a new run when idle. */
    fun send() {
        val s = _state.value
        val text = s.draft.trim()
        val t = target
        if (text.isEmpty() || t == null || s.load != ChatLoad.Ready || preparingSend) return
        val run = s.run
        when {
            run == null -> {
                preparingSend = true
                viewModelScope.launch {
                    try {
                        val session = readSessionId() ?: return@launch
                        val attempts = if (pendingTurn?.outcome == Outcome.Answered) 3 else 1
                        repeat(attempts) { attempt ->
                            if (attempt > 0) delay(attempt * 1_000L)
                            if (loadHistory(t, session)) {
                                if (pendingTurn?.outcome == Outcome.NoAnswer) retirePending(keepMessages = true)
                                if (pendingTurn == null) {
                                    startRun(t, text)
                                    return@launch
                                }
                            }
                            if (pendingTurn?.outcome != Outcome.Answered) return@launch
                        }
                        _state.update { it.copy(historyFailure = ChatFailure.AnswerUnsaved) }
                    } finally {
                        preparingSend = false
                    }
                }
            }
            run.runId == null -> Unit // still starting; the composer is disabled in this window
            else -> steer(t, run.runId, text)
        }
    }

    private fun startRun(t: HermesTarget, text: String) {
        val key = pendingKey?.takeIf { it.first == text }?.second ?: newKey()
        pendingKey = text to key
        check(historyLoaded && pendingTurn == null)
        val message = ChatMessage(nextId++, MessageKind.User, text)
        pendingTurn = PendingTurn(Anchor.Known(serverHistory.lastOrNull()?.key), messages = listOf(message))
        _state.update {
            it.copy(draft = "", run = ActiveRun(null, now(), StatusLabel.Starting)).display()
        }
        val session = sessionId
        viewModelScope.launch {
            when (val r = runs.startRun(t, text, session, key)) {
                is HermesResult.Ok -> {
                    val id = r.value.runId
                    var applied = false
                    _state.update {
                        applied = it.run != null && it.run.runId == null
                        if (applied) it.withRun { run -> run.copy(runId = id) } else it
                    }
                    if (!applied) return@launch
                    pendingKey = null
                    persist(id)
                    followJob = viewModelScope.launch { collectRun(t, id, null) }
                }
                is HermesResult.Err -> _state.update {
                    // Only the run still waiting for its id can fail to start.
                    if (it.run == null || it.run.runId != null) it
                    else {
                        pendingTurn = null
                        it.copy(run = null, draft = joinDraft(text, it.draft)).fail(ChatFailure.Api(r.error))
                    }
                }
            }
        }
    }

    private fun steer(t: HermesTarget, id: String, text: String) {
        _state.update { it.copy(draft = "") }
        viewModelScope.launch {
            // One steer at a time, in the order typed; a run that ended while this one waited gets nothing sent.
            val r = steerLock.withLock { if (isCurrent(id)) runs.steer(t, id, text) else null }
            when {
                r == null -> restoreDraft(text, sameRun = false)
                r is HermesResult.Ok -> updateRun(id) { it.msg(MessageKind.UserSteer, text) }
                r is HermesResult.Err -> {
                    restoreDraft(text, sameRun = isCurrent(id))
                    if (!isCurrent(id)) return@launch
                    if (r.error == HermesError.Server(409)) steerRefused(t, id)
                    else updateRun(id) { it.fail(ChatFailure.Api(r.error)) }
                }
            }
        }
    }

    /**
     * The server refuses steers unless the run is `running` (api_server_runs.py:1228), so 409 does not mean "finished".
     * Ask the status once: terminal = finish (the next Send starts a new run); otherwise keep the run and say so.
     * This check never marks live updates unavailable.
     */
    private fun steerRefused(t: HermesTarget, id: String) {
        if (statusJob?.isActive == true) {
            // A status check is already settling this run.
            updateRun(id) { it.fail(ChatFailure.SteerRejected) }
            return
        }
        statusJob = viewModelScope.launch {
            val st = (runs.status(t, id) as? HermesResult.Ok)?.value
            if (!isCurrent(id)) return@launch
            val ev = st?.terminalEvent()
            if (ev != null) {
                onEvent(id, ev)
                return@launch
            }
            st?.approval?.let { mergeApproval(id, it) }
            updateRun(id) { it.fail(ChatFailure.SteerRejected) }
        }
    }

    fun stop() {
        val t = target ?: return
        val run = _state.value.run ?: return
        val id = run.runId ?: return
        if (run.stopRequested) return
        updateRun(id) { it.withRun { r -> r.copy(stopRequested = true) } }
        viewModelScope.launch {
            when (val r = runs.stop(t, id)) {
                // The stream was lost: nothing will report the end, so look again.
                is HermesResult.Ok -> {
                    val cur = _state.value.run
                    if (cur?.runId == id) {
                        if (cur.status == StatusLabel.Lost) reconnect()
                        else if (cur.status == StatusLabel.LiveUpdatesUnavailable) refresh()
                    }
                }
                // Back to a state where Stop can be tried again, with the specific cause shown.
                is HermesResult.Err -> updateRun(id) {
                    it.withRun { run -> run.copy(stopRequested = false) }.fail(ChatFailure.Api(r.error))
                }
            }
        }
    }

    /** Manual reconnect after [StatusLabel.Lost]: resumes after the last seen event, input is never resent. */
    fun reconnect() {
        val t = target ?: return
        val run = _state.value.run ?: return
        val id = run.runId ?: return
        if (run.status != StatusLabel.Lost) return
        followJob?.cancel()
        statusJob?.cancel()
        updateRun(id) { s ->
            s.withRun { it.copy(status = if (s.approvals.isEmpty()) StatusLabel.Thinking else StatusLabel.WaitingApproval) }
        }
        followJob = viewModelScope.launch { collectRun(t, id, run.lastSeq) }
    }

    /** Refresh after [StatusLabel.LiveUpdatesUnavailable]: asks the server for the run's status again (one at a time). */
    fun refresh() {
        val t = target ?: return
        val run = _state.value.run ?: return
        val id = run.runId ?: return
        if (run.status != StatusLabel.LiveUpdatesUnavailable) return
        startStatusCheck(t, id, attach = false)
    }

    private fun startStatusCheck(t: HermesTarget, id: String, attach: Boolean) {
        val prev = statusJob?.takeIf { it.isActive }
        // An overlapping request is kept: it runs after the one in flight (checkStatus re-checks isCurrent).
        statusJob = viewModelScope.launch {
            prev?.join()
            if (isCurrent(id)) checkStatus(t, id, attach)
        }
    }

    /**
     * Events said 404, which only means the SSE transport expired (the server keeps the run, api_server_runs.py:1294,
     * 1082). Ask GET /v1/runs/{id}: terminal = finish like a stream would; still going = keep id and controls (and merge
     * the approval the status carries, add-only); 404 too = the run is gone; anything else = cannot tell, so keep the
     * run and let the user reconnect. [attach] is the re-open path: a run still going is followed from the beginning of the event buffer.
     */
    private suspend fun checkStatus(t: HermesTarget, id: String, attach: Boolean) {
        val r = runs.status(t, id)
        if (!isCurrent(id)) return
        when (r) {
            is HermesResult.Ok -> {
                val ev = r.value.terminalEvent()
                if (ev != null) {
                    onEvent(id, ev)
                    return
                }
                r.value.approval?.let { mergeApproval(id, it) }
                if (attach) {
                    // A new view has no streamed prefix: replay the buffered events from the beginning.
                    followJob = viewModelScope.launch { collectRun(t, id, null) }
                }
                else updateRun(id) { it.withRun { run -> run.copy(status = StatusLabel.LiveUpdatesUnavailable) } }
            }
            is HermesResult.Err ->
                if (r.error == HermesError.NotFound) {
                    if (!historyLoaded) {
                        missingRunWaitingForHistory = id
                    } else if (pendingTurn?.let { after(it.anchor) }?.any { it.role == "assistant" } == true) {
                        finish(id, outcome = Outcome.Answered)
                    } else {
                        finish(id) { it.fail(ChatFailure.Api(r.error)) }
                    }
                } else {
                    updateRun(id) { it.withRun { run -> run.copy(status = StatusLabel.Lost) }.fail(ChatFailure.Api(r.error)) }
                }
        }
    }

    // ---- approvals ----

    /** [requestId] is the one the sheet DISPLAYS; a tap on a request that is no longer pending (or already sent) does nothing. */
    fun answerApproval(requestId: String?, choice: ApprovalChoice) {
        val t = target ?: return
        val id = _state.value.run?.runId ?: return
        val key = requestId ?: ""
        val s = _state.value
        if (s.approvals.none { it.requestId == requestId } || key in s.answering) return
        _state.update { it.copy(answering = it.answering + key) }
        viewModelScope.launch {
            when (val r = runs.approve(t, id, choice, requestId)) {
                is HermesResult.Ok -> updateRun(id) { removeApproval(it.copy(answering = it.answering - key), requestId) }
                is HermesResult.Err -> updateRun(id) {
                    val n = it.copy(answering = it.answering - key)
                    // 409: the server has no such pending approval any more (approval_not_pending / approval_not_active).
                    if (r.error == HermesError.Server(409)) removeApproval(n, requestId).msg(MessageKind.Info, INFO_APPROVAL_GONE)
                    else n.fail(ChatFailure.Api(r.error))
                }
            }
        }
    }

    /**
     * Removes [requestId] (an answer of ours that succeeded, or a 409 on it). A null id (server without request ids)
     * removes the oldest id-less one. Responded events never come here without an id: they cannot be matched to anything.
     */
    private fun removeApproval(s: ChatState, requestId: String?): ChatState {
        val left = if (requestId != null) {
            s.approvals.filterNot { it.requestId == requestId }
        } else {
            val i = s.approvals.indexOfFirst { it.requestId == null }
            if (i < 0) s.approvals else s.approvals.filterIndexed { j, _ -> j != i }
        }
        val r = s.run?.let { if (left.isEmpty() && it.status == StatusLabel.WaitingApproval) it.copy(status = StatusLabel.Thinking) else it }
        return s.copy(approvals = left, run = r)
    }

    /** Adds [e] unless it is already there. A same-id request from the stream replaces in place when [replace]. */
    private fun addApproval(s: ChatState, e: RunEvent.ApprovalRequest, replace: Boolean): ChatState {
        val ap = ApprovalUi(e.requestId, e.command, e.description, canDeny = e.choices.isEmpty() || "deny" in e.choices)
        val has = e.requestId != null && s.approvals.any { it.requestId == e.requestId }
        val idlessHas = e.requestId == null && !replace && s.approvals.any { it.requestId == null }
        val list = when {
            has && replace -> s.approvals.map { if (it.requestId == e.requestId) ap else it }
            has || idlessHas -> s.approvals
            else -> s.approvals + ap
        }
        val cur = s.run?.status
        // Lost and LiveUpdatesUnavailable keep their label so Reconnect / Refresh stay on the card.
        val st = if (cur == StatusLabel.Lost || cur == StatusLabel.LiveUpdatesUnavailable) cur else StatusLabel.WaitingApproval
        return s.copy(approvals = list, run = s.run?.copy(status = st ?: StatusLabel.WaitingApproval))
    }

    /** An approval taken from GET /v1/runs/{id}: add-only, because the status is lossy (api_server_runs.py:270-271, 1158). */
    private fun mergeApproval(id: String, e: RunEvent.ApprovalRequest) = updateRun(id) { addApproval(it, e, replace = false) }

    // ---- following the run ----

    private suspend fun collectRun(t: HermesTarget, runId: String, after: Long?) {
        follow(t, runId, after).collect { u ->
            if (!isCurrent(runId)) return@collect
            when (u) {
                is RunUpdate.Reconnecting -> updateRun(runId) { it.withRun { r -> r.copy(status = StatusLabel.Reconnecting(u.attempt, u.max)) } }
                is RunUpdate.Lost ->
                    if (u.error == HermesError.NotFound) startStatusCheck(t, runId, attach = false)
                    else updateRun(runId) {
                        // Anything but 404: the run may still be going (losing SSE does not stop it),
                        // so keep id, Stop and steer.
                        it.withRun { r -> r.copy(status = StatusLabel.Lost) }.fail(ChatFailure.Api(u.error))
                    }
                is RunUpdate.Event -> {
                    u.seq?.let { seq ->
                        updateRun(runId) { it.withRun { r -> r.copy(lastSeq = seq) } }
                        persist(runId, seq)
                    }
                    onEvent(runId, u.event)
                }
            }
        }
    }

    /** Progress events set the base status; while approvals are open it stays WaitingApproval. */
    private fun setStatus(id: String, s: StatusLabel) = updateRun(id) { st ->
        val next = if (s.isProgress() && st.approvals.isNotEmpty()) StatusLabel.WaitingApproval else s
        st.withRun { it.copy(status = next) }
    }

    private fun onEvent(id: String, e: RunEvent) {
        if (!isCurrent(id)) return
        when (e) {
            is RunEvent.MessageDelta -> {
                updateRun(id) { appendDelta(it, e.delta) }
                setStatus(id, StatusLabel.Writing)
            }
            is RunEvent.MessageInterim -> updateRun(id) { s ->
                val n = if (!e.alreadyStreamed && e.text.isNotBlank()) s.msg(MessageKind.Assistant, e.text) else s
                n.withRun { it.copy(draftId = null) }
            }
            is RunEvent.ToolStarted -> {
                // A tool call ends the current answer segment: the next text is its own bubble.
                updateRun(id) { it.withRun { r -> r.copy(draftId = null) } }
                setStatus(id, StatusLabel.Tool(e.tool))
            }
            is RunEvent.ToolCompleted, is RunEvent.Reasoning -> setStatus(id, StatusLabel.Thinking)
            is RunEvent.Subagent -> setStatus(id, StatusLabel.Helper)
            is RunEvent.ApprovalRequest -> updateRun(id) { addApproval(it, e, replace = true) }
            // Without an id the event cannot be matched to a pending approval, so it removes nothing.
            is RunEvent.ApprovalResponded -> if (e.requestId != null) updateRun(id) { removeApproval(it, e.requestId) }
            is RunEvent.ReplayTruncated -> updateRun(id) { it.msg(MessageKind.Info, INFO_REPLAY_TRUNCATED) }
            RunEvent.Steered, RunEvent.Stopping -> Unit
            is RunEvent.Completed -> finish(id, e.pendingSteer, outcome = if (e.output.isNotEmpty()) Outcome.Answered else Outcome.NoAnswer) { s ->
                if (e.output.isNotEmpty()) setFinalText(s, e.output) else s
            }
            is RunEvent.Failed -> finish(id, e.pendingSteer) { it.fail(ChatFailure.RunFailed(e.error)) }
            is RunEvent.Interrupted -> finish(id, e.pendingSteer) { it.fail(ChatFailure.RunInterrupted(e.error)) }
            is RunEvent.Cancelled -> finish(id, e.pendingSteer) { it.msg(MessageKind.Info, INFO_STOPPED) }
            is RunEvent.Unknown -> Unit
        }
    }

    private fun appendDelta(s: ChatState, delta: String): ChatState {
        val pending = pendingTurn ?: return s
        val id = s.run?.draftId
        return if (id == null) {
            val message = ChatMessage(nextId++, MessageKind.Assistant, delta)
            pendingTurn = pending.copy(messages = pending.messages + message)
            s.display().withRun { it.copy(draftId = message.id) }
        } else {
            pendingTurn = pending.copy(messages = pending.messages.map { if (it.id == id) it.copy(text = it.text + delta) else it })
            s.display()
        }
    }

    /** Terminal text belongs only to the pending turn, replacing its open streamed segment. */
    private fun setFinalText(s: ChatState, text: String): ChatState {
        val pending = pendingTurn ?: return s
        val id = s.run?.draftId
        return if (id != null) {
            pendingTurn = pending.copy(messages = pending.messages.map { if (it.id == id) it.copy(text = text) else it })
            s.display()
        } else s.msg(MessageKind.Assistant, text)
    }

    companion object {
        /** Markers for Info messages; the screen maps them to string resources. */
        const val INFO_STOPPED = "stopped"
        const val INFO_REPLAY_TRUNCATED = "replay_truncated"
        const val INFO_APPROVAL_GONE = "approval_gone"
        const val INFO_STEER_RETURNED = "steer_returned"
    }
}
