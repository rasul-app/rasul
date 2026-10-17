package app.rasul.bridge

import app.rasul.chat.Anchor
import app.rasul.chat.RunMemory
import app.rasul.chat.TargetLoader
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import kotlinx.coroutines.CancellationException

fun bridgeTargetLoader(repository: BridgeBotsRepository, bridgeId: String, profileId: String) = TargetLoader {
    try { repository.target(bridgeId, profileId) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { HermesResult.Err(bridgeError(e)) }
}

/** Same persistence as chatRunMemory (ChatTarget.kt): run id, last event id and anchor key, keyed by bridge bot + profile. */
fun bridgeRunMemory(store: ConnectionStore, bridgeId: String, profileId: String) = object : RunMemory {
    private val id = bridgeBotId(bridgeId, profileId)
    override suspend fun sessionId() = store.sessionId(id, profileId)
    override suspend fun activeRun() = store.activeRun(id, profileId)
    override suspend fun lastEventId() = store.lastEventId(id, profileId)
    override suspend fun anchor(): Anchor = store.runAnchor(id, profileId)?.let {
        Anchor.Known(it.takeIf { key -> key.isNotEmpty() })
    } ?: Anchor.Unknown
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) =
        store.setActiveRun(id, profileId, runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null)
    override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor) =
        store.setCurrentSessionRun(id, profileId, sessionId, runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null)
}
