package app.rasul.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.ApprovalChoice
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.PendingAttachment
import app.rasul.data.RunEvent
import app.rasul.data.RunInputLimits
import app.rasul.data.RunInputPlan
import app.rasul.data.RunUpdate
import app.rasul.data.RunsApi
import app.rasul.data.SentAttachment
import app.rasul.data.followRun
import app.rasul.data.planRunInput
import app.rasul.data.toSent
import app.rasul.data.timeMillis
import app.rasul.sessions.ChatSessionControl
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

/** [error] is set on Error messages that come from a request; the UI turns it into specific text. [atMillis] drives day separators; null shows none. */
data class ChatMessage(
    val id: Long,
    val kind: MessageKind,
    val text: String = "",
    val error: ChatFailure? = null,
    val attachments: List<SentAttachment> = emptyList(),
    val atMillis: Long? = null,
)

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
    data object PreviousRunActive : ChatFailure
    data object SessionUnavailable : ChatFailure
    /** The staged request would exceed the bridge's request body cap (RunInputLimits.MAX_RUN_BODY_BYTES). */
    data object AttachmentTooLarge : ChatFailure

    /** The picked file's type/extension is not a text file. */
    data object FileTypeUnsupported : ChatFailure

    /** The picked file is over RunInputLimits.MAX_FILE_BYTES. */
    data object FileTooLarge : ChatFailure

    /** One photo still exceeds RunInputLimits.MAX_PHOTO_BYTES after re-encoding. */
    data object PhotoTooLarge : ChatFailure

    /** The picked image could not be decoded. */
    data object PhotoUnreadable : ChatFailure

    /** The picked file could not be opened/read. */
    data object FileUnreadable : ChatFailure

    /** Photos and a text file are mutually exclusive in one send. */
    data object AttachmentKindMixed : ChatFailure

    /** More than RunInputLimits.MAX_PHOTOS were staged. */
    data object AttachmentTooManyPhotos : ChatFailure

    /** Steer carries only text: attachments wait for an idle composer. */
    data object AttachmentRunActive : ChatFailure

    /** A notification reply could not be sent; its text was put back into the composer draft. */
    data object ReplyKept : ChatFailure

    /** The fork request failed; [error] is the specific cause. */
    data class ForkFailed(val error: HermesError) : ChatFailure
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
    val sessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val run: ActiveRun? = null,
    /** Pending approvals, oldest first, identified by request id. */
    val approvals: List<ApprovalUi> = emptyList(),
    /** Approval answers on the way (request id, "" when the server gave none): their buttons are off. */
    val answering: Set<String> = emptySet(),
    val draft: String = "",
    val historyFailure: ChatFailure? = null,
    val terminal: RunEvent? = null,
    val terminalFailure: ChatFailure? = null,
    val finishedRunId: String? = null,
    val queuedReplies: Int = 0,
    val preparingSend: Boolean = false,
    /** Staged (not yet sent) attachments, shown as removable chips above the composer. */
    val attachments: List<PendingAttachment> = emptyList(),
    /** Picker loads still in flight on the ViewModel scope; Send stays off until they land. */
    val pendingAttachments: Int = 0,
    /** Server-advertised per-chat actions (GET /v1/capabilities features); both stay hidden until the server says so. */
    val supportsFork: Boolean = false,
    val forking: Boolean = false,
    /** A fork the server created that the screen has not opened yet; the screen calls [ChatViewModel.forkOpened] after opening it. */
    val forkedSessionId: String? = null,
    /** Title of this chat's session when it is a fork (it has a parent session). */
    val forkTitle: String? = null,
    /** Last fork or model-change failure, shown in a dismissible row. */
    val actionFailure: ChatFailure? = null,
) {
    val canChooseModel: Boolean get() = load == ChatLoad.Ready && sessionId != null &&
        run == null && !preparingSend && queuedReplies == 0 && !forking

    val canFork: Boolean get() = supportsFork && canChooseModel

    /** The one the sheet shows: the oldest still pending. */
    val approval: ApprovalUi? get() = approvals.firstOrNull()
}

