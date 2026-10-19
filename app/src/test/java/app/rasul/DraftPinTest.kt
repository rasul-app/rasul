package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rasul.chat.Anchor
import app.rasul.chat.ChatDrafts
import app.rasul.chat.ChatViewModel
import app.rasul.chat.OrderedWriter
import app.rasul.chat.StoredChatDrafts
import app.rasul.chat.RunMemory
import app.rasul.chat.TargetLoader
import app.rasul.data.ConnectionStore
import app.rasul.data.DraftStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.PinStore
import app.rasul.data.RunFrame
import app.rasul.data.RunStatus
import app.rasul.data.RunUpdate
import app.rasul.data.RunsApi
import app.rasul.data.SecretCipher
import app.rasul.data.StartedRun
import app.rasul.data.ApprovalChoice
import app.rasul.data.Connection
import app.rasul.data.pinnedFirst
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reversible, but the ciphertext never contains the plain text. */
private object ScrambleCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:" + plain.toByteArray().reversed().joinToString("") { "%02x".format((it.toInt() and 0xff) xor 0x5a) }
    override fun decrypt(token: String): String {
        check(token.startsWith("enc:"))
        return String(token.removePrefix("enc:").chunked(2).map { (it.toInt(16) xor 0x5a).toByte() }.reversed().toByteArray())
    }
}

