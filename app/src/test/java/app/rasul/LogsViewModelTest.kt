package app.rasul

import app.rasul.bridge.DesktopError
import app.rasul.bridge.LogFile
import app.rasul.bridge.LogLevel
import app.rasul.desktop.LogLineKind
import app.rasul.desktop.LogsViewModel
import app.rasul.desktop.logLineKind
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
class LogsViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun open(fake: FakeDesktop = FakeDesktop().apply { logLines = listOf("one", "two") }): Pair<FakeDesktop, LogsViewModel> {
        val vm = LogsViewModel({ fake })
        sched.runCurrent()
        return fake to vm
    }

    @Test fun opensWithTheErrorsFileAndAllLevels() {
        val (fake, vm) = open()
        assertEquals(listOf(Triple(LogFile.Errors, 200, LogLevel.All)), fake.logCalls)
        assertEquals(listOf("one", "two"), vm.state.value.lines)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
    }

    @Test fun switchingFileAsksAgainWithTheKeptLevel() {
        val (fake, vm) = open()
        vm.selectLevel(LogLevel.Error); sched.runCurrent()
        vm.selectFile(LogFile.Gateway); sched.runCurrent()
        assertEquals(Triple(LogFile.Gateway, 200, LogLevel.Error), fake.logCalls.last())
        assertEquals(LogFile.Gateway, vm.state.value.file)
        assertEquals(LogLevel.Error, vm.state.value.level)
        assertEquals(3, fake.logCalls.size)
    }

    @Test fun switchingLevelAsksAgainWithTheKeptFile() {
        val (fake, vm) = open()
        vm.selectFile(LogFile.Agent); sched.runCurrent()
        vm.selectLevel(LogLevel.Warning); sched.runCurrent()
        assertEquals(Triple(LogFile.Agent, 200, LogLevel.Warning), fake.logCalls.last())
    }

    @Test fun choosingTheCurrentFileOrLevelAsksNothing() {
        val (fake, vm) = open()
        vm.selectFile(LogFile.Errors); vm.selectLevel(LogLevel.All); sched.runCurrent()
        assertEquals(1, fake.logCalls.size)
    }

    @Test fun refreshAsksAgainAndCountsTheRead() {
        val (fake, vm) = open()
        val before = vm.state.value.reads
        fake.logLines = listOf("three")
        vm.refresh()
        assertTrue(vm.state.value.loading)
        sched.runCurrent()
        assertEquals(listOf("three"), vm.state.value.lines)
        assertEquals(2, fake.logCalls.size)
        assertEquals(before + 1, vm.state.value.reads)
    }

    @Test fun anEmptyAnswerIsLoadedWithNoLines() {
        val (_, vm) = open(FakeDesktop())
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.lines.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test fun eachFailureIsShownAndNeverRetriedByItself() {
        listOf(DesktopError.LogsNotFound, DesktopError.RateLimited, DesktopError.DesktopFailed, DesktopError.Unreachable).forEach { error ->
            val fake = FakeDesktop().apply { logsError = error }
            val (_, vm) = open(fake)
            sched.advanceTimeBy(10 * 60_000L); sched.runCurrent()
            assertEquals(error, vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.lines.isEmpty())
            assertEquals(1, fake.logCalls.size)
        }
    }

    @Test fun retryAfterAFailureAsksOnceMore() {
        val fake = FakeDesktop().apply { logsError = DesktopError.RateLimited; logLines = listOf("ok") }
        val (_, vm) = open(fake)
        fake.logsError = null
        vm.refresh(); sched.runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(listOf("ok"), vm.state.value.lines)
        assertEquals(2, fake.logCalls.size)
    }

    @Test fun anOlderAnswerDoesNotOverwriteANewerChoice() {
        val (fake, vm) = open()
        vm.selectFile(LogFile.Agent)
        vm.selectFile(LogFile.Gateway)
        sched.runCurrent()
        assertEquals(LogFile.Gateway, vm.state.value.file)
        assertEquals(LogFile.Gateway, fake.logCalls.last().first)
    }

    @Test fun anOlderSlowAnswerNeverOverwritesTheNewerLines() {
        val fake = FakeDesktop().apply { holdLogs = true }
        val vm = LogsViewModel({ fake })
        sched.runCurrent()
        vm.selectFile(LogFile.Gateway)
        sched.runCurrent()
        assertEquals(2, fake.heldLogs.size)
        fake.heldLogs[1].complete(listOf("newer"))
        sched.runCurrent()
        fake.heldLogs[0].complete(listOf("older"))
        sched.runCurrent()
        assertEquals(listOf("newer"), vm.state.value.lines)
        assertEquals(LogFile.Gateway, vm.state.value.file)
    }

    @Test fun directBotAsksNothing() {
        val vm = LogsViewModel(null)
        sched.runCurrent()
        assertTrue(vm.state.value.direct)
        assertFalse(vm.state.value.loading)
    }

    @Test fun lineKindFollowsTheLevelWordNearTheStart() {
        assertEquals(LogLineKind.Error, logLineKind("2026-10-18 09:14:51,977 ERROR agent.tools: failed"))
        assertEquals(LogLineKind.Warning, logLineKind("2026-10-18 09:12:04,402 WARNING gateway: retry"))
        assertEquals(LogLineKind.Plain, logLineKind("2026-10-18 09:12:04,402 INFO gateway.platforms.telegram.poller: handled an ERROR earlier"))
        assertEquals(LogLineKind.Plain, logLineKind("continuation line"))
        assertEquals(LogLineKind.Plain, logLineKind("2026-10-18 09:15:02,310 INFO agent.run: ERROR x"))
        assertEquals(LogLineKind.Warning, logLineKind("2026-10-18 09:15:02,310 WARNING agent.run: ERROR x"))
        assertEquals(LogLineKind.Error, logLineKind("2026-10-18 09:15:02,310 CRITICAL agent.run: down"))
        assertEquals(LogLineKind.Plain, logLineKind("2026-10-18 09:15:02,310 ERRORS agent.run: x"))
    }
}
