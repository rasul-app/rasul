package app.rasul

import app.rasul.chat.BackgroundTasks
import app.rasul.chat.BgItem
import app.rasul.chat.BgKind
import app.rasul.chat.BgStatus
import app.rasul.chat.ChatState
import app.rasul.chat.ActiveRun
import app.rasul.chat.StatusLabel
import app.rasul.chat.TasksPill
import app.rasul.chat.tasksPill
import app.rasul.data.BridgeProcess
import app.rasul.data.RunEvent
import app.rasul.data.SseFrame
import app.rasul.data.parseProcesses
import app.rasul.data.parseRunFrame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background list built from run events (shapes copied from recorded streams, values made up) and the pill rules. */
class BackgroundTasksTest {
    private fun frame(json: String) = parseRunFrame(SseFrame("1", null, json)).event

    /** Feeds recorded SSE data lines through the real parser into the reducer, the way the chat does. */
    private fun fold(vararg lines: String, at: Long = 100_000L): BackgroundTasks =
        lines.fold(BackgroundTasks()) { tasks, line ->
            when (val e = frame(line)) {
                is RunEvent.ToolStarted -> tasks.toolStarted(e.tool, e.preview, e.atMillis ?: at)
                is RunEvent.ToolCompleted -> tasks.toolCompleted(e.tool, e.error, e.preview, e.atMillis ?: at)
                is RunEvent.Subagent -> tasks.subagent(e, at)
                else -> tasks
            }
        }

    private fun line(event: String, tool: String, preview: String, error: Boolean = false, stamp: Double? = null) =
        buildJsonObject {
            put("event", event)
            put("tool", tool)
            if (event == "tool.completed") {
                put("duration", 0.3)
                put("error", error)
            }
            put("preview", preview)
            if (stamp != null) put("timestamp", stamp)
        }.toString()

    private fun call(preview: String, stamp: Double? = null) = line("tool.started", "terminal", preview, stamp = stamp)

    private fun done(tool: String, result: String, error: Boolean = false) = line("tool.completed", tool, cut(result), error)

    private fun manage(preview: String, result: String, error: Boolean = false) =
        arrayOf(line("tool.started", "process_manage", preview), done("process_manage", result, error))

    /** The 500-character cut Hermes puts on a result preview. */
    private fun cut(text: String) = if (text.length <= 500) text else text.take(497) + "..."

    /** Process ids are "proc_" and 12 hex characters; Hermes cuts the id in a call preview to 16 characters. */
    private val id = "proc_a1b2c3d4e5f6"
    private val other = "proc_a1b2c3d4e5f7"
    private val short = id.take(16)

    /** The note a background terminal call returns when no completion notice was asked for. */
    private val hint = "background=true without notify_on_complete=true means this process runs SILENTLY - you will not be told when it exits. " +
        "If this is a bounded task (test suite, build, CI poller, deploy, anything with a defined end), you almost certainly wanted " +
        "notify_on_complete=true so the system pings you on exit. Re-launch with notify_on_complete=true, or call process(action='poll') " +
        "yourself to learn the outcome."

    private fun startResult(session: String = id) =
        """{"output": "Background process started", "session_id": "$session", "pid": 4242, "exit_code": 0, "error": null}"""

    private val started = call("npm run build", 1792100000.5)
    private val startedDone = done("terminal", startResult())
    private val startedWithHint = done("terminal", startResult().dropLast(1) + """, "hint": "$hint"}""")
    private val startedOther = arrayOf(call("npm run test"), done("terminal", startResult(other)))

    private fun poll(status: String, extra: String = "", session: String = id) =
        """{"session_id": "$session", "command": "npm run build", "status": "$status", "pid": 4242, "uptime_seconds": 12, "output_preview": "ok"$extra}"""

    private fun exitedWith(code: Int?, reason: String = "exit") =
        """, "exit_code": $code, "completion_reason": "$reason", "termination_source": null"""

