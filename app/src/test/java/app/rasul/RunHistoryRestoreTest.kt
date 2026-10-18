package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.BridgeClients
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.JobRunDto
import app.rasul.bridge.SavedBridge
import app.rasul.data.SecretCipher
import app.rasul.desktop.RunHistoryViewModel
import app.rasul.desktop.runHistoryService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Restoring run history after process death: the route's saved bridge + profile ids, never the in-memory token. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunHistoryRestoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = sched.advanceUntilIdle()

    /** Drains the test dispatcher until [cond] holds; bridge-store reads are real IO that resume late. */
    private fun await(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) {
            sched.runCurrent()
            Thread.sleep(20)
        }
        assertTrue("timed out", cond())
    }

    private val run = JobRunDto("r1", "nightly", JsonPrimitive(1760000000), JsonPrimitive(1760000120), false, "done")

    private fun store(scope: CoroutineScope): Pair<BridgeStore, BridgeClients> {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve(UUID.randomUUID().toString() + ".preferences_pb") }
        val store = BridgeStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(token: String) = token.reversed()
        })
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        })
        return store to clients
    }

    private fun bridge(id: String) = SavedBridge(
        id, "Studio PC", listOf("wss://example.invalid/v1/link"),
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
        UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(),
    )

    @Test fun historyRestoresFromTheSavedBridgeAndProfileWithNoTranscriptToken() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val (store, clients) = store(scope)
        val fake = FakeDesktop().apply { runs = listOf(run) }
        var seenProfile: String? = null
        try {
            store.save(bridge("bridge-1"))
            // What the nav graph rebuilds after process death: no TranscriptSources token, only route ids.
            val service = runHistoryService(store, clients, "bridge-1", "default") { endpoint ->
                seenProfile = endpoint.profileId
                fake
            }
            val vm = RunHistoryViewModel("job-1", service)
            await { vm.state.value.loaded }
            assertEquals("default", seenProfile)
            assertEquals(listOf("job-1" to 20), fake.jobIds)
            assertEquals(listOf(run), vm.state.value.runs)
            assertTrue(vm.state.value.loaded)
            assertFalse(vm.state.value.direct)
            assertFalse(vm.state.value.removed)
        } finally { job.cancelAndJoin() }
    }

    @Test fun historyForABridgeThatIsNoLongerSavedShowsTheRemovedState() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val (store, clients) = store(scope)
        val fake = FakeDesktop().apply { runs = listOf(run) }
        try {
            store.save(bridge("bridge-1"))
            store.delete("bridge-1")
            val service = runHistoryService(store, clients, "bridge-1", "default") { fake }
            val vm = RunHistoryViewModel("job-1", service)
            await { vm.state.value.removed }
            assertFalse(vm.state.value.direct)
            assertTrue(fake.jobIds.isEmpty())
            // Retry keeps the same verdict while the bridge stays deleted.
            vm.refresh(); await { vm.state.value.removed }
            assertTrue(vm.state.value.removed)
        } finally { job.cancelAndJoin() }
    }

    @Test fun noRestoreIdentityStillMeansTheNeedsBridgeState() {
        val vm = RunHistoryViewModel("job-1", null)
        idle()
        assertTrue(vm.state.value.direct)
        assertFalse(vm.state.value.removed)
        assertNull(vm.state.value.error)
    }
}
