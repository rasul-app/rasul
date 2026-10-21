package app.rasul

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelFieldDto
import app.rasul.bridge.ChannelState
import app.rasul.bridge.WhatsappMode
import app.rasul.bridge.WhatsappSetupDto
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

/** The WhatsApp setup and the settings form, driven through the real screen and view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChannelsWhatsappUiTest {
    @get:Rule val compose = createComposeRule()
    private val sched = TestCoroutineScheduler()
    private val payload = "2@secretPayloadThatMustNeverBeSpoken"
    private val pairing = "wa_pairing_0001"

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settle() { sched.runCurrent(); compose.waitForIdle(); sched.runCurrent(); compose.waitForIdle() }

    private val fields = listOf(
        ChannelFieldDto("BOT_TOKEN", required = true, isSet = true, prompt = "Bot token"),
        ChannelFieldDto("ALLOWED_USERS", isSet = true, isList = true, prompt = "Allowed users"),
        ChannelFieldDto("HOME_CHANNEL", prompt = "Home channel"),
    )

    private fun fake() = FakeChannels().apply {
        list = listOf(
            ChannelDto("whatsapp", "WhatsApp", "Message Hermes on WhatsApp", enabled = false, configured = false, state = ChannelState.NOT_CONFIGURED),
            ChannelDto("discord", "Discord", "Chat in Discord", enabled = true, configured = true, state = ChannelState.CONNECTED, fields = fields),
        )
        waStart = WhatsappSetupDto(pairing, "waiting", qrPayload = payload)
    }

    private fun show(fake: FakeChannels): ChannelsViewModel {
        val vm = ChannelsViewModel({ fake })
        compose.setContent { RasulTheme(false) { ChannelsScreen(vm, "My PC", {}, {}) } }
        settle()
        return vm
    }

    private fun startWhatsapp() {
        compose.onNodeWithText("Set up WhatsApp").performClick(); settle()
        compose.onNodeWithText("Start").performClick(); settle()
    }

    @Test fun optionsOfferBothModesAndRefuseBadNumbersWithASpecificLine() {
        val fake = fake()
        show(fake)
        compose.onNodeWithText("Set up WhatsApp").performClick(); settle()
        compose.onNodeWithText("Bot").assertExists()
        compose.onNodeWithText("Self-chat").performClick(); settle()
        compose.onNodeWithText("Allowed phone numbers (optional)").performTextInput("12ab")
        compose.onNodeWithText("Start").performClick(); settle()
        compose.onNodeWithText("Use digits only, with an optional + at the start, like +10000000000. Separate several numbers with commas.").assertExists()
        assertTrue(fake.waStarts.isEmpty())
    }

    @Test fun waitingShowsTheStepsTheInstructionAndAQrThatNeverSpeaksThePayload() {
        val fake = fake()
        show(fake)
        compose.onNodeWithText("Set up WhatsApp").performClick(); settle()
        compose.onNodeWithText("Self-chat").performClick()
        compose.onNodeWithText("Start").performClick(); settle()
        assertEquals(listOf(WhatsappMode.SelfChat to emptyList<String>()), fake.waStarts)
        compose.onNodeWithText("Installing the WhatsApp connector").assertExists()
        compose.onNodeWithText("Waiting for you to scan the code").assertExists()
        compose.onNodeWithText("Scan this code with WhatsApp").assertExists()
        compose.onNodeWithText("WhatsApp > Linked devices > Link a device", substring = true).assertExists()
        compose.onNodeWithContentDescription("QR code for linking WhatsApp.", substring = true).assertExists()
        compose.onNode(hasText(payload, substring = true) or hasContentDescription(payload, substring = true)).assertDoesNotExist()
    }

    @Test fun installingShowsNoQrYet() {
        val fake = fake().apply { waStart = WhatsappSetupDto(pairing, "installing") }
        show(fake)
        startWhatsapp()
        compose.onNodeWithContentDescription("Installing the WhatsApp connector, in progress").assertExists()
        compose.onNodeWithText("Scan this code with WhatsApp").assertDoesNotExist()
    }

    @Test fun connectedShowsTheAccountAndApplyAsksBeforeForcing() {
        val fake = fake().apply { busy = true; busyAgents = 1 }
        show(fake)
        startWhatsapp()
        fake.waStatusScript += WhatsappSetupDto(pairing, "connected", accountName = "Work phone", accountPhoneLast4 = "0000")
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("Account: Work phone").assertExists()
        compose.onNodeWithText("Number ending in 0000").assertExists()
        compose.onNodeWithText("Apply").performClick(); settle()
        compose.onNodeWithText("The agent is busy; apply anyway? Applying restarts the agent and stops any work in progress.").assertExists()
        assertEquals(listOf(pairing to false), fake.waApplies)
        compose.onNodeWithText("Apply anyway").performClick(); settle()
        assertEquals(listOf(pairing to false, pairing to true), fake.waApplies)
        compose.onNodeWithText("WhatsApp is connected").assertExists()
        compose.onNodeWithText("Restart the agent on your PC to apply this.").assertExists()
    }

    @Test fun anExpiredCodeSaysSoAndOffersStartAgain() {
        val fake = fake()
        show(fake)
        startWhatsapp()
        fake.waStatusScript += ChannelException(ChannelError.SetupExpired)
        sched.advanceTimeBy(3_000); settle()
        compose.onNodeWithText("The code expired. Start again.").assertExists()
        compose.onNodeWithText("Start again").performClick(); settle()
        compose.onNodeWithText("Start").assertExists()
    }

    private fun errorShows(code: String, text: String) {
        val fake = fake().apply { waStart = WhatsappSetupDto(pairing, "error", error = code) }
        show(fake)
        startWhatsapp()
        compose.onNodeWithText(text).assertExists()
        compose.onNodeWithText("Start again").assertExists()
    }

    @Test fun missingBridgeHasItsOwnSentence() =
        errorShows("whatsapp_bridge_missing", "The WhatsApp connector is missing from Rasul on the computer. Update Rasul, then try again.")

    @Test fun installFailureHasItsOwnSentence() =
        errorShows("whatsapp_install_failed", "The WhatsApp connector could not be installed on the computer. Check its internet connection, then try again.")

    @Test fun timeoutHasItsOwnSentence() =
        errorShows("whatsapp_timeout", "Installing the WhatsApp connector took too long. Try again.")

    @Test fun loggedOutHasItsOwnSentence() =
        errorShows("whatsapp_logged_out", "WhatsApp logged this device out. Start again to link it.")

    @Test fun genericFailureHasItsOwnSentence() =
        errorShows("whatsapp_setup_failed", "WhatsApp setup failed. Start again.")

    @Test fun settingsListFieldsStatusAndNeverPrefill() {
        val fake = fake()
        show(fake)
        compose.onNodeWithContentDescription("Settings for Discord").performClick(); settle()
        compose.onNodeWithText("Bot token").assertExists()
        compose.onNodeWithText("Allowed users").assertExists()
        compose.onNodeWithText("Home channel").assertExists()
        compose.onNodeWithText("Not set").assertExists()
        compose.onNodeWithText("Required").assertExists()
        compose.onNodeWithContentDescription("Clear Bot token").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun savingSendsOnlyWhatChangedAndShowsTheRestartPrompt() {
        val fake = fake().apply { saveNeedsRestart = true }
        show(fake)
        compose.onNodeWithContentDescription("Settings for Discord").performClick(); settle()
        compose.onNodeWithContentDescription("Home channel").performTextInput("general")
        compose.onNodeWithContentDescription("Clear Allowed users").performClick(); settle()
        compose.onNodeWithText("Will be cleared").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled().performClick(); settle()
        assertEquals(listOf(Triple("discord", mapOf("HOME_CHANNEL" to "general"), listOf("ALLOWED_USERS"))), fake.saves)
        compose.onNodeWithText("Discord settings saved.").assertExists()
        compose.onNodeWithText("OK").performClick(); settle()
        compose.onNodeWithText("Restart the agent on your PC to apply").assertExists()
    }

    @Test fun aMaskedLookingValueShowsItsLineAndSendsNothing() {
        val fake = fake()
        show(fake)
        compose.onNodeWithContentDescription("Settings for Discord").performClick(); settle()
        compose.onNodeWithContentDescription("Bot token").performTextInput("abcd...wxyz")
        compose.onNodeWithText("Save").performClick(); settle()
        compose.onNodeWithText("This looks like a hidden preview, not a real value. Paste the full value.").assertExists()
        assertTrue(fake.saves.isEmpty())
    }

    @Test fun aServerRefusalIsShownOnTheForm() {
        val fake = fake().apply { saveError = ChannelException(ChannelError.SettingRedacted) }
        show(fake)
        compose.onNodeWithContentDescription("Settings for Discord").performClick(); settle()
        compose.onNodeWithContentDescription("Home channel").performTextInput("general")
        compose.onNodeWithText("Save").performClick(); settle()
        compose.onNodeWithText("A value looks like a hidden preview, for example it contains ****. Paste the full value instead.").assertExists()
    }

}