    private fun waitExited(code: Int) =
        """{"status": "exited", "command": "npm run build", "exit_code": $code, "completion_reason": "exit", "termination_source": null, "output": "done"}"""

    @Test fun aBackgroundTerminalCallBecomesARunningProcess() {
        val t = fold(started, startedDone)
        val item = t.items.single()
        assertEquals(BgKind.Process, item.kind)
        assertEquals("npm run build", item.name)
        assertEquals(BgStatus.Running, item.status)
        assertEquals(id, item.procId)
        assertEquals(1_792_100_000_500L, item.startedAt)
        assertEquals(1, t.runningCount)
    }

    @Test fun aStartResultCutAtFiveHundredCharactersIsStillATask() {
        assertTrue(startedWithHint.contains("..."))
        assertEquals(id, fold(started, startedWithHint).items.single().procId)
    }

    @Test fun anOrdinaryTerminalCallIsNotATask() {
        val t = fold(started, done("terminal", """{"output": "build ok", "exit_code": 0, "error": null}"""))
        assertTrue(t.items.isEmpty())
    }

    @Test fun aProcessIdInOrdinaryOutputIsNotATask() {
        assertTrue(fold(started, done("terminal", """{"output": "Background process started $id", "exit_code": 0, "error": null}""")).items.isEmpty())
        assertTrue(fold(started, done("terminal", """{"output": "cat $id.log", "exit_code": 0, "error": null}""")).items.isEmpty())
    }

    @Test fun aFailedTerminalCallIsNotATask() {
        val t = fold(started, done("terminal", startResult(), error = true))
        assertTrue(t.items.isEmpty())
    }

    @Test fun aReplayDoesNotAddTheSameProcessTwice() {
        val t = fold(started, startedDone, started, startedDone)
        assertEquals(1, t.items.size)
    }

    private fun running(vararg more: String) = fold(started, startedDone, *startedOther, *more)

    private fun statuses(t: BackgroundTasks) = t.items.associate { it.procId to it.status }

    @Test fun killingAProcessFinishesIt() {
        val t = running(*manage("kill $short", """{"status": "killed", "session_id": "$id", "completion_reason": "killed", "termination_source": "process.kill", "output": ""}"""))
        assertEquals(BgStatus.Finished, statuses(t)[id])
        assertEquals(BgStatus.Running, statuses(t)[other])
        assertEquals(1, t.runningCount)
    }

    @Test fun aKillThatLeftTheProcessAliveChangesNothing() {
        val t = running(*manage("kill $short", """{"status": "error", "error": "Kill incomplete: 1 process(es) still alive (4243); session running", "session_id": "$id", "survivors": [4243], "process_running": true}""", error = true))
        assertEquals(BgStatus.Running, statuses(t)[id])
    }

    @Test fun closingStdinDoesNotEndAProcess() {
        // Whether the call text carries the whole id or the 16 characters Hermes cuts it to.
        for (named in listOf(short, id)) {
            val t = fold(started, startedDone, *manage("close $named", """{"status": "ok", "message": "stdin closed"}"""))
            assertEquals(BgStatus.Running, t.items.single().status)
        }
        // Even a result that looks like an exit does not end it when the call was a close, write, submit or log.
        for (action in listOf("close", "write", "submit", "log")) {
            for (named in listOf(short, id)) {
                val again = fold(started, startedDone, *manage("$action $named", """{"status": "already_exited", "error": "Process has already finished"}"""))
                assertEquals(BgStatus.Running, again.items.single().status)
            }
        }
    }

    @Test fun pollingARunningProcessKeepsItRunning() {
        val t = running(*manage("poll $short", poll("running")))
        assertEquals(BgStatus.Running, statuses(t)[id])
    }

    @Test fun pollingAnExitedProcessFinishesIt() {
        val t = running(*manage("poll $short", poll("exited", exitedWith(0))))
        assertEquals(BgStatus.Finished, statuses(t)[id])
        assertEquals(BgStatus.Running, statuses(t)[other])
    }

