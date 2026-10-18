package app.rasul.bridge

import app.rasul.data.HermesError
import app.rasul.data.reconnectBackoffMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** `new`/`resolved` bursts collapse into a single list refetch after this quiet period. */
private const val EVENT_REFETCH_MS = 300L

/** Minimum gap between the end of one background list GET and the start of the next on one feed. */
private const val FEED_GET_GAP_MS = 1_000L

/**
 * The per-PC slot is held this long after each background GET, so a bridge's total background
 * approvals traffic stays under ~30 requests/minute — half the bridge's 60/minute device budget,
 * which answers and stream opens also draw on (docs/bridge-approvals.md:127-129). Answers never
 * take the slot.
 */
private const val PC_GET_GAP_MS = 2_100L

/** A 429 answer gets this much longer before the refetch is re-queued (the bridge window is 60 s). */
private const val RATE_LIMIT_WAIT_MS = 15_000L

/**
 * App-level mirror of every ready bridge bot's pending approvals (docs/bridge-approvals.md:40-107).
 * One SSE stream per (bridge, profile) while the app is resumed; the Bots follower supplies the
 * profile list, so direct connections never get a stream. [refresh] wakes a backing-off or stopped
 * follower immediately (the approvals screen's Retry uses it).
 */
class ApprovalsRepository(
    private val store: BridgeStore,
    private val clients: BridgeClients,
    private val bots: StateFlow<BridgeBotsState>,
    private val api: (OkHttpClient, String) -> ApprovalsApi = ::BridgeApprovalsApi,
) {
    /** Per-bot feed. [unsupported] means this bridge has no approvals route; the UI hides the feature. */
    data class Feed(
        val items: List<ApprovalItemDto> = emptyList(),
        val loading: Boolean = true,
        val error: ApprovalsError? = null,
        val unsupported: Boolean = false,
        /** Profile-list failure currently starving this feed; the follower's error collector owns
         * it (sets it while the group fails, clears it on recovery or on any authoritative write). */
        val discoveryError: ApprovalsError? = null,
    )

    private val mutable = MutableStateFlow<Map<String, Feed>>(emptyMap())
    val state: StateFlow<Map<String, Feed>> = mutable
    private val refreshTicks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The UI follower (RESUMED) and the alerts service share one follower; the second caller waits. */
    private val followMutex = Mutex()

    /** Serializes background GETs per bridge so one PC cannot spend the whole request budget. */
    private val pcSlots = mutableMapOf<String, Mutex>()
    private val pcSlotsGuard = Mutex()

    fun feed(bridgeId: String, profileId: String): Flow<Feed?> =
        state.map { it[bridgeBotId(bridgeId, profileId)] }.distinctUntilChanged()

    /** The API for one bot's answers; null when the saved bridge is gone. */
    suspend fun api(bridgeId: String, profileId: String): ApprovalsApi? {
        val bridge = store.get(bridgeId) ?: return null
        return api(clients.client(bridge).client, profileId)
    }

    fun refresh(bridgeId: String, profileId: String) {
        val key = bridgeBotId(bridgeId, profileId)
        refreshTicks.update { it + (key to (it[key] ?: 0L) + 1L) }
    }

    private suspend fun pcSlot(bridgeId: String): Mutex =
        pcSlotsGuard.withLock { pcSlots.getOrPut(bridgeId) { Mutex() } }

    /**
     * Follows the bot set while resumed; cancellation closes every approval stream. Follower jobs
     * live in [jobs] keyed by bot: an emission only launches the added keys and cancels the removed
     * ones, so a discovery error or a flap on one PC never restarts another PC's stream or in-flight
     * GET. The discovery-error overlay is a separate collector that only annotates feeds.
     */
    suspend fun follow(): Unit = followMutex.withLock { followOnce() }

    private suspend fun followOnce(): Unit = try {
        coroutineScope {
            val jobs = mutableMapOf<String, Job>()
            launch {
                bots.map { s -> s.groups.associate { it.id to it.error } }
                    .distinctUntilChanged()
                    .collect { errors ->
                        // Annotate each feed with the discovery failure starving it; a null group
                        // error clears the annotation again, and a snapshot or successful GET does
                        // too. Keys are `bridge:<bridgeId>:<profileId>`; neither id can contain a
                        // colon. The feed's own error wins the screen slot (error ?: discoveryError).
                        mutable.update { m ->
                            m.mapValues { (key, feed) ->
                                val discovery = errors[key.substringAfter(':').substringBeforeLast(':')]
                                feed.copy(discoveryError = discovery?.approvalsError())
                            }
                        }
                    }
            }
            bots.map { botsState ->
                val wanted = mutableSetOf<Pair<String, String>>()
                botsState.groups.forEach { group ->
                    // A failed profile listing keeps the last snapshot's followers and feeds; only a
                    // successful listing or losing the saved bridge removes them.
                    if (!group.loading || group.profiles.isNotEmpty()) {
                        group.profiles.filter { it.ready }.forEach { wanted += group.id to it.id }
                    }
                }
                wanted
            }.distinctUntilChanged().collect { wanted ->
                val keys = wanted.mapTo(mutableSetOf()) { (bridge, profile) -> bridgeBotId(bridge, profile) }
                (jobs.keys - keys).forEach { jobs.remove(it)?.cancel() }
                mutable.update { m -> m.filterKeys { it in keys } }
                wanted.forEach { (bridge, profile) ->
                    val key = bridgeBotId(bridge, profile)
                    if (jobs[key]?.isActive != true) jobs[key] = launch { followOne(bridge, profile) }
                }
            }
        }
    } catch (e: CancellationException) { throw e }

    private fun setFeed(key: String, update: Feed.() -> Feed) {
        mutable.update { m -> m + (key to (m[key] ?: Feed()).update()) }
    }

    private suspend fun followOne(bridgeId: String, profileId: String) {
        val key = bridgeBotId(bridgeId, profileId)
        val ticks = refreshTicks.map { it[key] ?: 0L }.distinctUntilChanged()
        setFeed(key) { this }
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val seen = refreshTicks.value[key] ?: 0L
            val bridge = store.get(bridgeId) ?: return
            val api = try {
                api(clients.client(bridge).client, profileId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                setFeed(key) { copy(loading = false, error = approvalsTransportError(e)) }
                attempt = (attempt + 1).coerceAtMost(4)
                withTimeoutOrNull(reconnectBackoffMs(attempt)) { ticks.first { it != seen } }
                continue
            }
            var failure: ApprovalsError = ApprovalsError.Unreachable
            try {
                coroutineScope {
                    // One serial writer per feed: SSE snapshots apply their own items, id-only
                    // events, the screen's Retry and resume mark a conflated refetch, and a failed
                    // GET re-queues itself after a backoff so a missed change cannot stay hidden.
                    // A running GET is never cancelled and the drain order keeps the last write
                    // the newest (docs/bridge-approvals.md:88-107).
                    val triggers = Channel<FeedTrigger>(Channel.UNLIMITED)
                    val writer = launch {
                        var failedGets = 0
                        while (currentCoroutineContext().isActive) {
                            var snapshot = triggers.receive() as? FeedTrigger.ApplySnapshot
                            var refetch = snapshot == null
                            delay(EVENT_REFETCH_MS)
                            while (true) {
                                when (val next = triggers.tryReceive().getOrNull()) {
                                    null -> break
                                    is FeedTrigger.ApplySnapshot -> snapshot = next
                                    FeedTrigger.Refetch -> refetch = true
                                }
                            }
                            snapshot?.let { s ->
                                setFeed(key) {
                                    copy(items = s.items, loading = false, error = null, unsupported = false, discoveryError = null)
                                }
                            }
                            if (refetch) {
                                val getError = pcSlot(bridgeId).withLock {
                                    val result = refreshNow(api, key)
                                    delay(PC_GET_GAP_MS)
                                    result
                                }
                                if (getError == null) {
                                    failedGets = 0
                                } else {
                                    failedGets++
                                    val wait = if (getError == ApprovalsError.RateLimited)
                                        RATE_LIMIT_WAIT_MS else reconnectBackoffMs(failedGets.coerceAtMost(4))
                                    launch { delay(wait); triggers.trySend(FeedTrigger.Refetch) }
                                }
                                delay(FEED_GET_GAP_MS)
                            }
                        }
                    }
                    // The screen's Retry always runs a fresh GET, even while the stream is connected.
                    val refresher = launch { ticks.collect { if (it != seen) triggers.trySend(FeedTrigger.Refetch) } }
                    try {
                        api.events().collect { event ->
                            attempt = 0
                            // The connect snapshot already carries the full list; no GET follows it.
                            triggers.trySend(
                                if (event is ApprovalsEvent.Snapshot) FeedTrigger.ApplySnapshot(event.items)
                                else FeedTrigger.Refetch
                            )
                        }
                    } finally {
                        writer.cancel()
                        refresher.cancel()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ApprovalsException) { failure = e.error }
            catch (e: Exception) { failure = approvalsTransportError(e) }
            setFeed(key) {
                copy(loading = false, error = failure.feedError(), unsupported = unsupported || failure == ApprovalsError.Unsupported)
            }
            attempt = (attempt + 1).coerceAtMost(4)
            when (failure) {
                // Cannot heal by itself: the feature is off, or this device must pair again.
                ApprovalsError.Unsupported, ApprovalsError.DeviceRevoked,
                ApprovalsError.DeviceKeyRepair, ApprovalsError.Unauthorized ->
                    ticks.first { it != seen }
                else -> withTimeoutOrNull(reconnectBackoffMs(attempt)) { ticks.first { it != seen } }
            }
        }
    }

    /** Runs one authoritative GET; the result is the new snapshot for this feed. Returns the error. */
    private suspend fun refreshNow(api: ApprovalsApi, key: String): ApprovalsError? {
        try {
            val items = api.list()
            setFeed(key) { copy(items = items, loading = false, error = null, unsupported = false, discoveryError = null) }
            return null
        } catch (e: CancellationException) { throw e }
        catch (e: ApprovalsException) {
            setFeed(key) { copy(loading = false, error = e.error.feedError(), unsupported = unsupported || e.error == ApprovalsError.Unsupported) }
            return e.error
        }
        catch (e: Exception) {
            val error = approvalsTransportError(e)
            setFeed(key) { copy(loading = false, error = error) }
            return error
        }
    }

    /** Feed-level errors describe the bot, not one request: a list/stream 404 means it is gone. */
    private fun ApprovalsError.feedError(): ApprovalsError =
        if (this == ApprovalsError.NotFound) ApprovalsError.BotUnavailable else this

    /** Triggers accepted by a feed's serial writer. */
    private sealed interface FeedTrigger {
        /** A stream snapshot replaces the pending list without a GET. */
        data class ApplySnapshot(val items: List<ApprovalItemDto>) : FeedTrigger

        /** Id-only change (`new`/`resolved`), screen Retry or GET-retry: needs a list GET. */
        data object Refetch : FeedTrigger
    }
}
