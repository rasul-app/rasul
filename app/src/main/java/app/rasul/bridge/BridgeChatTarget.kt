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
    private var revision: String? = null
    override fun bindRevision(revision: String) { this.revision = revision }
    private suspend fun revision(): String = revision ?: store.revision(id).also { revision = it }
    override suspend fun sessionId() = store.sessionId(id, profileId, revision())
    override suspend fun currentSessionId() = store.currentSessionId(id, profileId)
    override suspend fun activeRun() = store.activeRun(id, profileId, sessionId())
    override suspend fun activeRun(sessionId: String) = store.activeRun(id, profileId, sessionId)
    override suspend fun activeRunSessions() = store.activeRunSessions(id, profileId)
    override suspend fun lastEventId() = store.lastEventId(id, profileId, sessionId())
    override suspend fun lastEventId(sessionId: String) = store.lastEventId(id, profileId, sessionId)
    override suspend fun anchor(): Anchor = anchor(sessionId())
    override suspend fun anchor(sessionId: String): Anchor = store.runAnchor(id, profileId, sessionId)?.let {
        Anchor.Known(it.takeIf { key -> key.isNotEmpty() })
    } ?: Anchor.Unknown
    private var boundSession: String? = null
    override suspend fun setSessionId(sessionId: String) {
        boundSession = sessionId
    }
    // Writes never follow the pointer: the session must be explicit (bound via setSessionId/setCurrentSessionRun).
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) =
        store.setActiveRun(id, profileId, checkNotNull(boundSession) { "setActiveRun needs an explicit session id" }, runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null, revision())
    override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor) =
        store.setCurrentSessionRun(id, profileId, sessionId, runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null, revision())
    override suspend fun setCurrentSessionRun(sessionId: String, runId: String?, lastEventId: Long?, anchor: Anchor, claim: ConnectionStore.PointerClaim) =
        store.setCurrentSessionRun(id, profileId, sessionId, runId, lastEventId, (anchor as? Anchor.Known)?.key ?: if (anchor is Anchor.Known) "" else null, revision(), claim)
}