/** Where a chat points: resolved from the saved connection and its decrypted key. */
fun interface TargetLoader {
    suspend fun load(): HermesResult<HermesTarget>
}

/** What survives leaving the chat: the run breadcrumb lives under its OWN session id, never under a shared slot. */
interface RunMemory {
    fun bindRevision(revision: String) = Unit
    suspend fun sessionId(): String
    suspend fun currentSessionId(): String? = null
    suspend fun newSessionFailure(): ChatFailure? = null
    suspend fun activeRun(): String?
    suspend fun activeRun(sessionId: String): String? = if (sessionId == currentSessionId()) activeRun() else null
    suspend fun activeRunSessions(): Set<String> = activeRun()?.let { setOf(sessionId()) } ?: emptySet()
    suspend fun lastEventId(): Long?
    suspend fun lastEventId(sessionId: String): Long? = if (sessionId == currentSessionId()) lastEventId() else null
    suspend fun anchor(): Anchor = Anchor.Unknown
    suspend fun anchor(sessionId: String): Anchor = if (sessionId == currentSessionId()) anchor() else Anchor.Unknown
    suspend fun setActiveRun(runId: String?, lastEventId: Long? = null, anchor: Anchor = Anchor.Unknown)
    suspend fun setSessionId(sessionId: String) = Unit
    suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor) {
        setSessionId(sessionId)
        setActiveRun(runId, lastEventId, anchor)
    }
    /** A new-chat route's one-shot pointer claim (ConnectionStore.setCurrentSessionRun); memories without a store ignore it. */
    suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor, claim: ConnectionStore.PointerClaim) =
        setCurrentSessionRun(sessionId, runId, lastEventId, anchor)
}

/**
 * Binds a chat to [newSession]: every read and write lands under that session's own run keys.
 * [claim] arms a one-shot pointer move for `newSession=` routes: the first `runId != null` write claims the
 * current-session pointer inside the same store edit, only while it still equals the value seen at tap time.
 */
