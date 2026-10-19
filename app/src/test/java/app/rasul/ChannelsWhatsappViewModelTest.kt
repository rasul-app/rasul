package app.rasul

import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelFieldDto
import app.rasul.bridge.ChannelState
import app.rasul.bridge.NumbersProblem
import app.rasul.bridge.SettingProblem
import app.rasul.bridge.WhatsappMode
import app.rasul.bridge.WhatsappSetupDto
import app.rasul.channels.BusyAction
import app.rasul.channels.BusyState
import app.rasul.channels.ChannelNotice
import app.rasul.channels.ChannelsViewModel
import app.rasul.channels.WhatsappSetup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsWhatsappViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun settle() = sched.runCurrent()
    private fun pass(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }

    private var clock = 1_000_000L
    private val pairing = "wa_pairing_0001"

    private fun open(fake: FakeChannels = FakeChannels()): Pair<FakeChannels, ChannelsViewModel> {
        val vm = ChannelsViewModel({ fake }, now = { clock })
        settle()
        vm.onVisible()
        return fake to vm
    }

    private fun waiting(qr: String? = "fake-payload", expires: String? = null) =
        WhatsappSetupDto(pairing, "waiting", qrPayload = qr, expiresAt = expires)

    private fun started(fake: FakeChannels = FakeChannels(), first: WhatsappSetupDto = waiting()): Pair<FakeChannels, ChannelsViewModel> {
        fake.waStart = first
        val (f, vm) = open(fake)
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        return f to vm
    }


    // ---------- start ----------

    @Test fun startSendsTheChosenModeAndNumbers() {
        val (fake, vm) = open()
        vm.startWhatsapp()
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Options)
        vm.setWhatsappMode(WhatsappMode.SelfChat)
        vm.setWhatsappNumbers("+10000000000, 10000000001")
        vm.beginWhatsapp(); settle()
        assertEquals(listOf(WhatsappMode.SelfChat to listOf("+10000000000", "10000000001")), fake.waStarts)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Active)
    }

    @Test fun badNumbersGetASpecificMessageAndSendNothing() {
        val (fake, vm) = open()
        vm.startWhatsapp()
        vm.setWhatsappNumbers("12ab")
        vm.beginWhatsapp(); settle()
        assertEquals(NumbersProblem.Format, (vm.state.value.whatsapp as WhatsappSetup.Options).numbersProblem)
        vm.setWhatsappNumbers((1..21).joinToString(",") { "1000000000$it" })
        vm.beginWhatsapp(); settle()
        assertEquals(NumbersProblem.TooMany, (vm.state.value.whatsapp as WhatsappSetup.Options).numbersProblem)
        assertTrue(fake.waStarts.isEmpty())
        vm.setWhatsappNumbers("+10000000000")
        assertNull((vm.state.value.whatsapp as WhatsappSetup.Options).numbersProblem)
    }

    @Test fun aFailedStartStaysOnTheOptionsWithTheReason() {
        val (_, vm) = open(FakeChannels().apply { waStartError = ChannelException(ChannelError.RateLimited) })
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        val options = vm.state.value.whatsapp as WhatsappSetup.Options
        assertFalse(options.starting)
        assertEquals(ChannelError.RateLimited, options.error!!.error)
    }

    @Test fun startingOtherFlowsIsBlockedWhileWhatsappIsOpen() {
        val (_, vm) = open()
        vm.startWhatsapp()
        vm.startSetup()
        assertNull(vm.state.value.setup)
        vm.openSettings("discord")
        assertNull(vm.state.value.settings)
    }

    @Test fun aLateStartAnswerDoesNotReplaceAReopenedForm() {
        val gate = CompletableDeferred<Unit>()
        val (fake, vm) = open(FakeChannels().apply { waStartGate = gate })
        vm.startWhatsapp()
        vm.setWhatsappMode(WhatsappMode.SelfChat)
        vm.setWhatsappNumbers("+10000000000")
        vm.beginWhatsapp(); settle()
        vm.cancelWhatsapp(); settle()
        vm.startWhatsapp()
        vm.setWhatsappNumbers("+10000000001")
        gate.complete(Unit); settle()
        val options = vm.state.value.whatsapp as WhatsappSetup.Options
        assertEquals(WhatsappMode.Bot, options.mode)
        assertEquals("+10000000001", options.numbers)
        assertFalse(options.starting)
        assertNull(options.error)
        assertEquals(listOf(pairing), fake.waCancels)
        pass(60_000); assertEquals(0, fake.waStatusReads)
    }

    @Test fun backWithoutReopeningThenALateStartCancelsThePcPairingOnce() {
        val gate = CompletableDeferred<Unit>()
        val (fake, vm) = open(FakeChannels().apply { waStartGate = gate })
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        vm.cancelWhatsapp(); settle()
        gate.complete(Unit); settle()
        assertNull(vm.state.value.whatsapp)
        assertEquals(listOf(pairing), fake.waCancels)
    }

    @Test fun aLateStartAnswerWithTheLivePairingIdIsNotCancelled() {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val fake = FakeChannels().apply { waStartGate = first }
        val (_, vm) = open(fake)
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        vm.cancelWhatsapp(); settle()
        fake.waStartGate = second
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        second.complete(Unit); settle()
        assertEquals(pairing, (vm.state.value.whatsapp as WhatsappSetup.Active).pairingId)
        first.complete(Unit); settle()
        assertTrue(fake.waCancels.isEmpty())
        assertEquals(pairing, (vm.state.value.whatsapp as WhatsappSetup.Active).pairingId)
    }

    @Test fun aLateStartErrorDoesNotTouchAReopenedForm() {
        val gate = CompletableDeferred<Unit>()
        val (fake, vm) = open(FakeChannels().apply { waStartGate = gate; waStartError = ChannelException(ChannelError.RateLimited) })
        vm.startWhatsapp()
        vm.setWhatsappNumbers("+10000000000")
        vm.beginWhatsapp(); settle()
        vm.cancelWhatsapp(); settle()
        vm.startWhatsapp()
        vm.setWhatsappNumbers("+10000000001")
        gate.complete(Unit); settle()
        val options = vm.state.value.whatsapp as WhatsappSetup.Options
        assertEquals("+10000000001", options.numbers)
        assertNull(options.error)
        assertTrue(fake.waCancels.isEmpty())
    }

    @Test fun aNormalStartStillShowsTheCodeAfterASlowAnswer() {
        val gate = CompletableDeferred<Unit>()
        val (fake, vm) = open(FakeChannels().apply { waStartGate = gate })
        vm.startWhatsapp(); vm.beginWhatsapp(); settle()
        assertTrue((vm.state.value.whatsapp as WhatsappSetup.Options).starting)
        gate.complete(Unit); settle()
        assertEquals(pairing, (vm.state.value.whatsapp as WhatsappSetup.Active).pairingId)
        assertTrue(fake.waCancels.isEmpty())
    }

    @Test fun aLongNumbersTextIsRefusedNotCut() {
        val (fake, vm) = open()
        vm.startWhatsapp()
        val text = "+10000000000".repeat(41).take(481)
        vm.setWhatsappNumbers(text)
        assertEquals(text, (vm.state.value.whatsapp as WhatsappSetup.Options).numbers)
        vm.beginWhatsapp(); settle()
        assertEquals(NumbersProblem.TooLong, (vm.state.value.whatsapp as WhatsappSetup.Options).numbersProblem)
        assertEquals(text, (vm.state.value.whatsapp as WhatsappSetup.Options).numbers)
        assertTrue(fake.waStarts.isEmpty())
    }

    // ---------- polling ----------

    @Test fun pollingGoesEveryThreeSecondsAndNoFaster() {
        val (fake, vm) = started(first = WhatsappSetupDto(pairing, "installing"))
        assertEquals(0, fake.waStatusReads)
        pass(2_999); assertEquals(0, fake.waStatusReads)
        pass(1); assertEquals(1, fake.waStatusReads)
        pass(2_999); assertEquals(1, fake.waStatusReads)
        pass(1); assertEquals(2, fake.waStatusReads)
        pass(60_000); assertEquals(22, fake.waStatusReads) // 20 more in a minute: under the 30 per minute limit
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Active)
    }

    @Test fun statusStepsAndTheQrFollowTheBridge() {
        val (fake, vm) = started(first = WhatsappSetupDto(pairing, "installing"))
        fake.waStatusScript += WhatsappSetupDto(pairing, "starting")
        fake.waStatusScript += waiting("payload-1")
        pass(3_000)
        assertEquals("starting", (vm.state.value.whatsapp as WhatsappSetup.Active).status)
        pass(3_000)
        val active = vm.state.value.whatsapp as WhatsappSetup.Active
        assertEquals("waiting", active.status)
        assertEquals("payload-1", active.qrPayload)
        fake.waStatusScript += waiting("payload-2") // the code is renewed while waiting
        pass(3_000)
        assertEquals("payload-2", (vm.state.value.whatsapp as WhatsappSetup.Active).qrPayload)
    }

    @Test fun leavingTheScreenStopsPollingAndReturningStartsAgain() {
        val (fake, vm) = started()
        pass(3_000); assertEquals(1, fake.waStatusReads)
        vm.onHidden()
        pass(30_000); assertEquals(1, fake.waStatusReads)
        vm.onVisible()
        pass(3_000); assertEquals(2, fake.waStatusReads)
    }

    @Test fun cancellingStopsPollingAndDeletesTheSetup() {
        val (fake, vm) = started()
        vm.cancelWhatsapp(); settle()
        assertNull(vm.state.value.whatsapp)
        assertEquals(listOf(pairing), fake.waCancels)
        pass(30_000); assertEquals(0, fake.waStatusReads)
    }

    @Test fun connectedStopsPollingAndShowsTheAccount() {
        val (fake, vm) = started()
        fake.waStatusScript += WhatsappSetupDto(pairing, "connected", accountName = "Work phone", accountPhoneLast4 = "0000")
        pass(3_000)
        val connected = vm.state.value.whatsapp as WhatsappSetup.Connected
        assertEquals("Work phone", connected.accountName)
        assertEquals("0000", connected.phoneLast4)
        pass(60_000); assertEquals(1, fake.waStatusReads)
    }

    @Test fun errorStateShowsTheSpecificMessageAndStopsPolling() {
        val codes = mapOf(
            "whatsapp_bridge_missing" to ChannelError.WhatsappBridgeMissing,
            "whatsapp_install_failed" to ChannelError.WhatsappInstallFailed,
            "whatsapp_timeout" to ChannelError.WhatsappTimeout,
            "whatsapp_logged_out" to ChannelError.WhatsappLoggedOut,
            "whatsapp_setup_failed" to ChannelError.WhatsappSetupFailed,
        )
        codes.forEach { (code, expected) ->
            val (fake, vm) = started()
            fake.waStatusScript += WhatsappSetupDto(pairing, "error", error = code)
            pass(3_000)
            assertEquals(code, expected, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
            pass(60_000); assertEquals(code, 1, fake.waStatusReads)
        }
    }

    @Test fun cancelledOnThePcEndsTheSetup() {
        val (fake, vm) = started()
        fake.waStatusScript += WhatsappSetupDto(pairing, "cancelled")
        pass(3_000)
        assertEquals(ChannelError.SetupCancelled, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
        pass(30_000); assertEquals(1, fake.waStatusReads)
    }

    @Test fun anExpiredCodeStopsAtTheExpiryTimeWithoutAnotherRead() {
        clock = 1_000_000L
        val (fake, vm) = started(first = waiting(expires = "1970-01-01T00:16:45+00:00")) // 1_005_000 ms
        pass(3_000); assertEquals(1, fake.waStatusReads)
        clock = 1_005_000L
        pass(3_000)
        assertEquals(1, fake.waStatusReads)
        assertEquals(ChannelError.SetupExpired, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
        pass(60_000); assertEquals(1, fake.waStatusReads)
    }

    @Test fun a410OnPollIsTheExpiredCodeAndStartAgainReopensTheOptions() {
        val (fake, vm) = started()
        fake.waStatusScript += ChannelException(ChannelError.SetupExpired)
        pass(3_000)
        assertEquals(ChannelError.SetupExpired, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
        vm.restartWhatsapp()
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Options)
    }

    @Test fun aSetupTheBridgeForgotEndsToo() {
        val (fake, vm) = started()
        fake.waStatusScript += ChannelException(ChannelError.SetupNotFound)
        pass(3_000)
        assertEquals(ChannelError.SetupNotFound, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
    }

    @Test fun aFailedPollKeepsTheCodeAndKeepsPolling() {
        val (fake, vm) = started()
        fake.waStatusScript += ChannelException(ChannelError.Unreachable)
        pass(3_000)
        val active = vm.state.value.whatsapp as WhatsappSetup.Active
        assertEquals(ChannelError.Unreachable, active.pollError!!.error)
        assertEquals("fake-payload", active.qrPayload)
        pass(3_000)
        assertNull((vm.state.value.whatsapp as WhatsappSetup.Active).pollError)
        assertEquals(2, fake.waStatusReads)
    }

    @Test fun aRateLimitAnswerSlowsPollingDown() {
        val (fake, vm) = started()
        fake.waStatusScript += ChannelException(ChannelError.RateLimited)
        pass(3_000); assertEquals(1, fake.waStatusReads)
        pass(11_999); assertEquals(1, fake.waStatusReads)
        pass(1); assertEquals(2, fake.waStatusReads)
        pass(3_000); assertEquals(3, fake.waStatusReads) // back to the normal pace
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Active)
    }

    // ---------- apply ----------

    private fun connected(fake: FakeChannels = FakeChannels()): Pair<FakeChannels, ChannelsViewModel> {
        val (f, vm) = started(fake)
        f.waStatusScript += WhatsappSetupDto(pairing, "connected", accountName = "Work phone", accountPhoneLast4 = "0000")
        pass(3_000)
        return f to vm
    }

    @Test fun applySendsNoForceFirstAndShowsTheRestartOutcome() {
        val (fake, vm) = connected()
        vm.applyWhatsapp(); settle()
        assertEquals(listOf(pairing to false), fake.waApplies)
        val done = vm.state.value.whatsapp as WhatsappSetup.Done
        assertTrue(done.needsRestart)
        assertFalse(done.restartStarted)
        assertTrue(vm.state.value.restartNeeded)
    }

    @Test fun applyThatStartedTheRestartSaysSo() {
        val (fake, vm) = connected(FakeChannels().apply { waApplyResult = app.rasul.bridge.WhatsappApplyDto(true, restartStarted = true, needsRestart = false) })
        vm.applyWhatsapp(); settle()
        val done = vm.state.value.whatsapp as WhatsappSetup.Done
        assertTrue(done.restartStarted)
        assertFalse(done.needsRestart)
        assertEquals(1, fake.waApplies.size)
    }

    @Test fun busyApplyAsksThenForcesWithTheSamePairing() {
        val (fake, vm) = connected(FakeChannels().apply { busy = true; busyAgents = 2 })
        vm.applyWhatsapp(); settle()
        assertEquals(BusyState.Asking(BusyAction.WhatsappApply, 2), vm.state.value.busy)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Connected)
        vm.restartNow(); settle()
        assertEquals(listOf(pairing to false, pairing to true), fake.waApplies)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Done)
        assertEquals(BusyState.None, vm.state.value.busy)
    }

    @Test fun cancellingTheBusyQuestionSendsNoForce() {
        val (fake, vm) = connected(FakeChannels().apply { busy = true })
        vm.applyWhatsapp(); settle()
        vm.cancelBusy()
        assertEquals(listOf(pairing to false), fake.waApplies)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Connected)
    }

    @Test fun waitingRepeatsTheApplyWithoutForce() {
        val (fake, vm) = connected(FakeChannels().apply { busy = true })
        vm.applyWhatsapp(); settle()
        vm.waitForIdle()
        fake.busy = false
        pass(15_000)
        assertEquals(listOf(pairing to false, pairing to false), fake.waApplies)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Done)
    }

    @Test fun anExpiredApplyEndsTheSetupWithTheExpiredCode() {
        val (fake, vm) = connected(FakeChannels().apply { waApplyError = ChannelException(ChannelError.SetupExpired) })
        vm.applyWhatsapp(); settle()
        assertEquals(ChannelError.SetupExpired, (vm.state.value.whatsapp as WhatsappSetup.Failed).failure.error)
        assertNull(vm.state.value.actionError)
        assertEquals(1, fake.waApplies.size)
    }

    @Test fun aNotReadyApplyIsAnErrorDialogAndTheSetupStays() {
        val (_, vm) = connected(FakeChannels().apply { waApplyError = ChannelException(ChannelError.SetupNotReady, "Scan first.") })
        vm.applyWhatsapp(); settle()
        assertEquals(ChannelError.SetupNotReady, vm.state.value.actionError!!.error)
        assertTrue(vm.state.value.whatsapp is WhatsappSetup.Connected)
    }

    @Test fun restartAfterAnApplyMarksTheDoneScreen() {
        val (_, vm) = connected()
        vm.applyWhatsapp(); settle()
        vm.restart(); settle()
        val done = vm.state.value.whatsapp as WhatsappSetup.Done
        assertTrue(done.restartStarted)
        assertFalse(done.needsRestart)
        vm.finishWhatsapp()
        assertNull(vm.state.value.whatsapp)
    }

    // ---------- settings ----------

    private fun withFields(): FakeChannels = FakeChannels().apply {
        list = listOf(
            ChannelDto(
                "discord", "Discord", "Chat", enabled = true, configured = true, state = ChannelState.CONNECTED,
                fields = listOf(
                    ChannelFieldDto("BOT_TOKEN", required = true, isSet = true, prompt = "Bot token"),
                    ChannelFieldDto("ALLOWED_USERS", isSet = true, isList = true),
                    ChannelFieldDto("HOME_CHANNEL"),
                ),
            ),
            ChannelDto("empty", "Empty", null, enabled = false),
        )
    }

    @Test fun aChannelWithoutFieldsOpensNoForm() {
        val (_, vm) = open(withFields())
        vm.openSettings("empty")
        assertNull(vm.state.value.settings)
    }

    @Test fun saveSendsOnlyChangedValuesAndClearKeysThenClosesAndWipesTheText() {
        val (fake, vm) = open(withFields())
        fake.saveNeedsRestart = true
        vm.openSettings("discord")
        assertNotNullForm(vm)
        vm.setSettingInput("HOME_CHANNEL", " general ")
        vm.setSettingInput("BOT_TOKEN", "   ")
        vm.toggleSettingClear("ALLOWED_USERS")
        vm.saveSettings(); settle()
        assertEquals(listOf(Triple("discord", mapOf("HOME_CHANNEL" to " general "), listOf("ALLOWED_USERS"))), fake.saves)
        assertNull(vm.state.value.settings)
        assertTrue(vm.state.value.restartNeeded)
        assertEquals(ChannelNotice.SettingsSaved("Discord"), vm.state.value.notice)
        vm.openSettings("discord")
        assertTrue(vm.state.value.settings!!.inputs.isEmpty())
        assertTrue(vm.state.value.settings!!.clearing.isEmpty())
    }

    private fun assertNotNullForm(vm: ChannelsViewModel) = assertTrue(vm.state.value.settings != null)

    @Test fun nothingChangedSendsNothing() {
        val (fake, vm) = open(withFields())
        vm.openSettings("discord")
        assertFalse(vm.state.value.settings!!.changed)
        vm.saveSettings(); settle()
        assertTrue(fake.saves.isEmpty())
    }

    @Test fun aMaskedLookingValueIsRefusedInTheAppAndNothingIsSent() {
        val (fake, vm) = open(withFields())
        vm.openSettings("discord")
        vm.setSettingInput("BOT_TOKEN", "1234...abcd")
        vm.saveSettings(); settle()
        assertTrue(fake.saves.isEmpty())
        assertEquals(SettingProblem.Masked, vm.state.value.settings!!.problems["BOT_TOKEN"])
        vm.setSettingInput("BOT_TOKEN", "real-token")
        assertTrue(vm.state.value.settings!!.problems.isEmpty())
        vm.saveSettings(); settle()
        assertEquals(mapOf("BOT_TOKEN" to "real-token"), fake.saves.single().second)
    }

    @Test fun clearingIsOnlyOfferedForSetFieldsAndCanBeUndone() {
        val (_, vm) = open(withFields())
        vm.openSettings("discord")
        vm.toggleSettingClear("HOME_CHANNEL")
        assertTrue(vm.state.value.settings!!.clearing.isEmpty())
        vm.toggleSettingClear("BOT_TOKEN")
        assertEquals(setOf("BOT_TOKEN"), vm.state.value.settings!!.clearing)
        vm.toggleSettingClear("BOT_TOKEN")
        assertTrue(vm.state.value.settings!!.clearing.isEmpty())
    }

    @Test fun serverRefusalsStayOnTheFormWithTheirOwnMessage() {
        val codes = listOf(
            ChannelError.SettingUnknown, ChannelError.SettingInvalid, ChannelError.SettingRedacted, ChannelError.ChannelNotFound,
        )
        codes.forEach { code ->
            val fake = withFields().apply { saveError = ChannelException(code) }
            val (_, vm) = open(fake)
            vm.openSettings("discord")
            vm.setSettingInput("HOME_CHANNEL", "general")
            vm.saveSettings(); settle()
            val form = vm.state.value.settings!!
            assertEquals(code, form.error!!.error)
            assertFalse(form.saving)
            assertEquals("general", form.inputs["HOME_CHANNEL"]) // kept so the owner can fix it
        }
    }

    @Test fun backDropsEverythingTyped() {
        val (_, vm) = open(withFields())
        vm.openSettings("discord")
        vm.setSettingInput("BOT_TOKEN", "secret-value")
        vm.closeSettings()
        assertNull(vm.state.value.settings)
        vm.openSettings("discord")
        assertTrue(vm.state.value.settings!!.inputs.isEmpty())
    }

    @Test fun theFormNeverPrintsTheTypedText() {
        val (_, vm) = open(withFields())
        vm.openSettings("discord")
        vm.setSettingInput("BOT_TOKEN", "secret-value")
        assertFalse(vm.state.value.settings.toString().contains("secret-value"))
    }

    @Test fun moreThanEightChangesAreRefusedWithoutARequest() {
        val fake = FakeChannels().apply {
            list = listOf(ChannelDto("many", "Many", null, fields = (1..9).map { ChannelFieldDto("K$it") }))
        }
        val (_, vm) = open(fake)
        vm.openSettings("many")
        (1..9).forEach { vm.setSettingInput("K$it", "v") }
        vm.saveSettings(); settle()
        assertTrue(fake.saves.isEmpty())
        assertTrue(vm.state.value.settings!!.tooMany)
    }
}
