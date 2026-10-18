package app.rasul

import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelException
import app.rasul.bridge.ChannelState
import app.rasul.bridge.ChannelTestDto
import app.rasul.bridge.ChannelsApi
import app.rasul.bridge.TelegramApplyDto
import app.rasul.bridge.TelegramSetupStartDto
import app.rasul.bridge.TelegramSetupStatusDto
import app.rasul.channels.BusyAction
import app.rasul.channels.BusyState
import app.rasul.channels.ChannelNotice
import app.rasul.channels.ChannelsViewModel
import app.rasul.channels.LinkProblem
import app.rasul.channels.TelegramSetup
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

internal class FakeChannels : ChannelsApi {
    var list = listOf(
        ChannelDto("telegram", "Telegram", "Chat in Telegram", enabled = false, configured = false, state = ChannelState.NOT_CONFIGURED),
        ChannelDto("discord", "Discord", "Chat in Discord", enabled = true, configured = true, state = ChannelState.CONNECTED),
    )
    var listError: ChannelException? = null
    var reads = 0

    val toggles = mutableListOf<Pair<String, Boolean>>()
    var toggleError: ChannelException? = null
    var toggleNeedsRestart = false

    var testResult = ChannelTestDto(true, "connected", "Looks good")
    var testError: ChannelException? = null
    val tests = mutableListOf<String>()

    val restarts = mutableListOf<Boolean>()
    var busyAgents: Int? = 2
    var busy = false
    var restartError: ChannelException? = null

    val starts = mutableListOf<String?>()
    var startError: ChannelException? = null
    var start = TelegramSetupStartDto("pair_abcdef123456", "https://t.me/hermes_bot?start=abc", "hermes_bot", null)
    val statusScript = ArrayDeque<Any>() // TelegramSetupStatusDto or ChannelException
    var statusReads = 0
    val applies = mutableListOf<Triple<String, List<String>, Boolean>>()
    var applyError: ChannelException? = null
    var applyResult = TelegramApplyDto(true, "hermes_bot", restartStarted = false, needsRestart = true)
    val cancels = mutableListOf<String>()

    override suspend fun channels(): List<ChannelDto> {
        reads++
        listError?.let { throw it }
        return list
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): ChannelDto {
        toggles += id to enabled
        toggleError?.let { throw it }
        val old = list.first { it.id == id }
        val row = old.copy(
            enabled = enabled,
            state = if (toggleNeedsRestart) ChannelState.PENDING_RESTART else old.state,
            needsRestart = toggleNeedsRestart,
        )
        list = list.map { if (it.id == id) row.copy(needsRestart = false) else it }
        return row
    }

    override suspend fun test(id: String): ChannelTestDto {
        tests += id
        testError?.let { throw it }
        return testResult
    }

    override suspend fun restart(force: Boolean) {
        restarts += force
        restartError?.let { throw it }
        if (busy && !force) throw ChannelException(ChannelError.Busy, activeAgents = busyAgents)
    }

    override suspend fun startTelegramSetup(botName: String?): TelegramSetupStartDto {
        starts += botName
        startError?.let { throw it }
        return start
    }

    override suspend fun telegramSetupStatus(pairingId: String): TelegramSetupStatusDto {
        statusReads++
        val next = statusScript.removeFirstOrNull() ?: TelegramSetupStatusDto("waiting")
        if (next is ChannelException) throw next
        return next as TelegramSetupStatusDto
    }

    override suspend fun applyTelegramSetup(pairingId: String, allowedUserIds: List<String>, force: Boolean): TelegramApplyDto {
        applies += Triple(pairingId, allowedUserIds, force)
        applyError?.let { throw it }
        if (busy && !force) throw ChannelException(ChannelError.Busy, activeAgents = busyAgents)
        return applyResult
    }