fun newChatRunMemory(persistent: RunMemory, newSession: String, claim: ConnectionStore.PointerClaim? = null): RunMemory = object : RunMemory {
    private var pendingClaim = claim
    override fun bindRevision(revision: String) = persistent.bindRevision(revision)
    override suspend fun sessionId() = newSession
    override suspend fun currentSessionId() = persistent.currentSessionId()
    override suspend fun newSessionFailure(): ChatFailure? = try {
        if (persistent.currentSessionId() != newSession && (persistent.activeRunSessions() - newSession).isNotEmpty()) ChatFailure.PreviousRunActive else null
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ChatFailure.SessionUnavailable
    }
    override suspend fun activeRun(): String? = persistent.activeRun(newSession)
    override suspend fun activeRun(sessionId: String): String? = persistent.activeRun(sessionId)
    override suspend fun activeRunSessions() = persistent.activeRunSessions()
    override suspend fun lastEventId(): Long? = persistent.lastEventId(newSession)
    override suspend fun lastEventId(sessionId: String): Long? = persistent.lastEventId(sessionId)
    override suspend fun anchor() = persistent.anchor(newSession)
    override suspend fun anchor(sessionId: String) = persistent.anchor(sessionId)
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) {
        val armed = if (runId != null) pendingClaim.also { pendingClaim = null } else null
        if (armed != null) persistent.setCurrentSessionRun(newSession, runId, lastEventId, anchor, armed)
        else persistent.setCurrentSessionRun(newSession, runId, lastEventId, anchor)
    }
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
    scope: kotlinx.coroutines.CoroutineScope? = null,
    /** Encode + request serialization run here; tests inject their scheduler to keep virtual time. */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Turns picker URIs into staged attachments; null in tests that never pick. */
    private val attachLoader: AttachmentLoader? = null,
    private val control: ChatSessionControl = ChatSessionControl.None,
) : ViewModel() {
    private val chatScope = kotlinx.coroutines.CoroutineScope((scope ?: viewModelScope).coroutineContext + kotlinx.coroutines.SupervisorJob((scope ?: viewModelScope).coroutineContext[Job]))
    internal var onFinished: ((String, ChatState) -> Unit)? = null

    fun close() {
        onFinished = null
        notificationReplies.close()
        chatScope.coroutineContext[Job]?.cancel()
    }
    private val notificationReplies = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private var target: HermesTarget? = null
    private var sessionId: String? = null
    internal val resolvedTarget: HermesTarget? get() = target
    internal val resolvedSessionId: String? get() = sessionId
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
    private var preparingSend: Boolean
        get() = _state.value.preparingSend
        set(value) { _state.update { if (value) it.copy(preparingSend = true, terminal = null, terminalFailure = null, finishedRunId = null) else it.copy(preparingSend = false) } }

    /** The key of the message whose start has not been acknowledged yet; reused when the same text is sent again. */
    private var pendingKey: Pair<String, String>? = null
    private val steerLock = Mutex()

    /** Notification replies this model put back into the draft; only these may be removed from it when they start. */
    private val keptReplies = mutableSetOf<String>()

    init {
        chatScope.launch {
            for (text in notificationReplies) {
                try {
                    state.first { it.load != ChatLoad.Loading }
                    val t = target
                    if (t == null) {
                        restoreDraft(text, sameRun = false, keptReply = true)
                        _state.update { it.copy(terminalFailure = ChatFailure.ReplyKept) }
                        continue
                    }
                    val active = state.value.run?.runId
                    if (active != null) {
                        val result = steerLock.withLock {
                            if (isCurrent(active)) runs.steer(t, active, text) else null
                        }
                        if (state.value.load != ChatLoad.Ready) {
                            restoreDraft(text, sameRun = false)
                            continue
                        }
                        if (result is HermesResult.Ok) {
                            updateRun(active) { it.msg(MessageKind.UserSteer, text) }
                            continue
                        }
                        if (result is HermesResult.Err && result.error != HermesError.Server(409)) {
                            _state.update { it.fail(ChatFailure.Api(result.error)) }
                        }
                        if (result is HermesResult.Err && result.error == HermesError.Server(409) && isCurrent(active)) steerRefused(t, active)
                    }
                    state.first { it.run == null && !it.preparingSend && it.pendingAttachments == 0 }
                    sendText(text, fromNotification = true)?.join()
                    if (state.value.run == null) {
                        restoreDraft(text, sameRun = false, keptReply = true)
                        _state.update { it.copy(terminalFailure = ChatFailure.ReplyKept) }
                    }
                } finally {
                    _state.update { it.copy(queuedReplies = it.queuedReplies - 1) }
                }
            }
        }
        chatScope.launch {
            when (val r = loader.load()) {
                is HermesResult.Ok -> {
                    target = r.value
                    readSessionId()
                    loadSessionControl(r.value)
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
        val message = ChatMessage(nextId++, kind, text, atMillis = System.currentTimeMillis())
        if (kind == MessageKind.Info || kind == MessageKind.Error) addNotice(message)
        else pendingTurn = pendingTurn?.let { it.copy(messages = it.messages + message) }
        return display()
    }

    private fun ChatState.fail(f: ChatFailure): ChatState {
        addNotice(ChatMessage(nextId++, MessageKind.Error, error = f, atMillis = System.currentTimeMillis()))
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
            // A terminal event means the server already finished writing the turn; the next
            // served history is the truth, so the local rows retire unconditionally.
            Outcome.Answered -> if (tail?.any { it.role == "assistant" } == true || sequence > finished)
                retirePending(keepMessages = false)
            Outcome.NoAnswer -> if (sequence > finished) {
                retirePending(keepMessages = tail?.any { it.role == "user" } != true)
            }
            null -> Unit
        }
    }

    private suspend fun readSessionId(): String? {
        return try {
            val previous = sessionId
            val current = memory.sessionId()
            if (previous != null && previous != current) {
                followJob?.cancel()
                statusJob?.cancel()
                historySequence++
                newestAppliedHistory = historySequence
                historyLoaded = false
                serverHistory = emptyList()
                historyIds.clear()
                nextHistoryId = -1L
                pendingTurn = null
                notices = emptyList()
                pendingKey = null
                missingRunWaitingForHistory = null
                persist(null)
                _state.update {
                    it.copy(
                        messages = emptyList(),
                        run = null,
                        approvals = emptyList(),
                        answering = emptySet(),
                        historyFailure = null,
                        terminal = null,
                        terminalFailure = null,
                        finishedRunId = null,
                        attachments = emptyList(),
                        forkTitle = null,
                        actionFailure = null,
                    )
                }
            }
            sessionId = current
            _state.update { it.copy(sessionId = current) }
            current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(historyFailure = ChatFailure.SessionUnavailable) }
            null
        }
    }

    /** Reads what the server supports and whether this chat is a fork; neither blocks the chat. */
    private fun loadSessionControl(t: HermesTarget) {
        chatScope.launch {
            val support = control.support(t)
            _state.update { it.copy(supportsFork = support.fork) }
            val id = sessionId ?: return@launch
            val info = (control.info(t, id) as? HermesResult.Ok)?.value ?: return@launch
            if (info.parentSessionId != null && sessionId == id) {
                _state.update { it.copy(forkTitle = info.title?.takeIf(String::isNotBlank) ?: "") }
            }
        }
    }

    fun dismissActionFailure() = _state.update { it.copy(actionFailure = null) }

    /**
     * POST /api/sessions/{id}/fork. Runs in the chat's own scope, so leaving the screen does not cancel it and the
     * [ChatState.forking] flag always resets. The new session id lands in [ChatState.forkedSessionId] (or the cause in
     * [ChatState.actionFailure]); the screen opens it and calls [forkOpened].
     */
    fun fork() {
        val t = target
        val id = sessionId
        if (t == null || id == null || !_state.value.canFork) return
        _state.update { it.copy(forking = true, actionFailure = null) }
        chatScope.launch {
            try {
                when (val r = control.fork(t, id)) {
                    is HermesResult.Ok -> _state.update { it.copy(forkedSessionId = r.value.id) }
                    is HermesResult.Err -> _state.update { it.copy(actionFailure = ChatFailure.ForkFailed(r.error)) }
                }
            } finally {
                _state.update { it.copy(forking = false) }
            }
        }
    }

    fun forkOpened() = _state.update { it.copy(forkedSessionId = null) }

    private fun joinDraft(first: String, second: String) = if (second.isBlank()) first else if (first.isBlank()) second else "$first\n$second"

    /** True when [text] is already a whole line of [draft]; a failed send must not duplicate it. */
    private fun draftHas(draft: String, text: String) =
        draft == text || draft.startsWith("$text\n") || draft.endsWith("\n$text") || draft.contains("\n$text\n")

    /** Removes one whole-line occurrence of [text] from [draft]; a kept reply that then starts must not stay in the composer. */
    private fun dropDraftLine(draft: String, text: String) = when {
        draft == text -> ""
        draft.endsWith("\n$text") -> draft.removeSuffix("\n$text")
        draft.startsWith("$text\n") -> draft.removePrefix("$text\n")
        else -> draft.replaceFirst("\n$text\n", "\n")
    }

    /** Puts an unsent text back, at most once. Touches the composer only, never the run; after the run changed it goes after what was typed since. */
    private fun restoreDraft(text: String, sameRun: Boolean, keptReply: Boolean = false) =
        run {
            var added = false
            _state.update {
                added = !draftHas(it.draft, text)
                if (!added) it else it.copy(draft = if (sameRun) joinDraft(text, it.draft) else joinDraft(it.draft, text))
            }
            if (added && keptReply) keptReplies.add(text)
        }

    private fun persist(runId: String?, lastEventId: Long? = null) {
        val anchor = pendingTurn?.anchor ?: Anchor.Unknown
        writer.submit { memory.setActiveRun(runId, lastEventId, anchor) }
    }

    /** Each response owns its request sequence; older responses cannot overwrite newer history. */
    fun retryHistory() {
        val t = target ?: return
        chatScope.launch { readSessionId()?.let { loadHistory(t, it) } }
    }

    private suspend fun loadHistory(t: HermesTarget, id: String): Boolean {
        val sequence = ++historySequence
        val result = sessions.messages(t, id)
        // A newer applied load already settled history; a session reset leaves nothing to send against.
        if (sequence < newestAppliedHistory) return historyLoaded
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
            val text = message.displayText()
            val key = message.id?.toString() ?: "idx:$index"
            val attachments = message.attachments()
            HistoryRow(key, message.role, if (kind == null || (text.isBlank() && attachments.isEmpty())) null else {
                ChatMessage(historyIds.getOrPut(key) { nextHistoryId-- }, kind, text, attachments = attachments, atMillis = timeMillis(message.timestamp))
            })
        }
        resolvePending(sequence)
        _state.update {
            it.copy(historyFailure = null).display()
        }
        resolveMissingRunAfterHistory()
        return true
    }

    private fun resolveMissingRunAfterHistory() {
        val id = missingRunWaitingForHistory ?: return
        if (!historyLoaded || !isCurrent(id)) return
        missingRunWaitingForHistory = null
        finishMissingRun(id)
    }

    private fun finishMissingRun(id: String) {
        val answer = pendingTurn?.let { after(it.anchor) }?.lastOrNull { it.role == "assistant" }?.message?.text
        if (answer != null) finish(id, outcome = Outcome.Answered) { it.copy(terminal = RunEvent.Completed(answer), terminalFailure = null) }
        else finish(id) { it.copy(terminalFailure = ChatFailure.Api(HermesError.NotFound)).fail(ChatFailure.Api(HermesError.NotFound)) }
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
        pendingTurn = pendingTurn?.let {
            it.copy(outcome = outcome, finishedSequence = ++historySequence)
        }
        resolvePending(newestAppliedHistory)
        followJob?.cancel()
        statusJob?.cancel()
        if (missingRunWaitingForHistory == id) missingRunWaitingForHistory = null
        var finished = changed.copy(run = null, finishedRunId = id, approvals = emptyList(), answering = emptySet())
        if (!pendingSteer.isNullOrBlank()) {
            finished = finished.copy(draft = joinDraft(pendingSteer, finished.draft)).msg(MessageKind.Info, INFO_STEER_RETURNED)
        }
        val terminalState = finished.display()
        onFinished?.invoke(id, terminalState)
        _state.value = terminalState
        persist(null)
        if (reloadHistory) {
            val t = target
            val session = sessionId
            if (t != null && session != null) {
                chatScope.launch { loadHistory(t, session) }
            }
        }
    }

    // ---- composer ----

    fun onDraft(text: String) {
        if (preparingSend) return
        _state.update { it.copy(draft = text) }
        // A kept reply the user edited away must not delete a line typed later.
        keptReplies.retainAll { draftHas(text, it) }
    }

    /**
     * Stages processed attachments as composer chips. One send carries up to
     * [RunInputLimits.MAX_PHOTOS] photos OR one text file — never a mix. A result that lands while
     * a send is being prepared is kept: [sendText] copies its set before launching, so a late
     * arrival simply stays staged for the next send.
     */
    fun addAttachments(items: List<PendingAttachment>) {
        if (items.isEmpty()) return
        val current = _state.value.attachments
        val next = current + items
        val hasFile = next.any { it is PendingAttachment.TextFile }
        if ((hasFile && next.size > 1) || (current.isNotEmpty() && items.any { it is PendingAttachment.TextFile })) {
            _state.update { it.fail(ChatFailure.AttachmentKindMixed) }
            return
        }
        if (next.size > RunInputLimits.MAX_PHOTOS) {
            _state.update { it.fail(ChatFailure.AttachmentTooManyPhotos) }
            return
        }
        _state.update { it.copy(attachments = next) }
    }

    fun removeAttachment(key: Long) {
        if (preparingSend) return
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.key == key }) }
    }

    /** The picker/loader layer reports why an attachment could not be staged. */
    fun attachError(failure: ChatFailure) {
        _state.update { it.fail(failure) }
    }

    /**
     * The photo picker's result. Loading runs on this ViewModel's scope — not the screen's — so a
     * recreation cannot drop it, and [ChatState.pendingAttachments] keeps Send off until it lands.
     */
    fun loadPhotos(uris: List<Uri>) {
        val loader = attachLoader ?: return
        if (uris.isEmpty()) return
        // Photos already staged and photos still loading share the same request: count both.
        val total = (_state.value.attachments.size + inFlightPhotos + uris.size).coerceIn(1, RunInputLimits.MAX_PHOTOS)
        val budget = RunInputLimits.photoBudget(total)
        inFlightPhotos += uris.size
        pendingAttachmentLoad({ inFlightPhotos -= uris.size }) { loader.loadPhotos(uris, budget) }
    }

    private var inFlightPhotos = 0

    /** The file picker's result; same ViewModel-owned loading as [loadPhotos]. */
    fun loadFile(uri: Uri) {
        val loader = attachLoader ?: return
        pendingAttachmentLoad({}) { loader.loadFile(uri) }
    }

    /**
     * One picker load: counted in [ChatState.pendingAttachments] for its whole flight. A result is
     * staged only when the session it was picked under is still current — a session switch mid-load
     * must not leak the old pick into the new chat.
     */
    private fun pendingAttachmentLoad(done: () -> Unit, load: suspend () -> AttachLoad) {
        val atSession = sessionId
        _state.update { it.copy(pendingAttachments = it.pendingAttachments + 1) }
        chatScope.launch {
            try {
                when (val r = load()) {
                    is AttachLoad.Ok -> stageLoaded(r.items, atSession)
                    is AttachLoad.Failed -> if (sessionId == atSession) _state.update { it.fail(r.failure) }
                }
            } finally {
                done()
                _state.update { it.copy(pendingAttachments = (it.pendingAttachments - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** One staging at a time: a fit suspends, and an interleaved staging would read stale staged photos. */
    private val stagingLock = Mutex()

    /**
     * Stages a load's result under [stagingLock]. Photos encoded for a larger budget than the final
     * total — staged earlier or in this very result — are re-encoded first; if one cannot fit,
     * nothing is staged and the photo-too-large message shows. The session is re-checked after
     * every suspension: a pick made in another chat is dropped, never staged into this one.
     */
    private suspend fun stageLoaded(items: List<PendingAttachment>, atSession: String?) = stagingLock.withLock {
        if (sessionId != atSession) return@withLock
        val loader = attachLoader
        var toAdd = items
        val staged = _state.value.attachments
        if (staged.size + items.size > RunInputLimits.MAX_PHOTOS) {
            addAttachments(items) // refuses with the too-many message before any refit
            return@withLock
        }
        if (loader != null && items.any { it is PendingAttachment.Photo }) {
            val budget = RunInputLimits.photoBudget((staged.size + items.size).coerceIn(1, RunInputLimits.MAX_PHOTOS))
            val over = (staged + items).filterIsInstance<PendingAttachment.Photo>().filter { it.jpeg.size > budget }
            if (over.isNotEmpty()) {
                // A new key: Photo equality is by key, so the same key would make the update a no-op.
                val fitted = over.associate { it.key to loader.fit(it, budget)?.copy(key = PendingAttachment.newKey()) }
                if (sessionId != atSession) return@withLock
                if (fitted.values.any { it == null }) {
                    _state.update { it.fail(ChatFailure.PhotoTooLarge) }
                    return@withLock
                }
                _state.update { s -> s.copy(attachments = s.attachments.map { fitted[it.key] ?: it }) }
                toAdd = items.map { fitted[it.key] ?: it }
            }
        }
        addAttachments(toAdd)
    }

    /** Captures notification input before any asynchronous work. */
    fun replyFromNotification(text: String) {
        text.trim().takeIf { it.isNotEmpty() }?.let { captured ->
            _state.update { it.copy(queuedReplies = it.queuedReplies + 1) }
            notificationReplies.trySend(captured)
        }
    }

    /** The user pressed send: steer when active, start when idle. */
    fun send() { sendText(_state.value.draft.trim()) }

    private fun sendText(text: String, fromNotification: Boolean = false): Job? {
        val s = _state.value
        val t = target
        // Read the user-chosen attachments before any suspend point; they cannot change while sending.
        // Notification replies stay text-only: staged chips are left in the composer for the next manual send.
        val staged = if (fromNotification) emptyList() else s.attachments
        if ((text.isEmpty() && staged.isEmpty()) || t == null || s.load != ChatLoad.Ready || preparingSend || s.pendingAttachments > 0) return null
        val run = s.run
        if (staged.isNotEmpty() && run != null) {
            // Steer is text-only: keep chips and draft, explain instead of silently dropping the send.
            _state.update { it.fail(ChatFailure.AttachmentRunActive) }
            return null
        }
        return when {
            run == null -> {
                preparingSend = true
                chatScope.launch {
                    try {
                        memory.newSessionFailure()?.let { failure ->
                            _state.update { it.fail(failure) }
                            return@launch
                        }
                        val session = readSessionId() ?: return@launch
                        // Base64 encode + serialize happen once, on the worker; the client posts the element as-is.
                        val plan = if (staged.isEmpty()) RunInputPlan.TextOnly
                            else withContext(workDispatcher) { planRunInput(text, staged) }
                        val input = when (plan) {
                            is RunInputPlan.WithParts -> plan.input
                            RunInputPlan.TextOnly -> JsonPrimitive(text)
                            RunInputPlan.TooLarge -> {
                                _state.update { it.fail(ChatFailure.AttachmentTooLarge) }
                                return@launch
                            }
                        }
                        // The previous run's terminal event already settled its turn; one reload
                        // shows what the server kept. A failed reload keeps the text unsent
                        // rather than anchoring the new turn to stale history.
                        if (!loadHistory(t, session)) return@launch
                        pendingTurn?.let { retirePending(keepMessages = it.outcome != Outcome.Answered) }
                        startRun(t, text, input, staged, clearDraft = !fromNotification, fromNotification = fromNotification)
                    } finally {
                        preparingSend = false
                    }
                }
            }
            run.runId == null -> null // still starting; the composer is disabled in this window
            else -> { steer(t, run.runId, text); null }
        }
    }

    private fun startRun(t: HermesTarget, text: String, input: JsonElement, staged: List<PendingAttachment> = emptyList(), clearDraft: Boolean = true, fromNotification: Boolean = false) {
        // The idempotency key tracks the send intent: same text + same staged attachments reuse it.
        val keySeed = if (staged.isEmpty()) text else text + "|" + staged.joinToString(",") { it.key.toString() }
        val key = pendingKey?.takeIf { it.first == keySeed }?.second ?: newKey()
        pendingKey = keySeed to key
        check(historyLoaded && pendingTurn == null)
        val message = ChatMessage(nextId++, MessageKind.User, text, attachments = staged.map { it.toSent() }, atMillis = System.currentTimeMillis())
        pendingTurn = PendingTurn(Anchor.Known(serverHistory.lastOrNull()?.key), messages = listOf(message))
        if (clearDraft) keptReplies.clear()
        _state.update {
            it.copy(draft = if (clearDraft) "" else it.draft, terminal = null, terminalFailure = null, finishedRunId = null, run = ActiveRun(null, now(), StatusLabel.Starting)).display()
        }
        val session = sessionId
        chatScope.launch {
            when (val r = runs.startRun(t, input, session, key)) {
                is HermesResult.Ok -> {
                    val id = r.value.runId
                    var applied = false
                    _state.update {
                        applied = it.run != null && it.run.runId == null
                        // Drop only the attachments this send carried; a text-only notification reply leaves staged chips.
                        if (applied) it.withRun { run -> run.copy(runId = id) }
                            .copy(attachments = it.attachments.filterNot { a -> staged.any { s -> s.key == a.key } }) else it
                    }
                    if (!applied) return@launch
                    // A kept reply that now started must not stay in the composer to be sent twice.
                    if (fromNotification && keptReplies.remove(text)) _state.update {
                        it.copy(draft = if (draftHas(it.draft, text)) dropDraftLine(it.draft, text) else it.draft)
                    }
                    pendingKey = null
                    persist(id)
                    followJob = chatScope.launch { collectRun(t, id, null) }
                }
                is HermesResult.Err -> {
                    var kept = false
                    _state.update {
                        // Only the run still waiting for its id can fail to start.
                        if (it.run == null || it.run.runId != null) it
                        else {
                            pendingTurn = null
                            kept = fromNotification && !draftHas(it.draft, text)
                            it.copy(run = null, terminalFailure = if (fromNotification) ChatFailure.ReplyKept else ChatFailure.Api(r.error), draft = if (draftHas(it.draft, text)) it.draft else joinDraft(text, it.draft)).fail(ChatFailure.Api(r.error))
                        }
                    }
                    if (kept) keptReplies.add(text)
                }
            }
        }
    }

    private fun steer(t: HermesTarget, id: String, text: String) {
        keptReplies.clear()
        _state.update { it.copy(draft = "") }
        chatScope.launch {
            // One steer at a time, in the order typed; a run that ended while this one waited gets nothing sent.
            val r = steerLock.withLock {
                if (isCurrent(id)) runs.steer(t, id, text) else null
            }
            if (state.value.load != ChatLoad.Ready) {
                restoreDraft(text, sameRun = false)
                return@launch
            }
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
        statusJob = chatScope.launch {
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
        chatScope.launch {
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
        followJob = chatScope.launch { collectRun(t, id, run.lastSeq) }
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
        statusJob = chatScope.launch {
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
                    followJob = chatScope.launch { collectRun(t, id, null) }
                }
                else updateRun(id) { it.withRun { run -> run.copy(status = StatusLabel.LiveUpdatesUnavailable) } }
            }
            is HermesResult.Err ->
                if (r.error == HermesError.NotFound) {
                    if (!historyLoaded) {
                        missingRunWaitingForHistory = id
                    } else {
                        finishMissingRun(id)
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
        chatScope.launch {
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
                (if (e.output.isNotEmpty()) setFinalText(s, e.output) else s).copy(terminal = e)
            }
            is RunEvent.Failed -> finish(id, e.pendingSteer) { it.copy(terminal = e).fail(ChatFailure.RunFailed(e.error)) }
            is RunEvent.Interrupted -> finish(id, e.pendingSteer) { it.copy(terminal = e).fail(ChatFailure.RunInterrupted(e.error)) }
            is RunEvent.Cancelled -> finish(id, e.pendingSteer) { it.copy(terminal = e).msg(MessageKind.Info, INFO_STOPPED) }
            is RunEvent.Unknown -> Unit
        }
    }

    private fun appendDelta(s: ChatState, delta: String): ChatState {
        val pending = pendingTurn ?: return s
        val id = s.run?.draftId
        return if (id == null) {
            val message = ChatMessage(nextId++, MessageKind.Assistant, delta, atMillis = System.currentTimeMillis())
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
