package app.rasul

import app.rasul.data.HermesError
import app.rasul.data.RunEvent
import app.rasul.data.RunFrame
import app.rasul.data.RunStreamException
import app.rasul.data.RunUpdate
import app.rasul.data.followRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFollowTest {
    private fun delta(seq: Long, t: String) = RunFrame(seq, RunEvent.MessageDelta(t))

    @Test fun dropThenResumeFromLastSeqWithoutDuplicates() = runBlocking {
        val asked = mutableListOf<Long?>()
        val open: (Long?) -> Flow<RunFrame> = { last ->
            asked += last
            if (asked.size == 1) {
                flow {
                    emit(delta(0, "a"))
                    emit(delta(1, "b"))
                    throw RunStreamException(HermesError.Unreachable("reset"))
                }
            } else {
                flow {
                    emit(delta(1, "b")) // replayed duplicate, must be dropped
                    emit(delta(2, "c"))
                    emit(RunFrame(3, RunEvent.Completed("abc")))
                }
            }
        }
        val waits = mutableListOf<Long>()
        val out = followRun(open, wait = { waits += it }).toList()
        assertEquals(listOf<Long?>(null, 1L), asked)
        assertEquals(1, out.filterIsInstance<RunUpdate.Reconnecting>().size)
        val events = out.filterIsInstance<RunUpdate.Event>().map { it.event }
        assertEquals(
            listOf(RunEvent.MessageDelta("a"), RunEvent.MessageDelta("b"), RunEvent.MessageDelta("c"), RunEvent.Completed("abc")),
            events,
        )
        assertEquals(listOf(1000L), waits)
    }

    @Test fun streamEndingWithoutTerminalEventIsReopened() = runBlocking {
        var n = 0
        val open: (Long?) -> Flow<RunFrame> = {
            n++
            if (n == 1) flow { emit(delta(0, "a")) } else flow { emit(RunFrame(1, RunEvent.Cancelled())) }
        }
        val out = followRun(open, wait = {}).toList()
        assertEquals(2, n)
        assertTrue(out.last() is RunUpdate.Event)
    }

    @Test fun givesUpAfterFiveReconnects() = runBlocking {
        var opens = 0
        val open: (Long?) -> Flow<RunFrame> = { opens++; flow { throw RunStreamException(HermesError.Unreachable("down")) } }
        val out = followRun(open, wait = {}).toList()
        assertEquals(6, opens)
        assertEquals(5, out.filterIsInstance<RunUpdate.Reconnecting>().size)
        assertTrue((out.last() as RunUpdate.Lost).error is HermesError.Unreachable)
    }

    @Test fun unauthorizedIsNotRetried() = runBlocking {
        var opens = 0
        val open: (Long?) -> Flow<RunFrame> = { opens++; flow { throw RunStreamException(HermesError.Unauthorized) } }
        val out = followRun(open, wait = {}).toList()
        assertEquals(1, opens)
        assertEquals(listOf<RunUpdate>(RunUpdate.Lost(HermesError.Unauthorized)), out)
    }

    @Test fun counterResetsWhenEventsArrive() = runBlocking {
        var opens = 0
        val open: (Long?) -> Flow<RunFrame> = { last ->
            opens++
            flow {
                if (opens <= 8) {
                    emit(delta((last ?: -1) + 1, "x"))
                    throw RunStreamException(HermesError.Unreachable("flap"))
                }
                emit(RunFrame((last ?: -1) + 1, RunEvent.Cancelled()))
            }
        }
        val out = followRun(open, wait = {}).toList()
        assertEquals(9, opens)
        assertTrue(out.none { it is RunUpdate.Lost })
    }
}
