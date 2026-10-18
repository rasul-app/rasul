package app.rasul

import app.rasul.bridge.BridgeStatusApi
import app.rasul.bridge.BridgeStatusDto
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.HermesChangeDto
import app.rasul.bridge.HermesUpdateDto
import app.rasul.bridge.HermesUpdateResultDto
import app.rasul.bridge.StatusHermesDto
import app.rasul.bridge.StatusProfileDto
import app.rasul.bridge.StatusUrlsDto
import app.rasul.desktop.CheckLine
import app.rasul.desktop.HermesUpdateViewModel
import app.rasul.desktop.PcCheckViewModel
import app.rasul.desktop.PcInfoViewModel
import app.rasul.desktop.checkLines
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

private class FakeStatus : BridgeStatusApi {
    var status = BridgeStatusDto(bridgeVersion = "0.2.6", hermes = StatusHermesDto("0.22.0", 0))
    var error: DesktopError? = null
    var reads = 0
    override suspend fun status(): BridgeStatusDto {
        reads++
        error?.let { throw DesktopException(it) }
        return status
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HermesUpdateViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    /** Runs everything due now; never advanceUntilIdle, because the poll loops never end. */
    private fun settle() = sched.runCurrent()
    private fun pass(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }

    private val available = HermesUpdateDto(
        current = "0.21.4", latest = "9f3c2ab", behind = 4, canApply = true,
        changes = listOf(HermesChangeDto("Faster startup")), activeAgents = 0,
    )

    private fun update(fake: FakeDesktop = FakeDesktop().apply { update = available }): Pair<FakeDesktop, HermesUpdateViewModel> {
        val vm = HermesUpdateViewModel({ fake })
        settle()
        return fake to vm
    }

    @Test fun screenLoadsTheStatusOnOpen() {
        val (fake, vm) = update()
        assertEquals(1, fake.updateReads)
        assertEquals(available, vm.state.value.status)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun directBotShowsOnlyTheBridgeNotice() {
        val vm = HermesUpdateViewModel(null)
        settle()
        assertTrue(vm.state.value.direct)
        assertNull(vm.state.value.status)
    }

    @Test fun olderBridgeReadShowsTheUpdateBridgeMessage() {
        val (_, vm) = update(FakeDesktop().apply { updateError = DesktopError.NotFound })
        assertEquals(DesktopError.NotFound, vm.state.value.error)
        assertNull(vm.state.value.status)
    }

    @Test fun updateWhenIdleSendsTheModeAndShowsQueued() {
        val (fake, vm) = update()
        vm.updateWhenIdle(); settle()
        assertEquals(listOf("when_idle"), fake.updateStarts)
        assertTrue(vm.state.value.status!!.isQueued)
        assertFalse(vm.state.value.busy)
    }

    @Test fun updateNowSendsNowAndShowsRunning() {
        val (fake, vm) = update()
        vm.updateNow(); settle()
        assertEquals(listOf("now"), fake.updateStarts)
        assertTrue(vm.state.value.status!!.isRunning)
    }

    @Test fun aSecondTapWhileTheFirstRequestIsInFlightIsIgnored() {
        val (fake, vm) = update()
        vm.updateWhenIdle(); vm.updateNow(); vm.updateWhenIdle(); settle()
        assertEquals(listOf("when_idle"), fake.updateStarts)
    }

    @Test fun cancelReturnsToIdle() {
        val (fake, vm) = update(FakeDesktop().apply { update = available.copy(state = "queued") })
        vm.cancel(); settle()
        assertEquals(1, fake.cancels)
        assertEquals("idle", vm.state.value.status!!.state)
    }

    @Test fun everyRefusalIsShownWithItsOwnErrorThenTheStatusIsReread() {
        listOf(
            DesktopError.BridgeElevated, DesktopError.DashboardElevated, DesktopError.CannotVerifyDashboard,
            DesktopError.DashboardNotRunning, DesktopError.UpToDate, DesktopError.UpdateRateLimited, DesktopError.UpdateInProgress,
            DesktopError.UpdateNotStarted, DesktopError.CheckFailed, DesktopError.NotApplicable,
        ).forEach { error ->
            val (fake, vm) = update(FakeDesktop().apply { update = available; startError = error })
            val readsBefore = fake.updateReads
            vm.updateNow(); settle()
            assertEquals(error.name, error, vm.state.value.actionError)
            assertFalse(vm.state.value.busy)
            assertEquals(error.name, readsBefore + 1, fake.updateReads)
            vm.dismissActionError()
            assertNull(vm.state.value.actionError)
        }
    }

    @Test fun cancelRefusalsAreSpecific() {
        listOf(DesktopError.NothingQueued, DesktopError.UpdateRunning).forEach { error ->
            val (_, vm) = update(FakeDesktop().apply { update = available.copy(state = "queued"); cancelError = error })
            vm.cancel(); settle()
            assertEquals(error, vm.state.value.actionError)
        }
    }

    @Test fun queuedThenRunningThenDoneFollowsThePollEveryFiveSeconds() {
        val (fake, vm) = update()
        vm.onVisible()
        vm.updateWhenIdle(); settle()
        val readsAfterStart = fake.updateReads
        // Nothing changes until the first 5 s tick, and the tick reads once.
        pass(4_999)
        assertEquals(readsAfterStart, fake.updateReads)
        fake.update = fake.update.copy(state = "queued", activeAgents = 1)
        pass(1)
        assertEquals(readsAfterStart + 1, fake.updateReads)
        assertEquals(1, vm.state.value.status!!.activeAgents)
        // Another 5 s: the update started.
        fake.update = fake.update.copy(state = "running", canApply = false)
        pass(5_000)
        assertTrue(vm.state.value.status!!.isRunning)
        // And finished: result shown, polling stops reading.
        fake.update = fake.update.copy(
            state = "done", behind = 0, result = HermesUpdateResultDto(true, "0.21.4", "0.22.0", "Update finished."),
        )
        pass(5_000)
        val done = vm.state.value.status!!
        assertEquals("done", done.state); assertEquals("0.22.0", done.result?.to)
        val readsAtDone = fake.updateReads
        pass(30_000)
        assertEquals(readsAtDone, fake.updateReads)
        vm.onHidden()
    }

    @Test fun failedUpdateShowsFromToAndMessage() {
        val (fake, vm) = update(FakeDesktop().apply { update = available.copy(state = "running") })
        vm.onVisible()
        fake.update = fake.update.copy(state = "failed", result = HermesUpdateResultDto(false, "0.21.4", "0.21.4", "The update stopped unexpectedly."))
        pass(5_000)
        val failed = vm.state.value.status!!
        assertEquals("failed", failed.state)
        assertEquals("The update stopped unexpectedly.", failed.result?.message)
        vm.onHidden()
    }

    @Test fun noPollingWhenIdleOrHidden() {
        val (fake, vm) = update()
        vm.onVisible()
        pass(60_000)
        assertEquals(1, fake.updateReads)
        fake.update = available.copy(state = "running")
        vm.refresh(); settle()
        val reads = fake.updateReads
        vm.onHidden()
        pass(60_000)
        assertEquals(reads, fake.updateReads)
    }

    @Test fun aFailedPollKeepsTheLastStatusAndFlagsTheLostLink() {
        val (fake, vm) = update(FakeDesktop().apply { update = available.copy(state = "running") })
        vm.onVisible()
        fake.updateError = DesktopError.Unreachable
        pass(5_000)
        assertTrue(vm.state.value.linkLost)
        assertTrue(vm.state.value.status!!.isRunning)
        assertNull(vm.state.value.error)
        fake.updateError = null
        pass(5_000)
        assertFalse(vm.state.value.linkLost)
        vm.onHidden()
    }

    // ---------- More rows: versions and the update badge ----------

    private class Clock(val sched: TestCoroutineScheduler) { fun now() = sched.currentTime }

    private fun info(status: FakeStatus?, fake: FakeDesktop?, clock: Clock = Clock(sched)): PcInfoViewModel =
        PcInfoViewModel(status?.let { s -> { s } }, fake?.let { f -> { f } }, now = clock::now).also { settle() }

    @Test fun moreRowsShowVersionsAndTheUpdateBadge() {
        val vm = info(FakeStatus(), FakeDesktop().apply { update = available })
        val s = vm.state.value
        assertEquals("0.2.6", s.bridgeVersion); assertEquals("0.22.0", s.hermesVersion)
        assertTrue(s.updateAvailable); assertTrue(s.loaded)
    }

    @Test fun noBadgeWhenUpToDateAndUnknownVersionsAreHidden() {
        val status = FakeStatus().apply { error = DesktopError.NotFound }
        val vm = info(status, FakeDesktop().apply { update = HermesUpdateDto(behind = 0, canApply = false, reason = "up_to_date") })
        assertNull(vm.state.value.bridgeVersion); assertNull(vm.state.value.hermesVersion)
        assertFalse(vm.state.value.updateAvailable)
    }

    @Test fun reopeningMoreWithinAMinuteDoesNotReadAgainButAfterItDoes() {
        val status = FakeStatus(); val fake = FakeDesktop().apply { update = available }
        val vm = info(status, fake)
        assertEquals(1, status.reads)
        vm.onOpened(); settle()
        assertEquals(1, status.reads)
        sched.advanceTimeBy(61_000); vm.onOpened(); settle()
        assertEquals(2, status.reads); assertEquals(2, fake.updateReads)
    }

    @Test fun backgroundCheckRunsOnlyEverySixHours() {
        val status = FakeStatus(); val fake = FakeDesktop().apply { update = available }
        info(status, fake)
        assertEquals(1, status.reads)
        pass(6 * 60 * 60 * 1000L - 1)
        assertEquals(1, status.reads)
        pass(1)
        assertEquals(2, status.reads); assertEquals(2, fake.updateReads)
        pass(6 * 60 * 60 * 1000L)
        assertEquals(3, status.reads)
    }

    @Test fun badgeClearsAfterTheUpdateLands() {
        val fake = FakeDesktop().apply { update = available }
        val vm = info(FakeStatus(), fake)
        assertTrue(vm.state.value.updateAvailable)
        fake.update = HermesUpdateDto(current = "0.22.0", behind = 0, canApply = false, reason = "up_to_date")
        pass(6 * 60 * 60 * 1000L)
        assertFalse(vm.state.value.updateAvailable)
    }

    // ---------- connection check ----------

    private val fullOk = BridgeStatusDto(
        bridgeVersion = "0.2.6", hermesHomeFound = true,
        profiles = listOf(StatusProfileDto("default", true, true)),
        hermes = StatusHermesDto("0.22.0", 0), dashboardFound = true, dashboardCheck = "ok",
        approvalsListener = true, urls = StatusUrlsDto(1, 1, 0),
    )

    private fun problems(lines: List<CheckLine>) = lines.filter { it.ok == false }

    @Test fun allOkMeansNoFailedLineAndNoAdvice() {
        val lines = checkLines(fullOk)
        assertTrue(problems(lines).isEmpty())
        assertTrue(lines.none { it.advice != null })
        assertTrue(lines.any { it.title.res == R.string.check_urls })
    }

    @Test fun everyFailedLineGetsExactlyOneAdviceSentence() {
        val broken = fullOk.copy(
            hermesHomeFound = false, dashboardFound = false, dashboardCheck = "ok", approvalsListener = false,
            profiles = listOf(StatusProfileDto("default", false, false)),
        )
        val failed = problems(checkLines(broken))
        assertEquals(5, failed.size)
        assertTrue(failed.all { it.advice != null })
        assertEquals(
            setOf(R.string.check_fix_hermes_home, R.string.check_fix_dashboard, R.string.check_fix_approvals, R.string.check_fix_key, R.string.check_fix_gateway),
            failed.map { it.advice }.toSet(),
        )
    }

    @Test fun dashboardTimeoutIsNamedAndHasItsOwnAdvice() {
        val line = checkLines(fullOk.copy(dashboardFound = false, dashboardCheck = "timeout")).first { it.title.res == R.string.check_dashboard }
        assertEquals(false, line.ok)
        assertEquals(R.string.check_dashboard_timeout, line.detail?.res)
        assertEquals(R.string.check_fix_dashboard_timeout, line.advice)
    }

    @Test fun profileLinesNameTheBotAndUnknownValuesReadUnknown() {
        val lines = checkLines(BridgeStatusDto(profiles = listOf(StatusProfileDto("research", true, false))))
        val gateway = lines.first { it.title.res == R.string.check_bot_gateway }
        assertEquals(listOf<Any>("research"), gateway.title.args)
        assertEquals(false, gateway.ok)
        assertEquals(R.string.hupd_unknown, lines.first { it.title.res == R.string.check_agents }.detail?.res)
        assertEquals(R.string.hupd_unknown, lines.first { it.title.res == R.string.check_hermes }.detail?.res)
    }

    @Test fun urlCountsAreCountsOnly() {
        val urls = checkLines(fullOk.copy(urls = StatusUrlsDto(2, 1, 3))).first { it.title.res == R.string.check_urls }
        assertEquals(listOf<Any>(2, 1, 3), urls.detail?.args)
    }

    @Test fun checkLoadsAndReportsOlderBridge() {
        val ok = PcCheckViewModel { FakeStatus().apply { status = fullOk } }
        settle()
        assertEquals(fullOk, ok.state.value.status)
        val old = PcCheckViewModel { FakeStatus().apply { error = DesktopError.NotFound } }
        settle()
        assertEquals(DesktopError.NotFound, old.state.value.error)
        val direct = PcCheckViewModel(null)
        settle()
        assertTrue(direct.state.value.direct)
    }
}
