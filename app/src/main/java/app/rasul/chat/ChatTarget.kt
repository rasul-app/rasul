package app.rasul.chat

import app.rasul.AppContainer
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget

/** Session id, active run id, and last event id for one connection+profile. The profile is read once. */
fun chatRunMemory(container: AppContainer, id: String) = object : RunMemory {
    private var profile: String? = null
    private var read = false

    private suspend fun profile(): String? {
        if (!read) {
            profile = container.connections.get(id)?.profile
            read = true
        }
        return profile
    }

    override suspend fun sessionId() = container.connections.sessionId(id, profile())
    override suspend fun activeRun() = container.connections.activeRun(id, profile())
    override suspend fun lastEventId() = container.connections.lastEventId(id, profile())
    override suspend fun anchor(): Anchor = container.connections.runAnchor(id, profile())?.let {
        Anchor.Known(it.takeIf { key -> key.isNotEmpty() })
    } ?: Anchor.Unknown
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) =
        container.connections.setActiveRun(id, profile(), runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null)
}

/** Reads the saved connection and decrypts its key; a missing piece is a specific error, not a blank screen. */
fun chatTargetLoader(container: AppContainer, id: String) = TargetLoader {
    try {
        val c = container.connections.get(id)
        val key = c?.let { container.connections.apiKey(id) }
        when {
            c == null -> HermesResult.Err(HermesError.NotFound)
            key == null -> HermesResult.Err(HermesError.InvalidKey)
            else -> HermesResult.Ok(HermesTarget(c.baseUrl, c.profile, key))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        HermesResult.Err(HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
    }
}
