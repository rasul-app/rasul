package app.rasul.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApprovalCardState(
    val item: ApprovalItemDto,
    /** Buttons off: an answer is in flight, or the request was already answered/closed. */
    val sending: Boolean = false,
    /** The contract error of the last failed answer attempt on this card. */
    val error: ApprovalsError? = null,
)

data class ApprovalsScreenState(
    /** True for direct (non-bridge) targets: the feature does not exist there. */
    val direct: Boolean = false,
    val loading: Boolean = true,
    val feedError: ApprovalsError? = null,
    val unsupported: Boolean = false,
    val cards: List<ApprovalCardState> = emptyList(),
)

/**
 * One bridge bot's approvals. The visible list is the feed's last authoritative snapshot; local
 * state is which request ids are answered (their cards stay disabled until a snapshot without them
 * arrives or they expire) and the last answer error per card. An empty loading feed is a recreated
 * stream, not a snapshot: the previous list stays until the next GET lands. When no feed exists,
 * [bots] decides: an unloaded group keeps the spinner, a missing/not-ready profile reports the bot
 * gone, and a discovery failure shows its error with a Retry that also restarts discovery.
 * [service] is null for direct targets, which keeps every button and request off.
 */
class ApprovalsViewModel(
    feed: Flow<ApprovalsRepository.Feed?>,
    private val service: (suspend () -> ApprovalsApi)?,
    private val refreshFeed: () -> Unit = {},
    bots: Flow<BridgeBotsState> = flowOf(BridgeBotsState()),
    private val bridgeId: String = "",
    private val profileId: String = "",
    private val retryDiscovery: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(ApprovalsScreenState(direct = service == null, loading = service != null))
    val state: StateFlow<ApprovalsScreenState> = mutable

    /** Requests this screen already answered (or learned are closed); they never re-enable. */
    private val answered = mutableSetOf<String>()

    init {
        if (service != null) viewModelScope.launch {
            combine(feed, bots) { f, b -> f to b }.collect { (value, botsState) ->
                if (value != null) applyFeed(value) else applyFeedless(botsState)
            }
        }
    }

    private fun applyFeed(feed: ApprovalsRepository.Feed) {
        mutable.update { s ->
            val items = if (feed.loading && feed.items.isEmpty()) s.cards.map { it.item } else feed.items
            answered.retainAll(items.mapTo(mutableSetOf()) { it.requestId })
            val cards = items.map { item ->
                val card = s.cards.firstOrNull { it.item.requestId == item.requestId }?.copy(item = item)
                    ?: ApprovalCardState(item)
                // A snapshot that still lists an answered request (a GET started before the answer
                // landed) must not bring its buttons back (docs/bridge-approvals.md:107).
                if (item.requestId in answered) card.copy(sending = true) else card
            }
            val feedError = feed.error ?: feed.discoveryError
            s.copy(
                loading = feed.loading && cards.isEmpty() && feedError == null,
                feedError = feedError,
                unsupported = feed.unsupported,
                cards = cards,
            )
        }
    }

    /** No feed: the repository only drops or never creates one once the bot is not wanted. */
    private fun applyFeedless(bots: BridgeBotsState) {
        if (!bots.loaded) return
        val group = bots.groups.firstOrNull { it.id == bridgeId }
        val error = when {
            group == null -> ApprovalsError.BridgeRemoved
            group.loading -> null
            group.error != null -> group.error.approvalsError()
            else -> {
                val profile = group.profiles.firstOrNull { it.id == profileId }
                if (profile == null || !profile.ready) ApprovalsError.BotUnavailable else null
            }
        } ?: return
        mutable.update { it.copy(loading = false, feedError = error, unsupported = false, cards = emptyList()) }
    }

    /** One answer in flight per card; the tapped choice is captured before any suspension. */
    fun answer(requestId: String, choice: String) {
        val loadService = service ?: return
        val card = mutable.value.cards.firstOrNull { it.item.requestId == requestId } ?: return
        if (card.sending) return
        val allowed = choice == "once" || choice == "deny" ||
            (choice == "session" && card.item.allowSession) ||
            (choice == "always" && card.item.allowSession && card.item.allowPermanent)
        if (!allowed) return
        mutable.update { st -> st.copy(cards = st.cards.map {
            if (it.item.requestId == requestId) it.copy(sending = true, error = null) else it
        }) }
        viewModelScope.launch {
            try {
                loadService().answer(requestId, choice)
                answered += requestId
                // The recorded answer resolves the request on the bridge; refetch so the next
                // snapshot drops the card instead of depending on SSE timing. The card stays
                // disabled meanwhile (docs/bridge-approvals.md:107).
                refreshFeed()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = (e as? ApprovalsException)?.error ?: approvalsTransportError(e)
                // Already answered, expired or gone are final on the bridge: leave the card
                // disabled and refetch so it disappears instead of accepting more taps.
                val terminal = error == ApprovalsError.Conflict || error == ApprovalsError.Expired ||
                    error == ApprovalsError.NotFound
                if (terminal) {
                    answered += requestId
                    refreshFeed()
                }
                mutable.update { st -> st.copy(cards = st.cards.map {
                    if (it.item.requestId == requestId) it.copy(sending = terminal, error = error) else it
                }) }
            }
        }
    }

    /** Retry wakes the repository follower, which refetches the list even while SSE is connected,
     * and restarts profile discovery when its stream is dead. */
    fun reload() {
        refreshFeed()
        retryDiscovery()
    }
}
