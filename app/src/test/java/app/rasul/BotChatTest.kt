package app.rasul

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionList
import app.rasul.sessions.SessionsClient
import app.rasul.sessions.SessionsViewModel
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The hidden "Bot Chat" lookup: one GET api/sessions by exact title with include_hidden, and only a row
 * titled exactly "Bot Chat" counts (SessionsJobsClientTest.kt:60-97 shows the same MockWebServer pattern).
 */
class BotChatClientTest {
    private lateinit var server: MockWebServer
    private val sessions = SessionsClient(HermesClient())

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target() = HermesTarget("http://localhost:${server.port}/", null, "secret-key")

    @Test fun asksForTheExactHiddenTitleAndReturnsTheMatchingRow() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"bot1","title":"Bot Chat","started_at":1760000000,"last_active":1760000100,"ended_at":null}]}""",
        ))
        val r = sessions.botChat(target()) as HermesResult.Ok
        assertEquals("bot1", r.value?.id)
        assertEquals("Bot Chat", r.value?.title)
        assertEquals("/api/sessions?title=Bot%20Chat&include_hidden=true&limit=1", server.takeRequest().path)
    }

    @Test fun aDifferentlyTitledRowIsNotTheBotChat() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"n1","title":"Other"}]}""",
        ))
        val r = sessions.botChat(target()) as HermesResult.Ok
        assertNull(r.value)
    }

    @Test fun emptyDataMeansNoBotChat() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))
        assertEquals(HermesResult.Ok(null), sessions.botChat(target()))
    }

    @Test fun anErrorPropagates() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HermesError.Unauthorized, (sessions.botChat(target()) as HermesResult.Err).error)
    }
}

