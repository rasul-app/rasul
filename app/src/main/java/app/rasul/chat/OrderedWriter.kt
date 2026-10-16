package app.rasul.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One application-owned queue: writes run one at a time in the order they were submitted, across every screen.
 * A failed write is dropped (losing a breadcrumb only costs the re-attach after Back); cancellation stops the queue.
 */
class OrderedWriter(scope: CoroutineScope) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // swallowed per write
                }
            }
        }
    }

    fun submit(job: suspend () -> Unit) {
        queue.trySend(job)
    }
}
