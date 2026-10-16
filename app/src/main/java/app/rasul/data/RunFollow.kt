package app.rasul.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the UI sees while following a run. */
sealed interface RunUpdate {
    data class Event(val event: RunEvent, val seq: Long? = null) : RunUpdate

    /** The stream dropped while the run was still going; reconnect [attempt] of [max] is about to start. */
    data class Reconnecting(val attempt: Int, val max: Int) : RunUpdate

    /** Followed no further: [error] is why (retries used up, or a failure retrying cannot fix). */
    data class Lost(val error: HermesError) : RunUpdate
}

const val MAX_RECONNECTS = 5

/** 1 s, 2 s, 4 s, 8 s, 8 s. */
fun reconnectBackoffMs(attempt: Int): Long = minOf(1000L shl (attempt - 1).coerceIn(0, 3), 8000L)

private fun HermesError.retryable() = this is HermesError.Unreachable || this is HermesError.Server ||
    this == HermesError.BridgeUnreachable || this == HermesError.BridgeTimeout

/**
 * Follows a run across dropped connections. [open] makes one SSE connection starting after the given seq.
 * Ends after a terminal event. A stream that ends without one, or breaks, is reopened (at most
 * [maxReconnects] in a row; the count resets when an event arrives). Only the events GET is repeated, the
 * input is never resent. Frames at or below the last seen seq are dropped as replay duplicates.
 */
fun followRun(
    open: (lastSeq: Long?) -> Flow<RunFrame>,
    maxReconnects: Int = MAX_RECONNECTS,
    backoffMs: (Int) -> Long = ::reconnectBackoffMs,
    wait: suspend (Long) -> Unit = { delay(it) },
    startAfter: Long? = null,
): Flow<RunUpdate> = flow {
    var last: Long? = startAfter
    var attempt = 0
    while (true) {
        var terminal = false
        var failure: HermesError? = null
        try {
            open(last).collect { f ->
                val seq = f.seq
                val seen = last
                if (seq != null && seen != null && seq <= seen) return@collect
                if (seq != null) last = seq
                attempt = 0
                emit(RunUpdate.Event(f.event, f.seq))
                if (f.event.isTerminal) terminal = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RunStreamException) {
            failure = e.error
        }
        if (terminal) return@flow
        if (failure != null && !failure.retryable()) {
            emit(RunUpdate.Lost(failure))
            return@flow
        }
        attempt++
        if (attempt > maxReconnects) {
            emit(RunUpdate.Lost(failure ?: HermesError.Unreachable("stream closed before the run finished")))
            return@flow
        }
        emit(RunUpdate.Reconnecting(attempt, maxReconnects))
        wait(backoffMs(attempt))
    }
}
