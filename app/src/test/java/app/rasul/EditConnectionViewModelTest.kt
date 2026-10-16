package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.SecretCipher
import app.rasul.ui.EditConnectionViewModel
import app.rasul.ui.SaveError
import app.rasul.ui.TestState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

@OptIn(ExperimentalCoroutinesApi::class)
class EditConnectionViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    private val okCipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }
    private val brokenCipher = object : SecretCipher {
        override fun encrypt(plain: String): String = error("keystore down")
        override fun decrypt(token: String) = token
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun store(cipher: SecretCipher, name: String = "s.preferences_pb") = ConnectionStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve(name)
        },
        cipher,
    )

    @Test fun editWhileTestRunningDropsTheStaleResult() {
        val caps = """{"object":"hermes.api_server.capabilities",
            "features":{"run_submission":true,"run_events_sse":true,"run_stop":true}}"""
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(caps))
        val vm = EditConnectionViewModel(store(okCipher), HermesClient(), null)
        vm.edit(name = "n", url = "http://localhost:${server.port}/", key = "k1")
        vm.test()
        assertEquals(TestState.Running, vm.state.value.test)
        vm.edit(key = "k2")
        assertEquals(TestState.Idle, vm.state.value.test)
        Thread.sleep(1500) // long enough for the old request to finish and (wrongly) publish
        assertEquals(TestState.Idle, vm.state.value.test)
    }

    @Test fun saveFailureCausesAreDistinguished() {
        val enc = EditConnectionViewModel(store(brokenCipher), HermesClient(), null)
        enc.edit(name = "n", url = "http://localhost:1/", key = "k")
        enc.save()
        waitFor { enc.state.value.saveError != null }
        assertEquals(SaveError.Encryption, enc.state.value.saveError)

        // Storage failure: the DataStore file's parent is a regular file, so the write cannot succeed.
        val blocker = tmp.newFile("blocker")
        val bad = ConnectionStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                blocker.resolve("x.preferences_pb")
            },
            okCipher,
        )
        val sto = EditConnectionViewModel(bad, HermesClient(), null)
        sto.edit(name = "n", url = "http://localhost:1/", key = "k")
        sto.save()
        waitFor { sto.state.value.saveError != null }
        assertEquals(SaveError.Storage, sto.state.value.saveError)
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }
}
