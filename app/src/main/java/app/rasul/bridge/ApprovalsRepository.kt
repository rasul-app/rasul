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
 * While the app is resumed each bridge holds ONE SSE stream for all its bots ([bridgeApi]); items
 * and events are split by profile into the per-bot feeds. A bridge that answers 404 on the
 * bridge-wide routes is older: it falls back to one stream per (bridge, profile) and stays there
 * until its client changes or the app restarts. The Bots follower supplies the profile list, so
 * direct connections never get a stream. [refresh] wakes a backing-off or stopped follower
 * immediately (the approvals screen's Retry uses it). A null [bridgeApi] means per-profile only.
 */
class ApprovalsRepository(
    private val store: BridgeStore,
    private val clients: BridgeClients,
    private val bots: StateFlow<BridgeBotsState>,
    private val bridgeApi: ((OkHttpClient) -> ApprovalsFeedApi)? = null,
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

    /** Bridges whose client answered 404 on the bridge-wide routes (an older bridge), keyed to that client. */
    private val perProfileBridges = java.util.concurrent.ConcurrentHashMap<String, BridgeTransport>()

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
            val profilesByBridge = mutableMapOf<String, MutableStateFlow<Set<String>>>()
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
                val wanted = mutableMapOf<String, Set<String>>()
                botsState.groups.forEach { group ->
                    // A failed profile listing keeps the last snapshot's followers and feeds; only a
                    // successful listing or losing the saved bridge removes them.
                    if (!group.loading || group.profiles.isNotEmpty()) {
                        val ready = group.profiles.filter { it.ready }.mapTo(mutableSetOf()) { it.id }
                        if (ready.isNotEmpty()) wanted[group.id] = ready
                    }
                }
                wanted.toMap()
            }.distinctUntilChanged().collect { wanted ->
                val keys = wanted.flatMapTo(mutableSetOf()) { (bridge, profiles) -> profiles.map { bridgeBotId(bridge, it) } }
                (jobs.keys - wanted.keys).forEach { jobs.remove(it)?.cancel(); profilesByBridge.remove(it); perProfileBridges.remove(it) }
                // One follower per bridge: a flap or discovery error on one PC never restarts another PC's stream.
                wanted.forEach { (bridge, profiles) ->
                    val flow = profilesByBridge.getOrPut(bridge) { MutableStateFlow(profiles) }
                    flow.value = profiles
                    if (jobs[bridge]?.isActive != true) jobs[bridge] = launch { followBridge(bridge, flow) }
                }
                // After the wanted sets are published: a bridge-wide write checks them inside its own update.
                mutable.update { m -> m.filterKeys { it in keys } }
            }
        }
    } catch (e: CancellationException) { throw e }

    private fun setFeed(key: String, update: Feed.() -> Feed) {
        mutable.update { m -> m + (key to (m[key] ?: Feed()).update()) }
    }

    /** The bridge has no such route (older version): retrying the same route cannot heal it. */
    private fun ApprovalsError.noBridgeRoute() = this == ApprovalsError.Unsupported || this == ApprovalsError.NotFound

    private fun bridgeTickSum(m: Map<String, Long>, bridgeId: String) =
        m.filterKeys { it.startsWith("bridge:$bridgeId:") }.values.sum()

    /**
     * Updates the feeds of [targets] that are still in [wanted] at write time, in one atomic step: a
     * bot removed while a request was in flight never gets its feed back.
     */
    private fun setBridgeFeeds(
        bridgeId: String, wanted: StateFlow<Set<String>>, targets: Set<String> = wanted.value, update: Feed.(String) -> Feed,
    ) {
        mutable.update { m ->
            m + wanted.value.intersect(targets).associate { profile ->
                val key = bridgeBotId(bridgeId, profile)
                key to (m[key] ?: Feed()).update(profile)
            }
        }
    }

    /** Splits one bridge-wide list into the per-bot feeds named in [targets]; other profiles are ignored. */
    private fun applyBridgeItems(bridgeId: String, items: List<ApprovalItemDto>, wanted: StateFlow<Set<String>>, targets: Set<String>) {
        val byProfile = items.groupBy { it.profile }
        setBridgeFeeds(bridgeId, wanted, targets) { profile ->
            copy(items = byProfile[profile].orEmpty(), loading = false, error = null, unsupported = false, discoveryError = null)
        }
    }

    private suspend fun followBridge(bridgeId: String, profiles: StateFlow<Set<String>>) {
        val ticks = refreshTicks.map { bridgeTickSum(it, bridgeId) }.distinctUntilChanged()
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val seen = bridgeTickSum(refreshTicks.value, bridgeId)
            val bridge = store.get(bridgeId) ?: return
            val transport = try {
                clients.client(bridge)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                setBridgeFeeds(bridgeId, profiles) { copy(loading = false, error = approvalsTransportError(e)) }
                attempt = (attempt + 1).coerceAtMost(4)
                withTimeoutOrNull(reconnectBackoffMs(attempt)) { ticks.first { it != seen } }
                continue
            }
            val feedApi = bridgeApi?.takeIf { perProfileBridges[bridgeId] !== transport }?.invoke(transport.client)
            if (feedApi == null) {
                followPerProfile(bridgeId, profiles)
                return
            }
            var failure: ApprovalsError = ApprovalsError.Unreachable
            try {
                coroutineScope {
                    // One serial writer per bridge, same rules as a per-bot feed: SSE snapshots apply
                    // their own items, id-only events, the screen's Retry and resume mark a conflated
                    // refetch (limited to the profiles named, or all), and a failed GET re-queues itself
                    // after a backoff. A running GET is never cancelled.
                    val triggers = Channel<BridgeTrigger>(Channel.UNLIMITED)
                    val writer = launch {
                        var failedGets = 0
                        while (currentCoroutineContext().isActive) {
                            val first = triggers.receive()
                            var snapshot = first as? BridgeTrigger.ApplySnapshot
                            var refetchAll = first is BridgeTrigger.Refetch && first.profiles == null
                            val refetchSome = mutableSetOf<String>()
                            (first as? BridgeTrigger.Refetch)?.profiles?.let { refetchSome += it }
                            delay(EVENT_REFETCH_MS)
                            while (true) {
                                when (val next = triggers.tryReceive().getOrNull()) {
                                    null -> break
                                    is BridgeTrigger.ApplySnapshot -> snapshot = next
                                    is BridgeTrigger.Refetch ->
                                        if (next.profiles == null) refetchAll = true else refetchSome += next.profiles
                                }
                            }
                            snapshot?.let { applyBridgeItems(bridgeId, it.items, profiles, profiles.value) }
                            // Profiles that are not bots (any more) cost no request.
                            val only = if (refetchAll) null else refetchSome.intersect(profiles.value)
                            if (only == null || only.isNotEmpty()) {
                                val getError = pcSlot(bridgeId).withLock {
                                    val result = refreshBridge(feedApi, bridgeId, profiles, only)
                                    delay(PC_GET_GAP_MS)
                                    result
                                }
                                if (getError == null) {
                                    failedGets = 0
                                } else {
                                    if (getError.noBridgeRoute()) throw ApprovalsException(getError)
                                    failedGets++
                                    val wait = if (getError == ApprovalsError.RateLimited)
                                        RATE_LIMIT_WAIT_MS else reconnectBackoffMs(failedGets.coerceAtMost(4))
                                    launch { delay(wait); triggers.trySend(BridgeTrigger.Refetch(only)) }
                                }
                                delay(FEED_GET_GAP_MS)
                            }
                        }
                    }
                    // The screen's Retry always runs a fresh GET, even while the stream is connected.
                    val refresher = launch { ticks.collect { if (it != seen) triggers.trySend(BridgeTrigger.Refetch(null)) } }
                    // Bots added while the stream is up get a feed now and a GET of their own.
                    val watcher = launch {
                        var known: Set<String>? = null
                        profiles.collect { now ->
                            setBridgeFeeds(bridgeId, profiles, now) { this }
                            val added = now - (known ?: now)
                            if (added.isNotEmpty()) triggers.trySend(BridgeTrigger.Refetch(added))
                            known = now
                        }
                    }
                    try {
                        feedApi.events().collect { event ->
                            attempt = 0
                            triggers.trySend(
                                when (event) {
                                    is ApprovalsEvent.Snapshot -> BridgeTrigger.ApplySnapshot(event.items)
                                    is ApprovalsEvent.New -> BridgeTrigger.Refetch(event.profile?.let { setOf(it) })
                                    is ApprovalsEvent.Resolved -> BridgeTrigger.Refetch(event.profile?.let { setOf(it) })
                                }
                            )
                        }
                    } finally {
                        writer.cancel()
                        refresher.cancel()
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ApprovalsException) { failure = e.error }
            catch (e: Exception) { failure = approvalsTransportError(e) }
            if (failure.noBridgeRoute()) {
                // An older bridge: remember it for this client so the next pass goes straight to per-profile streams.
                perProfileBridges[bridgeId] = transport
                continue
            }
            setBridgeFeeds(bridgeId, profiles) { copy(loading = false, error = failure.feedError()) }
            attempt = (attempt + 1).coerceAtMost(4)
            when (failure) {
                ApprovalsError.DeviceRevoked, ApprovalsError.DeviceKeyRepair, ApprovalsError.Unauthorized ->
                    ticks.first { it != seen }
                else -> withTimeoutOrNull(reconnectBackoffMs(attempt)) { ticks.first { it != seen } }
            }
        }
    }

    /** One bridge-wide GET; only the [only] profiles (all when null) of [wanted] are updated. */
    private suspend fun refreshBridge(
        api: ApprovalsFeedApi, bridgeId: String, wanted: StateFlow<Set<String>>, only: Set<String>?,
    ): ApprovalsError? {
        val targets = only ?: wanted.value
        try {
            applyBridgeItems(bridgeId, api.list(), wanted, targets)
            return null
        } catch (e: CancellationException) { throw e }
        catch (e: ApprovalsException) {
            if (!e.error.noBridgeRoute()) setBridgeFeeds(bridgeId, wanted, targets) { copy(loading = false, error = e.error.feedError()) }
            return e.error
        }
        catch (e: Exception) {
            val error = approvalsTransportError(e)
            setBridgeFeeds(bridgeId, wanted, targets) { copy(loading = false, error = error) }
            return error
        }
    }

    /** Older bridge: one follower per bot, diffed against the bot set as it changes. */
    private suspend fun followPerProfile(bridgeId: String, profiles: StateFlow<Set<String>>): Unit = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        profiles.collect { wanted ->
            (jobs.keys - wanted).forEach { jobs.remove(it)?.cancel() }
            wanted.forEach { profile ->
                if (jobs[profile]?.isActive != true) jobs[profile] = launch { followOne(bridgeId, profile) }
            }
        }
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

    /** Triggers accepted by a bridge's serial writer. */
    private sealed interface BridgeTrigger {
        data class ApplySnapshot(val items: List<ApprovalItemDto>) : BridgeTrigger

        /** A list GET for [profiles], or for every bot of the bridge when null. */
        data class Refetch(val profiles: Set<String>?) : BridgeTrigger
    }

    /** Triggers accepted by a feed's serial writer. */
    private sealed interface FeedTrigger {
        /** A stream snapshot replaces the pending list without a GET. */
        data class ApplySnapshot(val items: List<ApprovalItemDto>) : FeedTrigger

        /** Id-only change (`new`/`resolved`), screen Retry or GET-retry: needs a list GET. */
        data object Refetch : FeedTrigger
    }
}
