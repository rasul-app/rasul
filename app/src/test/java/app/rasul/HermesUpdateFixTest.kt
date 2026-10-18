package app.rasul

import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.HermesUpdateDto
import app.rasul.desktop.HermesUpdateViewModel
import app.rasul.desktop.IdleChip
import app.rasul.desktop.idleChip
import app.rasul.desktop.knownVersion
import app.rasul.desktop.updateNotice
import app.rasul.desktop.updateResultMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** RASULHUPDAPPF1: fix-round tests (chip, strings, result sentences, poll errors). */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesUpdateFixTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun settle() = sched.runCurrent()
    private fun pass(ms: Long) { sched.advanceTimeBy(ms); sched.runCurrent() }

    private val available = HermesUpdateDto(current = "0.21.4", latest = "9f3c2ab", behind = 4, canApply = true)

    // ---- B2 ----
    @Test fun upToDateChipOnlyForTheUpToDateReason() {
        assertEquals(IdleChip.UpToDate, idleChip(HermesUpdateDto(behind = 0, reason = "up_to_date")))
        listOf("dashboard_not_running", "check_failed", "not_applicable", "something_new", null).forEach { reason ->
            assertEquals(reason, IdleChip.Unknown, idleChip(HermesUpdateDto(behind = 0, reason = reason)))
        }
    }

    @Test fun availableWinsOverTheReason() {
        assertEquals(IdleChip.Available, idleChip(available))
        assertEquals(IdleChip.Available, idleChip(HermesUpdateDto(behind = null, canApply = true)))
    }

    @Test fun bridgeUnknownVersionLiteralIsNotShownRaw() {
        assertNull(knownVersion("unknown"))
        assertNull(knownVersion("  "))
        assertNull(knownVersion(null))
        assertEquals("0.21.4", knownVersion("0.21.4"))
    }

    // ---- N2 ----
    @Test fun everyFixedBridgeSentenceHasItsOwnString() {
        val sentences = listOf(
            "Cancelled.", "The queued update expired.", "The update stopped unexpectedly.", "Update completed.",
            "Update completed with some steps skipped.", "Update did not complete.", "The update did not report a result in time.",
            "The update could not be started.",
        )
        val ids = sentences.map { updateResultMessage(it) }
        assertTrue(ids.none { it == null })
        assertEquals(sentences.size, ids.toSet().size)
        assertNull(updateResultMessage("Update completed; follow-up needed: restart"))
        assertNull(updateResultMessage(null))
        assertNull(updateResultMessage(""))
    }

    // ---- B1 / strings in both languages ----
    private fun names(path: String): Set<String> =
        Regex("<(?:string|plurals) name=\"([a-z0-9_]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    @Test fun renamedStringsExistInBothLanguagesAndDoNotClash() {
        val en = names("src/main/res/values/strings.xml")
        val ar = names("src/main/res/values-ar/strings.xml")
        assertTrue(en.contains("hupd_err_rate_limited") && ar.contains("hupd_err_rate_limited"))
        // the GitHub rate-limit message of the app update keeps its own, different name
        assertTrue(en.contains("update_err_rate_limited") && ar.contains("update_err_rate_limited"))
        listOf("hupd_refresh", "hupd_chip_unknown", "hupd_res_cancelled", "hupd_res_expired", "hupd_res_stopped", "hupd_res_completed",
            "hupd_res_skipped", "hupd_res_not_completed", "hupd_res_no_report", "hupd_res_not_started", "mcp_list_stale").forEach {
            assertTrue("en $it", en.contains(it)); assertTrue("ar $it", ar.contains(it))
        }
    }

    // ---- N3 ----
    private fun vm(fake: FakeDesktop = FakeDesktop().apply { update = available.copy(state = "running") }): Pair<FakeDesktop, HermesUpdateViewModel> {
        val vm = HermesUpdateViewModel({ fake })
        settle()
        return fake to vm
    }

    @Test fun pollFailuresAreMappedByCause() {
        val (fake, vm) = vm()
        vm.onVisible()
        listOf(DesktopError.RateLimited, DesktopError.DeviceRevoked, DesktopError.NotFound).forEach { error ->
            fake.updateError = error
            pass(5_000)
            assertEquals(error.name, error, vm.state.value.pollError)
            assertEquals(error.name, error.message, updateNotice(vm.state.value))
        }
        fake.updateError = DesktopError.Unreachable
        pass(5_000)
        assertEquals(R.string.hupd_link_lost, updateNotice(vm.state.value))
        fake.updateError = null
        pass(5_000)
        assertNull(vm.state.value.pollError)
        assertNull(updateNotice(vm.state.value))
        vm.onHidden()
    }

    @Test fun failedManualRefreshWhileAStatusIsShownKeepsItAndShowsAnInlineError() {
        val (fake, vm) = vm(FakeDesktop().apply { update = available })
        fake.updateError = DesktopError.RateLimited
        vm.refresh(); settle()
        assertEquals(available, vm.state.value.status)
        assertEquals(DesktopError.RateLimited, vm.state.value.error)
        assertEquals(DesktopError.RateLimited.message, updateNotice(vm.state.value))
        fake.updateError = null
        vm.refresh(); settle()
        assertNull(updateNotice(vm.state.value))
    }

    @Test fun noNoticeWhenNothingFailed() {
        val (_, vm) = vm(FakeDesktop().apply { update = available })
        assertNull(updateNotice(vm.state.value))
        assertTrue(DesktopException(DesktopError.RateLimited).error == DesktopError.RateLimited)
    }
}
