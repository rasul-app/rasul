package app.rasul

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.chat.Anchor
import app.rasul.chat.AttachLoad
import app.rasul.chat.AttachmentLoader
import app.rasul.chat.ChatFailure
import app.rasul.chat.ChatLoad
import app.rasul.chat.ChatViewModel
import app.rasul.chat.MessageKind
import app.rasul.chat.RunMemory
import app.rasul.chat.newChatRunMemory
import app.rasul.chat.StatusLabel
import app.rasul.chat.TargetLoader
import app.rasul.data.ApprovalChoice
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SecretCipher
import app.rasul.data.RunEvent
import app.rasul.data.RunFrame
import app.rasul.data.RunStatus
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import app.rasul.data.RunUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.rasul.data.PendingAttachment
import app.rasul.data.SentAttachment
import app.rasul.data.RunInputPlan
import app.rasul.data.RunsApi
import app.rasul.data.planRunInput
import app.rasul.data.StartedRun
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A RunsApi whose replies can be held and released, so the tests run on the test scheduler only (no IO threads). */
internal class FakeRuns : RunsApi {
    val calls = mutableListOf<String>()
    val startKeys = mutableListOf<String?>()
    val startInputs = mutableListOf<kotlinx.serialization.json.JsonElement>()
    var startN = 0
    var onStart: suspend (String) -> HermesResult<StartedRun> = { HermesResult.Ok(StartedRun("run_$startN")) }
    var onStatus: suspend (String) -> HermesResult<RunStatus> = { HermesResult.Ok(RunStatus("running", null, null)) }
    var onStop: suspend (String) -> HermesResult<Unit> = { HermesResult.Ok(Unit) }
    var onSteer: suspend (String, String) -> HermesResult<Unit> = { _, _ -> HermesResult.Ok(Unit) }
    var onApprove: suspend (String, String?) -> HermesResult<Unit> = { _, _ -> HermesResult.Ok(Unit) }

    override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> {
        startN++
        val text = (input as? JsonPrimitive)?.contentOrNull ?: input.toString()
        calls += "start $text session=$sessionId"
        startKeys += idempotencyKey
        startInputs += input
        return onStart(text)
    }

    /** The bridge file ids each send carried (empty for sends without files). */
    val startFiles = mutableListOf<List<String>>()

    override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?, files: List<String>): HermesResult<StartedRun> {
        startFiles += files
        return startRun(target, input, sessionId, idempotencyKey)
    }

    override suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus> {
        calls += "status $runId"
        return onStatus(runId)
    }

    override suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit> {
        calls += "stop $runId"
        return onStop(runId)
    }

    override suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit> {
        calls += "steer $runId $text"
        return onSteer(runId, text)
    }

    override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?): HermesResult<Unit> {
        calls += "approve $runId ${choice.wire} $requestId"
        return onApprove(runId, requestId)
    }

    val eventRequests = mutableListOf<Long?>()
    val eventFlows = mutableListOf<MutableSharedFlow<RunFrame>>()

    override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> {
        eventRequests += lastSeq
        return MutableSharedFlow<RunFrame>(replay = 64).also { eventFlows += it }
    }
}

/** Picker loads the tests can hold and release; records the per-photo budget the VM computed. */
internal class FakeAttachLoader : AttachmentLoader {
    var gate: CompletableDeferred<Unit>? = null

    /** Gates handed to successive loads, one each, before [gate] is used. */
    val gateQueue = ArrayDeque<CompletableDeferred<Unit>>()
    var nextPhotos: AttachLoad = AttachLoad.Ok(emptyList())
    var nextFile: AttachLoad = AttachLoad.Ok(emptyList())
    val photoBudgets = mutableListOf<Int>()
    val fitBudgets = mutableListOf<Int>()

    /** When set, [fit] waits here (completed by the test) so a session switch can land mid-fit. */
    var fitGate: CompletableDeferred<Unit>? = null

    override suspend fun loadPhotos(uris: List<Uri>, photoBudget: Int): AttachLoad {
        photoBudgets += photoBudget
        (gateQueue.removeFirstOrNull() ?: gate)?.await()
        return nextPhotos
    }

    override suspend fun fit(photo: PendingAttachment.Photo, budget: Int): PendingAttachment.Photo? {
        fitBudgets += budget
        fitGate?.await()
        return photo.copy(jpeg = ByteArray(minOf(photo.jpeg.size, budget)))
    }

    override suspend fun loadFile(uri: Uri): AttachLoad {
        gate?.await()
        return nextFile
    }
}

internal class FakeSessions : SessionsApi {
    val calls = mutableListOf<String>()
    var result: HermesResult<List<SessionMessageDto>> = HermesResult.Ok(emptyList())
    val queuedResults = mutableListOf<HermesResult<List<SessionMessageDto>>>()
    var historyGate: CompletableDeferred<Unit>? = null
    var historyRequested: CompletableDeferred<Unit>? = null
    var onMessages: (suspend () -> HermesResult<List<SessionMessageDto>>)? = null

    override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> {
        calls += sessionId
        historyRequested?.complete(Unit)
        onMessages?.let { return it() }
        val response = if (queuedResults.isNotEmpty()) queuedResults.removeAt(0) else result
        historyGate?.await()
        return response
    }
}

internal class FakeMemory(var active: String? = null, var cursor: Long? = null) : RunMemory {
    val writes = mutableListOf<String?>()
    val sessionWrites = mutableListOf<String>()

    /** When set, run-state reads and writes suspend until the gate is released. */
    var readGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var sessionFailure = false
    var storedSession: String? = "sess-1"
    var storedAnchor: Anchor = Anchor.Unknown

    /** Run triples stored under sessions other than the current one (session-keyed persistence). */
    private val otherRuns = mutableMapOf<String, Triple<String?, Long?, Anchor>>()

    /** The persisted run under [sessionId]; `active`/`cursor`/`storedAnchor` hold the current session's slot. */
    fun runFor(sessionId: String?): String? = if (sessionId == storedSession) active else otherRuns[sessionId]?.first