    override suspend fun cancelTelegramSetup(pairingId: String) {
        cancels += pairingId
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    /** Runs everything due now; never advanceUntilIdle, because the retry and poll loops never end on their own. */
    private fun settle() = sched.runCurrent()
    private fun pass(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }

    private var clock = 1_000_000L

    private fun open(fake: FakeChannels = FakeChannels(), visible: Boolean = true): Pair<FakeChannels, ChannelsViewModel> {
        val vm = ChannelsViewModel({ fake }, now = { clock })
        settle()
        if (visible) vm.onVisible()
        return fake to vm
    }

    private val unreadyStatus = TelegramSetupStatusDto("waiting")

    // ---------- list ----------

    @Test fun listLoadsOnOpen() {
        val (fake, vm) = open()
        assertEquals(1, fake.reads)
        assertEquals(listOf("telegram", "discord"), vm.state.value.channels!!.map { it.id })
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun directBotShowsOnlyTheBridgeNotice() {
        val vm = ChannelsViewModel(null)
        settle()
        assertTrue(vm.state.value.direct)
        assertNull(vm.state.value.channels)
    }

    @Test fun olderBridgeNotFoundIsItsOwnError() {
        val (_, vm) = open(FakeChannels().apply { listError = ChannelException(ChannelError.OlderBridge) })
        assertEquals(ChannelError.OlderBridge, vm.state.value.error!!.error)
        assertNull(vm.state.value.channels)
    }

    @Test fun refreshKeepsTheOldListWhenTheReadFails() {
        val (fake, vm) = open()
        fake.listError = ChannelException(ChannelError.Unreachable)
        vm.refresh(); settle()
        assertEquals(2, vm.state.value.channels!!.size)
        assertEquals(ChannelError.Unreachable, vm.state.value.error!!.error)
    }

    // ---------- switch and test ----------

    @Test fun toggleSendsTheChoiceAndReplacesOnlyThatRow() {
        val (fake, vm) = open()
        vm.toggle("telegram", true); settle()
        assertEquals(listOf("telegram" to true), fake.toggles)
        val rows = vm.state.value.channels!!
        assertTrue(rows.first { it.id == "telegram" }.enabled)
        assertTrue(rows.first { it.id == "discord" }.enabled)
        assertNull(vm.state.value.switchingId)
        assertFalse(vm.state.value.restartNeeded)
    }

    @Test fun aSecondTapWhileTheFirstRequestRunsIsIgnored() {
        val (fake, vm) = open()
        vm.toggle("discord", false); vm.toggle("telegram", true); vm.test("discord"); settle()
        assertEquals(listOf("discord" to false), fake.toggles)
        assertTrue(fake.tests.isEmpty())
    }

    @Test fun conflictKeepsTheSwitchWhereItWasAndShowsHermesText() {
        val (fake, vm) = open(FakeChannels().apply { toggleError = ChannelException(ChannelError.Conflict, "Token missing") })
        vm.toggle("telegram", true); settle()
        assertFalse(vm.state.value.channels!!.first { it.id == "telegram" }.enabled)
        val failure = vm.state.value.actionError!!
        assertEquals(ChannelError.Conflict, failure.error)
        assertEquals("Token missing", failure.detail)
        assertEquals(1, fake.toggles.size)
        vm.dismissActionError()
        assertNull(vm.state.value.actionError)
    }

    @Test fun needsRestartFromASwitchShowsTheBanner() {
        val (_, vm) = open(FakeChannels().apply { toggleNeedsRestart = true })
        vm.toggle("telegram", true); settle()
        assertTrue(vm.state.value.restartNeeded)
        assertTrue(vm.state.value.channels!!.first { it.id == "telegram" }.pendingRestart)
    }

    @Test fun pendingRestartStateAloneShowsTheBanner() {
        val (_, vm) = open(FakeChannels().apply {
            list = list.map { if (it.id == "discord") it.copy(state = ChannelState.PENDING_RESTART) else it }
        })
        assertTrue(vm.state.value.restartNeeded)
    }

    @Test fun testShowsThePassMessage() {
        val (fake, vm) = open()
        vm.test("discord"); settle()
        assertEquals(listOf("discord"), fake.tests)
        assertEquals(ChannelNotice.Test("Discord", true, "Looks good"), vm.state.value.notice)
        assertNull(vm.state.value.testingId)
    }

    @Test fun testFailureAndRequestErrorAreDifferentThings() {
        val (fake, vm) = open(FakeChannels().apply { testResult = ChannelTestDto(false, "startup_failed", "") })
        vm.test("discord"); settle()
        assertEquals(ChannelNotice.Test("Discord", false, null), vm.state.value.notice)
        vm.dismissNotice()
        fake.testError = ChannelException(ChannelError.RateLimited)
        vm.test("discord"); settle()
        assertEquals(ChannelError.RateLimited, vm.state.value.actionError!!.error)
        assertNull(vm.state.value.notice)
    }

    // ---------- restart and the busy rule ----------

    @Test fun restartWhenIdleSendsNoForceAndClearsTheBanner() {
        val (fake, vm) = open(FakeChannels().apply { toggleNeedsRestart = true })
        vm.toggle("telegram", true); settle()
        vm.restart(); settle()
        assertEquals(listOf(false), fake.restarts)
        assertFalse(vm.state.value.restartFlag)
        assertEquals(ChannelNotice.Restarted, vm.state.value.notice)
        assertFalse(vm.state.value.acting)
        assertEquals(BusyState.None, vm.state.value.busy)
    }

    @Test fun busyRestartAsksWithTheCountAndSendsNothingElse() {
        val (fake, vm) = open(FakeChannels().apply { busy = true; busyAgents = 3 })
        vm.restart(); settle()
        assertEquals(BusyState.Asking(BusyAction.Restart, 3), vm.state.value.busy)
        assertEquals(listOf(false), fake.restarts)
        assertFalse(vm.state.value.acting)
    }

    @Test fun unknownCountStillAsks() {
        val (_, vm) = open(FakeChannels().apply { busy = true; busyAgents = null })
        vm.restart(); settle()
        assertEquals(BusyState.Asking(BusyAction.Restart, null), vm.state.value.busy)
    }

    @Test fun waitRetriesEveryFifteenSecondsUntilIdleThenRestarts() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle()
        vm.waitForIdle(); settle()
        assertEquals(BusyState.Waiting(BusyAction.Restart), vm.state.value.busy)
        pass(14_999)
        assertEquals(1, fake.restarts.size)
        pass(1)
        assertEquals(2, fake.restarts.size)
        assertEquals(BusyState.Waiting(BusyAction.Restart), vm.state.value.busy)
        fake.busy = false
        pass(15_000)
        assertEquals(listOf(false, false, false), fake.restarts)
        assertEquals(BusyState.None, vm.state.value.busy)
        assertEquals(ChannelNotice.Restarted, vm.state.value.notice)
        pass(60_000)
        assertEquals(3, fake.restarts.size)
    }

    @Test fun cancelStopsTheWaiting() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle(); vm.waitForIdle(); settle()
        vm.cancelBusy()
        pass(60_000)
        assertEquals(1, fake.restarts.size)
        assertEquals(BusyState.None, vm.state.value.busy)
        assertFalse(vm.state.value.acting)
    }

