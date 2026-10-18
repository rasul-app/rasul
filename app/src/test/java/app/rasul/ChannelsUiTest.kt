package app.rasul

import androidx.compose.ui.test.assertIsEnabled
import androidx.test.espresso.Espresso
import app.rasul.channels.BusyState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelState
import app.rasul.bridge.TelegramSetupStatusDto
import app.rasul.channels.ChannelsScreen
import app.rasul.channels.ChannelsViewModel
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real screen through the real ViewModel with a fake bridge, so clicks exercise the whole flow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChannelsUiTest {
    @get:Rule val compose = createComposeRule()
    private val sched = TestCoroutineScheduler()
    private val opened = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settle() { sched.runCurrent(); compose.waitForIdle(); sched.runCurrent(); compose.waitForIdle() }

    private fun show(fake: FakeChannels): ChannelsViewModel {
        val vm = ChannelsViewModel({ fake })
        compose.setContent { RasulTheme(false) { ChannelsScreen(vm, "My PC", {}, { opened += it }) } }
        settle()
        return vm
    }

    private fun pending(fake: FakeChannels) = fake.apply {
        list = list.map { if (it.id == "discord") it.copy(state = ChannelState.PENDING_RESTART) else it }
    }

    @Test fun rowsShowTheirChipsAndTheConnectButton() {
        show(FakeChannels())
        compose.onNodeWithText("Telegram").assertExists()
        compose.onNodeWithText("Discord").assertExists()
        compose.onNodeWithText("Needs setup").assertExists()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText("Connect Telegram").assertExists()
    }

    @Test fun switchesAndTestButtonsAreNamedForScreenReaders() {
        show(FakeChannels())
        compose.onNodeWithContentDescription("Discord").assertExists().assertIsEnabled()
        compose.onNodeWithContentDescription("Test Discord").assertExists()
    }

    @Test fun anOlderBridgeShowsTheUpdateLineAndRetry() {
        show(FakeChannels().apply { listError = ChannelException(ChannelError.OlderBridge) })
        compose.onNodeWithText("Update the bridge on the computer to manage channels.").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test fun theRestartBannerAsksBeforeStoppingBusyWork() {
        val fake = pending(FakeChannels().apply { busy = true; busyAgents = 2 })
        show(fake)
        compose.onNodeWithText("Restart Hermes to apply").assertExists()
        compose.onNodeWithText("Restart").performClick()
        settle()
        compose.onNodeWithText("Hermes is busy").assertExists()
        compose.onNodeWithText("2 tasks are running. Restarting Hermes stops them.").assertExists()
        assertEquals(listOf(false), fake.restarts)
        compose.onNodeWithText("Restart now").performClick()
        settle()
        assertEquals(listOf(false, true), fake.restarts)
    }

    @Test fun waitingShowsItsLineAndCancelStopsIt() {
        val fake = pending(FakeChannels().apply { busy = true })
        show(fake)
        compose.onNodeWithText("Restart").performClick()
        settle()
        compose.onNodeWithText("Wait").performClick()
        settle()
        compose.onNodeWithText("Waiting for Hermes to be idle…").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        settle()
        compose.onNodeWithText("Restart Hermes to apply").assertExists()
        compose.onNodeWithText("Restart").assertIsEnabled()
        sched.advanceTimeBy(60_000); sched.runCurrent()
        assertEquals(1, fake.restarts.size)
    }

    @Test fun setupWalksFromTheNameStepToTheReadyStep() {
        val fake = FakeChannels().apply {
            statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "123456789")
        }
        show(fake)
        compose.onNodeWithText("Connect Telegram").performClick()
        settle()
        compose.onNodeWithText("Create the bot").performClick()
        settle()
        compose.onNodeWithText("Finish in Telegram").assertExists()
        compose.onNodeWithText("Suggested username: hermes_bot").assertExists()
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("Telegram bot is ready").assertExists()
        compose.onNodeWithText("Bot: @hermes_bot").assertExists()
        compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Apply").performClick()
        settle()
        assertEquals(listOf("123456789"), fake.applies.single().second)
        compose.onNodeWithText("Telegram is connected").assertExists()
        compose.onNodeWithText("Restart Hermes to apply this.").assertExists()
    }

    @Test fun aLinkThatFailsTheCheckIsNeverHandedToTheOpener() {
        val fake = FakeChannels().apply { start = start.copy(deepLink = "https://evil.example/hermes_bot") }
        show(fake)
        compose.onNodeWithText("Connect Telegram").performClick(); settle()
        compose.onNodeWithText("Create the bot").performClick(); settle()
        compose.onNodeWithText("Open Telegram").performClick(); settle()
        compose.onNodeWithText("This is not a Telegram link, so it was not opened.").assertExists()
        assertTrue(opened.isEmpty())
    }

    @Test fun aGoodLinkIsHandedToTheOpenerOnTap() {
        show(FakeChannels())
        compose.onNodeWithText("Connect Telegram").performClick(); settle()
        compose.onNodeWithText("Create the bot").performClick(); settle()
        assertTrue(opened.isEmpty())
        compose.onNodeWithText("Open Telegram").performClick(); settle()
        assertEquals(listOf("https://t.me/hermes_bot?start=abc"), opened)
    }

    @Test fun anExpiredSetupOffersStartAgain() {
        val fake = FakeChannels().apply { statusScript += ChannelException(ChannelError.SetupExpired) }
        show(fake)
        compose.onNodeWithText("Connect Telegram").performClick(); settle()
        compose.onNodeWithText("Create the bot").performClick(); settle()
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("Setup expired. Start again.").assertExists()
        compose.onNodeWithText("Start again").performClick(); settle()
        compose.onNodeWithText("Create the bot").assertExists()
    }

    @Test fun badIdsShowTheFieldErrorAndSendNothing() {
        val fake = FakeChannels().apply { statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "") }
        show(fake)
        compose.onNodeWithText("Connect Telegram").performClick(); settle()
        compose.onNodeWithText("Create the bot").performClick(); settle()
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("Apply").performClick(); settle()
        compose.onNodeWithText("Enter 1 to 20 numeric IDs separated by commas.").assertExists()
        assertTrue(fake.applies.isEmpty())
    }

    @Test fun dismissingTheBusyQuestionCancelsInsteadOfWaiting() {
        val fake = pending(FakeChannels().apply { busy = true })
        val vm = show(fake)
        compose.onNodeWithText("Restart").performClick()
        settle()
        compose.onNodeWithText("Hermes is busy").assertExists()
        Espresso.pressBack()
        settle()
        compose.onNodeWithText("Hermes is busy").assertDoesNotExist()
        compose.onNodeWithText("Waiting for Hermes to be idle…").assertDoesNotExist()
        assertEquals(BusyState.None, vm.state.value.busy)
        sched.advanceTimeBy(60_000); sched.runCurrent()
        assertEquals(1, fake.restarts.size)
    }

    @Test fun doneScreenDropsRestartOnceTheRestartWorked() {
        val fake = FakeChannels().apply { statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "123456789") }
        show(fake)
        compose.onNodeWithText("Connect Telegram").performClick(); settle()
        compose.onNodeWithText("Create the bot").performClick(); settle()
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("Apply").performClick(); settle()
        compose.onNodeWithText("Restart Hermes to apply this.").assertExists()
        compose.onNodeWithText("Restart").performClick(); settle()
        assertEquals(listOf(false), fake.restarts)
        compose.onNodeWithText("Restart Hermes to apply this.").assertDoesNotExist()
        compose.onNodeWithText("Restart").assertDoesNotExist()
    }
}
