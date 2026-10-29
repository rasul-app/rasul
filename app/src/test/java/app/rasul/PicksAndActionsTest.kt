package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rasul.bridge.bridgeBotId
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.jobs.JobsClient
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobsApi
import app.rasul.jobs.JobsViewModel
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionsManagementApi
import app.rasul.sessions.SessionsClient
import app.rasul.sessions.SessionsViewModel
import app.rasul.settings.MoreMode
import app.rasul.settings.moreMode
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.PcPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One holder snapshot, ordered picks, source-bound actions, and pinned chats. */
@OptIn(ExperimentalCoroutinesApi::class)
class PicksAndActionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }

    /** [delayMs] slows every response; [jobId] lets two servers expose the SAME job id. */
    private fun server(tag: String, jobId: String = "j$tag", delayMs: Long = 0) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBodyDelay(delayMs, TimeUnit.MILLISECONDS).setBody(
                if (request.path.orEmpty().contains("jobs")) {
                    """{"jobs":[{"id":"$jobId","name":"Job$tag","enabled":false,"state":"paused"}]}"""
                } else {
                    """{"object":"list","data":[{"id":"s$tag","title":"Sess$tag","started_at":1760000000,"message_count":1}],"limit":50,"offset":0,"has_more":false}"""
                },
            )
        }
        start()
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        a = server("A")
        b = server("B")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { a.shutdown() }
        runCatching { b.shutdown() }
    }

    private val ds: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve("f.preferences_pb")
        }
    }

    /** Same data as [ds], but every write first runs [before] (n = 0, 1, ...): delay or throw. */
    private fun wrapped(before: suspend (Int) -> Unit): DataStore<Preferences> = object : DataStore<Preferences> by ds {
        var n = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            before(n++)
            return ds.updateData(transform)
        }
    }

    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)
    private fun url(s: MockWebServer) = "http://localhost:${s.port}/"
    private fun save(s: ConnectionStore, name: String, url: String) = runBlocking { s.save(Connection("", name, url), "k$name") }
    private fun jobsReady(vm: JobsViewModel, conn: String) = (vm.state.value as? Load.Ready)?.connection == conn
    private fun sessionsReady(vm: SessionsViewModel, conn: String) = (vm.state.value as? Load.Ready)?.connection == conn
    private fun jobsSource(vm: JobsViewModel) = (vm.state.value as Load.Ready).source
    private fun jobRow(vm: JobsViewModel) = (vm.state.value as Load.Ready).value.single()

    // Item 1
    @Test fun pendingActionFromAIsNotSentToBThatHasTheSameJobId() {
        a.shutdown(); b.shutdown()
        a = server("A", jobId = "same"); b = server("B", jobId = "same")
        val s = ConnectionStore(ds, cipher)
        save(s, "A", url(a))
        val idB = save(s, "B", url(b))
        val holder = ActivePcHolder(s, scope())
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        waitFor { jobsReady(jobs, "A") }
        val sourceA = jobsSource(jobs)
        val rowA = jobRow(jobs)
        holder.selectPc(idB)
        waitFor { jobsReady(jobs, "B") }

        jobs.togglePause(sourceA, rowA) // the dialog was opened on A and confirmed after B became active
        waitFor { jobs.actionError.value == HermesError.ConnectionChanged && jobs.busy.value == null }
        waitFor { jobsReady(jobs, "B") }
        repeat(b.requestCount) { assertEquals("GET", b.takeRequest().method) } // 0 action requests reached B
    }

    // Item 2
    @Test fun editingAddressProfileOrKeyOfTheActivePcReloadsBothViewModels() {
        val s = ConnectionStore(ds, cipher)
        val idA = save(s, "A", url(a))
        val holder = ActivePcHolder(s, scope())
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        val sessions = SessionsViewModel(s, holder, SessionsClient(HermesClient()))
        waitFor { jobsReady(jobs, "A") && sessionsReady(sessions, "A") }
        fun both(check: (HermesTarget) -> Boolean) = waitFor {
            val j = (jobs.state.value as? Load.Ready)?.source?.target
            val x = (sessions.state.value as? Load.Ready)?.source?.target
            j != null && x != null && check(j) && check(x)
        }
        val urlB = url(b)
        runBlocking { s.save(Connection(idA, "A", urlB), null) } // address only
        both { it.baseUrl == urlB && it.profile == null && it.apiKey == "kA" }
        runBlocking { s.save(Connection(idA, "A", urlB, "p2"), null) } // profile only
        both { it.profile == "p2" && it.apiKey == "kA" }
        runBlocking { s.save(Connection(idA, "A", urlB, "p2"), "k2") } // key only
        both { it.apiKey == "k2" }
        assertEquals("JobB", jobRow(jobs).name)
    }

    // Item 3
    @Test fun delayedWriteForBThenPickOfAEndsAtA() {
        val s0 = ConnectionStore(ds, cipher)
        val idA = save(s0, "A", url(a))
        val idB = save(s0, "B", url(b))
        val holder = ActivePcHolder(ConnectionStore(wrapped { n -> if (n == 0) delay(400) }, cipher), scope())
        waitFor { holder.state.value.loaded }
        holder.selectPc(idB) // slow write
        holder.selectPc(idA) // A is also what is observed: must still be written, after B
        Thread.sleep(900)
        waitFor { holder.state.value.activeId == idA && runBlocking { s0.active()?.id } == idA }
        assertNull(holder.state.value.writeError)
    }

    @Test fun picksBCDQuicklyEndAtD() {
        val s0 = ConnectionStore(ds, cipher)
        save(s0, "A", url(a))
        val ids = listOf("B", "C", "D").map { save(s0, it, url(b) + it + "/") }
        val holder = ActivePcHolder(ConnectionStore(wrapped { delay(50) }, cipher), scope())
        waitFor { holder.state.value.loaded }
        ids.forEach { holder.selectPc(it) }
        waitFor { holder.state.value.activeId == ids[2] }
        Thread.sleep(300)
        assertEquals(ids[2], holder.state.value.activeId)
        assertEquals(ids[2], runBlocking { s0.active()?.id })
    }

    @Test fun failingOldWriteNeverReplacesANewerGoodState() {
        val s0 = ConnectionStore(ds, cipher)
        save(s0, "A", url(a))
        val idB = save(s0, "B", url(b))
        val idC = save(s0, "C", url(b) + "c/")
        val gate = CountDownLatch(1)
        val finished = AtomicInteger(0)
        val flaky = object : DataStore<Preferences> by ds {
            var n = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                try {
                    if (n++ == 0) {
                        withContext(Dispatchers.IO) { runInterruptible { gate.await() } } // B's write waits here, then fails
                        throw IOException("disk full")
                    }
                    return ds.updateData(transform)
                } finally {
                    finished.incrementAndGet()
                }
            }
        }
        val holder = ActivePcHolder(ConnectionStore(flaky, cipher), scope())
        waitFor { holder.state.value.loaded }
        holder.selectPc(idB)
        holder.selectPc(idC)
        Thread.sleep(300) // an unordered writer lets C succeed here, before B has failed
        gate.countDown() // B's failure is released only now
        waitFor { finished.get() == 2 } // both writes are over
        Thread.sleep(300) // let the state settle
        assertEquals(idC, holder.state.value.activeId)
        assertEquals(idC, runBlocking { s0.active()?.id })
        assertNull(holder.state.value.writeError) // B's older failure must not outlive C's success
        assertEquals(3, holder.state.value.pcs.size)
        assertNull(holder.state.value.readError)
    }

    // Item 4 (+ holder write failure)
    @Test fun throwingSetActiveKeepsRowsAndShowsANotice() {
        val s0 = ConnectionStore(ds, cipher)
        save(s0, "A", url(a))
        val idB = save(s0, "B", url(b))
        val holder = ActivePcHolder(ConnectionStore(wrapped { throw IOException("disk full") }, cipher), scope())
        val jobs = JobsViewModel(s0, holder, JobsClient(HermesClient()))
        val sessions = SessionsViewModel(s0, holder, SessionsClient(HermesClient()))
        waitFor { jobsReady(jobs, "A") && sessionsReady(sessions, "A") }
        holder.selectPc(idB)
        waitFor { holder.state.value.writeError == "disk full" }
        Thread.sleep(200)
        assertTrue(jobsReady(jobs, "A") && sessionsReady(sessions, "A")) // rows kept, not Failed
        assertEquals(2, holder.state.value.pcs.size)
        assertNull(holder.state.value.readError)
        holder.clearWriteError()
        assertNull(holder.state.value.writeError)
    }

    // Item 5 / 8 (More rendering decision)
    @Test fun moreShowsNothingThenReadErrorThenRecovers() {
        val s0 = ConnectionStore(ds, cipher)
        val idA = save(s0, "A", url(a))
        var reads = 0
        val flaky = object : DataStore<Preferences> by ds {
            override val data: Flow<Preferences> = flow {
                if (reads++ < 1) throw IOException("transient")
                ds.data.collect { emit(it) }
            }
        }
        assertEquals(MoreMode.Blank, moreMode(PcPicker())) // before the first read: nothing
        val holder = ActivePcHolder(ConnectionStore(flaky, cipher), scope())
        assertEquals(MoreMode.Error("transient"), moreMode(holder.state.value))
        holder.retry()
        waitFor { moreMode(holder.state.value) == MoreMode.Content }
        assertEquals(idA, holder.state.value.activeId)
        assertEquals(1, holder.state.value.pcs.size)
    }

    // Item 6
    @Test fun actionInFlightForAThenSwitchToBLeavesBUsableAndAErrorFree() {
        a.shutdown(); b.shutdown()
        val postSeen = CountDownLatch(1)
        val release = CountDownLatch(1)
        a = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postSeen.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                    }
                    return MockResponse().setBody("""{"jobs":[{"id":"j1","name":"JobA","enabled":false,"state":"paused"}]}""")
                }
            }
            start()
        }
        b = server("B", jobId = "j1")
        val s = ConnectionStore(ds, cipher)
        save(s, "A", url(a))
        val idB = save(s, "B", url(b))
        val holder = ActivePcHolder(s, scope())
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        waitFor { jobsReady(jobs, "A") }
        jobs.togglePause(jobsSource(jobs), jobRow(jobs))
        assertTrue("A's POST never arrived", postSeen.await(5, TimeUnit.SECONDS))
        holder.selectPc(idB)
        waitFor { jobsReady(jobs, "B") && jobs.busy.value == null }
        release.countDown() // A now fails with its own 500
        Thread.sleep(800)
        assertNull(jobs.actionError.value) // A's late error is dropped
        assertNull(jobs.busy.value) // B's buttons stay enabled
        assertTrue(jobsReady(jobs, "B"))
        val before = b.requestCount
        jobs.togglePause(jobsSource(jobs), jobRow(jobs)) // and B's button still works
        waitFor { b.requestCount == before + 1 }
    }

    // Item 7 / 8
    @Test fun slowAFinishingAfterSwitchToBShowsNoARowsInEitherViewModel() {
        a.shutdown()
        a = server("A", delayMs = 1200)
        val s = ConnectionStore(ds, cipher)
        save(s, "A", url(a))
        val idB = save(s, "B", url(b))
        val holder = ActivePcHolder(s, scope())
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        val sessions = SessionsViewModel(s, holder, SessionsClient(HermesClient()))
        Thread.sleep(200) // A requests are in flight
        holder.selectPc(idB)
        waitFor { jobsReady(jobs, "B") && sessionsReady(sessions, "B") }
        Thread.sleep(1600) // A's responses arrive now
        assertTrue(jobsReady(jobs, "B") && sessionsReady(sessions, "B"))
        assertEquals("JobB", jobRow(jobs).name)
        assertEquals("SessB", (sessions.state.value as Load.Ready).value.single().title)
    }

    @Test fun refreshLoadsExactlyTheHolderTarget() {
        val s = ConnectionStore(ds, cipher)
        val idA = save(s, "A", url(a))
        val idB = save(s, "B", url(b))
        runBlocking { s.setActive(idA) }
        // The holder sees the store once (A) and then never again; the store itself moves on to B.
        val frozen = object : DataStore<Preferences> by ds {
            override val data: Flow<Preferences> = flow {
                emit(ds.data.first())
                awaitCancellation()
            }
        }
        val holder = ActivePcHolder(ConnectionStore(frozen, cipher), scope())
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        val sessions = SessionsViewModel(s, holder, SessionsClient(HermesClient()))
        waitFor { jobsReady(jobs, "A") && sessionsReady(sessions, "A") }
        waitFor { a.requestCount == 3 } // jobs list + sessions list + the bot chat look-up, all settled on A
        runBlocking { s.setActive(idB) }
        assertEquals(idB, runBlocking { s.active()?.id }) // targets now diverge: holder A, store B
        val na = a.requestCount
        val nb = b.requestCount
        jobs.refresh()
        sessions.refresh()
        waitFor { a.requestCount == na + 3 }
        Thread.sleep(300)
        assertEquals(na + 3, a.requestCount)
        assertEquals(nb, b.requestCount) // nothing went to the store's target
        assertTrue(jobsReady(jobs, "A") && sessionsReady(sessions, "A"))
    }

    @Test fun renamingAJobSendsOnlyChangedFieldsAndUnchangedEditSendsNothing() {
        val store = ConnectionStore(ds, cipher)
        save(store, "A", url(a))
        val holder = ActivePcHolder(store, scope())
        val api = MutableJobsApi()
        val jobs = JobsViewModel(store, holder, api)
        waitFor { jobsReady(jobs, "A") }

        val ready = jobs.state.value as Load.Ready
        val original = ready.value.single()
        jobs.save(ready.source, original, "Evening summary", original.scheduleInput, original.prompt.orEmpty())
        waitFor {
            (jobs.state.value as? Load.Ready)?.value?.singleOrNull()?.name == "Evening summary" && jobs.busy.value == null
        }
        assertEquals(listOf(Triple("Evening summary", null, null)), api.updates)

        val renamed = (jobs.state.value as Load.Ready).value.single()
        jobs.save(ready.source, renamed, renamed.name.orEmpty(), renamed.scheduleInput, renamed.prompt.orEmpty())
        assertEquals(1, api.updates.size)
    }

    @Test fun deletingCurrentSessionClearsMatchingDirectAndBridgePointers() {
        val store = ConnectionStore(ds, cipher)
        val directId = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        val currentSession = runBlocking { store.sessionId(directId, null) }
        val bridgeId = bridgeBotId("bridge-one", "profile-one")
        val bridgeSessionKey = stringPreferencesKey("session_${bridgeId}_profile-one")
        runBlocking {
            ds.edit { prefs -> prefs[bridgeSessionKey] = currentSession }
            store.setActiveRun(directId, null, currentSession, "direct-run", 7L, "direct-anchor")
            store.setActiveRun(bridgeId, "profile-one", currentSession, "bridge-run", 9L, "bridge-anchor")
        }

        val holder = ActivePcHolder(store, scope())
        val api = MutableSessionsApi(currentSession)
        val sessions = SessionsViewModel(store, holder, api)
        waitFor { sessionsReady(sessions, "A") }
        val ready = sessions.state.value as Load.Ready
        sessions.delete(ready.source, ready.value.single())
        waitFor { (sessions.state.value as? Load.Ready)?.value?.isEmpty() == true && sessions.busy.value == null }

        assertNotEquals(currentSession, runBlocking { store.sessionId(directId, null) })
        assertNotEquals(currentSession, runBlocking { store.sessionId(bridgeId, "profile-one") })
        assertNull(runBlocking { store.activeRun(directId, null, currentSession) })
        assertNull(runBlocking { store.lastEventId(directId, null, currentSession) })
        assertNull(runBlocking { store.runAnchor(directId, null, currentSession) })
        assertNull(runBlocking { store.activeRun(bridgeId, "profile-one", currentSession) })
        assertNull(runBlocking { store.lastEventId(bridgeId, "profile-one", currentSession) })
        assertNull(runBlocking { store.runAnchor(bridgeId, "profile-one", currentSession) })
    }

    @Test fun pinningShowsInTheViewModelAndDeletingTheChatClearsItsDraftAndPin() {
        val store = ConnectionStore(ds, cipher)
        val drafts = app.rasul.data.DraftStore(ds, cipher)
        val pins = app.rasul.data.PinStore(MemoryPrefs())
        val id = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        val holder = ActivePcHolder(store, scope())
        val sessions = SessionsViewModel(store, holder, MutableSessionsApi("chat-one"), drafts = drafts, pins = pins)
        waitFor { sessionsReady(sessions, "A") }
        val ready = sessions.state.value as Load.Ready
        val chat = ready.value.single()
        runBlocking { drafts.save(id, null, chat.id, "unsent words\nsecond line") }
        waitFor { sessions.draftLines.value == mapOf("chat-one" to "unsent words") }

        sessions.togglePin(ready.source, chat)
        waitFor { sessions.pinned.value == listOf("chat-one") }
        sessions.togglePin(ready.source, chat)
        waitFor { sessions.pinned.value.isEmpty() }
        sessions.togglePin(ready.source, chat)
        waitFor { sessions.pinned.value == listOf("chat-one") }

        sessions.delete(ready.source, chat)
        waitFor { (sessions.state.value as? Load.Ready)?.value?.isEmpty() == true && sessions.busy.value == null }
        waitFor { sessions.pinned.value.isEmpty() && sessions.draftLines.value.isEmpty() }
        assertNull(runBlocking { drafts.load(id, null, chat.id) })
    }

    @Test fun pinningPastTheLimitIsRefusedWithANoticeWhenTheTwentyPinnedChatsAreShown() {
        val store = ConnectionStore(ds, cipher)
        val pins = app.rasul.data.PinStore(MemoryPrefs())
        val id = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        // A full list (the newest 50), 20 of whose chats are pinned.
        val api = MutableSessionsApi("chat-one", more = app.rasul.sessions.SESSIONS_LIST_LIMIT - 1)
        runBlocking { repeat(app.rasul.data.PinStore.MAX_PER_BOT) { pins.pin(id, null, "row-$it") } }
        val holder = ActivePcHolder(store, scope())
        val sessions = SessionsViewModel(store, holder, api, pins = pins)
        waitFor { sessionsReady(sessions, "A") }
        val ready = sessions.state.value as Load.Ready
        sessions.togglePin(ready.source, ready.value.first { it.id == "chat-one" })
        waitFor { sessions.pinLimit.value }
        assertTrue("chat-one" !in sessions.pinned.value)
        assertEquals(app.rasul.data.PinStore.MAX_PER_BOT, sessions.pinned.value.size)
        sessions.clearPinLimit()
        assertFalse(sessions.pinLimit.value)
    }

    @Test fun pinsOfChatsMissingFromACompleteListAreDroppedSoANewPinIsAllowed() {
        val store = ConnectionStore(ds, cipher)
        val pins = app.rasul.data.PinStore(MemoryPrefs())
        val id = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        runBlocking { repeat(app.rasul.data.PinStore.MAX_PER_BOT) { pins.pin(id, null, "old-$it") } }
        val holder = ActivePcHolder(store, scope())
        val sessions = SessionsViewModel(store, holder, MutableSessionsApi("chat-one"), pins = pins)
        waitFor { sessionsReady(sessions, "A") }
        // The list holds 1 chat, fewer than the limit: it is complete, so the 20 stale pins are gone from the store.
        // (The view model's own pin list starts empty, so only the store proves the prune ran.)
        waitFor { runBlocking { pins.pinned(id, null).first() }.isEmpty() }
        waitFor { sessionsReady(sessions, "A") }
        val ready = sessions.state.value as Load.Ready
        sessions.togglePin(ready.source, ready.value.single())
        waitFor { sessions.pinned.value == listOf("chat-one") }
        assertFalse(sessions.pinLimit.value)
    }

    @Test fun twentyPinsOutsideAnIncompleteListStayAndStillAllowANewPin() {
        val store = ConnectionStore(ds, cipher)
        val pins = app.rasul.data.PinStore(MemoryPrefs())
        val id = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        runBlocking { repeat(app.rasul.data.PinStore.MAX_PER_BOT) { pins.pin(id, null, "old-$it") } }
        // 50 rows = the limit: the list may be cut, so older pinned chats are not assumed gone.
        val api = MutableSessionsApi("chat-one", more = app.rasul.sessions.SESSIONS_LIST_LIMIT - 1)
        val holder = ActivePcHolder(store, scope())
        val sessions = SessionsViewModel(store, holder, api, pins = pins)
        waitFor { sessionsReady(sessions, "A") }
        waitFor { sessions.pinned.value.size == app.rasul.data.PinStore.MAX_PER_BOT }
        val ready = sessions.state.value as Load.Ready
        // Old pins are neither fetched nor shown: the list is exactly what the server returned.
        assertEquals(app.rasul.sessions.SESSIONS_LIST_LIMIT, ready.value.size)
        assertTrue(ready.value.none { it.id.startsWith("old-") })
        sessions.togglePin(ready.source, ready.value.first { it.id == "chat-one" })
        waitFor { sessions.pinned.value.lastOrNull() == "chat-one" }
        assertFalse(sessions.pinLimit.value)
        assertEquals(app.rasul.data.PinStore.MAX_PER_BOT + 1, sessions.pinned.value.size)
        assertEquals("old-0", sessions.pinned.value.first())
    }

    @Test fun deletingAnotherSessionLeavesTheCurrentSessionPointerUntouched() {
        val store = ConnectionStore(ds, cipher)
        val directId = runBlocking { store.save(Connection("", "A", url(a)), "kA") }
        val currentSession = runBlocking { store.sessionId(directId, null) }
        val holder = ActivePcHolder(store, scope())
        val sessions = SessionsViewModel(store, holder, MutableSessionsApi("another-session"))
        waitFor { sessionsReady(sessions, "A") }
        val ready = sessions.state.value as Load.Ready
        sessions.delete(ready.source, ready.value.single())
        waitFor { (sessions.state.value as? Load.Ready)?.value?.isEmpty() == true && sessions.busy.value == null }

        assertEquals(currentSession, runBlocking { store.sessionId(directId, null) })
    }

    @Test fun resourceActionsUpdateRowsWithoutReloading() {
        val store = ConnectionStore(ds, cipher)
        save(store, "A", url(a))
        val holder = ActivePcHolder(store, scope())
        val jobs = JobsViewModel(store, holder, MutableJobsApi())
        val sessions = SessionsViewModel(store, holder, MutableSessionsApi())
        waitFor { jobsReady(jobs, "A") && sessionsReady(sessions, "A") }

        val jobSource = jobsSource(jobs)
        jobs.save(jobSource, null, "Evening summary", "0 10 * * *", "Summarize recent activity")
        waitFor { (jobs.state.value as? Load.Ready)?.value?.any { it.name == "Evening summary" } == true && jobs.busy.value == null }
        assertTrue(jobs.state.value is Load.Ready)
        val newJob = (jobs.state.value as Load.Ready).value.first { it.name == "Evening summary" }
        jobs.save(jobSource, newJob, "Morning summary", "0 9 * * *", "Summarize recent activity")
        waitFor {
            (jobs.state.value as? Load.Ready)?.value?.any { it.id == newJob.id && it.name == "Morning summary" } == true &&
                jobs.busy.value == null
        }
        assertEquals(2, (jobs.state.value as Load.Ready).value.size)
        jobs.delete(jobSource, newJob.copy(name = "Morning summary"))
        waitFor { (jobs.state.value as? Load.Ready)?.value?.none { it.id == newJob.id } == true && jobs.busy.value == null }
        assertTrue(jobs.state.value is Load.Ready)

        val sessionState = sessions.state.value as Load.Ready
        val session = sessionState.value.single()
        sessions.rename(sessionState.source, session, "Retitled chat")
        waitFor { (sessions.state.value as? Load.Ready)?.value?.singleOrNull()?.title == "Retitled chat" && sessions.busy.value == null }
        assertTrue(sessions.state.value is Load.Ready)
        sessions.delete(sessionState.source, session)
        waitFor { (sessions.state.value as? Load.Ready)?.value?.isEmpty() == true && sessions.busy.value == null }
        assertTrue(sessions.state.value is Load.Ready)
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }

    private class MutableJobsApi : JobsApi {
        val updates = mutableListOf<Triple<String?, String?, String?>>()
        private val rows = mutableListOf(
            JobDto(
                "job-one", "Morning summary", "0 9 * * *", enabled = true,
                schedule = JsonPrimitive("0 9 * * *"), prompt = "Summarize recent activity",
            ),
        )

        override suspend fun create(target: HermesTarget, name: String, schedule: String, prompt: String): HermesResult<JobDto> {
            val created = JobDto(
                "job-two", name, schedule, enabled = true, schedule = JsonPrimitive(schedule), prompt = prompt,
            )
            rows += created
            return HermesResult.Ok(created)
        }

        override suspend fun update(
            target: HermesTarget,
            id: String,
            name: String?,
            schedule: String?,
            prompt: String?,
        ): HermesResult<JobDto> {
            updates += Triple(name, schedule, prompt)
            val index = rows.indexOfFirst { it.id == id }
            if (index < 0) return HermesResult.Err(HermesError.ResourceNotFound)
            val old = rows[index]
            val updated = old.copy(
                name = name ?: old.name,
                scheduleDisplay = schedule ?: old.scheduleDisplay,
                schedule = schedule?.let(::JsonPrimitive) ?: old.schedule,
                prompt = prompt ?: old.prompt,
            )
            rows[index] = updated
            return HermesResult.Ok(updated)
        }

        override suspend fun delete(target: HermesTarget, id: String): HermesResult<Unit> =
            if (rows.removeIf { it.id == id }) HermesResult.Ok(Unit) else HermesResult.Err(HermesError.ResourceNotFound)

        override suspend fun list(target: HermesTarget): HermesResult<List<JobDto>> = HermesResult.Ok(rows.toList())
        override suspend fun pause(target: HermesTarget, id: String): HermesResult<JobDto> = HermesResult.Err(HermesError.NotFound)
        override suspend fun resume(target: HermesTarget, id: String): HermesResult<JobDto> = HermesResult.Err(HermesError.NotFound)
        override suspend fun runNow(target: HermesTarget, id: String): HermesResult<JobDto> = HermesResult.Err(HermesError.NotFound)
    }

    /** In-memory preferences: a file-backed store fails a write on Windows while a collector holds the file. */
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        private val lock = kotlinx.coroutines.sync.Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private class MutableSessionsApi(initialSessionId: String = "session-one", more: Int = 0) : SessionsManagementApi {
        private val rows = (listOf(SessionDto(initialSessionId, "Current title")) + (0 until more).map { SessionDto("row-$it", "Row $it") }).toMutableList()

        override suspend fun list(target: HermesTarget, limit: Int): HermesResult<List<SessionDto>> =
            HermesResult.Ok(rows.toList())

        override suspend fun rename(target: HermesTarget, sessionId: String, title: String): HermesResult<SessionDto> {
            val index = rows.indexOfFirst { it.id == sessionId }
            if (index < 0) return HermesResult.Err(HermesError.ResourceNotFound)
            val renamed = rows[index].copy(title = title)
            rows[index] = renamed
            return HermesResult.Ok(renamed)
        }

        override suspend fun delete(target: HermesTarget, sessionId: String): HermesResult<Unit> =
            if (rows.removeIf { it.id == sessionId }) HermesResult.Ok(Unit) else HermesResult.Err(HermesError.ResourceNotFound)

    }
}
