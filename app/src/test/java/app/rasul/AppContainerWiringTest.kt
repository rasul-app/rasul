package app.rasul

import androidx.compose.ui.test.junit4.createComposeRule
import app.rasul.chat.Anchor
import app.rasul.chat.RunMemory
import app.rasul.chat.TargetLoader
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.ui.RasulNav
import app.rasul.ui.forkChatRoute
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production wiring of fork: the container hands the real session control to every chat, and the fork route reaches a chat screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerWiringTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyChatTheContainerBuildsCanFork() = runBlocking {
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path == "/v1/capabilities" -> MockResponse().setBody("""{"object":"hermes.api_server.capabilities","features":{"run_submission":true,"run_events_sse":true,"run_stop":true,"session_fork":true}}""")
                    request.path.orEmpty().endsWith("/messages") -> MockResponse().setBody("""{"data":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val container = AppContainer(context, appScope = scope)
            val target = HermesTarget("http://localhost:${server.port}/", null, "sample-key")
            val memory = object : RunMemory {
                override suspend fun sessionId() = "sess-1"
                override suspend fun currentSessionId() = "sess-1"
                override suspend fun activeRun(): String? = null
                override suspend fun lastEventId(): Long? = null
                override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) = Unit
            }
            val model = container.newChatModel(scope, TargetLoader { HermesResult.Ok(target) }, memory)
            withTimeout(10_000) { while (!model.state.value.supportsFork) delay(20) }
            assertTrue(model.state.value.canFork)
        } finally {
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test fun theContainersPinsFollowThePcRemovalNotItsEdit() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val container = AppContainer(context, appScope = scope)
            val id = container.connections.save(app.rasul.data.Connection("", "A", "https://a.example.invalid"), null)
            container.pins.pin(id, null, "s1")
            // Editing the PC keeps the pin; deleting it takes the pin along.
            container.connections.save(app.rasul.data.Connection(id, "A", "https://moved.example.invalid"), null)
            assertEquals(listOf("s1"), container.pins.pinned(id, null).first())
            container.connections.delete(id)
            assertTrue(container.pins.pinned(id, null).first().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun theForkRouteLandsOnTheDirectAndTheBridgeChatDestinations() {
        lateinit var nav: androidx.navigation.testing.TestNavHostController
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        compose.setContent {
            nav = androidx.compose.runtime.remember {
                androidx.navigation.testing.TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(androidx.navigation.compose.ComposeNavigator())
                }
            }
            RasulTheme(false) { RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav = nav) }
        }
        compose.runOnIdle { nav.navigate(forkChatRoute(session = "fork-1", revision = "7", id = "pc-1", runProfile = "p")) }
        compose.waitForIdle()
        assertTrue(nav.currentDestination?.route.orEmpty().startsWith("chat/{id}"))
        assertEquals("fork-1", nav.currentBackStackEntry?.arguments?.getString("session"))
        assertEquals("7", nav.currentBackStackEntry?.arguments?.getString("rev"))
        compose.runOnIdle { nav.navigate(forkChatRoute(session = "fork-2", revision = "8", bridge = "b1", profile = "default")) }
        compose.waitForIdle()
        assertTrue(nav.currentDestination?.route.orEmpty().startsWith("bridge-chat/"))
        assertEquals("fork-2", nav.currentBackStackEntry?.arguments?.getString("session"))
    }
}