    @Test fun aWaitThatSeesTheExitFinishesItByTheIdInTheCall() {
        // A wait result carries no session id: the call text names the process.
        val t = fold(started, startedDone, *manage("wait ${id.take(16)} 30s", waitExited(0)))
        assertEquals(BgStatus.Finished, t.items.single().status)
    }

    @Test fun aWaitThatTimedOutKeepsItRunning() {
        val t = fold(started, startedDone, *manage("wait $short 30s", """{"status": "timeout", "command": "npm run build", "output": "", "process_running": true, "timeout_note": "Wait window elapsed. This is not an error."}"""))
        assertEquals(BgStatus.Running, t.items.single().status)
    }

    @Test fun aNonZeroExitCodeIsFailedAndAKillIsNot() {
        val failed = fold(started, startedDone, *manage("wait $short", waitExited(2)))
        assertEquals(BgStatus.Failed, failed.items.single().status)
        val killed = running(*manage("poll $short", poll("already_exited", exitedWith(-15, "killed"))))
        assertEquals(BgStatus.Finished, statuses(killed)[id])
    }

    @Test fun aProcessTheAgentNoLongerKnowsIsOver() {
        val gone = """{"status": "not_found", "error": "No process with ID $id"}"""
        val one = fold(started, startedDone, *manage("poll $short", gone, error = true))
        assertEquals(BgStatus.Finished, one.items.single().status)
    }

    @Test fun anAmbiguousOrTooShortIdInTheCallChangesNothing() {
        // Both ids share their first 16 characters.
        assertEquals(2, running(*manage("wait $short", waitExited(0))).runningCount)
        // Fewer than 10 characters, or not a process id at all.
        assertEquals(1, fold(started, startedDone, *manage("wait proc_a1b", waitExited(0))).runningCount)
        assertEquals(1, fold(started, startedDone, *manage("wait abcdefghijklmnop", waitExited(0))).runningCount)
        // The full id in a result decides, even when the call text is ambiguous.
        assertEquals(BgStatus.Finished, statuses(running(*manage("poll $short", poll("exited", exitedWith(0)))))[id])
    }

    @Test fun aResultCutAtFiveHundredCharactersStillSaysWhatHappened() {
        val long = poll("exited", exitedWith(0)).replace("\"ok\"", "\"" + "x".repeat(600) + "\"")
        val cutLine = done("process_manage", long)
        assertTrue(cutLine.contains("..."))
        val t = fold(started, startedDone, line("tool.started", "process_manage", "poll $short"), cutLine)
        assertEquals(BgStatus.Finished, t.items.single().status)
    }

    @Test fun listEndsOnlyTheProcessesItShowsAsExited() {
        val rows = """{"processes": [{"session_id": "$id", "command": "a", "pid": 1, "status": "exited", "exit_code": 1, "completion_reason": "exit"}, {"session_id": "$other", "command": "b", "pid": 2, "status": "running"}]}"""
        val t = running(*manage("list", rows))
        assertEquals(BgStatus.Failed, statuses(t)[id])
        assertEquals(BgStatus.Running, statuses(t)[other])
        // A list that is not complete JSON is not read (its first row would stand for the others).
        val cutRows = line("tool.completed", "process_manage", rows.take(120) + "...")
        val ignored = fold(started, startedDone, line("tool.started", "process_manage", "list"), cutRows)
        assertEquals(BgStatus.Running, ignored.items.single().status)
        // A process the list leaves out is not ended by that.
        assertEquals(BgStatus.Running, statuses(running(*manage("list", """{"processes": []}""")))[id])
    }

    @Test fun aHelperRunsThenFinishesOrFails() {
        val t = fold(
            """{"event":"subagent.start","subagent_id":"sa-1","goal":"Research the logs","timestamp":1792100000}""",
            """{"event":"subagent.start","subagent_id":"sa-2","goal":"Check the tests"}""",
            """{"event":"subagent.complete","subagent_id":"sa-1","summary":"done","status":"completed"}""",
            """{"event":"subagent.complete","subagent_id":"sa-2","summary":"x","status":"failed"}""",
        )
        assertEquals(listOf(BgStatus.Finished, BgStatus.Failed), t.items.map { it.status })
        assertEquals(listOf(BgKind.Helper, BgKind.Helper), t.items.map { it.kind })
        assertEquals(1_792_100_000_000L, t.items[0].startedAt)
    }

