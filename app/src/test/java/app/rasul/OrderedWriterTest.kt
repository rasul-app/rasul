package app.rasul

import app.rasul.chat.OrderedWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedWriterTest {
    private val sched = TestCoroutineScheduler()

    // StandardTestDispatcher runs launched coroutines FIFO. A launch-per-write writer starts write 2 as soon as
    // write 1 suspends, so the first assertion below fails on that implementation; the channel queue waits for write 1.
    @Test fun aLaterWriteWaitsForAnEarlierSuspendedWrite() {
        val scope = CoroutineScope(StandardTestDispatcher(sched) + Job())
        val writer = OrderedWriter(scope)
        val log = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        writer.submit { release.await(); log += 1 }
        writer.submit { log += 2 }
        sched.advanceUntilIdle()
        assertEquals(emptyList<Int>(), log)
        release.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals(listOf(1, 2), log)
        scope.cancel()
    }

    @Test fun aThrowingWriteDoesNotStopLaterWrites() {
        val scope = CoroutineScope(StandardTestDispatcher(sched) + Job())
        val writer = OrderedWriter(scope)
        val log = mutableListOf<Int>()
        writer.submit { throw IllegalStateException("boom") }
        writer.submit { log += 2 }
        sched.advanceUntilIdle()
        assertEquals(listOf(2), log)
        scope.cancel()
    }

    @Test fun cancellingTheScopeStopsEveryLaterWrite() {
        val scope = CoroutineScope(StandardTestDispatcher(sched) + Job())
        val writer = OrderedWriter(scope)
        val log = mutableListOf<Int>()
        writer.submit { CompletableDeferred<Unit>().await(); log += 1 } // never released
        writer.submit { log += 2 }
        sched.advanceUntilIdle()
        scope.cancel()
        sched.advanceUntilIdle()
        writer.submit { log += 3 }
        sched.advanceUntilIdle()
        assertEquals(emptyList<Int>(), log)
    }
}
