package app.rasul

import app.rasul.data.RunEvent
import app.rasul.data.parseRunFrame
import app.rasul.data.sseFrames
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun frames(s: String) = Buffer().writeUtf8(s).sseFrames().toList()

    @Test fun multiLineDataCommentsAndIdAreParsed() {
        val f = frames(": open\n\nid: 7\nevent: x\ndata: a\ndata: b\n\n: keepalive\n\n").single()
        assertEquals("7", f.id)
        assertEquals("x", f.event)
        assertEquals("a\nb", f.data)
    }

    @Test fun frameWithoutDataAndHalfWrittenLastFrameAreDropped() {
        assertTrue(frames("id: 1\n\n").isEmpty())
        assertEquals(1, frames("data: one\n\ndata: half").size)
    }

    @Test fun noSpaceAfterColonAndCrLfAreAccepted() {
        assertEquals("v", frames("data:v\r\n\r\n").single().data)
    }

    @Test fun runEventsAreNamedByTheJsonEventKeyAndSeqComesFromId() {
        val f = frames("id: 3\ndata: {\"event\":\"tool.started\",\"run_id\":\"r\",\"tool\":\"bash\",\"preview\":\"ls\"}\n\n").single()
        val rf = parseRunFrame(f)
        assertEquals(3L, rf.seq)
        assertEquals(RunEvent.ToolStarted("bash", "ls"), rf.event)
    }

    @Test fun unknownEventAndBrokenJsonNeverCrash() {
        val u = parseRunFrame(frames("data: {\"event\":\"future.thing\",\"x\":1}\n\n").single()).event
        assertTrue(u is RunEvent.Unknown && u.name == "future.thing")
        val b = parseRunFrame(frames("data: not json\n\n").single()).event
        assertTrue(b is RunEvent.Unknown)
    }

    @Test fun approvalAndTerminalEventsAreModelled() {
        val a = parseRunFrame(
            frames("data: {\"event\":\"approval.request\",\"command\":\"rm -rf x\",\"request_id\":\"q1\",\"choices\":[\"once\",\"deny\"]}\n\n").single(),
        ).event
        assertEquals(RunEvent.ApprovalRequest("q1", "rm -rf x", null, listOf("once", "deny")), a)
        val c = parseRunFrame(frames("data: {\"event\":\"run.completed\",\"output\":\"Done\"}\n\n").single()).event
        assertEquals(RunEvent.Completed("Done"), c)
        assertTrue(c.isTerminal)
    }
}