    override suspend fun anchor() = anchor(storedSession.orEmpty())
    override suspend fun anchor(sessionId: String): Anchor =
        if (sessionId == storedSession) storedAnchor else otherRuns[sessionId]?.third ?: Anchor.Unknown
    override suspend fun currentSessionId() = storedSession
    override suspend fun sessionId(): String {
        check(!sessionFailure)
        return storedSession ?: "sess-1".also { storedSession = it }
    }
    override suspend fun activeRun(): String? = activeRun(storedSession.orEmpty())
    override suspend fun activeRun(sessionId: String): String? {
        readGate?.await()
        return if (sessionId == storedSession) active else otherRuns[sessionId]?.first
    }
    override suspend fun activeRunSessions(): Set<String> {
        readGate?.await()
        val sessions = otherRuns.filterValues { it.first != null }.keys.toMutableSet()
        if (active != null) storedSession?.let { sessions += it }
        return sessions
    }
    override suspend fun lastEventId() = lastEventId(storedSession.orEmpty())
    override suspend fun lastEventId(sessionId: String): Long? =
        if (sessionId == storedSession) cursor else otherRuns[sessionId]?.second
    override suspend fun setSessionId(sessionId: String) {
        storedSession = sessionId
        sessionWrites += sessionId
    }
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) {
        writeGate?.await()
        active = runId
        storedAnchor = if (runId == null) Anchor.Unknown else anchor
        cursor = if (runId == null) null else lastEventId
        writes += runId
    }
    override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor) {
        writeGate?.await()
        if (sessionId == storedSession) {
            active = runId
            storedAnchor = if (runId == null) Anchor.Unknown else anchor
            cursor = if (runId == null) null else lastEventId
        } else {
            otherRuns[sessionId] = Triple(runId, lastEventId, if (runId == null) Anchor.Unknown else anchor)
        }
        writes += runId
        if (storedSession == null) {
            storedSession = sessionId
            sessionWrites += sessionId
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sched = TestCoroutineScheduler()
    private val fake = FakeRuns()
    private val sessions = FakeSessions()
    private val memory = FakeMemory()
    private val fakeAttach = FakeAttachLoader()
    private val followAfter = mutableListOf<Long?>()
    private val flows = mutableListOf<MutableSharedFlow<RunUpdate>>()
    private var keyN = 0

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun idle() = sched.advanceUntilIdle()

    private val writer = app.rasul.chat.OrderedWriter(CoroutineScope(StandardTestDispatcher(sched)))

    private fun build(mem: RunMemory = memory, loader: TargetLoader? = null): ChatViewModel {
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            loader ?: TargetLoader { HermesResult.Ok(t) }, fake, sessions, mem, writer, now = { 0L },
            follow = { _, _, after -> followAfter += after; MutableSharedFlow<RunUpdate>(replay = 64).also { flows += it } },
            newKey = { "key${++keyN}" },
            workDispatcher = StandardTestDispatcher(sched),
            attachLoader = fakeAttach,
        )
    }

    /** The last content part of the input element the last send posted. */
    private fun lastContentPart(): JsonObject {
        val message = (fake.startInputs.last() as JsonArray).single() as JsonObject
        return (message["content"] as JsonArray).last() as JsonObject
    }

    private fun vm(): ChatViewModel = build().also { idle() }

    private fun buildUsingFrameFollow(mem: RunMemory = memory): ChatViewModel {
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(t) }, fake, sessions, mem, writer,
            now = { 0L }, newKey = { "key${++keyN}" },
        )
    }

    private fun user(text: String) = SessionMessageDto(role = "user", content = JsonPrimitive(text))
    private fun assistant(text: String) = SessionMessageDto(role = "assistant", content = JsonPrimitive(text))

    @Test fun deletingSessionDropsStoredRunSoReopenedChatCanSend() {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val id = "connection"
            val store = ConnectionStore(
                PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("deleted-session.preferences_pb") },
                object : SecretCipher {
                    override fun encrypt(plain: String) = plain
                    override fun decrypt(token: String) = token
                },
            )
            val deletedSession = runBlocking {
                store.sessionId(id, null).also { session ->
                    store.setActiveRun(id, null, session, "run_old", 8L, "old-anchor")
                    store.clearSessionPointers(session)
                }
            }
            val persistedMemory = object : RunMemory {
                override suspend fun sessionId() = runBlocking { store.sessionId(id, null) }
                override suspend fun activeRun() = runBlocking { store.activeRun(id, null, sessionId()) }
                override suspend fun lastEventId() = runBlocking { store.lastEventId(id, null, sessionId()) }
                override suspend fun anchor(): Anchor = runBlocking {
                    store.runAnchor(id, null, sessionId())?.let { Anchor.Known(it) } ?: Anchor.Unknown
                }
                override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) {
                    runBlocking {
                        store.setActiveRun(
                            id, null, sessionId(), runId, lastEventId,
                            (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null,
                        )
                    }
                }
            }
            val reopened = build(persistedMemory)
            idle()
            val newSession = runBlocking { store.sessionId(id, null) }
            assertTrue(deletedSession != newSession)
            assertNull(reopened.state.value.run)
            assertTrue(fake.calls.none { it == "status run_old" })
            assertEquals(listOf(newSession), sessions.calls)

            reopened.onDraft("hello")
            reopened.send()
            idle()
            assertEquals(1, fake.startN)
            assertTrue(fake.calls.contains("start hello session=$newSession"))
            assertEquals("run_1", reopened.state.value.run?.runId)
        } finally {
            storeScope.cancel()
        }
    }

    private fun emit(u: RunUpdate) {
        flows.last().tryEmit(u)
        idle()
    }

    private fun ev(e: RunEvent, seq: Long? = null) = RunUpdate.Event(e, seq)

    private fun ChatViewModel.type(s: String) {
        onDraft(s)
        send()
        idle()
    }

    private fun ChatViewModel.startWithRun(): ChatViewModel {
        type("x")
        assertEquals("run_1", state.value.run?.runId)
        return this
    }

    private fun request(id: String?) = ev(RunEvent.ApprovalRequest(id, "cmd $id", null, listOf("once", "deny")))

    private fun errors(v: ChatViewModel) = v.state.value.messages.filter { it.kind == MessageKind.Error }

    @Test fun restoredBridgeChatWaitsForProfilesThenRestoresHistoryAndControls() = kotlinx.coroutines.runBlocking {
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        val dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = snapshots
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                snapshots.value = transform(snapshots.value)
                return snapshots.value
            }
        }
        val cipher = object : app.rasul.data.SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val store = app.rasul.bridge.BridgeStore(dataStore, cipher)
        val transports = mutableListOf<app.rasul.bridge.BridgeTransport>()
        val clients = app.rasul.bridge.BridgeClients(store, scope, { _, urls, _ ->
            app.rasul.bridge.BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports += it }
        }, {})
        val profiles = CompletableDeferred<app.rasul.bridge.ProfileSnapshot>()
        val entered = CompletableDeferred<Unit>()
        val repository = app.rasul.bridge.BridgeBotsRepository(store, clients) { object : app.rasul.bridge.ProfilesApi {
            override suspend fun list(): app.rasul.bridge.ProfileSnapshot { entered.complete(Unit); return profiles.await() }
            override fun events(): Flow<app.rasul.bridge.ProfileSnapshot> = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
        } }
        val saved = app.rasul.bridge.SavedBridge("one", "PC", listOf(java.net.URI("wss", java.util.UUID.randomUUID().toString(), null, null).toString()), "", "", "")
        val views = ViewModelStore()
        var following: kotlinx.coroutines.Job? = null
        try {
            store.save(saved)
            memory.active = "run_9"
            sessions.result = HermesResult.Ok(listOf(user("saved question"), assistant("saved answer")))
            val approval = RunEvent.ApprovalRequest("A", "cmd A", null, listOf("once", "deny"))
            fake.onStatus = { HermesResult.Ok(RunStatus("waiting_for_approval", null, null, approval = approval)) }
            val v = ChatViewModel(app.rasul.ui.bridgeChatTargetLoader(repository, saved.bridgeId, "default"), fake, sessions, memory, writer,
                follow = { _, _, after -> followAfter += after; MutableSharedFlow<RunUpdate>(replay = 64).also { flows += it } })
            views.put("chat", v)
            sched.runCurrent()
            assertEquals(app.rasul.chat.ChatLoad.Loading, v.state.value.load)
            assertTrue(sessions.calls.isEmpty())
            assertTrue(fake.calls.isEmpty())
            following = scope.launch { repository.follow() }
            kotlinx.coroutines.withTimeout(5000) { entered.await() }
            sched.runCurrent()
            assertEquals(app.rasul.chat.ChatLoad.Loading, v.state.value.load)
            profiles.complete(app.rasul.bridge.ProfileSnapshot(1, listOf(app.rasul.bridge.BridgeProfile("default", "default", "Default", true, true))))
            kotlinx.coroutines.withTimeout(5000) { repository.state.first { it.groups.singleOrNull()?.loading == false } }
            // The loader reads saved metadata on IO before continuing on the test dispatcher.
            val loaded = CompletableDeferred<Unit>()
            sessions.historyRequested = loaded
            kotlinx.coroutines.withTimeout(5000) {
                while (!loaded.isCompleted) { sched.runCurrent(); kotlinx.coroutines.yield() }
            }
            idle()
            assertEquals(app.rasul.chat.ChatLoad.Ready, v.state.value.load)
            assertEquals(listOf("saved question", "saved answer"), v.state.value.messages.map { it.text })
            assertEquals("run_9", v.state.value.run?.runId)
            assertEquals("A", v.state.value.approval?.requestId)
            v.answerApproval("A", ApprovalChoice.Once)
            idle()
            v.type("continue")
            v.stop()
            idle()
            assertTrue(fake.calls.contains("approve run_9 once A"))
            assertTrue(fake.calls.contains("steer run_9 continue"))
            assertTrue(fake.calls.contains("stop run_9"))
            assertEquals(listOf("sess-1"), sessions.calls)
        } finally {
            views.clear()
            following?.cancel()
            following?.join()
            transports.forEach { it.close() }
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        }
    }

    @Test fun idleSendStartsARunAndSecondSendWhileRunningSteers() {
        val v = vm()
        v.type("first")
        v.type("also use python")
        assertEquals(listOf("start first session=sess-1", "steer run_1 also use python"), fake.calls)
        assertEquals(listOf(MessageKind.User, MessageKind.UserSteer), v.state.value.messages.map { it.kind })
        assertEquals("", v.state.value.draft)
    }

    @Test fun ordinaryChatKeepsTreating404HistoryAsEmpty() {
        sessions.result = HermesResult.Err(HermesError.NotFound)
        val ordinary = vm()
        assertEquals(ChatLoad.Ready, ordinary.state.value.load)
        ordinary.onDraft("hello")
        ordinary.send()
        idle()
        assertEquals(1, fake.startN)
    }

    @Test fun newChatPersistsItsSessionAndActiveRunForReopen() {
        memory.storedSession = "old-session"
        memory.active = null
        val newChat = build(newChatRunMemory(memory, "rasul-fixed"))
        idle()
        assertNull(newChat.state.value.run)
        assertTrue(memory.sessionWrites.isEmpty())
        assertTrue(memory.writes.isEmpty())

        newChat.type("first")
        val startedSession = sessions.calls.first()
        assertEquals("rasul-fixed", startedSession)
        assertTrue(startedSession != "old-session")
        // The run is stored under the new session's own keys; the pointer only moves by user intent.
        assertEquals("old-session", memory.storedSession)
        assertEquals("run_1", memory.runFor("rasul-fixed"))
        assertNull(memory.active)
        assertEquals(listOf("run_1"), memory.writes)
        assertTrue(memory.sessionWrites.isEmpty())

        val reopened = build(newChatRunMemory(memory, "rasul-fixed"))
        idle()
        assertEquals(startedSession, sessions.calls.last())
        assertEquals("run_1", reopened.state.value.run?.runId)
        reopened.type("continue")
        assertTrue(fake.calls.contains("steer run_1 continue"))
        reopened.stop()
        idle()
        assertTrue(fake.calls.contains("stop run_1"))
        assertEquals("old-session", memory.storedSession)
    }

    @Test fun firstNewChatSendKeepsPreviousActiveRunReachable() {
        memory.active = "old-run"
        val newChat = build(newChatRunMemory(memory, "rasul-fixed"))
        idle()
        newChat.type("first")
        assertEquals("old-run", memory.active)
        assertEquals("sess-1", memory.storedSession)
        assertEquals(0, fake.startN)
        assertEquals(ChatFailure.PreviousRunActive, errors(newChat).last().error)
    }

    @Test fun suspendedNewChatCheckCapturesDraftAndKeepsPreviousRun() {
        val newChat = build(newChatRunMemory(memory, "rasul-fixed"))
        idle()
        memory.readGate = CompletableDeferred()
        newChat.onDraft("first")
        newChat.send()
        sched.runCurrent()
        newChat.onDraft("changed")
        memory.active = "old-run"
        memory.readGate!!.complete(Unit)
        idle()
        assertEquals("first", newChat.state.value.draft)
        assertEquals("old-run", memory.active)
        assertEquals(0, fake.startN)
        assertEquals(ChatFailure.PreviousRunActive, errors(newChat).last().error)
    }

    @Test fun leavingAnUnsentNewChatPreservesPreviousSessionBreadcrumbs() {
        memory.storedSession = "old-session"
        memory.active = "old-run"
        memory.cursor = 17L
        memory.storedAnchor = Anchor.Known("old-anchor")
        val newChat = build(newChatRunMemory(memory, "rasul-fixed"))
        idle()
        assertNull(newChat.state.value.run)
        assertTrue(memory.sessionWrites.isEmpty())
        assertTrue(memory.writes.isEmpty())
        assertEquals("old-session", memory.storedSession)
        assertEquals("old-run", memory.active)
        assertEquals(17L, memory.cursor)
        assertEquals(Anchor.Known("old-anchor"), memory.storedAnchor)

        val reopened = build(memory)
        idle()
        assertEquals("old-session", sessions.calls.last())
        assertEquals("old-run", reopened.state.value.run?.runId)
    }

    @Test fun afterTerminalEventTheNextSendStartsANewRun() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("Hel")))
        emit(ev(RunEvent.MessageDelta("lo")))
        sessions.result = HermesResult.Ok(listOf(user("x"), assistant("Hello")))
        emit(ev(RunEvent.Completed("Hello")))
        assertNull(v.state.value.run)
        assertEquals("Hello", v.state.value.messages.last().text)
        assertEquals(1, v.state.value.messages.count { it.kind == MessageKind.Assistant })
        v.type("second")
        assertEquals(2, fake.calls.count { it.startsWith("start") })
    }

    @Test fun approvalRequestShowsSheetStateAndAnswerPostsChoice() {
        val v = vm().startWithRun()
        emit(request("q1"))
        assertEquals(StatusLabel.WaitingApproval, v.state.value.run?.status)
        v.answerApproval("q1", ApprovalChoice.Deny)
        idle()
        assertNull(v.state.value.approval)
        assertEquals("approve run_1 deny q1", fake.calls.last())
    }

    @Test fun lostStreamKeepsTheRunStopAndSteerAndNeverStartsASecondRun() {
        val v = vm().startWithRun()
        emit(RunUpdate.Reconnecting(1, 5))
        assertEquals(StatusLabel.Reconnecting(1, 5), v.state.value.run?.status)
        emit(RunUpdate.Lost(HermesError.Unreachable("down")))
        assertEquals(StatusLabel.Lost, v.state.value.run?.status)
        assertTrue(errors(v).last().error is ChatFailure.Api)
        v.type("still there?")
        assertEquals(1, fake.calls.count { it.startsWith("start") })
        assertTrue(fake.calls.contains("steer run_1 still there?"))
        v.stop()
        idle()
        assertTrue(fake.calls.contains("stop run_1"))
    }

    @Test fun manualReconnectResumesAfterTheLastSeenEventAndATerminalEventClearsTheRun() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("a"), seq = 7))
        emit(RunUpdate.Lost(HermesError.Unreachable("down")))
        v.reconnect()
        idle()
        assertEquals(listOf<Long?>(null, 7L), followAfter)
        assertTrue(v.state.value.run?.status != StatusLabel.Lost)
        emit(ev(RunEvent.Completed("done"), seq = 8))
        assertNull(v.state.value.run)
    }

    @Test fun lastSeqBelongsToItsRunAndStartsFreshForTheNextRun() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("a"), seq = 9))
        sessions.result = HermesResult.Ok(listOf(user("x"), assistant("a")))
        emit(ev(RunEvent.Completed("a"), seq = 10))
        v.type("again")
        assertEquals(listOf<Long?>(null, null), followAfter)
        assertNull(v.state.value.run?.lastSeq)
    }

    @Test fun events404ButStatusTerminalFinishesTheRunLikeAStream() {
        val v = vm().startWithRun()
        sessions.queuedResults += HermesResult.Ok(listOf(user("x"), assistant("all done")))
        fake.onStatus = { HermesResult.Ok(RunStatus("completed", "all done", null)) }
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertNull(v.state.value.run)
        assertEquals("all done", v.state.value.messages.last().text)
        assertTrue(fake.calls.contains("status run_1"))
    }

    @Test fun events404WhileStatusSaysRunningKeepsTheRunAndControlsAndRefreshWorks() {
        val v = vm().startWithRun()
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertEquals(StatusLabel.LiveUpdatesUnavailable, v.state.value.run?.status)
        v.type("more")
        assertTrue(fake.calls.contains("steer run_1 more"))
        v.stop()
        idle()
        assertTrue(fake.calls.contains("stop run_1"))
        assertEquals(1, fake.calls.count { it.startsWith("start") })
        assertEquals(StatusLabel.LiveUpdatesUnavailable, v.state.value.run?.status)
        val gets = fake.calls.count { it == "status run_1" }
        v.refresh()
        idle()
        assertTrue(fake.calls.count { it == "status run_1" } > gets)
        assertEquals(StatusLabel.LiveUpdatesUnavailable, v.state.value.run?.status)
        fake.onStatus = { HermesResult.Ok(RunStatus("failed", null, "boom")) }
        v.refresh()
        idle()
        assertNull(v.state.value.run)
        assertEquals(ChatFailure.RunFailed("boom"), errors(v).last().error)
    }

    @Test fun events404AndStatus404MeansTheRunIsGone() {
        val v = vm().startWithRun()
        fake.onStatus = { HermesResult.Err(HermesError.NotFound) }
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertNull(v.state.value.run)
        assertEquals(ChatFailure.Api(HermesError.NotFound), errors(v).last().error)
    }

    @Test fun events404ThenStatusServerErrorKeepsTheRunAsLost() {
        val v = vm().startWithRun()
        fake.onStatus = { HermesResult.Err(HermesError.Server(500)) }
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertEquals(StatusLabel.Lost, v.state.value.run?.status)
        assertEquals("run_1", v.state.value.run?.runId)
        assertEquals(ChatFailure.Api(HermesError.Server(500)), errors(v).last().error)
    }

    @Test fun afterARunIsGoneTheNextRunsDeltasGoToANewBubble() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("old")))
        // The completed event settles the turn; the post-event reload serves its persisted rows.
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "x"), row("aA", "assistant", "old")))
        emit(ev(RunEvent.Completed("old")))
        assertNull(v.state.value.run)
        display(v, "x", "old")
        v.type("again")
        display(v, "x", "old", "again")
        assertEquals(2, fake.startN)
        assertEquals("", v.state.value.draft)
        sessions.result = HermesResult.Ok(listOf(
            row("qA", "user", "x"), row("aA", "assistant", "old"),
            row("qB", "user", "again"), row("aB", "assistant", "new")))
        emit(ev(RunEvent.MessageDelta("new")))
        emit(ev(RunEvent.Completed("new")))
        assertEquals(listOf("old", "new"), v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text })
    }

    @Test fun staleTapOnApprovalASendsNothingForB() {
        val v = vm().startWithRun()
        emit(request("A"))
        emit(request("B"))
        assertEquals(2, v.state.value.approvals.size)
        v.answerApproval("A", ApprovalChoice.Once)
        idle()
        assertEquals("B", v.state.value.approval?.requestId)
        assertEquals("approve run_1 once A", fake.calls.last())
        val n = fake.calls.size
        v.answerApproval("A", ApprovalChoice.Once) // A is no longer pending
        v.answerApproval("nope", ApprovalChoice.Deny)
        idle()
        assertEquals(n, fake.calls.size)
    }

    @Test fun respondedEventRemovesOnlyItsOwnRequest() {
        val v = vm().startWithRun()
        emit(request("A"))
        emit(request("B"))
        emit(ev(RunEvent.ApprovalResponded("once", "B")))
        assertEquals("A", v.state.value.approval?.requestId)
        assertEquals(StatusLabel.WaitingApproval, v.state.value.run?.status)
        emit(ev(RunEvent.ApprovalResponded("once", "A")))
        assertNull(v.state.value.approval)
        assertEquals(StatusLabel.Thinking, v.state.value.run?.status)
    }

    @Test fun failedStopGoesBackToARetryableStateWithASpecificError() {
        val v = vm().startWithRun()
        fake.onStop = { HermesResult.Err(HermesError.Server(500)) }
        v.stop()
        idle()
        assertEquals(ChatFailure.Api(HermesError.Server(500)), errors(v).last().error)
        assertFalse(v.state.value.run!!.stopRequested)
        fake.onStop = { HermesResult.Ok(Unit) }
        v.stop()
        idle()
        assertEquals(2, fake.calls.count { it == "stop run_1" })
        assertEquals(StatusLabel.Stopping, v.state.value.run?.label)
    }

    // ---- more run and refresh cases ----

    @Test fun overlappingRefreshIsSingleFlight() {
        val v = vm().startWithRun()
        emit(RunUpdate.Lost(HermesError.NotFound))
        val gate = CompletableDeferred<HermesResult<RunStatus>>()
        fake.onStatus = { gate.await() }
        val before = fake.calls.count { it == "status run_1" }
        v.refresh()
        v.refresh()
        idle()
        assertEquals(before + 1, fake.calls.count { it == "status run_1" })
        sessions.queuedResults += HermesResult.Ok(listOf(user("x"), assistant("fin")))
        gate.complete(HermesResult.Ok(RunStatus("completed", "fin", null)))
        idle()
        assertNull(v.state.value.run)
        assertEquals("fin", v.state.value.messages.last().text)
    }

    @Test fun staleStopSteerAndApproveResultsAfterANewRunTouchNothing() {
        val v = vm().startWithRun()
        emit(request("A"))
        val stopGate = CompletableDeferred<HermesResult<Unit>>()
        val steerGate = CompletableDeferred<HermesResult<Unit>>()
        val approveGate = CompletableDeferred<HermesResult<Unit>>()
        fake.onStop = { stopGate.await() }
        fake.onSteer = { _, _ -> steerGate.await() }
        fake.onApprove = { _, _ -> approveGate.await() }
        v.stop()
        v.type("more")
        v.answerApproval("A", ApprovalChoice.Once)
        idle()
        assertTrue(v.state.value.run!!.stopRequested)
        // run_1 ends, run_2 starts and gets its own approval A.
        sessions.result = HermesResult.Ok(listOf(user("x"), assistant("done")))
        emit(ev(RunEvent.Completed("done")))
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.type("next")
        assertEquals("run_2", v.state.value.run?.runId)
        emit(request("A"))
        val msgs = v.state.value.messages.size
        stopGate.complete(HermesResult.Err(HermesError.Server(500)))
        steerGate.complete(HermesResult.Err(HermesError.Server(409)))
        approveGate.complete(HermesResult.Ok(Unit))
        idle()
        val s = v.state.value
        assertEquals("run_2", s.run?.runId)
        assertFalse(s.run!!.stopRequested)
        assertEquals("A", s.approval?.requestId) // run_1's late answer did not remove run_2's A
        assertTrue(s.answering.isEmpty())
        assertEquals(msgs, s.messages.size) // no stale error / info lines
        assertEquals("more", s.draft) // the steer text is not lost
        assertFalse(fake.calls.contains("status run_2")) // the stale 409 did not trigger a status check for the new run
    }

    @Test fun doubleTapOnApproveSendsOneRequestAndDisablesTheButtons() {
        val v = vm().startWithRun()
        emit(request("A"))
        val gate = CompletableDeferred<HermesResult<Unit>>()
        fake.onApprove = { _, _ -> gate.await() }
        v.answerApproval("A", ApprovalChoice.Once)
        v.answerApproval("A", ApprovalChoice.Once)
        v.answerApproval("A", ApprovalChoice.Deny)
        idle()
        assertEquals(1, fake.calls.count { it.startsWith("approve") })
        assertEquals(setOf("A"), v.state.value.answering)
        gate.complete(HermesResult.Ok(Unit))
        idle()
        assertNull(v.state.value.approval)
        assertTrue(v.state.value.answering.isEmpty())
    }

    @Test fun approvalAnswer409RemovesItAndSaysSo() {
        val v = vm().startWithRun()
        emit(request("A"))
        fake.onApprove = { _, _ -> HermesResult.Err(HermesError.Server(409)) }
        v.answerApproval("A", ApprovalChoice.Once)
        idle()
        assertNull(v.state.value.approval)
        assertTrue(v.state.value.answering.isEmpty())
        val last = v.state.value.messages.last()
        assertEquals(MessageKind.Info, last.kind)
        assertEquals(ChatViewModel.INFO_APPROVAL_GONE, last.text)
        assertTrue(errors(v).isEmpty())
    }

    @Test fun approvalFromStatusIsMergedAddOnly() {
        val v = vm().startWithRun()
        val waiting = RunStatus("waiting_for_approval", null, null, approval = RunEvent.ApprovalRequest("A", "rm x", null, listOf("once", "deny")))
        fake.onStatus = { HermesResult.Ok(waiting) }
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertEquals("A", v.state.value.approval?.requestId)
        assertEquals(StatusLabel.LiveUpdatesUnavailable, v.state.value.run?.status) // Refresh stays on the card
        v.answerApproval("A", ApprovalChoice.Once)
        idle()
        assertNull(v.state.value.approval)
        // A later status without the approval, then one with it twice: never removed by omission, never duplicated.
        emit(request("B"))
        fake.onStatus = { HermesResult.Ok(RunStatus("running", null, null)) }
        v.refresh()
        idle()
        assertEquals("B", v.state.value.approval?.requestId)
        fake.onStatus = { HermesResult.Ok(RunStatus("waiting_for_approval", null, null, approval = RunEvent.ApprovalRequest("B", "cmd B", null, emptyList()))) }
        v.refresh()
        idle()
        assertEquals(1, v.state.value.approvals.size)
    }

    @Test fun retryAfterAFailedStartReusesTheIdempotencyKey() {
        val v = vm()
        fake.onStart = { HermesResult.Err(HermesError.Unreachable("lost reply")) }
        v.type("do it")
        assertNull(v.state.value.run)
        assertEquals("do it", v.state.value.draft)
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.send()
        idle()
        assertEquals(listOf<String?>("key1", "key1"), fake.startKeys)
        // The next message is a new request and gets a new key.
        sessions.result = HermesResult.Ok(listOf(user("do it"), assistant("ok")))
        emit(ev(RunEvent.Completed("ok")))
        v.type("another")
        assertEquals(listOf<String?>("key1", "key1", "key2"), fake.startKeys)
    }

    @Test fun pendingSteerOnTheTerminalEventGoesBackToTheComposer() {
        val v = vm().startWithRun()
        v.onDraft("typing")
        emit(ev(RunEvent.Completed("done", pendingSteer = "late one")))
        assertNull(v.state.value.run)
        assertEquals("late one\ntyping", v.state.value.draft)
        assertEquals(ChatViewModel.INFO_STEER_RETURNED, v.state.value.messages.last().text)
    }

    @Test fun pendingSteerOnATerminalStatusGoesBackToo() {
        val v = vm().startWithRun()
        fake.onStatus = { HermesResult.Ok(RunStatus("cancelled", null, null, pendingSteer = "x2")) }
        emit(RunUpdate.Lost(HermesError.NotFound))
        assertEquals("x2", v.state.value.draft)
    }

    @Test fun steer409WhileWaitingForApprovalKeepsTheRunAndExplainsIt() {
        val v = vm().startWithRun()
        emit(request("A"))
        fake.onSteer = { _, _ -> HermesResult.Err(HermesError.Server(409)) }
        fake.onStatus = { HermesResult.Ok(RunStatus("waiting_for_approval", null, null)) }
        v.type("hello?")
        assertEquals("run_1", v.state.value.run?.runId)
        assertEquals("A", v.state.value.approval?.requestId)
        assertEquals("hello?", v.state.value.draft)
        assertEquals(ChatFailure.SteerRejected, errors(v).last().error)
        assertEquals(StatusLabel.WaitingApproval, v.state.value.run?.status)
        assertTrue(v.state.value.run!!.status != StatusLabel.LiveUpdatesUnavailable)
    }

    @Test fun steer409WithATerminalStatusFinishesTheRunSoTheNextSendStartsANewOne() {
        val v = vm().startWithRun()
        sessions.result = HermesResult.Ok(listOf(user("x"), assistant("done")))
        fake.onSteer = { _, _ -> HermesResult.Err(HermesError.Server(409)) }
        fake.onStatus = { HermesResult.Ok(RunStatus("completed", "done", null)) }
        v.type("one more")
        assertNull(v.state.value.run)
        assertEquals("one more", v.state.value.draft)
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.send()
        idle()
        assertEquals("run_2", v.state.value.run?.runId)
    }

    @Test fun failedSteerKeepsTypedTextAndJoinsItWithNewlyTypedText() {
        val v = vm().startWithRun()
        val gate = CompletableDeferred<HermesResult<Unit>>()
        fake.onSteer = { _, _ -> gate.await() }
        v.type("first thought")
        v.onDraft("second thought")
        gate.complete(HermesResult.Err(HermesError.Server(500)))
        idle()
        assertEquals("first thought\nsecond thought", v.state.value.draft)
        assertEquals(ChatFailure.Api(HermesError.Server(500)), errors(v).last().error)
    }

    @Test fun failedStartKeepsTypedTextAndJoinsItWithNewlyTypedText() {
        val v = vm()
        val gate = CompletableDeferred<HermesResult<StartedRun>>()
        fake.onStart = { gate.await() }
        v.type("question")
        v.onDraft("and more")
        gate.complete(HermesResult.Err(HermesError.Server(500)))
        idle()
        assertEquals("question\nand more", v.state.value.draft)
    }

    @Test fun steersAreSentOneAtATimeInTheOrderTyped() {
        val v = vm().startWithRun()
        val gate = CompletableDeferred<HermesResult<Unit>>()
        fake.onSteer = { _, text -> if (text == "a") gate.await() else HermesResult.Ok(Unit) }
        v.type("a")
        v.type("b")
        assertEquals(listOf("steer run_1 a"), fake.calls.filter { it.startsWith("steer") })
        gate.complete(HermesResult.Ok(Unit))
        idle()
        assertEquals(listOf("steer run_1 a", "steer run_1 b"), fake.calls.filter { it.startsWith("steer") })
    }

    @Test fun anAcceptedStopKeepsTheStoppingLabelThroughLaterProgress() {
        val v = vm().startWithRun()
        v.stop()
        idle()
        assertEquals(StatusLabel.Stopping, v.state.value.run?.label)
        emit(ev(RunEvent.ToolStarted("bash", null)))
        emit(ev(RunEvent.MessageDelta("x")))
        assertEquals(StatusLabel.Stopping, v.state.value.run?.label)
        assertTrue(v.state.value.run!!.stopRequested)
        val n = fake.calls.count { it == "stop run_1" }
        v.stop()
        idle()
        assertEquals(n, fake.calls.count { it == "stop run_1" })
        emit(ev(RunEvent.Cancelled()))
        assertNull(v.state.value.run)
    }

    @Test fun theLabelIsWaitingForApprovalWhileOneIsOpenEvenAfterAReconnect() {
        val v = vm().startWithRun()
        emit(request("A"))
        emit(RunUpdate.Reconnecting(1, 5))
        emit(ev(RunEvent.ToolCompleted("bash", null, false, null)))
        assertEquals(StatusLabel.WaitingApproval, v.state.value.run?.label)
    }

    @Test fun anIdlessRespondedEventRemovesNothingAndOwnAnswersRemoveOneEach() {
        val v = vm().startWithRun()
        emit(request(null))
        emit(request(null))
        assertEquals(2, v.state.value.approvals.size)
        emit(ev(RunEvent.ApprovalResponded("once", null))) // another client's answer: cannot be matched, so nothing leaves
        assertEquals(2, v.state.value.approvals.size)
        v.answerApproval(null, ApprovalChoice.Once)
        idle()
        assertEquals(1, v.state.value.approvals.size)
        emit(ev(RunEvent.ApprovalResponded("once", null))) // the echo of our answer: still removes nothing
        assertEquals(1, v.state.value.approvals.size)
        v.answerApproval(null, ApprovalChoice.Once)
        idle()
        assertEquals(0, v.state.value.approvals.size)
    }

    @Test fun eachSegmentAfterAToolCallIsItsOwnBubbleAndCompletedKeepsEarlierOnes() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("First part")))
        emit(ev(RunEvent.ToolStarted("bash", null)))
        emit(ev(RunEvent.MessageDelta("Second ")))
        emit(ev(RunEvent.MessageDelta("part")))
        // Hold the post-completion reload: the streamed segments are checked while the local
        // rows are still shown, then the served transcript replaces them with the same rows.
        val gate = CompletableDeferred<Unit>()
        sessions.historyGate = gate
        sessions.result = HermesResult.Ok(listOf(
            row("u1", "user", "x"), row("a1", "assistant", "First part"), row("a2", "assistant", "Second part")))
        emit(ev(RunEvent.Completed("Second part")))
        assertEquals(listOf("First part", "Second part"), v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text })
        gate.complete(Unit)
        sessions.historyGate = null
        idle()
        assertEquals(listOf("First part", "Second part"), v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text })
    }

    @Test fun completedAfterAToolCallWithNothingStreamedAddsTheFinalBubbleAfterTheEarlierOnes() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.MessageDelta("Working on it")))
        emit(ev(RunEvent.ToolStarted("bash", null)))
        val gate = CompletableDeferred<Unit>()
        sessions.historyGate = gate
        sessions.result = HermesResult.Ok(listOf(
            row("u1", "user", "x"), row("a1", "assistant", "Working on it"), row("a2", "assistant", "All done")))
        emit(ev(RunEvent.Completed("All done")))
        assertEquals(listOf("Working on it", "All done"), v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text })
        gate.complete(Unit)
        sessions.historyGate = null
        idle()
        assertEquals(listOf("Working on it", "All done"), v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text })
    }

    @Test fun reopeningTheChatWithAStoredRunReattachesAndDeliversEventZero() {
        memory.active = "run_9"
        val v = vm()
        assertEquals("run_9", v.state.value.run?.runId)
        assertEquals(listOf("status run_9"), fake.calls)
        assertEquals(listOf<Long?>(null), followAfter) // nothing seen yet: event 0 must still be delivered
        emit(ev(RunEvent.MessageDelta("first"), seq = 0))
        assertEquals(0L, v.state.value.run?.lastSeq)
        assertEquals("first", v.state.value.messages.last().text)
        sessions.result = HermesResult.Ok(listOf(assistant("welcome back")))
        emit(ev(RunEvent.Completed("welcome back"), seq = 3))
        assertNull(v.state.value.run)
        assertEquals("welcome back", v.state.value.messages.last().text)
        assertNull(memory.active)
    }

    @Test fun reopeningLoadsHistoryReplaysFullAnswerAndDropsDuplicateFrames() {
        memory.active = "run_9"
        memory.cursor = 5L
        sessions.result = HermesResult.Ok(listOf(user("question")))
        val v = buildUsingFrameFollow().also { idle() }

        assertEquals(listOf("sess-1"), sessions.calls)
        display(v, "question")
        assertEquals(listOf<Long?>(null), fake.eventRequests)

        val replay = fake.eventFlows.last()
        replay.tryEmit(RunFrame(0L, RunEvent.MessageDelta("ans")))
        replay.tryEmit(RunFrame(1L, RunEvent.MessageDelta("wer")))
        replay.tryEmit(RunFrame(1L, RunEvent.MessageDelta("wer")))
        idle()
        display(v, "question", "answer")
        sessions.result = HermesResult.Ok(listOf(user("question"), assistant("answer")))
        replay.tryEmit(RunFrame(6L, RunEvent.Completed("answer")))
        idle()
        display(v, "question", "answer")
        assertNull(v.state.value.run)
        assertNull(memory.active)
        assertNull(memory.cursor)
    }

    @Test fun historyIsLoadedBeforeAStoredRunReattachesAndReplaysItsFirstEvents() {
        memory.active = "run_9"
        memory.cursor = 5L
        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer"), user("live question")))
        val gate = CompletableDeferred<Unit>()
        sessions.historyGate = gate
        sessions.historyRequested = CompletableDeferred()
        val v = buildUsingFrameFollow()
        idle()

        assertTrue(sessions.historyRequested!!.isCompleted)
        assertTrue(fake.calls.isEmpty())
        assertTrue(fake.eventRequests.isEmpty())
        assertTrue(followAfter.isEmpty())

        gate.complete(Unit)
        sessions.historyGate = null
        idle()
        assertEquals(listOf("status run_9"), fake.calls)
        assertEquals(listOf<Long?>(null), fake.eventRequests)
        assertEquals(listOf("old question", "old answer", "live question"), v.state.value.messages.map { it.text })

        fake.eventFlows.last().tryEmit(RunFrame(6L, RunEvent.MessageDelta("first live answer")))
        idle()
        assertEquals(
            listOf("old answer", "first live answer"),
            v.state.value.messages.filter { it.kind == MessageKind.Assistant }.map { it.text },
        )
    }

    @Test fun missingHistoryStillAllowsAStoredRunToReattach() {
        memory.active = "run_9"
        sessions.result = HermesResult.Err(HermesError.ResourceNotFound)
        val v = buildUsingFrameFollow().also { idle() }

        assertNull(v.state.value.historyFailure)
        assertEquals("run_9", v.state.value.run?.runId)
        assertEquals(listOf<Long?>(null), fake.eventRequests)
    }

    @Test fun retryAfterHistoryFailureKeepsTheLiveRunAndClearsTheHistoryError() {
        memory.active = "run_9"
        sessions.result = HermesResult.Err(HermesError.Server(503))
        val v = buildUsingFrameFollow().also { idle() }

        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)
        assertEquals("run_9", v.state.value.run?.runId)
        assertEquals(listOf<Long?>(null), fake.eventRequests)

        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer"), user("live question")))
        v.retryHistory()
        idle()

        assertNull(v.state.value.historyFailure)
        assertEquals("run_9", v.state.value.run?.runId)
        assertEquals(listOf("old question", "old answer", "live question"), v.state.value.messages.map { it.text })
        assertEquals(listOf<Long?>(null), fake.eventRequests)
    }

    @Test fun historyRetryResolvesAStoredRun404WithoutLeavingAnError() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(null)
        sessions.result = HermesResult.Err(HermesError.Server(503))
        fake.onStatus = { HermesResult.Err(HermesError.NotFound) }
        val v = buildUsingFrameFollow().also { idle() }

        assertEquals("run_9", v.state.value.run?.runId)
        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)
        assertTrue(errors(v).isEmpty())

        sessions.result = HermesResult.Ok(listOf(user("question"), assistant("finished answer")))
        v.retryHistory()
        idle()

        assertNull(v.state.value.run)
        assertNull(v.state.value.historyFailure)
        assertEquals(listOf("question", "finished answer"), v.state.value.messages.map { it.text })
        assertTrue(errors(v).isEmpty())
        assertNull(memory.active)
    }

    @Test fun identicalTextInTwoTurnsRemainsInBothHistoryPositions() {
        val v = vm()
        v.type("OK")
        val firstRefresh = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { firstRefresh.await() }
        emit(ev(RunEvent.Completed("OK")))
        assertEquals(listOf("OK", "OK"), v.state.value.messages.map { it.text })

        sessions.onMessages = null
        sessions.result = HermesResult.Ok(listOf(user("OK"), assistant("OK")))
        v.type("OK")
        val secondRefresh = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { secondRefresh.await() }
        emit(ev(RunEvent.MessageDelta("O")))
        emit(ev(RunEvent.Completed("OK")))
        firstRefresh.complete(HermesResult.Ok(listOf(user("OK"))))
        idle()
        assertEquals(listOf("OK", "OK", "OK", "OK"), v.state.value.messages.map { it.text })
        secondRefresh.complete(HermesResult.Ok(listOf(user("OK"), assistant("OK"), user("OK"), assistant("OK"))))
        idle()
        assertEquals(listOf("OK", "OK", "OK", "OK"), v.state.value.messages.map { it.text })
        assertEquals(2, v.state.value.messages.count { it.kind == MessageKind.User })
        assertEquals(2, v.state.value.messages.count { it.kind == MessageKind.Assistant })
    }

    @Test fun delayedCompletionHistoryCannotReplaceTheNextLocalUser() {
        val v = vm().startWithRun()
        val refresh = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { refresh.await() }
        emit(ev(RunEvent.Completed("first answer")))
        sessions.onMessages = null
        sessions.result = HermesResult.Ok(listOf(user("x"), assistant("first answer")))
        v.type("second question")
        val userId = v.state.value.messages.last().id
        refresh.complete(HermesResult.Ok(listOf(user("x"))))
        idle()
        assertEquals("run_2", v.state.value.run?.runId)
        assertEquals(listOf("x", "first answer", "second question"), v.state.value.messages.map { it.text })
        assertEquals(userId, v.state.value.messages.last().id)
    }

    @Test fun historyStartedBeforeFinishCanDropAProvenPendingTurn() {
        val v = vm().startWithRun()
        val beforeFinish = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        val afterFinish = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { beforeFinish.await() }
        v.retryHistory()
        idle()
        sessions.onMessages = { afterFinish.await() }
        emit(ev(RunEvent.Completed("current answer")))
        beforeFinish.complete(HermesResult.Ok(listOf(user("x"), assistant("current answer"))))
        idle()
        assertEquals(listOf("x", "current answer"), v.state.value.messages.map { it.text })
        afterFinish.complete(HermesResult.Ok(listOf(user("x"), assistant("current answer"))))
        idle()
        assertEquals(listOf("x", "current answer"), v.state.value.messages.map { it.text })
    }

    @Test fun historyRetryAppendsEachNoticeAndErrorOnceWithUniqueIds() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.ReplayTruncated(1L)))
        emit(RunUpdate.Lost(HermesError.Unreachable("down")))
        val notices = v.state.value.messages.filter { it.kind == MessageKind.Info || it.kind == MessageKind.Error }
        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer")))
        repeat(2) {
            v.retryHistory()
            idle()
            val messages = v.state.value.messages
            assertEquals(messages.size, messages.map { it.id }.toSet().size)
            assertEquals(notices, messages.filter { it.kind == MessageKind.Info || it.kind == MessageKind.Error })
            assertEquals(1, messages.count { it.text == ChatViewModel.INFO_REPLAY_TRUNCATED })
            assertEquals(1, errors(v).size)
        }
    }

    @Test fun completionReloadsHistoryAndShowsTheFinalAnswerOnce() {
        memory.active = "run_9"
        memory.cursor = 5L
        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer"), user("current question")))
        val v = buildUsingFrameFollow().also { idle() }
        val events = fake.eventFlows.last()
        events.tryEmit(RunFrame(6L, RunEvent.MessageDelta("hel")))
        idle()
        assertEquals("hel", v.state.value.messages.last().text)

        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer"), user("current question"), assistant("hello")))
        events.tryEmit(RunFrame(7L, RunEvent.Completed("hello")))
        idle()

        assertNull(v.state.value.run)
        assertEquals(listOf("old question", "old answer", "current question", "hello"), v.state.value.messages.map { it.text })
        assertEquals(1, v.state.value.messages.count { it.kind == MessageKind.Assistant && it.text == "hello" })

        sessions.queuedResults += HermesResult.Ok(
            listOf(user("old question"), assistant("old answer"), user("current question"), assistant("hello")),
        )
        v.retryHistory()
        idle()

        assertNull(v.state.value.historyFailure)
        assertEquals(listOf("old question", "old answer", "current question", "hello"), v.state.value.messages.map { it.text })
        assertEquals(1, v.state.value.messages.count { it.kind == MessageKind.Assistant && it.text == "hello" })
        assertEquals(listOf("sess-1", "sess-1", "sess-1"), sessions.calls)
    }

    @Test fun historyNotFoundIsEmptyAndSendStillStarts() {
        sessions.result = HermesResult.Err(HermesError.ResourceNotFound)
        val v = vm()

        assertEquals(emptyList<app.rasul.chat.ChatMessage>(), v.state.value.messages)
        assertNull(v.state.value.historyFailure)
        assertEquals(app.rasul.chat.ChatLoad.Ready, v.state.value.load)
        v.type("hello")
        assertTrue(fake.calls.contains("start hello session=sess-1"))
    }

    @Test fun historyErrorsRequireASuccessfulLoadBeforeSend() {
        sessions.result = HermesResult.Err(HermesError.Server(503))
        val v = vm()
        v.type("hello")
        assertEquals(2, sessions.calls.size)
        assertTrue(fake.calls.isEmpty())
        assertEquals("hello", v.state.value.draft)
        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)

        val gate = CompletableDeferred<Unit>()
        sessions.historyGate = gate
        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer")))
        v.send()
        idle()
        v.onDraft("changed")
        v.send()
        assertEquals("hello", v.state.value.draft)
        assertTrue(fake.calls.isEmpty())
        gate.complete(Unit)
        sessions.historyGate = null
        idle()
        assertEquals(listOf("start hello session=sess-1"), fake.calls)

        // The terminal event settles the turn; the post-event reload serves its persisted rows.
        sessions.result = HermesResult.Ok(listOf(user("old question"), assistant("old answer"), user("hello"), assistant("answer")))
        emit(ev(RunEvent.Completed("answer")))
        assertEquals(listOf("old question", "old answer", "hello", "answer"), v.state.value.messages.map { it.text })
        v.retryHistory()
        idle()
        assertEquals(listOf("old question", "old answer", "hello", "answer"), v.state.value.messages.map { it.text })
    }

    @Test fun reopeningWithAStoredRunThatEndedFinishesItWithoutFollowing() {
        memory.active = "run_9"
        sessions.result = HermesResult.Ok(listOf(user("question"), assistant("it finished")))
        fake.onStatus = { HermesResult.Ok(RunStatus("completed", "it finished", null)) }
        val v = vm()
        assertNull(v.state.value.run)
        assertEquals(listOf("question", "it finished"), v.state.value.messages.map { it.text })
        assertTrue(followAfter.isEmpty())
        assertNull(memory.active)
    }

    @Test fun terminalStatusOnReopenDoesNotAppendWhileItsRefreshIsHeld() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(null)
        sessions.result = HermesResult.Ok(listOf(user("question"), assistant("answer")))
        val refresh = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        fake.onStatus = {
            sessions.onMessages = { refresh.await() }
            HermesResult.Ok(RunStatus("completed", "answer", null))
        }
        val v = vm()
        assertEquals(listOf("question", "answer"), v.state.value.messages.map { it.text })
        refresh.complete(HermesResult.Ok(listOf(user("question"), assistant("answer"))))
        idle()
        assertEquals(listOf("question", "answer"), v.state.value.messages.map { it.text })
    }

    @Test fun reopeningWithAStoredRunTheServerForgotClearsIt() {
        memory.active = "run_9"
        fake.onStatus = { HermesResult.Err(HermesError.NotFound) }
        val v = vm()
        assertNull(v.state.value.run)
        assertNull(memory.active)
    }

    @Test fun reopeningAfterTheRunFinishedUsesTranscriptWithoutANotFoundError() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(null)
        sessions.result = HermesResult.Ok(listOf(user("question"), assistant("final answer")))
        fake.onStatus = { HermesResult.Err(HermesError.NotFound) }
        val v = vm()

        assertNull(v.state.value.run)
        assertEquals(listOf("question", "final answer"), v.state.value.messages.map { it.text })
        assertTrue(errors(v).isEmpty())
        assertNull(memory.active)
    }

    @Test fun receivedEventCursorIsPersistedWithTheActiveRun() {
        val v = vm().startWithRun()
        emit(ev(RunEvent.ToolStarted("bash", null), seq = 7L))
        assertEquals("run_1", memory.active)
        assertEquals(7L, memory.cursor)
        emit(ev(RunEvent.Completed("done"), seq = 8L))
        assertNull(memory.active)
        assertNull(memory.cursor)
    }

    @Test fun startingARunStoresItsIdAndFinishingClearsIt() {
        val v = vm().startWithRun()
        assertEquals("run_1", memory.active)
        emit(ev(RunEvent.Completed("x")))
        assertNull(memory.active)
    }

    @Test fun sendDuringTheStoredRunLookupStartsNothing() {
        memory.active = "run_9"
        val gate = CompletableDeferred<Unit>()
        memory.readGate = gate
        val v = build()
        idle()
        assertTrue(v.state.value.load != app.rasul.chat.ChatLoad.Ready)
        v.onDraft("hello")
        v.send()
        idle()
        assertTrue(fake.calls.none { it.startsWith("start") })
        gate.complete(Unit)
        idle()
        assertEquals(app.rasul.chat.ChatLoad.Ready, v.state.value.load)
        assertEquals("run_9", v.state.value.run?.runId)
        assertTrue(fake.calls.none { it.startsWith("start") })
    }

    @Test fun theActiveRunWriteSurvivesTheViewModelBeingCleared() {
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST")
        val v = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
            },
        )[ChatViewModel::class.java]
        idle()
        val gate = CompletableDeferred<Unit>()
        memory.writeGate = gate
        v.type("x")
        assertEquals("run_1", v.state.value.run?.runId)
        assertTrue(memory.writes.isEmpty()) // the write is held
        store.clear() // Back
        idle()
        gate.complete(Unit)
        idle()
        assertEquals(listOf<String?>("run_1"), memory.writes)
        assertEquals("run_1", memory.active)
    }

    @Test fun clearingChatViewModelDoesNotStopItsStoredRun() {
        memory.active = "run_9"
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST")
        val v = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
            },
        )[ChatViewModel::class.java]
        idle()
        assertEquals("run_9", v.state.value.run?.runId)

        store.clear()
        idle()
        assertTrue(fake.calls.none { it.startsWith("stop ") })
        assertEquals("run_9", memory.active)
    }

    @Test fun aSteerStatusCheckIsCancelledWhenTheRunFinishes() {
        val v = vm().startWithRun()
        val gate = CompletableDeferred<HermesResult<RunStatus>>()
        var cancelled = false
        fake.onSteer = { _, _ -> HermesResult.Err(HermesError.Server(409)) }
        fake.onStatus = {
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        v.type("more")
        assertTrue(fake.calls.contains("status run_1"))
        emit(ev(RunEvent.Completed("done")))
        assertTrue(cancelled)
        val before = v.state.value.messages.size
        gate.complete(HermesResult.Ok(RunStatus("running", null, null)))
        idle()
        assertEquals(before, v.state.value.messages.size)
        assertNull(v.state.value.run)
    }

    @Test fun aHeldRunIdWriteIsFollowedByTheClearInOrder() {
        val v = vm()
        val gate = CompletableDeferred<Unit>()
        memory.writeGate = gate
        v.type("x")
        emit(ev(RunEvent.Completed("done"))) // clear is submitted while the first write is held
        assertTrue(memory.writes.isEmpty())
        gate.complete(Unit)
        idle()
        assertEquals(listOf<String?>("run_1", null), memory.writes)
        assertNull(memory.active)
    }

    @Test fun aSecondViewModelsWriteAfterTheFirstIsClearedKeepsOrder() {
        val store = ViewModelStore()
        @Suppress("UNCHECKED_CAST")
        val first = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
            },
        )[ChatViewModel::class.java]
        idle()
        val gate = CompletableDeferred<Unit>()
        memory.writeGate = gate
        first.type("a") // run_1 start acknowledged; its breadcrumb write is held
        assertEquals("run_1", first.state.value.run?.runId)
        store.clear() // first VM cleared (onCleared) while its run is still active
        idle()
        val second = build()
        idle()
        second.type("b") // run_2 queued behind the held run_1 write
        gate.complete(Unit)
        idle()
        assertEquals(listOf<String?>("run_1", "run_2"), memory.writes)
        assertEquals("run_2", memory.active)
    }

    @Test fun aSteerStatusCheckOverlappingAnSseNotFoundStillRecovers() {
        val v = vm().startWithRun()
        val gate = CompletableDeferred<HermesResult<RunStatus>>()
        var n = 0
        fake.onSteer = { _, _ -> HermesResult.Err(HermesError.Server(409)) }
        fake.onStatus = { if (n++ == 0) gate.await() else HermesResult.Ok(RunStatus("running", null, null)) }
        v.type("more") // steer 409 -> status check held
        emit(RunUpdate.Lost(HermesError.NotFound)) // arrives while the steer check is in flight
        gate.complete(HermesResult.Ok(RunStatus("running", null, null)))
        idle()
        assertEquals(2, fake.calls.count { it == "status run_1" })
        assertEquals(StatusLabel.LiveUpdatesUnavailable, v.state.value.run?.status)
    }

    private fun row(id: String, role: String, text: String) =
        SessionMessageDto(id = JsonPrimitive(id), role = role, content = JsonPrimitive(text))

    private fun display(v: ChatViewModel, vararg text: String) {
        val messages = v.state.value.messages
        assertEquals(text.toList(), messages.map {
            when (it.error) {
                is ChatFailure.RunFailed -> "err"
                ChatFailure.Api(HermesError.NotFound) -> "err NotFound"
                is ChatFailure.Api -> "err Unreachable"
                else -> it.text
            }
        })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
    }

    @Test fun cancelledEmptyHistoryPreservesQuestionAndStopBeforeNextTurn() {
        val v = vm()
        v.type("q")
        emit(ev(RunEvent.Cancelled()))
        display(v, "q", "stopped")
        v.type("q2")
        display(v, "q", "stopped", "q2")
        assertEquals("run_2", v.state.value.run?.runId)
        sessions.result = HermesResult.Ok(listOf(row("q2", "user", "q2"), row("a2", "assistant", "A2")))
        emit(ev(RunEvent.Completed("A2")))
        display(v, "q", "stopped", "q2", "A2")
    }

    @Test fun failedTurnWithOnlySavedUserUnblocksSendAndAnchorsError() {
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(row("q1", "user", "q₁")))
        emit(ev(RunEvent.Failed("failure")))
        display(v, "q₁", "err")
        v.type("q2")
        display(v, "q₁", "err", "q2")
        assertEquals("run_2", v.state.value.run?.runId)
        sessions.result = HermesResult.Ok(listOf(row("q1", "user", "q₁"), row("q2", "user", "q2"), row("a2", "assistant", "A2")))
        emit(ev(RunEvent.Completed("A2")))
        display(v, "q₁", "err", "q2", "A2")
    }

    @Test fun restoredCompletedStatusKeepsAnswerWhenReloadFails() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(JsonPrimitive("a0").toString())
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("q1", "user", "q₁")))
        fake.onStatus = {
            sessions.result = HermesResult.Err(HermesError.Server(503))
            HermesResult.Ok(RunStatus("completed", "A", null))
        }
        val v = vm()
        display(v, "q0", "a0", "q₁", "A")
        assertNull(memory.active)
        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)
    }

    @Test fun restoredCompletionReplacedByTheServedTranscript() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(JsonPrimitive("a0").toString())
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("q1", "user", "q₁")))
        val v = vm()
        emit(ev(RunEvent.Completed("A")))
        // The completed event settles the turn; the post-event reload is the truth even when the
        // served transcript still lacks the run's output.
        display(v, "q0", "a0", "q₁")
    }

    @Test fun responseRequestedBeforeCompletionCanProveAnswerSaved() {
        val v = vm()
        v.type("q")
        val before = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        val after = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { before.await() }
        v.retryHistory()
        idle()
        sessions.onMessages = { after.await() }
        emit(ev(RunEvent.Completed("A")))
        display(v, "q", "A")
        before.complete(HermesResult.Ok(listOf(row("q1", "user", "q₁"), row("a1", "assistant", "a₁"))))
        idle()
        display(v, "q₁", "a₁")
        after.complete(HermesResult.Err(HermesError.Server(503)))
        idle()
        display(v, "q₁", "a₁")
    }

    @Test fun restoredSavedAnswerAppearsOnceWithKnownAnchor() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(JsonPrimitive("a0").toString())
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("q1", "user", "q₁"), row("a1", "assistant", "a₁")))
        fake.onStatus = { HermesResult.Ok(RunStatus("completed", "a", null)) }
        val v = vm()
        display(v, "q0", "a0", "q₁", "a₁")
        v.retryHistory()
        idle()
        display(v, "q0", "a0", "q₁", "a₁")
    }

    @Test fun firstHistoryFailureBlocksStartUntilSuccessfulRetry() {
        sessions.result = HermesResult.Err(HermesError.Server(503))
        val v = vm()
        v.type("q")
        display(v)
        assertTrue(fake.calls.isEmpty())
        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)
        sessions.result = HermesResult.Ok(emptyList())
        v.retryHistory()
        idle()
        v.send()
        idle()
        display(v, "q")
        assertEquals("run_1", v.state.value.run?.runId)
    }

    @Test fun olderCompletionRefreshCannotChangeNextQuestionId() {
        val v = vm()
        v.type("qA")
        val held = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { held.await() }
        emit(ev(RunEvent.Completed("aA")))
        sessions.onMessages = null
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA"), row("aA", "assistant", "aA")))
        v.type("qB")
        val id = v.state.value.messages.last().id
        held.complete(HermesResult.Ok(listOf(row("qA", "user", "qA"), row("aA", "assistant", "aA"))))
        idle()
        display(v, "qA", "aA", "qB")
        assertEquals(id, v.state.value.messages.last().id)
    }

    @Test fun threeHistoryFailureRetriesKeepEachNoticeIdOnce() {
        val v = vm()
        v.type("q")
        emit(ev(RunEvent.ReplayTruncated(1L)))
        emit(RunUpdate.Lost(HermesError.Unreachable("offline")))
        val ids = v.state.value.messages.map { it.id }
        repeat(3) {
            sessions.result = HermesResult.Err(HermesError.Server(503))
            v.retryHistory()
            idle()
            display(v, "q", "replay_truncated", "err Unreachable")
            sessions.result = HermesResult.Ok(emptyList())
            v.retryHistory()
            idle()
            display(v, "q", "replay_truncated", "err Unreachable")
            assertEquals(ChatFailure.Api(HermesError.Unreachable("offline")), errors(v).single().error)
            assertEquals(ids, v.state.value.messages.map { it.id })
        }
    }

    @Test fun missingRunWithOnlyEarlierAnswerShowsNotFound() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(JsonPrimitive("a0").toString())
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0")))
        fake.onStatus = { HermesResult.Err(HermesError.NotFound) }
        val v = vm()
        display(v, "q0", "a0", "err NotFound")
        assertNull(memory.active)
    }

    @Test fun missingSessionAfterCompletedStillSends() {
        sessions.result = HermesResult.Err(HermesError.ResourceNotFound)
        val v = vm()
        v.type("q")
        emit(ev(RunEvent.Completed("A")))
        // The completed run settled; the server says the session is gone, so the view shows that truth.
        display(v)
        v.type("q2")
        display(v, "q2")
        assertEquals(2, fake.startN)
        assertEquals("run_2", v.state.value.run?.runId)
        assertEquals("", v.state.value.draft)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun sendAfterCompletedReloadsHistoryOnceAndStarts() {
        val v = vm()
        v.type("qA")
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA")))
        emit(ev(RunEvent.Completed("aA")))
        val calls = sessions.calls.size
        v.onDraft("qB")
        v.send()
        idle()
        // One reload, no retries: the local "aA" row is replaced by what the server served.
        assertEquals(calls + 1, sessions.calls.size)
        assertEquals(2, fake.startN)
        display(v, "qA", "qB")
        assertEquals("", v.state.value.draft)
        assertNull(v.state.value.historyFailure)
        assertEquals(Anchor.Known(JsonPrimitive("qA").toString()), memory.storedAnchor)
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA"), row("aA", "assistant", "aA"), row("qB", "user", "qB"), row("bB", "assistant", "bB")))
        emit(ev(RunEvent.Completed("bB")))
        display(v, "qA", "aA", "qB", "bB")
    }

    @Test fun sendAfterCompletedDoesNotWaitOrRetry() {
        val v = vm()
        v.type("qA")
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA")))
        emit(ev(RunEvent.Completed("aA")))
        val calls = sessions.calls.size
        val start = sched.currentTime
        v.onDraft("qB")
        v.send()
        idle()
        assertEquals(start, sched.currentTime)
        assertEquals(calls + 1, sessions.calls.size)
        assertEquals(2, fake.startN)
        display(v, "qA", "qB")
    }

    @Test fun nextTurnAnchorsOnTheLastServedRowAndKeepsItsOwnCompletion() {
        val v = vm()
        v.type("qA")
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA")))
        emit(ev(RunEvent.Completed("aA")))
        v.onDraft("qB")
        v.send()
        idle()
        assertEquals(2, fake.startN)
        // The next turn anchors on the last row the server served, even though "aA" is not in it.
        assertEquals(Anchor.Known(JsonPrimitive("qA").toString()), memory.storedAnchor)
        display(v, "qA", "qB")
        sessions.result = HermesResult.Err(HermesError.Server(503))
        emit(ev(RunEvent.Completed("bB")))
        display(v, "qA", "qB", "bB")
        assertEquals(ChatFailure.Api(HermesError.Server(503)), v.state.value.historyFailure)
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA"), row("aA", "assistant", "aA"), row("qB", "user", "qB"), row("bB", "assistant", "bB")))
        v.retryHistory()
        idle()
        display(v, "qA", "aA", "qB", "bB")
    }

    @Test fun sendHistoryReloadCapturesDraftAndBlocksEditsAndRepeatedSendWhileSuspended() {
        val v = vm()
        v.type("qA")
        sessions.result = HermesResult.Ok(listOf(row("qA", "user", "qA")))
        emit(ev(RunEvent.Completed("aA")))
        val held = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { held.await() }
        val calls = sessions.calls.size
        v.onDraft("qB")
        v.send()
        sched.runCurrent()
        v.onDraft("changed")
        v.send()
        sched.runCurrent()
        assertEquals(calls + 1, sessions.calls.size)
        assertEquals("qB", v.state.value.draft)
        assertEquals(1, fake.startN)
        display(v, "qA")
        held.complete(HermesResult.Ok(listOf(row("qA", "user", "qA"), row("aA", "assistant", "aA"))))
        idle()
        display(v, "qA", "aA", "qB")
        assertEquals(2, fake.startN)
        assertEquals("", v.state.value.draft)
        assertEquals(Anchor.Known(JsonPrimitive("aA").toString()), memory.storedAnchor)
    }

    @Test fun oldBreadcrumbResolvesOnlyFromPostTerminalHistory() {
        memory.active = "run_9"
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("q1", "user", "q₁")))
        val v = vm()
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("q1", "user", "q₁"), row("a1", "assistant", "a₁")))
        emit(ev(RunEvent.Completed("A")))
        display(v, "q0", "a0", "q₁", "a₁")
    }

    @Test fun anOpenChatRereadsItsSessionPointerBeforeSending() {
        val vm = vm()
        memory.storedSession = "sess-2"

        vm.onDraft("hello")
        vm.send()
        idle()

        assertEquals(listOf("sess-1", "sess-2"), sessions.calls)
        assertTrue(fake.calls.contains("start hello session=sess-2"))
        assertFalse(fake.calls.contains("start hello session=sess-1"))
    }

    @Test fun sessionReadFailureShowsSpecificFailureAndCanRetry() {
        memory.sessionFailure = true
        val v = vm()
        assertEquals(ChatFailure.SessionUnavailable, v.state.value.historyFailure)
        v.type("q")
        display(v)
        assertTrue(fake.calls.isEmpty())
        assertEquals("q", v.state.value.draft)
        assertEquals(ChatFailure.SessionUnavailable, v.state.value.historyFailure)
        memory.sessionFailure = false
        v.retryHistory()
        idle()
        assertNull(v.state.value.historyFailure)
        v.send()
        idle()
        display(v, "q")
    }

    @Test fun keyedHistoryIdsSurviveInsertionsAndFallbackRowsRemainUnique() {
        sessions.result = HermesResult.Ok(listOf(row("q1", "user", "q"), row("a1", "assistant", "a"), user("fallback")))
        val v = vm()
        display(v, "q", "a", "fallback")
        val ids = v.state.value.messages.take(2).map { it.id }
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "earlier"), row("q1", "user", "q"), row("a1", "assistant", "a"), user("fallback")))
        v.retryHistory()
        idle()
        display(v, "earlier", "q", "a", "fallback")
        assertEquals(ids, v.state.value.messages.drop(1).take(2).map { it.id })
    }

    @Test fun activeTurnHidesSavedTailUntilTerminalProof() {
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(row("q1", "user", "q₁"), row("a1", "assistant", "a₁")))
        v.retryHistory()
        idle()
        display(v, "q")
        emit(ev(RunEvent.Completed("A")))
        display(v, "q₁", "a₁")
    }

    @Test fun missingAnchorAfterCompletedTrustsTheServedTranscript() {
        memory.active = "run_9"
        memory.storedAnchor = Anchor.Known(JsonPrimitive("missing").toString())
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0")))
        val v = vm()
        emit(ev(RunEvent.Completed("A")))
        // The anchor row is gone (server rewrite); the reload replaces the local "A" bubble.
        display(v, "q0", "a0")
        v.type("q2")
        display(v, "q0", "a0", "q2")
        assertEquals(1, fake.startN)
        assertEquals("", v.state.value.draft)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun rewrittenTranscriptDropsTheAnchorRowButCompletedStillSends() {
        sessions.result = HermesResult.Ok(listOf(row("u0", "user", "hi"), row("a0", "assistant", "older answer")))
        val v = vm()
        v.type("q")
        // A mid-turn server rewrite (in-place compression) archives every old row id and re-inserts
        // the transcript under new ids; the completed event itself settles the turn.
        sessions.result = HermesResult.Ok(listOf(
            row("u0r", "user", "hi"), row("a0r", "assistant", "older answer"),
            row("q1", "user", "q"), row("a1", "assistant", "A"),
        ))
        emit(ev(RunEvent.Completed("A")))
        display(v, "hi", "older answer", "q", "A")
        v.type("next")
        assertEquals(2, fake.startN)
        assertEquals("run_2", v.state.value.run?.runId)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun identicalOldAndNewAnswersStillSendAfterRewrite() {
        sessions.result = HermesResult.Ok(listOf(row("u0", "user", "hi"), row("a0", "assistant", "OK")))
        val v = vm()
        v.type("same")
        // The rewrite re-ids every row; an older identical "OK" stays, the new turn's answer is absent.
        sessions.result = HermesResult.Ok(listOf(
            row("u0r", "user", "hi"), row("a0r", "assistant", "OK"), row("q1", "user", "same"),
        ))
        emit(ev(RunEvent.Completed("OK")))
        display(v, "hi", "OK", "same")
        v.type("next")
        assertEquals(2, fake.startN)
        assertEquals("run_2", v.state.value.run?.runId)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun completedOutputWithAnAdvisoryTailStillSends() {
        sessions.result = HermesResult.Ok(listOf(row("u0", "user", "hi"), row("a0", "assistant", "older answer")))
        val v = vm()
        v.type("q")
        // The server can append an advisory to run.completed's output after the transcript was
        // persisted, so the stored assistant row keeps only "A".
        sessions.result = HermesResult.Ok(listOf(
            row("u0r", "user", "hi"), row("a0r", "assistant", "older answer"),
            row("q1", "user", "q"), row("a1", "assistant", "A"),
        ))
        emit(ev(RunEvent.Completed("A\n\nNote: a file edit failed.")))
        display(v, "hi", "older answer", "q", "A")
        v.type("next")
        assertEquals(2, fake.startN)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun notificationReplyAfterTranscriptRewriteStartsANewRun() {
        sessions.result = HermesResult.Ok(listOf(row("u0", "user", "hi"), row("a0", "assistant", "older answer")))
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(
            row("u0r", "user", "hi"), row("a0r", "assistant", "older answer"),
            row("q1", "user", "q"), row("a1", "assistant", "A"),
        ))
        emit(ev(RunEvent.Completed("A")))
        v.replyFromNotification("typed in the shade")
        idle()
        assertTrue(fake.calls.contains("start typed in the shade session=sess-1"))
        assertEquals("", v.state.value.draft)
        assertEquals(0, v.state.value.queuedReplies)
        assertNull(v.state.value.historyFailure)
    }

    @Test fun notificationReplyThatCannotBeSentLandsInTheDraftOnce() {
        memory.sessionFailure = true
        val v = vm()
        v.replyFromNotification("typed in the shade")
        idle()
        assertEquals(0, fake.startN)
        assertEquals("typed in the shade", v.state.value.draft)
        assertEquals(ChatFailure.ReplyKept, v.state.value.terminalFailure)
        assertEquals(0, v.state.value.queuedReplies)
        v.replyFromNotification("typed in the shade")
        idle()
        assertEquals("typed in the shade", v.state.value.draft)
        assertEquals(0, fake.startN)
    }

    @Test fun notificationReplyThatFailsAtStartAlsoStaysInTheDraftOnce() {
        fake.onStart = { HermesResult.Err(HermesError.Server(503)) }
        val v = vm()
        v.replyFromNotification("same reply")
        idle()
        assertEquals("same reply", v.state.value.draft)
        v.replyFromNotification("same reply")
        idle()
        assertEquals("same reply", v.state.value.draft)
        assertEquals(2, fake.startN)
        assertEquals(ChatFailure.ReplyKept, v.state.value.terminalFailure)
    }

    @Test fun keptNotificationReplyLeavesTheDraftWhenTheRetryStarts() {
        fake.onStart = { HermesResult.Err(HermesError.Server(503)) }
        val v = vm()
        v.replyFromNotification("same reply")
        idle()
        assertEquals("same reply", v.state.value.draft)
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.replyFromNotification("same reply")
        idle()
        assertEquals(2, fake.startN)
        assertEquals("", v.state.value.draft)
        assertEquals("run_2", v.state.value.run?.runId)
    }

    @Test fun notificationReplyThatStartsKeepsAMatchingLineTheUserTyped() {
        val v = vm()
        v.onDraft("Plan:\nok\nthen deploy")
        v.replyFromNotification("ok")
        idle()
        assertEquals(1, fake.startN)
        assertEquals("Plan:\nok\nthen deploy", v.state.value.draft)
    }

    @Test fun keptReplyThenComposerSendDoesNotDeleteALaterTypedLine() {
        fake.onStart = { HermesResult.Err(HermesError.Server(503)) }
        val v = vm()
        v.replyFromNotification("ok")
        idle()
        assertEquals("ok", v.state.value.draft)
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.send()
        idle()
        assertEquals("run_2", v.state.value.run?.runId)
        emit(ev(RunEvent.Completed("A")))
        idle()
        v.onDraft("Plan:\nok\nthen deploy")
        v.replyFromNotification("ok")
        idle()
        assertEquals(3, fake.startN)
        assertEquals("Plan:\nok\nthen deploy", v.state.value.draft)
    }

    @Test fun keptReplyDeletedByTheUserDoesNotDeleteALaterTypedLine() {
        fake.onStart = { HermesResult.Err(HermesError.Server(503)) }
        val v = vm()
        v.replyFromNotification("ok")
        idle()
        assertEquals("ok", v.state.value.draft)
        v.onDraft("")
        v.onDraft("Plan:\nok\nthen deploy")
        fake.onStart = { HermesResult.Ok(StartedRun("run_2")) }
        v.replyFromNotification("ok")
        idle()
        assertEquals(2, fake.startN)
        assertEquals("Plan:\nok\nthen deploy", v.state.value.draft)
    }

    @Test fun sendAfterCompletedRunKeepsTheDraftWhenTheReloadFails() {
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "hi"), row("a0", "assistant", "older answer")))
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(
            row("u0r", "user", "hi"), row("a0r", "assistant", "older answer"),
            row("q1", "user", "q"), row("a1", "assistant", "A"),
        ))
        emit(ev(RunEvent.Completed("A")))
        display(v, "hi", "older answer", "q", "A")
        sessions.result = HermesResult.Err(HermesError.BridgeTimeout)
        v.type("next")
        idle()
        assertEquals(1, fake.startN)
        assertEquals("next", v.state.value.draft)
        assertEquals(ChatFailure.Api(HermesError.BridgeTimeout), v.state.value.historyFailure)
    }

    @Test fun cancelledTurnWaitsForPostTerminalRequestBeforeRetiring() {
        val v = vm()
        v.type("q")
        val before = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        val after = CompletableDeferred<HermesResult<List<SessionMessageDto>>>()
        sessions.onMessages = { before.await() }
        v.retryHistory()
        idle()
        sessions.onMessages = { after.await() }
        emit(ev(RunEvent.Cancelled()))
        before.complete(HermesResult.Ok(listOf(row("q1", "user", "q₁"))))
        idle()
        display(v, "q", "stopped")
        after.complete(HermesResult.Ok(listOf(row("q1", "user", "q₁"))))
        idle()
        display(v, "q₁", "stopped")
    }

    @Test fun pendingAnchorPersistsAcrossCursorWrites() {
        sessions.result = HermesResult.Ok(listOf(row("a0", "assistant", "a0")))
        val v = vm()
        v.type("q")
        assertEquals(Anchor.Known(JsonPrimitive("a0").toString()), memory.storedAnchor)
        emit(ev(RunEvent.MessageDelta("A"), 0L))
        assertEquals(Anchor.Known(JsonPrimitive("a0").toString()), memory.storedAnchor)
        display(v, "a0", "q", "A")
    }

    @Test fun noAnswerPreservesEmptyAnchorBeforeAssistantOnlyTail() {
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(row("a1", "assistant", "a₁")))
        emit(ev(RunEvent.Cancelled()))
        display(v, "q", "stopped", "a₁")
    }

    @Test fun hiddenHistoryRowStillDefinesTheSendAnchor() {
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("t0", "tool", "hidden")))
        val v = vm()
        display(v, "q0", "a0")
        v.type("q")
        display(v, "q0", "a0", "q")
        assertEquals(Anchor.Known(JsonPrimitive("t0").toString()), memory.storedAnchor)
        sessions.result = HermesResult.Ok(listOf(row("q0", "user", "q0"), row("a0", "assistant", "a0"), row("t0", "tool", "hidden"), row("a1", "assistant", "a₁")))
        emit(ev(RunEvent.Cancelled()))
        display(v, "q0", "a0", "q", "stopped", "a₁")
    }

    @Test fun assistantRowAfterAnchorProvesPersistenceEvenWithEmptyText() {
        val v = vm()
        v.type("q")
        sessions.result = HermesResult.Ok(listOf(row("q1", "user", "q₁"), row("a1", "assistant", "")))
        emit(ev(RunEvent.Completed("A")))
        display(v, "q₁")
    }

    // ---- attachments ----

    private fun photo(byteCount: Int) = PendingAttachment.Photo(
        PendingAttachment.newKey(), ByteArray(byteCount) { it.toByte() }, ByteArray(8) { it.toByte() },
    )

    @Test fun stagedTextFileSendsAsNamedTextPartAndClears() {
        val v = vm()
        v.addAttachments(listOf(PendingAttachment.TextFile(1, "notes.md", "hello file")))
        v.onDraft("caption")
        v.send()
        idle()
        assertEquals(1, fake.startN)
        val part = lastContentPart()
        assertEquals("text", (part["type"] as JsonPrimitive).content)
        assertEquals("notes.md:\nhello file", (part["text"] as JsonPrimitive).content)
        assertTrue(v.state.value.attachments.isEmpty())
        val sent = v.state.value.messages.last { it.kind == MessageKind.User }
        assertEquals("caption", sent.text)
        assertEquals(listOf(SentAttachment.NamedFile("notes.md")), sent.attachments)
    }

    @Test fun stagedPhotoSendsImagePartAndBubbleGetsThumb() {
        val v = vm()
        v.addAttachments(listOf(photo(1024)))
        v.send()
        idle()
        assertEquals(1, fake.startN)
        val part = lastContentPart()
        assertEquals("image_url", (part["type"] as JsonPrimitive).content)
        val url = ((part["image_url"] as JsonObject)["url"] as JsonPrimitive).content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertTrue(v.state.value.attachments.isEmpty())
        val sent = v.state.value.messages.last { it.kind == MessageKind.User }
        assertTrue(sent.attachments.single() is SentAttachment.LocalPhoto)
    }

    @Test fun oversizedAttachmentsRefuseAndKeepDraftAndChips() {
        val v = vm()
        v.addAttachments(listOf(photo(720 * 1024)))
        v.onDraft("keep me")
        v.send()
        idle()
        assertEquals(0, fake.startN)
        assertEquals("keep me", v.state.value.draft)
        assertEquals(1, v.state.value.attachments.size)
        assertTrue(errors(v).any { it.error == ChatFailure.AttachmentTooLarge })
    }

    @Test fun attachmentsWhileRunActiveRefuseWithoutSending() {
        val v = vm().startWithRun()
        v.addAttachments(listOf(PendingAttachment.TextFile(1, "a.txt", "t")))
        v.onDraft("with file")
        v.send()
        idle()
        assertEquals(1, fake.startN)
        assertTrue(fake.calls.none { it.startsWith("steer") })
        assertEquals("with file", v.state.value.draft)
        assertEquals(1, v.state.value.attachments.size)
        assertTrue(errors(v).any { it.error == ChatFailure.AttachmentRunActive })
    }

    @Test fun fileAndPhotosMixedRefuseWithSpecificMessage() {
        val v = vm()
        v.addAttachments(listOf(PendingAttachment.TextFile(1, "a.txt", "t")))
        v.addAttachments(listOf(photo(10)))
        assertEquals(1, v.state.value.attachments.size)
        assertTrue(errors(v).any { it.error == ChatFailure.AttachmentKindMixed })
    }

    @Test fun notificationReplyStaysTextOnlyAndKeepsStagedChips() {
        val v = vm()
        v.addAttachments(listOf(PendingAttachment.TextFile(1, "a.txt", "t")))
        v.replyFromNotification("notif reply")
        idle()
        assertEquals(1, fake.startN)
        assertEquals(listOf("start notif reply session=sess-1"), fake.calls.filter { it.startsWith("start") })
        assertTrue(fake.startInputs.last() is JsonPrimitive)
        assertEquals(1, v.state.value.attachments.size)
    }

    @Test fun pendingPhotoLoadKeepsSendOffUntilStaged() {
        val v = vm()
        fakeAttach.gate = CompletableDeferred()
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(10)))
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        idle()
        assertEquals(1, v.state.value.pendingAttachments)
        assertTrue(v.state.value.attachments.isEmpty())
        v.onDraft("hi")
        v.send()
        idle()
        assertEquals(0, fake.startN)
        fakeAttach.gate!!.complete(Unit)
        idle()
        assertEquals(0, v.state.value.pendingAttachments)
        assertEquals(1, v.state.value.attachments.size)
        v.send()
        idle()
        assertEquals(1, fake.startN)
        val part = lastContentPart()
        assertEquals("image_url", (part["type"] as JsonPrimitive).content)
        assertEquals(app.rasul.data.RunInputLimits.photoBudget(1), fakeAttach.photoBudgets.single())
    }

    @Test fun sharedTextIsAppendedToTheExistingDraftAndNeverSent() {
        val v = vm()
        v.onDraft("mine")
        v.acceptShare("shared link", emptyList(), null, 0)
        idle()
        assertEquals("mine\nshared link", v.state.value.draft)
        assertEquals(0, fake.startN)
        assertEquals(0, v.state.value.pendingAttachments)
    }

    @Test fun sharedTextBecomesTheDraftWhenThereIsNone() {
        val v = vm()
        v.acceptShare("Title\nbody", emptyList(), null, 0)
        idle()
        assertEquals("Title\nbody", v.state.value.draft)
    }

    @Test fun sharedItemsStageLikeAPickAndKeepSendOffUntilStaged() {
        val v = vm()
        v.acceptShare("", listOf(photo(10), photo(10)), null, 0)
        assertEquals(1, v.state.value.pendingAttachments)
        idle()
        assertEquals(0, v.state.value.pendingAttachments)
        assertEquals(2, v.state.value.attachments.size)
        assertEquals(0, fake.startN)
    }

    @Test fun sharedPhotosOverTheSharedBudgetAreRefitLikeAPick() {
        val v = vm()
        val over = app.rasul.data.RunInputLimits.photoBudget(2) + 1
        v.acceptShare("", listOf(photo(over), photo(10)), null, 0)
        idle()
        assertEquals(listOf(app.rasul.data.RunInputLimits.photoBudget(2)), fakeAttach.fitBudgets)
        assertEquals(2, v.state.value.attachments.size)
    }

    @Test fun sharedItemsAddToThoseAlreadyStagedAndStopAtTheLimit() {
        val v = vm()
        v.addAttachments(listOf(photo(10), photo(10), photo(10)))
        v.acceptShare("", listOf(photo(10), photo(10)), null, 0)
        idle()
        assertEquals(4, v.state.value.attachments.size)
        assertEquals(ChatFailure.ShareItemsDropped(1), errors(v).last().error)
    }

    @Test fun sharedItemsWithNoRoomLeftAreAllReportedAndNothingIsRemoved() {
        val v = vm()
        v.addAttachments(listOf(photo(10), photo(10), photo(10), photo(10)))
        val staged = v.state.value.attachments
        v.acceptShare("", listOf(photo(10), photo(10)), null, 1)
        idle()
        assertEquals(staged, v.state.value.attachments)
        assertEquals(ChatFailure.ShareItemsDropped(3), errors(v).last().error)
        assertEquals(0, v.state.value.pendingAttachments)
    }

    @Test fun sharedTextWaitsOutASendBeingPreparedAndLandsInTheDraftAfterIt() {
        val v = vm()
        val gate = CompletableDeferred<Unit>()
        sessions.historyGate = gate
        v.onDraft("question")
        v.send()
        idle()
        assertTrue(v.state.value.preparingSend)
        v.acceptShare("shared link", emptyList(), null, 0)
        idle()
        assertTrue(v.state.value.preparingSend)
        assertEquals(1, v.state.value.pendingAttachments)
        sessions.historyGate = null
        gate.complete(Unit)
        idle()
        assertEquals(1, fake.startN)
        assertEquals("shared link", v.state.value.draft)
        assertEquals(0, v.state.value.pendingAttachments)
    }

    @Test fun itemsLeftOutAreReportedWithTheirCount() {
        val v = vm()
        v.acceptShare("t", listOf(photo(10)), null, 3)
        idle()
        assertEquals(ChatFailure.ShareItemsDropped(3), errors(v).last().error)
        assertEquals(1, v.state.value.attachments.size)
    }

    @Test fun aReadFailureShowsTheSameErrorTheAttachButtonShows() {
        val v = vm()
        v.acceptShare("t", emptyList(), ChatFailure.FileTooLarge, 0)
        idle()
        assertEquals(ChatFailure.FileTooLarge, errors(v).last().error)
        assertEquals("t", v.state.value.draft)
    }

    @Test fun aReadFailureAndLeftOutItemsShareOneNotice() {
        val v = vm()
        v.acceptShare("", emptyList(), ChatFailure.FileUnreadable, 2)
        idle()
        assertEquals(listOf<ChatFailure?>(ChatFailure.ShareItemsDropped(2, ChatFailure.FileUnreadable)), errors(v).map { it.error })
    }

    @Test fun leftOutItemsAndARefusedMixShareOneNotice() {
        val v = vm()
        v.addAttachments(listOf(photo(10)))
        v.acceptShare("", listOf(PendingAttachment.TextFile(1, "a.txt", "t")), null, 1)
        idle()
        assertEquals(listOf<ChatFailure?>(ChatFailure.ShareItemsDropped(1, ChatFailure.AttachmentKindMixed)), errors(v).map { it.error })
        assertEquals(1, v.state.value.attachments.size)
    }

    private fun planOf(v: ChatViewModel): RunInputPlan =
        planRunInput("cap", v.state.value.attachments)

    @Test fun photosPickedOneByOneStillFitTheRequestCap() {
        val v = vm()
        val one = app.rasul.data.RunInputLimits.photoBudget(1)
        val four = app.rasul.data.RunInputLimits.photoBudget(4)
        v.addAttachments(listOf(photo(one)))
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(four), photo(four), photo(four)))
        v.loadPhotos(listOf(Uri.parse("content://x/1"), Uri.parse("content://x/2"), Uri.parse("content://x/3")))
        idle()
        assertEquals(4, v.state.value.attachments.size)
        assertTrue(planOf(v) is RunInputPlan.WithParts)
        assertEquals(listOf(four), fakeAttach.fitBudgets)
    }

    @Test fun twoPlusTwoPhotosStillFitTheRequestCap() {
        val v = vm()
        val two = app.rasul.data.RunInputLimits.photoBudget(2)
        val four = app.rasul.data.RunInputLimits.photoBudget(4)
        v.addAttachments(listOf(photo(two), photo(two)))
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(four), photo(four)))
        v.loadPhotos(listOf(Uri.parse("content://x/1"), Uri.parse("content://x/2")))
        idle()
        assertEquals(4, v.state.value.attachments.size)
        assertTrue(planOf(v) is RunInputPlan.WithParts)
    }

    @Test fun inFlightPhotoLoadsCountInTheBudget() {
        val v = vm()
        val g1 = CompletableDeferred<Unit>()
        val g2 = CompletableDeferred<Unit>()
        fakeAttach.gateQueue += g1
        fakeAttach.gateQueue += g2
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        v.loadPhotos(listOf(Uri.parse("content://x/2"), Uri.parse("content://x/3")))
        idle()
        assertEquals(
            listOf(app.rasul.data.RunInputLimits.photoBudget(1), app.rasul.data.RunInputLimits.photoBudget(3)),
            fakeAttach.photoBudgets,
        )
    }

    @Test fun sessionSwitchDoesNotZeroAnotherLoadsPendingCount() {
        val v = vm()
        val gA = CompletableDeferred<Unit>()
        val gB = CompletableDeferred<Unit>()
        fakeAttach.gateQueue += gA
        fakeAttach.gateQueue += gB
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(10)))
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        idle()
        memory.storedSession = "sess-2"
        v.retryHistory()
        idle()
        v.loadPhotos(listOf(Uri.parse("content://x/2")))
        idle()
        gA.complete(Unit)
        idle()
        assertEquals(1, v.state.value.pendingAttachments)
        v.onDraft("hi")
        v.send()
        idle()
        assertEquals(0, fake.startN)
        gB.complete(Unit)
        idle()
        assertEquals(0, v.state.value.pendingAttachments)
        assertEquals(1, v.state.value.attachments.size)
    }

    @Test fun sessionSwitchDuringFitDoesNotStageIntoTheNewChat() {
        val v = vm()
        val one = app.rasul.data.RunInputLimits.photoBudget(1)
        val two = app.rasul.data.RunInputLimits.photoBudget(2)
        v.addAttachments(listOf(photo(one)))
        fakeAttach.fitGate = CompletableDeferred()
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(two)))
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        idle()
        assertEquals(1, v.state.value.pendingAttachments)
        assertEquals(1, fakeAttach.fitBudgets.size)
        memory.storedSession = "sess-2"
        v.retryHistory()
        idle()
        fakeAttach.fitGate!!.complete(Unit)
        idle()
        assertEquals(0, v.state.value.pendingAttachments)
        assertTrue(v.state.value.attachments.isEmpty())
    }

    @Test fun photosLandingOutOfOrderStillFitTheRequestCap() {
        val v = vm()
        val one = app.rasul.data.RunInputLimits.photoBudget(1)
        val two = app.rasul.data.RunInputLimits.photoBudget(2)
        val gA = CompletableDeferred<Unit>()
        val gB = CompletableDeferred<Unit>()
        fakeAttach.gateQueue += gA
        fakeAttach.gateQueue += gB
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        v.loadPhotos(listOf(Uri.parse("content://x/2")))
        idle()
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(two)))
        gB.complete(Unit)
        idle()
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(one)))
        gA.complete(Unit)
        idle()
        assertEquals(2, v.state.value.attachments.size)
        assertEquals(0, v.state.value.pendingAttachments)
        assertTrue(planOf(v) is RunInputPlan.WithParts)
    }

    @Test fun attachmentLoadOutlivesScreenScope() {
        // The load runs on the ViewModel's scope: a recreated screen re-reads the same ChatState.
        val v = vm()
        fakeAttach.gate = CompletableDeferred()
        fakeAttach.nextPhotos = AttachLoad.Ok(listOf(photo(10)))
        v.loadPhotos(listOf(Uri.parse("content://x/1")))
        idle()
        assertEquals(1, v.state.value.pendingAttachments)
        fakeAttach.gate!!.complete(Unit)
        idle()
        assertEquals(0, v.state.value.pendingAttachments)
        assertEquals(1, v.state.value.attachments.size)
    }

    @Test fun attachmentsStagedWhileSendRunsAreKept() {
        val v = vm()
        sessions.historyGate = CompletableDeferred()
        v.onDraft("hi")
        v.send()
        idle()
        v.addAttachments(listOf(photo(10)))
        sessions.historyGate!!.complete(Unit)
        idle()
        assertEquals(1, fake.startN)
        assertEquals(1, v.state.value.attachments.size)
    }

    @Test fun historyRowWithImagePartShowsRemotePhoto() {
        sessions.result = HermesResult.Ok(listOf(
            SessionMessageDto(role = "user", content = buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "cap") })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,AA") })
                })
            }),
        ))
        val v = vm()
        val sent = v.state.value.messages.last { it.kind == MessageKind.User }
        assertEquals("cap", sent.text)
        assertEquals(listOf(SentAttachment.RemotePhoto("data:image/jpeg;base64,AA")), sent.attachments)
    }
}
