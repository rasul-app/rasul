package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rasul.bridge.DesktopError
import app.rasul.bridge.JobRunDto
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobsClient
import app.rasul.jobs.JobsViewModel
import app.rasul.jobs.LastResult
import app.rasul.ui.ActivePcHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.JsonPrimitive

/** "Last result" on a job card: nothing is fetched on list load, one run is fetched per tap, 429 is shown and retried. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobResultsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val fake = FakeDesktop()

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }

    private val ds: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve("f.preferences_pb")
        }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    """{"jobs":[{"id":"j1","name":"Nightly","enabled":true,"last_run_at":"2030-01-01T00:00:00+00:00","last_status":"ok"}]}""",
                )
            }
            start()
        }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }

    private fun viewModel(): JobsViewModel {
        val store = ConnectionStore(ds, cipher)
        runBlocking { store.save(Connection("", "A", "http://localhost:${server.port}/"), "kA") }
        val holder = ActivePcHolder(store, CoroutineScope(Job() + Dispatchers.Unconfined))
        return JobsViewModel(store, holder, JobsClient(HermesClient()), desktop = { fake })
    }

    private fun ready(vm: JobsViewModel): Pair<app.rasul.data.ActiveTarget.Ready, JobDto> {
        waitFor { vm.state.value is Load.Ready }
        val state = vm.state.value as Load.Ready
        return state.source to state.value.single()
    }

    private fun run(preview: String?) = JobRunDto("r1", "Nightly", JsonPrimitive(1_900_000_000), null, false, preview)

    @Test fun loadingTheListFetchesNoRunsAndKeepsTheStatusFields() {
        val vm = viewModel()
        val (_, job) = ready(vm)
        assertEquals("ok", job.lastStatus)
        assertTrue(fake.jobIds.isEmpty())
        assertTrue(vm.lastResults.value.isEmpty())
    }

    @Test fun oneTapFetchesOneRunForThatJobAndASecondTapClosesWithoutFetching() {
        fake.runs = listOf(run("All good."))
        val vm = viewModel()
        val (source, job) = ready(vm)
        vm.toggleLastResult(source, job)
        waitFor { vm.lastResults.value["j1"] is LastResult.Ready }
        assertEquals(listOf("j1" to 1), fake.jobIds)
        assertEquals("All good.", (vm.lastResults.value["j1"] as LastResult.Ready).run?.preview)
        vm.toggleLastResult(source, job)
        assertNull(vm.lastResults.value["j1"])
        assertEquals(1, fake.jobIds.size)
    }

    @Test fun anEmptyRunListIsAReadyResultWithNoRun() {
        fake.runs = emptyList()
        val vm = viewModel()
        val (source, job) = ready(vm)
        vm.toggleLastResult(source, job)
        waitFor { vm.lastResults.value["j1"] is LastResult.Ready }
        assertNull((vm.lastResults.value["j1"] as LastResult.Ready).run)
    }

    @Test fun rateLimitIsShownAndTheNextTapRetries() {
        fake.runsError = DesktopError.RateLimited
        val vm = viewModel()
        val (source, job) = ready(vm)
        vm.toggleLastResult(source, job)
        waitFor { vm.lastResults.value["j1"] is LastResult.Failed }
        assertEquals(DesktopError.RateLimited, (vm.lastResults.value["j1"] as LastResult.Failed).error)
        assertEquals(R.string.desktop_rate_limited, DesktopError.RateLimited.message)
        fake.runsError = null
        fake.runs = listOf(run("Recovered."))
        vm.toggleLastResult(source, job)
        waitFor { vm.lastResults.value["j1"] is LastResult.Ready }
        assertEquals(2, fake.jobIds.size)
    }

    @Test fun aDirectConnectionHasNoHistoryApiSoATapDoesNothing() {
        val store = ConnectionStore(ds, cipher)
        runBlocking { store.save(Connection("", "A", "http://localhost:${server.port}/"), "kA") }
        val holder = ActivePcHolder(store, CoroutineScope(Job() + Dispatchers.Unconfined))
        val vm = JobsViewModel(store, holder, JobsClient(HermesClient())) // real desktop(): null without a bridge
        val (source, job) = ready(vm)
        vm.toggleLastResult(source, job)
        assertTrue(vm.lastResults.value.isEmpty())
        assertTrue(fake.jobIds.isEmpty())
    }

    @Test fun refreshClearsAnExpandedResult() {
        fake.runs = listOf(run("Old."))
        val vm = viewModel()
        val (source, job) = ready(vm)
        vm.toggleLastResult(source, job)
        waitFor { vm.lastResults.value["j1"] is LastResult.Ready }
        vm.refresh()
        waitFor { vm.lastResults.value.isEmpty() }
    }
}