    @Test fun leavingTheScreenPausesTheRepeatsAndComingBackResumesThem() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle(); vm.waitForIdle(); settle()
        vm.onHidden()
        pass(60_000)
        assertEquals(1, fake.restarts.size)
        assertEquals(BusyState.Waiting(BusyAction.Restart), vm.state.value.busy)
        vm.onVisible(); settle()
        pass(15_000)
        assertEquals(2, fake.restarts.size)
    }

    @Test fun restartNowSendsTheForceFlag() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle()
        vm.restartNow(); settle()
        assertEquals(listOf(false, true), fake.restarts)
        assertEquals(BusyState.None, vm.state.value.busy)
        assertEquals(ChannelNotice.Restarted, vm.state.value.notice)
    }

    @Test fun aFailureWhileWaitingEndsTheWaitWithTheError() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle(); vm.waitForIdle(); settle()
        fake.restartError = ChannelException(ChannelError.DesktopFailed)
        pass(15_000)
        assertEquals(BusyState.None, vm.state.value.busy)
        assertEquals(ChannelError.DesktopFailed, vm.state.value.actionError!!.error)
        pass(60_000)
        assertEquals(2, fake.restarts.size)
    }

    @Test fun controlsAreLockedWhileTheOwnerIsBeingAsked() {
        val (fake, vm) = open(FakeChannels().apply { busy = true })
        vm.restart(); settle()
        vm.toggle("discord", false); vm.test("discord"); vm.restart(); settle()
        assertTrue(fake.toggles.isEmpty())
        assertTrue(fake.tests.isEmpty())
        assertEquals(1, fake.restarts.size)
    }

    // ---------- Telegram setup ----------

    private fun toWaiting(fake: FakeChannels = FakeChannels()): Pair<FakeChannels, ChannelsViewModel> {
        val (f, vm) = open(fake)
        vm.startSetup()
        vm.setBotName("  Helper  "); vm.beginTelegram(); settle()
        return f to vm
    }

    @Test fun setupStartsWithTheTrimmedOptionalNameAndWaits() {
        val (fake, vm) = toWaiting()
        assertEquals(listOf<String?>("Helper"), fake.starts)
        val waiting = vm.state.value.setup as TelegramSetup.Waiting
        assertEquals("pair_abcdef123456", waiting.pairingId)
        assertEquals("hermes_bot", waiting.suggestedUsername)
    }

    @Test fun emptyNameSendsNoName() {
        val (fake, vm) = open()
        vm.startSetup(); vm.beginTelegram(); settle()
        assertEquals(listOf<String?>(null), fake.starts)
    }

    @Test fun startFailureStaysOnTheNameStepWithTheError() {
        val (_, vm) = open(FakeChannels().apply { startError = ChannelException(ChannelError.BadLink) })
        vm.startSetup(); vm.beginTelegram(); settle()
        val name = vm.state.value.setup as TelegramSetup.Name
        assertEquals(ChannelError.BadLink, name.error!!.error)
        assertFalse(name.starting)
    }

    @Test fun pollingRunsEveryThreeSecondsWhileVisibleThenTurnsReady() {
        val (fake, vm) = toWaiting(FakeChannels().apply {
            statusScript += unreadyStatus
            statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "123456789")
        })
        assertEquals(0, fake.statusReads)
        pass(3_000)
        assertEquals(1, fake.statusReads)
        assertTrue(vm.state.value.setup is TelegramSetup.Waiting)
        pass(3_000)
        assertEquals(2, fake.statusReads)
        val ready = vm.state.value.setup as TelegramSetup.Ready
        assertEquals("123456789", ready.userIds)
        assertEquals("hermes_bot", ready.botUsername)
        pass(30_000)
        assertEquals(2, fake.statusReads)
    }

    @Test fun pollingPausesWhileHiddenAndResumesWhenVisible() {
        val (fake, vm) = toWaiting()
        vm.onHidden()
        pass(30_000)
        assertEquals(0, fake.statusReads)
        vm.onVisible(); settle()
        pass(3_000)
        assertEquals(1, fake.statusReads)
    }

    @Test fun aPollFailureIsShownAndPollingGoesOn() {
        val (fake, vm) = toWaiting(FakeChannels().apply {
            statusScript += ChannelException(ChannelError.Unreachable)
            statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "5")
        })
        pass(3_000)
        assertEquals(ChannelError.Unreachable, (vm.state.value.setup as TelegramSetup.Waiting).pollError!!.error)
        pass(3_000)
        assertEquals(2, fake.statusReads)
        assertTrue(vm.state.value.setup is TelegramSetup.Ready)
    }

    @Test fun expiredSetupStopsPollingAndOffersStartAgain() {
        val (fake, vm) = toWaiting(FakeChannels().apply { statusScript += ChannelException(ChannelError.SetupExpired) })
        pass(3_000)
        val failed = vm.state.value.setup as TelegramSetup.Failed
        assertEquals(ChannelError.SetupExpired, failed.failure.error)
        pass(30_000)
        assertEquals(1, fake.statusReads)
        vm.restartSetup()
        assertTrue(vm.state.value.setup is TelegramSetup.Name)
    }

    @Test fun unknownSetupIsTreatedAsAFinishedSetup() {
        val (_, vm) = toWaiting(FakeChannels().apply { statusScript += ChannelException(ChannelError.SetupNotFound) })
        pass(3_000)
        assertEquals(ChannelError.SetupNotFound, (vm.state.value.setup as TelegramSetup.Failed).failure.error)
    }

    @Test fun theExpiryTimeStopsThePollWithoutAnotherRequest() {
        val soon = "2026-10-18T10:00:00+00:00"
        val epoch = java.time.OffsetDateTime.parse(soon).toInstant().toEpochMilli()
        clock = epoch - 1_000
        val fake = FakeChannels().apply { start = start.copy(expiresAt = soon) }
        val (_, vm) = toWaiting(fake)
        clock = epoch
        pass(3_000)
        assertEquals(0, fake.statusReads)
        assertEquals(ChannelError.SetupExpired, (vm.state.value.setup as TelegramSetup.Failed).failure.error)
    }

    @Test fun cancelInTheWaitingStepDeletesThePairingAndStopsPolling() {
        val (fake, vm) = toWaiting()
        vm.cancelSetup(); settle()
        assertEquals(listOf("pair_abcdef123456"), fake.cancels)
        assertNull(vm.state.value.setup)
        pass(30_000)
        assertEquals(0, fake.statusReads)
    }

    @Test fun cancelBeforeStartingSendsNothing() {
        val (fake, vm) = open()
        vm.startSetup(); vm.cancelSetup(); settle()
        assertTrue(fake.cancels.isEmpty())
        assertNull(vm.state.value.setup)
    }

    @Test fun onlyATelegramLinkIsHandedToTheOpener() {
        val (_, vm) = toWaiting()
        assertEquals("https://t.me/hermes_bot?start=abc", vm.openTelegramLink())
        assertNull((vm.state.value.setup as TelegramSetup.Waiting).linkProblem)
        vm.telegramLinkUnavailable()
        assertEquals(LinkProblem.NoApp, (vm.state.value.setup as TelegramSetup.Waiting).linkProblem)
    }

    @Test fun aLinkThatFailsTheCheckIsNeverOpened() {
        val bad = FakeChannels().apply { start = start.copy(deepLink = "https://evil.example/hermes_bot") }
        val (_, vm) = toWaiting(bad)
        assertNull(vm.openTelegramLink())
        assertEquals(LinkProblem.Rejected, (vm.state.value.setup as TelegramSetup.Waiting).linkProblem)
    }

    private fun toReady(fake: FakeChannels = FakeChannels()): Pair<FakeChannels, ChannelsViewModel> {
        fake.statusScript += TelegramSetupStatusDto("ready", "hermes_bot", "123456789")
        val (f, vm) = toWaiting(fake)
        pass(3_000)
        assertTrue(vm.state.value.setup is TelegramSetup.Ready)
        return f to vm
    }

    @Test fun applySendsTheIdsOnceAndEndsInDoneWithTheBanner() {
        val (fake, vm) = toReady()
        vm.setUserIds("123456789, 42,42"); vm.applyTelegram(); settle()
        assertEquals(listOf(Triple("pair_abcdef123456", listOf("123456789", "42"), false)), fake.applies)
        val done = vm.state.value.setup as TelegramSetup.Done
        assertEquals("hermes_bot", done.botUsername)
        assertTrue(done.needsRestart)
        assertTrue(vm.state.value.restartNeeded)
        vm.finishSetup(); settle()
        assertNull(vm.state.value.setup)
        assertEquals(2, fake.reads)
    }

    @Test fun badIdsAreRefusedBeforeAnyRequest() {
        val (fake, vm) = toReady()
        vm.setUserIds("12ab"); vm.applyTelegram(); settle()
        assertTrue(fake.applies.isEmpty())
        assertTrue((vm.state.value.setup as TelegramSetup.Ready).idsInvalid)
        vm.setUserIds("77")
        assertFalse((vm.state.value.setup as TelegramSetup.Ready).idsInvalid)
    }

    @Test fun busyApplyAsksThenWaitsAndRepeatsTheSameIds() {
        val (fake, vm) = toReady(FakeChannels().apply { busy = true })
        vm.setUserIds("5"); vm.applyTelegram(); settle()
        assertEquals(BusyState.Asking(BusyAction.Apply, 2), vm.state.value.busy)
        vm.waitForIdle(); settle()
        vm.setUserIds("999") // the field is locked while the request is pending
        pass(15_000)
        fake.busy = false
        pass(15_000)
        assertEquals(listOf(false, false, false), fake.applies.map { it.third })
        assertTrue(fake.applies.all { it.first == "pair_abcdef123456" && it.second == listOf("5") })
        assertTrue(vm.state.value.setup is TelegramSetup.Done)
        assertEquals(BusyState.None, vm.state.value.busy)
    }

    @Test fun applyNowSendsForce() {
        val (fake, vm) = toReady(FakeChannels().apply { busy = true })
        vm.setUserIds("5"); vm.applyTelegram(); settle()
        vm.restartNow(); settle()
        assertEquals(listOf(false, true), fake.applies.map { it.third })
        assertTrue(vm.state.value.setup is TelegramSetup.Done)
    }

    @Test fun anApplyRefusalShowsTheMessageAndKeepsTheFieldFilled() {
        val (_, vm) = toReady(FakeChannels().apply {
            applyError = ChannelException(ChannelError.SetupNotReady, "Open the bot first")
        })
        vm.applyTelegram(); settle()
        assertEquals("Open the bot first", vm.state.value.actionError!!.detail)
        assertEquals("123456789", (vm.state.value.setup as TelegramSetup.Ready).userIds)
        assertFalse(vm.state.value.acting)
    }

    @Test fun cancelFromTheReadyStepDeletesThePairing() {
        val (fake, vm) = toReady()
        vm.cancelSetup(); settle()
        assertEquals(listOf("pair_abcdef123456"), fake.cancels)
    }

    @Test fun doneWithARestartStartedNeedsNoSecondRestart() {
        val (_, vm) = toReady(FakeChannels().apply { applyResult = TelegramApplyDto(true, "hermes_bot", restartStarted = true, needsRestart = false) })
        vm.applyTelegram(); settle()
        val done = vm.state.value.setup as TelegramSetup.Done
        assertTrue(done.restartStarted)
        assertFalse(vm.state.value.restartNeeded)
    }

    @Test fun aWorkingRestartFromTheDoneScreenClearsItsRestartOffer() {
        val (fake, vm) = toReady()
        vm.applyTelegram(); settle()
        assertTrue((vm.state.value.setup as TelegramSetup.Done).needsRestart)
        vm.restart(); settle()
        assertEquals(listOf(false), fake.restarts)
        val done = vm.state.value.setup as TelegramSetup.Done
        assertFalse(done.needsRestart)
        assertTrue(done.restartStarted)
    }
}