    @Test fun nameIsOneLineAtMostTwoHundredCharacters() {
        val long = "echo " + "x".repeat(400) + "\nsecond line"
        val t = fold(call(long), startedDone)
        val name = t.items.single().name
        assertTrue(name.length <= BackgroundTasks.NAME_MAX)
        assertTrue('\n' !in name)
        assertTrue(name.endsWith("…"))
    }

    @Test fun whenTheRunEndsHelpersEndButProcessesGoOn() {
        val t = fold(started, startedDone, """{"event":"subagent.start","subagent_id":"sa-1","goal":"Helper"}""").runEnded()
        assertEquals(BgStatus.Running, t.items.first { it.kind == BgKind.Process }.status)
        assertEquals(BgStatus.Finished, t.items.first { it.kind == BgKind.Helper }.status)
    }

    @Test fun afterTheRunTheSheetListsProcessesCheckedAgainstTheBridge() {
        val t = fold(started, startedDone, """{"event":"subagent.start","subagent_id":"sa-1","goal":"Helper"}""").runEnded()
        // Live: everything seen.
        assertEquals(2, t.sheetItems(runLive = true, remote = null).size)
        // Ended and the bridge not asked: processes only, as last seen.
        assertEquals(listOf(BgKind.Process), t.sheetItems(false, null).map { it.kind })
        // The bridge no longer lists it: finished. One the chat never saw: added as running.
        val gone = t.sheetItems(false, emptyList())
        assertEquals(BgStatus.Finished, gone.single().status)
        val unseen = BridgeProcess("proc_zz", "tail -f log", 5L, "s")
        val both = t.sheetItems(false, listOf(BridgeProcess(id, "npm run build", 1L, "s"), unseen))
        assertEquals(listOf(BgStatus.Running, BgStatus.Running), both.map { it.status })
        assertEquals("tail -f log", both[1].name)
    }

    @Test fun pillShowsOnlyForPositiveCounts() {
        val none = ChatState(run = ActiveRun("r", 0L, StatusLabel.Thinking))
        assertNull(tasksPill(none))
        val running = none.copy(background = BackgroundTasks(listOf(BgItem("a", BgKind.Process, "x", 1L, BgStatus.Running), BgItem("b", BgKind.Helper, "y", 1L, BgStatus.Finished))))
        assertEquals(TasksPill.Working(1), tasksPill(running))
        val ended = ChatState(run = null, background = running.background, remoteProcesses = null)
        assertNull(tasksPill(ended))
        assertNull(tasksPill(ended.copy(remoteProcesses = emptyList())))
        assertEquals(TasksPill.StillRunning(2), tasksPill(ended.copy(remoteProcesses = listOf(BridgeProcess("a", "", null, "s"), BridgeProcess("b", "", null, "s")))))
    }

    @Test fun parsesTheBridgeList() {
        val list = parseProcesses("""[{"id":"proc_1","command":"npm run build","started_at":1792100000.5,"owner":"sess-1"},{"id":"proc_2","command":"","started_at":null,"owner":null},{"command":"no id"},7]""")!!
        assertEquals(listOf("proc_1", "proc_2"), list.map { it.id })
        assertEquals(1_792_100_000_500L, list[0].startedAtMillis)
        assertEquals("sess-1", list[0].owner)
        assertNull(list[1].startedAtMillis)
        assertNull(list[1].owner)
    }

    @Test fun aReplyThatIsNotAListIsRefused() {
        assertNull(parseProcesses("""{"error":"x"}"""))
        assertNull(parseProcesses("not json"))
        assertEquals(emptyList<BridgeProcess>(), parseProcesses("[]"))
    }
}
