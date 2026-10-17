package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.data.activeTarget
import app.rasul.data.isoText
import app.rasul.data.loadActive
import app.rasul.jobs.JobsClient
import app.rasul.jobs.JobsViewModel
import app.rasul.sessions.SessionsClient
import app.rasul.sessions.SessionsViewModel
import app.rasul.sessions.TranscriptViewModel
import app.rasul.ui.ActivePcHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class S3FixRoundTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        a = MockWebServer().apply { start() }
        b = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { a.shutdown() }
        runCatching { b.shutdown() }
    }

    private fun store() = ConnectionStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve("s.preferences_pb")
        },
        cipher,
    )

    private val jobsJson = """{"jobs":[{"id":"j1","name":"Daily","enabled":false,"state":"paused"}]}"""

    @Test fun actionNeverReachesAConnectionOtherThanTheOneThatLoadedTheJobs() {
        val s = store()
        val idA = runBlocking { s.save(Connection("", "A", "http://localhost:${a.port}/"), "key-a") }
        a.enqueue(MockResponse().setBody(jobsJson))
        val pcScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val vm = JobsViewModel(s, ActivePcHolder(s, pcScope), JobsClient(HermesClient()))
        waitFor { vm.state.value is Load.Ready }
        assertEquals("A", (vm.state.value as Load.Ready).connection)
        // Simulate the change event not having reached the view-model yet (race between the edit and the tap).
        pcScope.cancel()

        // The user deletes A and adds B in More -> PCs, then comes back to the old view-model.
        runBlocking {
            s.delete(idA)
            s.save(Connection("", "B", "http://localhost:${b.port}/"), "key-b")
        }
        b.enqueue(MockResponse().setBody(jobsJson))
        vm.togglePause((vm.state.value as Load.Ready).source, (vm.state.value as Load.Ready).value.single()) // resume
        waitFor { vm.actionError.value != null && vm.busy.value == null }
        assertEquals(HermesError.ConnectionChanged, vm.actionError.value)

        // Nothing reached B: no resume POST and no list GET (the dead holder never published B, and refresh loads only
        // the holder's target, never the store directly).
        assertEquals(0, b.requestCount)
    }

    @Test fun transcriptOpenedFromAIsRefusedWhenBIsActive() {
        val s = store()
        runBlocking { s.save(Connection("", "B", "http://localhost:${b.port}/"), "key-b") }
        val fromA = ActiveTarget.Ready("id-of-A", "A", HermesTarget("http://localhost:${a.port}/", null, "key-a"))
        val vm = TranscriptViewModel(s, SessionsClient(HermesClient()), "sess-1", fromA)
        waitFor { vm.state.value is Load.Failed }
        assertEquals(HermesError.ConnectionChanged, (vm.state.value as Load.Failed).error)
        assertEquals(0, b.requestCount)
    }

    @Test fun transcriptRefusesAnEditedAddressThatKeepsTheSameId() {
        val s = store()
        val idA = runBlocking { s.save(Connection("", "A", "http://localhost:${a.port}/"), "key-a") }
        a.enqueue(MockResponse().setBody(sessionsJson))
        val listScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val list = SessionsViewModel(s, ActivePcHolder(s, listScope), SessionsClient(HermesClient()))
        waitFor { list.state.value is Load.Ready }
        val source = (list.state.value as Load.Ready).source
        listScope.cancel() // the list screen is gone; only the transcript's own guard is under test

        // The user edits the same saved connection (same id, same name, same key) to address B.
        runBlocking { s.save(Connection(idA, "A", "http://localhost:${b.port}/"), null) }
        val vm = TranscriptViewModel(s, SessionsClient(HermesClient()), "sess-1", source)
        waitFor { vm.state.value is Load.Failed }
        assertEquals(HermesError.ConnectionChanged, (vm.state.value as Load.Failed).error)
        assertEquals(0, b.requestCount) // no session id reached B
        assertEquals(1, a.requestCount) // only the list GET, from before the edit
    }

    private val sessionsJson = """{"object":"list","data":[{"id":"sess-1","title":"Hello","started_at":1760000000,"message_count":1}],"limit":50,"offset":0,"has_more":false}"""

    private val throwingStore = ConnectionStore(
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("store unreadable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("store unreadable")
        },
        cipher,
    )

    @Test fun storageFailureBecomesASpecificStateNotACrash() = runBlocking {
        assertEquals(ActiveTarget.Unreadable("store unreadable"), throwingStore.activeTarget())
        val l = throwingStore.loadActive<Unit> { error("must not be called") }
        assertEquals(Load.Failed(HermesError.Storage("store unreadable")), l)
    }

    @Test fun jobsViewModelOnAThrowingStoreShowsRetryableFailure() {
        val vm = JobsViewModel(throwingStore, ActivePcHolder(throwingStore, CoroutineScope(Job() + Dispatchers.Unconfined)), JobsClient(HermesClient()))
        waitFor { vm.state.value is Load.Failed }
        assertEquals(HermesError.Storage("store unreadable"), (vm.state.value as Load.Failed).error)
    }

    @Test fun isoTimeWithOffsetIsShownInThePhoneZone() {
        val t = isoText("2026-10-16T09:00:00+00:00", ZoneOffset.ofHours(2), Locale.ENGLISH)
        assertTrue(t, t.contains("11:00"))
        assertEquals("not a date", isoText("not a date", ZoneId.of("UTC"), Locale.ENGLISH))
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }
}