/** Saved drafts and pinned chats: the stores, the chat's use of them, and the list order. */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftPinTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ds: DataStore<Preferences>
    private var clock = 1L
    private lateinit var drafts: DraftStore
    private lateinit var pins: PinStore

    @Before fun setUp() {
        ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("drafts.preferences_pb") }
        drafts = DraftStore(ds, ScrambleCipher, now = { clock++ })
        pins = PinStore(ds)
    }

    @After fun tearDown() = scope.cancel()

    private fun rawValues() = runBlocking { ds.data.first().asMap().values.map { it.toString() } }

    // ---- drafts: the store ----

    @Test fun draftRoundTripsAndIsEncryptedAtRest() = runBlocking {
        drafts.save("bot", "default", "s1", "meet me at the secret place")
        assertEquals("meet me at the secret place", drafts.load("bot", "default", "s1"))
        assertTrue(rawValues().isNotEmpty())
        assertTrue(rawValues().none { it.contains("secret") })
        assertNull(drafts.load("bot", "default", "s2"))
        assertNull(drafts.load("other", "default", "s1"))
        assertNull(drafts.load("bot", "work", "s1"))
    }

    @Test fun aDraftThatCannotBeDecryptedReadsAsNone() = runBlocking {
        drafts.save("bot", null, "s1", "text")
        val broken = DraftStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String): String = error("keystore reset")
        })
        assertNull(broken.load("bot", null, "s1"))
        assertTrue(broken.firstLines("bot", null).first().isEmpty())
    }

    @Test fun aDraftIsCutAtTwentyThousandChars() = runBlocking {
        drafts.save("bot", null, "s1", "x".repeat(DraftStore.MAX_CHARS + 500))
        assertEquals(20_000, drafts.load("bot", null, "s1")!!.length)
        drafts.save("bot", null, "s2", "y".repeat(20_000))
        assertEquals(20_000, drafts.load("bot", null, "s2")!!.length)
    }

    @Test fun theOldestDraftsDropPastTwoHundred() = runBlocking {
        repeat(DraftStore.MAX_DRAFTS) { drafts.save("bot", null, "s$it", "draft $it") }
        assertEquals("draft 0", drafts.load("bot", null, "s0"))
        // Re-saving s0 makes it the newest; s1 is now the oldest.
        drafts.save("bot", null, "s0", "draft 0 again")
        drafts.save("bot", null, "extra", "one more")
        assertNull(drafts.load("bot", null, "s1"))
        assertEquals("draft 0 again", drafts.load("bot", null, "s0"))
        assertEquals("one more", drafts.load("bot", null, "extra"))
        assertEquals("draft 2", drafts.load("bot", null, "s2"))
        assertEquals(DraftStore.MAX_DRAFTS, drafts.firstLines("bot", null).first().size)
    }

    @Test fun blankTextClearsTheDraft() = runBlocking {
        drafts.save("bot", null, "s1", "hello")
        drafts.save("bot", null, "s1", "  \n ")
        assertNull(drafts.load("bot", null, "s1"))
        assertTrue(rawValues().isEmpty())
    }

    @Test fun clearingOneDraftLeavesTheOthers() = runBlocking {
        drafts.save("bot", null, "s1", "one")
        drafts.save("bot", null, "s2", "two")
        drafts.clear("bot", null, "s1")
        assertNull(drafts.load("bot", null, "s1"))
        assertEquals("two", drafts.load("bot", null, "s2"))
    }

    @Test fun deletingAChatClearsItsDraftUnderEveryBot() = runBlocking {
        drafts.save("bot-a", null, "gone", "one")
        drafts.save("bot-b", "work", "gone", "two")
        drafts.save("bot-a", null, "stays", "three")
        drafts.clearSession("gone")
        assertNull(drafts.load("bot-a", null, "gone"))
        assertNull(drafts.load("bot-b", "work", "gone"))
        assertEquals("three", drafts.load("bot-a", null, "stays"))
    }

    private fun connectionStore() = ConnectionStore(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("connections.preferences_pb") },
        ScrambleCipher,
    ) { prefix -> drafts.clearBot(prefix); pins.clearBot(prefix) }

    @Test fun removingABotClearsItsDraftsAndPinsButNotAnotherBots() = runBlocking {
        val store = connectionStore()
        val a = store.save(Connection("", "A", "https://a.example.invalid"), "ka")
        val b = store.save(Connection("", "B", "https://b.example.invalid"), "kb")
        drafts.save(a, null, "s1", "draft a")
        drafts.save(b, null, "s1", "draft b")
        pins.pin(a, null, "s1")
        pins.pin(b, null, "s1")
        store.delete(a)
        assertNull(drafts.load(a, null, "s1"))
        assertTrue(pins.pinned(a, null).first().isEmpty())
        assertEquals("draft b", drafts.load(b, null, "s1"))
        assertEquals(listOf("s1"), pins.pinned(b, null).first())
    }

    // Decision (ConnectionStore.save): editing a PC (new address, profile or key) keeps its drafts and pins. They are
    // keyed by chat id, a chat id means nothing to another server, and a pin that no longer matches a chat is dropped
    // by the list; only the run pointers, which would resume a run on the wrong server, are reset by an edit.
    @Test fun editingAPcKeepsItsDraftsAndPinsAndRemovingItClearsThem() = runBlocking {
        val store = connectionStore()
        val id = store.save(Connection("", "A", "https://a.example.invalid"), "ka")
        drafts.save(id, null, "s1", "draft a")
        drafts.save(id, null, "", "new chat draft")
        pins.pin(id, null, "s1")
        store.save(Connection(id, "A", "https://moved.example.invalid", "other"), "new-key")
        assertEquals("draft a", drafts.load(id, null, "s1"))
        assertEquals("new chat draft", drafts.load(id, null, ""))
        assertEquals(listOf("s1"), pins.pinned(id, null).first())
        store.delete(id)
        assertNull(drafts.load(id, null, "s1"))
        assertNull(drafts.load(id, null, ""))
        assertTrue(pins.pinned(id, null).first().isEmpty())
        assertTrue(rawValues().isEmpty())
    }

    @Test fun removingABridgeClearsItsDraftsAndPinsButNotAnotherBridges() = runBlocking {
        val bridges = app.rasul.bridge.BridgeStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("bridges.preferences_pb") }, ScrambleCipher,
        ) { prefix -> drafts.clearBot(prefix); pins.clearBot(prefix) }
        bridges.save(app.rasul.bridge.SavedBridge("one", "Bridge", listOf("wss://example.invalid"), "", "", "one"))
        drafts.save("bridge:one:default", "default", "s1", "draft one")
        drafts.save("bridge:two:default", "default", "s1", "draft two")
        pins.pin("bridge:one:default", "default", "s1")
        pins.pin("bridge:two:default", "default", "s1")
        bridges.delete("one")
        assertNull(drafts.load("bridge:one:default", "default", "s1"))
        assertTrue(pins.pinned("bridge:one:default", "default").first().isEmpty())
        assertEquals("draft two", drafts.load("bridge:two:default", "default", "s1"))
        assertEquals(listOf("s1"), pins.pinned("bridge:two:default", "default").first())
    }

    @Test fun anEmojiAtTheCutIsNeverSplit() = runBlocking {
        val emoji = "\uD83D\uDE00"
        // The pair would straddle the 20 000th char: it is dropped whole.
        drafts.save("bot", null, "s1", "a".repeat(DraftStore.MAX_CHARS - 1) + emoji + "tail")
        val straddling = drafts.load("bot", null, "s1")!!
        assertEquals(DraftStore.MAX_CHARS - 1, straddling.length)
        assertTrue(straddling.none { it.isSurrogate() })
        // The pair fits exactly: it stays whole.
        drafts.save("bot", null, "s2", "a".repeat(DraftStore.MAX_CHARS - 2) + emoji + "tail")
        val fitting = drafts.load("bot", null, "s2")!!
        assertEquals(DraftStore.MAX_CHARS, fitting.length)
        assertTrue(fitting.endsWith(emoji))
        // Short text is untouched.
        assertEquals("x$emoji", DraftStore.cut("x$emoji"))
    }

    @Test fun worstCaseDraftsNeverTouchTheBreadcrumbStore() = runBlocking {
        val crumbFile = tmp.root.resolve("crumbs.preferences_pb")
        val crumbs = ConnectionStore(PreferenceDataStoreFactory.create(scope = scope) { crumbFile }, ScrambleCipher)
        val id = crumbs.save(Connection("", "A", "https://a.example.invalid"), "ka")
        crumbs.setCurrentSessionRun(id, null, crumbs.sessionId(id, null), "run-1", 3, "anchor")
        val before = crumbFile.readBytes()
        val plain = DraftStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        })
        repeat(DraftStore.MAX_DRAFTS) { plain.save(id, null, "s$it", "x".repeat(DraftStore.MAX_CHARS)) }
        assertEquals(DraftStore.MAX_DRAFTS, plain.firstLines(id, null).first().size)
        assertTrue(tmp.root.resolve("drafts.preferences_pb").length() > 200L * DraftStore.MAX_CHARS)
        // Run breadcrumbs are written to their own file, byte for byte as before the drafts existed.
        assertTrue(before.contentEquals(crumbFile.readBytes()))
        crumbs.setCurrentSessionRun(id, null, crumbs.sessionId(id, null), "run-1", 4, "anchor")
        assertTrue(crumbFile.length() < 5_000)
    }

    @Test fun theListShowsTheFirstNonBlankLineOfEachDraft() = runBlocking {
        drafts.save("bot", "default", "s1", "\n  first line  \nsecond")
        drafts.save("bot", "default", "s2", "only")
        drafts.save("bot", "work", "s3", "other profile")
        drafts.save("other", "default", "s4", "other bot")
        assertEquals(mapOf("s1" to "first line", "s2" to "only"), drafts.firstLines("bot", "default").first())
    }

    // ---- pins ----

    @Test fun pinsKeepPinOrderAndUnpinRemoves() = runBlocking {
        assertTrue(pins.pin("bot", null, "c"))
        assertTrue(pins.pin("bot", null, "a"))
        assertTrue(pins.pin("bot", null, "b"))
        assertTrue(pins.pin("bot", null, "a"))
        assertEquals(listOf("c", "a", "b"), pins.pinned("bot", null).first())
        pins.unpin("bot", null, "a")
        assertEquals(listOf("c", "b"), pins.pinned("bot", null).first())
        assertTrue(pins.pinned("bot", "work").first().isEmpty())
        // Only ids are stored.
        assertTrue(rawValues().all { it.startsWith("[") })
    }

    @Test fun aBotHoldsAtMostTwentyPins() = runBlocking {
        repeat(PinStore.MAX_PER_BOT) { assertTrue(pins.pin("bot", null, "s$it")) }
        assertFalse(pins.pin("bot", null, "one-too-many"))
        assertTrue(pins.pin("bot", null, "s3"))
        assertEquals(20, pins.pinned("bot", null).first().size)
        assertTrue(pins.pin("other", null, "s1"))
        pins.unpin("bot", null, "s0")
        assertTrue(pins.pin("bot", null, "one-too-many"))
        assertEquals("one-too-many", pins.pinned("bot", null).first().last())
    }

    @Test fun pinsOfChatsMissingFromACompleteListAreDroppedAndTheCapFrees() = runBlocking {
        repeat(PinStore.MAX_PER_BOT) { pins.pin("bot", null, "gone-$it") }
        pins.pin("other", null, "gone-0")
        pins.retain("bot", null, setOf("a", "b"))
        assertTrue(pins.pinned("bot", null).first().isEmpty())
        assertEquals(listOf("gone-0"), pins.pinned("other", null).first())
        assertTrue(pins.pin("bot", null, "a", visible = setOf("a", "b")))
        pins.pin("bot", null, "b")
        pins.pin("bot", null, "c")
        pins.retain("bot", null, setOf("b", "a"))
        assertEquals(listOf("a", "b"), pins.pinned("bot", null).first())
    }

    @Test fun onlyPinsOfChatsTheListShowsCountTowardTheCap() = runBlocking {
        repeat(PinStore.MAX_PER_BOT) { pins.pin("bot", null, "old-$it") }
        // 20 pins of chats the list no longer reaches: a new pin is allowed and the hidden ones stay.
        assertTrue(pins.pin("bot", null, "new", visible = setOf("new")))
        assertEquals(PinStore.MAX_PER_BOT + 1, pins.pinned("bot", null).first().size)
        // 20 pins of shown chats still stop the next one.
        val shown = (0 until PinStore.MAX_PER_BOT).map { "old-$it" }.toSet() + "new"
        assertFalse(pins.pin("bot", null, "one-too-many", visible = shown + "one-too-many"))
        assertEquals(PinStore.MAX_PER_BOT + 1, pins.pinned("bot", null).first().size)
        assertEquals("old-0", pins.pinned("bot", null).first().first())
    }

    @Test fun theStoredPinListIsBoundedAndKeepsTheNewestPinsLast() = runBlocking {
        repeat(150) { assertTrue(pins.pin("bot", null, "s$it", visible = setOf("s$it"))) }
        val stored = pins.pinned("bot", null).first()
        assertEquals(PinStore.MAX_STORED, stored.size)
        assertEquals("s50", stored.first())
        assertEquals("s149", stored.last())
    }

    @Test fun aDeletedChatLeavesEveryPinList() = runBlocking {
        pins.pin("bot-a", null, "gone")
        pins.pin("bot-a", null, "stays")
        pins.pin("bot-b", "work", "gone")
        pins.removeSession("gone")
        assertEquals(listOf("stays"), pins.pinned("bot-a", null).first())
        assertTrue(pins.pinned("bot-b", "work").first().isEmpty())
        assertTrue(rawValues().none { it.contains("gone") })
    }

    @Test fun pinnedChatsSortFirstInPinOrderAndTheRestKeepTheirOrder() {
        val list = listOf("a", "b", "c", "d", "e").map { SessionDto(it, "chat $it") }
        assertEquals(listOf("a", "b", "c", "d", "e"), pinnedFirst(list, emptyList()) { it.id }.map { it.id })
        assertEquals(listOf("d", "b", "a", "c", "e"), pinnedFirst(list, listOf("d", "b")) { it.id }.map { it.id })
        // A pin whose chat is not in the list (deleted elsewhere, or filtered out) is skipped.
        assertEquals(listOf("c", "a", "b", "d", "e"), pinnedFirst(list, listOf("zzz", "c")) { it.id }.map { it.id })
    }

    // ---- the chat ----

    private class Drafts(var stored: String? = null, val gate: CompletableDeferred<Unit>? = null) : ChatDrafts {
        val log = mutableListOf<String>()
        override suspend fun load(session: String): String? { gate?.await(); return stored }
        override fun save(session: String, text: String) { log += "save $session $text"; stored = text }
        override fun clear(session: String) { log += "clear $session"; stored = null }
        override fun accepted(session: String) { log += "accepted $session" }
    }

    private class Memory(override val drafts: ChatDrafts?, private val session: String = "sess-1") : RunMemory {
        override suspend fun sessionId() = session
        override suspend fun activeRun(): String? = null
        override suspend fun lastEventId(): Long? = null
        override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) = Unit
    }

    private class Runs : RunsApi {
        var start: HermesResult<StartedRun> = HermesResult.Ok(StartedRun("run_1"))
        var steer: HermesResult<Unit> = HermesResult.Ok(Unit)
        override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?) = start
        override suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus> = HermesResult.Ok(RunStatus("running", null, null))
        override suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit> = HermesResult.Ok(Unit)
        override suspend fun steer(target: HermesTarget, runId: String, text: String) = steer
        override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?): HermesResult<Unit> = HermesResult.Ok(Unit)
        override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> = emptyFlow()
    }

    private val sched = TestCoroutineScheduler()
    private val runs = Runs()
    private val chatDrafts = Drafts()

    private fun chat(store: ChatDrafts? = chatDrafts, session: String = "sess-1"): ChatViewModel {
        Dispatchers.setMain(StandardTestDispatcher(sched))
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(t) }, runs, object : SessionsApi {
                override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> = HermesResult.Ok(emptyList())
            },
            Memory(store, session), OrderedWriter(CoroutineScope(StandardTestDispatcher(sched))), now = { 0L },
            follow = { _, _, _ -> MutableSharedFlow<RunUpdate>(replay = 1) },
            workDispatcher = StandardTestDispatcher(sched),
        ).also { sched.advanceUntilIdle() }
    }

    @Test fun typingSavesOnlyAfterTheTypingPauses() {
        val vm = chat()
        vm.onDraft("hel")
        sched.advanceTimeBy(300)
        sched.runCurrent()
        vm.onDraft("hello")
        sched.advanceTimeBy(300)
        sched.runCurrent()
        assertTrue(chatDrafts.log.isEmpty())
        sched.advanceTimeBy(250)
        sched.runCurrent()
        assertEquals(listOf("save sess-1 hello"), chatDrafts.log)
    }

    @Test fun openingTheChatRestoresTheSavedDraftWithoutRewritingIt() {
        chatDrafts.stored = "left over"
        val vm = chat()
        assertEquals("left over", vm.state.value.draft)
        sched.advanceUntilIdle()
        assertTrue(chatDrafts.log.isEmpty())
    }

    @Test fun leavingBeforeTheDelayStillKeepsWhatWasTyped() {
        val vm = chat()
        vm.onDraft("quick words")
        vm.close()
        assertEquals("quick words", chatDrafts.stored)
    }

    @Test fun aSuccessfulSendClearsTheSavedDraft() {
        chatDrafts.stored = "send me"
        val vm = chat()
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
        assertNull(chatDrafts.stored)
        assertEquals(listOf("accepted sess-1", "clear sess-1"), chatDrafts.log)
    }

    @Test fun textTypedJustBeforeSendingIsNotSavedAfterItWasSent() {
        val vm = chat()
        vm.onDraft("sent text")
        vm.send()
        sched.advanceUntilIdle()
        sched.advanceTimeBy(2_000)
        assertNull(chatDrafts.stored)
        assertTrue(chatDrafts.log.none { it.startsWith("save") })
    }

    @Test fun aFailedSendKeepsTheDraftInTheComposerAndInTheStore() {
        runs.start = HermesResult.Err(HermesError.Server(500))
        chatDrafts.stored = "try again"
        val vm = chat()
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("try again", vm.state.value.draft)
        assertEquals("try again", chatDrafts.stored)
        assertTrue(chatDrafts.log.none { it.startsWith("clear") })
    }

    @Test fun aSteerClearsTheDraftOnlyOnceTheServerTookIt() {
        val vm = chat()
        vm.onDraft("first")
        vm.send()
        sched.advanceUntilIdle()
        assertNull(chatDrafts.stored)
        runs.steer = HermesResult.Err(HermesError.Server(500))
        vm.onDraft("then this")
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("then this", vm.state.value.draft)
        assertEquals("then this", chatDrafts.stored)
        runs.steer = HermesResult.Ok(Unit)
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
        assertNull(chatDrafts.stored)
    }

    @Test fun aDraftReadLateNeverOverwritesWhatTheUserDidMeanwhile() {
        val gate = CompletableDeferred<Unit>()
        val slow = Drafts(stored = "old saved text", gate = gate)
        val vm = chat(slow)
        vm.onDraft("typed")
        vm.onDraft("")
        gate.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals("", vm.state.value.draft)

        // Nothing touched the composer before the read ended: the saved text comes back.
        val gate2 = CompletableDeferred<Unit>()
        val untouched = chat(Drafts(stored = "old saved text", gate = gate2))
        gate2.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals("old saved text", untouched.state.value.draft)
    }

    @Test fun aSendMadeBeforeTheLateReadEndsAlsoBlocksTheRestore() {
        val gate = CompletableDeferred<Unit>()
        val slow = Drafts(stored = "old saved text", gate = gate)
        val vm = chat(slow)
        vm.onDraft("go")
        vm.send()
        sched.advanceUntilIdle()
        gate.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
    }

    @Test fun theChatTellsItsDraftStoreWhenTheServerTookTheFirstMessage() {
        runs.start = HermesResult.Err(HermesError.Server(500))
        val vm = chat(session = "rasul-new")
        vm.onDraft("first words")
        vm.send()
        sched.advanceUntilIdle()
        assertTrue(chatDrafts.log.none { it.startsWith("accepted") })
        runs.start = HermesResult.Ok(StartedRun("run_1"))
        vm.send()
        sched.advanceUntilIdle()
        assertTrue("accepted rasul-new" in chatDrafts.log)
    }

    // ---- a "New chat" draft survives the next New chat ----

    private class Rig(val sched: TestCoroutineScheduler, val tmp: java.io.File) {
        val dispatcher = StandardTestDispatcher(sched)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { tmp.resolve("rig.preferences_pb") }
        val store = DraftStore(ds, ScrambleCipher, io = dispatcher)
        val writer = OrderedWriter(scope)
        fun drafts(newSession: String?) = StoredChatDrafts(store, "bot", null, writer, newSession)
        fun <T> read(block: suspend () -> T): T {
            val d = scope.async { block() }
            sched.advanceUntilIdle()
            return d.getCompleted()
        }
        fun draftKeys() = read { ds.data.first().asMap().keys.map { it.name }.filter { it.startsWith("draft") } }
        fun stop() = scope.cancel()
    }

    private var rigs = mutableListOf<Rig>()
    private fun rig() = Rig(sched, tmp.root).also { rigs += it }

    @After fun stopRigs() = rigs.forEach { it.stop() }

    @Test fun textTypedInANewChatComesBackInTheNextNewChat() {
        val rig = rig()
        val first = chat(rig.drafts("rasul-a"), "rasul-a")
        first.onDraft("remember this")
        first.close()
        sched.advanceUntilIdle()
        assertEquals("remember this", rig.read { rig.store.load("bot", null, "") })
        // Nothing is stored under the throwaway session id.
        assertNull(rig.read { rig.store.load("bot", null, "rasul-a") })

        val second = chat(rig.drafts("rasul-b"), "rasul-b")
        sched.advanceUntilIdle()
        assertEquals("remember this", second.state.value.draft)
        second.close()
        sched.advanceUntilIdle()
        assertEquals(1, rig.draftKeys().count { it.startsWith("draft_") })
    }

    @Test fun sendingTheFirstMessageClearsTheNewChatDraftAndLaterTextIsKeptUnderTheChat() {
        val rig = rig()
        val vm = chat(rig.drafts("rasul-a"), "rasul-a")
        vm.onDraft("hello there")
        sched.advanceTimeBy(600)
        sched.advanceUntilIdle()
        assertEquals("hello there", rig.read { rig.store.load("bot", null, "") })
        vm.send()
        sched.advanceUntilIdle()
        assertNull(rig.read { rig.store.load("bot", null, "") })
        assertNull(rig.read { rig.store.load("bot", null, "rasul-a") })
        assertTrue(rig.draftKeys().none { it.startsWith("draft_") || it.startsWith("draftat_") })

        // The chat is a real chat now: what is typed next belongs to it, not to the next New chat.
        vm.onDraft("follow up")
        sched.advanceTimeBy(600)
        sched.advanceUntilIdle()
        assertEquals("follow up", rig.read { rig.store.load("bot", null, "rasul-a") })
        assertNull(rig.read { rig.store.load("bot", null, "") })
        val next = chat(rig.drafts("rasul-b"), "rasul-b")
        sched.advanceUntilIdle()
        assertEquals("", next.state.value.draft)
    }

    @Test fun aRebuiltChatThatWasAlreadySentStaysUnderItsOwnSessionId() {
        val rig = rig()
        val vm = chat(rig.drafts("rasul-a"), "rasul-a")
        vm.onDraft("hello there")
        vm.send()
        sched.advanceUntilIdle()

        // The same route is opened again (process death, new revision, failed load): a new store object, same newSession.
        val rebuilt = chat(rig.drafts("rasul-a"), "rasul-a")
        rebuilt.onDraft("typed after the send")
        sched.advanceTimeBy(600)
        sched.advanceUntilIdle()
        assertEquals("typed after the send", rig.read { rig.store.load("bot", null, "rasul-a") })
        assertNull(rig.read { rig.store.load("bot", null, "") })

        // A later rebuild reads it back from the chat, and the next New chat does not see it.
        val again = chat(rig.drafts("rasul-a"), "rasul-a")
        sched.advanceUntilIdle()
        assertEquals("typed after the send", again.state.value.draft)
        val next = chat(rig.drafts("rasul-b"), "rasul-b")
        sched.advanceUntilIdle()
        assertEquals("", next.state.value.draft)
    }

    @Test fun aRebuiltChatsLaterAcceptedMessageDoesNotWipeTheNextNewChatsDraft() {
        val rig = rig()
        val first = chat(rig.drafts("rasul-a"), "rasul-a")
        first.onDraft("hello")
        first.send()
        sched.advanceUntilIdle()
        // The next New chat keeps unsent text in the shared slot.
        val next = chat(rig.drafts("rasul-b"), "rasul-b")
        next.onDraft("for later")
        next.close()
        sched.advanceUntilIdle()
        // The sent chat, rebuilt, has another message accepted.
        val rebuilt = chat(rig.drafts("rasul-a"), "rasul-a")
        rebuilt.onDraft("second message")
        rebuilt.send()
        sched.advanceUntilIdle()
        assertEquals("for later", rig.read { rig.store.load("bot", null, "") })
    }

    @Test fun anUnsentNewChatStillUsesTheSharedSlotAfterARebuild() {
        val rig = rig()
        val vm = chat(rig.drafts("rasul-a"), "rasul-a")
        vm.onDraft("not sent yet")
        vm.close()
        sched.advanceUntilIdle()
        val rebuilt = chat(rig.drafts("rasul-a"), "rasul-a")
        sched.advanceUntilIdle()
        assertEquals("not sent yet", rebuilt.state.value.draft)
        assertEquals("not sent yet", rig.read { rig.store.load("bot", null, "") })
        assertNull(rig.read { rig.store.load("bot", null, "rasul-a") })
    }

    @Test fun aRebuiltChatThatLoadsBeforeTheQueuedMarkLandsReadsItsOwnDraft() {
        val rig = rig()
        rig.read { rig.store.save("bot", null, "rasul-a", "mine"); rig.store.save("bot", null, "", "shared") }
        val markWaits = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.writer.submit { markWaits.await(); rig.store.markAccepted("bot", null, "rasul-a") }
        val load = rig.scope.async { rig.drafts("rasul-a").load("rasul-a") }
        sched.advanceUntilIdle()
        markWaits.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals("mine", load.getCompleted())
    }

    @Test fun aNotYetAcceptedAnswerIsReadAgainWhenTheMarkLands() {
        val rig = rig()
        val drafts = rig.drafts("rasul-a")
        assertNull(rig.read { drafts.load("rasul-a") })
        rig.writer.submit { rig.store.markAccepted("bot", null, "rasul-a") }
        drafts.save("rasul-a", "after the mark")
        sched.advanceUntilIdle()
        assertEquals("after the mark", rig.read { rig.store.load("bot", null, "rasul-a") })
        assertNull(rig.read { rig.store.load("bot", null, "") })
    }

    @Test fun deletingTheChatAlsoForgetsThatItWasSent() {
        val rig = rig()
        val vm = chat(rig.drafts("rasul-a"), "rasul-a")
        vm.onDraft("hello")
        vm.send()
        sched.advanceUntilIdle()
        assertTrue(rig.draftKeys().any { it.startsWith("draftsent_") })
        rig.read { rig.store.clearSession("rasul-a") }
        assertTrue(rig.draftKeys().none { it.startsWith("draftsent_") })
    }

    @Test fun aFailedFirstSendKeepsTheNewChatDraft() {
        runs.start = HermesResult.Err(HermesError.Server(500))
        val rig = rig()
        val vm = chat(rig.drafts("rasul-a"), "rasul-a")
        vm.onDraft("not sent")
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("not sent", vm.state.value.draft)
        assertEquals("not sent", rig.read { rig.store.load("bot", null, "") })
    }

    @Test fun anOrdinaryChatKeepsItsDraftUnderItsOwnSessionId() {
        val rig = rig()
        val vm = chat(rig.drafts(null), "chat-1")
        vm.onDraft("mine")
        vm.close()
        sched.advanceUntilIdle()
        assertEquals("mine", rig.read { rig.store.load("bot", null, "chat-1") })
        assertNull(rig.read { rig.store.load("bot", null, "") })
    }

    @Test fun aChatWithoutADraftStoreStillWorks() {
        val vm = chat(store = null)
        vm.onDraft("plain")
        sched.advanceTimeBy(1_000)
        vm.send()
        sched.advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
    }

    @After fun resetMain() = Dispatchers.resetMain()
}