/**
 * The list's ViewModel reads the hidden chat alongside the list and exposes it for the shown source only:
 * a failure hides the row without touching the list, and a source switch clears the previous bot's row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }
    private val ds: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { File(tmp.newFolder("ds"), "test.preferences_pb") },
        )
    }
    private val sched = TestCoroutineScheduler()
    private val chats = FakeSessionList()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(id: String, title: String) =
        SessionDto(id, title, startedAt = JsonPrimitive(1_760_000_000), messageCount = 1)

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }

    private suspend fun holder(store: ConnectionStore): ActivePcHolder {
        val holder = ActivePcHolder(store, CoroutineScope(Job() + Dispatchers.Unconfined))
        waitFor { holder.state.value.loaded }
        return holder
    }

    private suspend fun open(): SessionsViewModel {
        val store = ConnectionStore(ds, cipher)
        store.save(Connection("a", "A", "http://localhost:1/"), "k")
        return SessionsViewModel(store, holder(store), chats).also {
            sched.runCurrent()
            assertTrue(it.state.value is Load.Ready)
        }
    }

    @Test fun botChatIsLookedUpOnLoadAndRefreshButNotOnAPollTick() = runBlocking {
        chats.list = listOf(row("s1", "First"))
        val bot = row("bot-1", "Bot Chat")
        chats.botFor = { HermesResult.Ok(bot) }
        val vm = open()
        assertEquals(bot, vm.botChat.value)
        assertEquals(1, chats.botCalls)
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(1, chats.botCalls)
        assertEquals(bot, vm.botChat.value)
        vm.refresh()
        sched.runCurrent()
        assertEquals(2, chats.botCalls)
        assertEquals(bot, vm.botChat.value)
    }

    @Test fun aBotChatErrorLeavesTheListReadyAndTheRowHidden() = runBlocking {
        chats.list = listOf(row("s1", "First"))
        chats.botFor = { HermesResult.Err(HermesError.Unreachable("down")) }
        val vm = open()
        assertTrue(vm.state.value is Load.Ready)
        assertEquals(listOf("s1"), (vm.state.value as Load.Ready).value.map { it.id })
        assertNull(vm.botChat.value)
    }

    @Test fun switchingSourceClearsThePreviousBotsChat() = runBlocking {
        val botA = row("bot-a", "Bot Chat")
        val botB = row("bot-b", "Bot Chat")
        chats.list = listOf(row("s1", "First"))
        chats.botFor = { t -> HermesResult.Ok(if (t.baseUrl == "http://localhost:1/") botA else botB) }
        val store = ConnectionStore(ds, cipher)
        store.save(Connection("a", "A", "http://localhost:1/"), "k")
        store.save(Connection("b", "B", "http://localhost:2/"), "k")
        val vm = SessionsViewModel(store, holder(store), chats)
        sched.runCurrent()
        assertEquals(botA, vm.botChat.value)

        chats.botGate = CompletableDeferred()
        vm.selectPc("b")
        waitFor { (vm.picker.value.target as? ActiveTarget.Ready)?.id == "b" }
        sched.runCurrent()
        assertTrue(vm.state.value is Load.Ready)
        // The new source's look-up is still suspended: the previous bot's chat must not show meanwhile.
        assertNull(vm.botChat.value)
        chats.botGate!!.complete(Unit)
        sched.runCurrent()
        assertEquals(botB, vm.botChat.value)
    }
}

/** The "Laptop chat" row on the sessions list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class BotChatUiTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: String, title: String) =
        SessionDto(id, title, startedAt = JsonPrimitive(1_760_000_000), messageCount = 1)

    @Test fun laptopChatShowsFirstAndOpensTheSession() {
        var opened: SessionDto? = null
        val bot = row("bot-1", "Bot Chat")
        compose.setContent {
            RasulTheme(false) {
                SessionList(
                    listOf(row("s1", "Normal chat")), "source", 1_760_000_000_000L,
                    onOpen = { opened = it }, botChat = bot,
                )
            }
        }
        val laptop = compose.onNodeWithText("Laptop chat")
        laptop.assertExists()
        val laptopTop = laptop.fetchSemanticsNode().boundsInRoot.top
        val normalTop = compose.onNodeWithText("Normal chat").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Laptop chat must be above the normal rows", laptopTop < normalTop)
        laptop.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        laptop.performClick()
        assertEquals(bot, opened)
    }

    @Test fun laptopChatHasNoLongPressMenu() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(
                    listOf(row("s1", "Normal chat")), "source", 1_760_000_000_000L,
                    onOpen = {}, botChat = row("bot-1", "Bot Chat"),
                )
            }
        }
        compose.onNode(hasText("Laptop chat") and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test fun noLaptopChatRowWithoutABotChat() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(listOf(row("s1", "Normal chat")), "source", 1_760_000_000_000L, onOpen = {})
            }
        }
        compose.onNodeWithText("Normal chat").assertExists()
        compose.onNodeWithText("Laptop chat").assertDoesNotExist()
    }

    @Test fun theSameIdShowsOnlyInTheTopRow() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(
                    listOf(row("bot-1", "Bot Chat"), row("s1", "Normal chat")), "source", 1_760_000_000_000L,
                    onOpen = {}, botChat = row("bot-1", "Bot Chat"),
                )
            }
        }
        compose.onAllNodesWithText("Laptop chat").assertCountEquals(1)
        compose.onNodeWithText("Bot Chat").assertDoesNotExist()
        compose.onNodeWithText("Normal chat").assertExists()
    }

    @Test fun laptopChatIsTheOnlyRowWhenTheListIsEmpty() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(emptyList(), "source", 1_760_000_000_000L, onOpen = {}, botChat = row("bot-1", "Bot Chat"))
            }
        }
        compose.onNodeWithText("Laptop chat").assertExists()
        compose.onNodeWithText("No sessions on this server yet.").assertDoesNotExist()
    }

    @Test fun laptopChatShowsTheBusyLineFromTheSameMap() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(
                    emptyList(), "source", 1_760_000_000_000L, onOpen = {},
                    busyChats = mapOf("bot-1" to app.rasul.data.BusyWhere.Desktop),
                    botChat = row("bot-1", "Bot Chat"),
                )
            }
        }
        compose.onNodeWithText("Replying on PC").assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-w390dp-h700dp")
    fun laptopChatUsesArabicStrings() {
        compose.setContent {
            RasulTheme(false) {
                SessionList(emptyList(), "source", 1_760_000_000_000L, onOpen = {}, botChat = row("bot-1", "Bot Chat"))
            }
        }
        compose.onNodeWithText("محادثة اللابتوب").assertExists()
        compose.onNodeWithText("نفس المحادثة الموجودة على حاسوبك").assertExists()
    }
}
