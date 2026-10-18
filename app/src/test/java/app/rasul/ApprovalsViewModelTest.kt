package app.rasul

import androidx.lifecycle.ViewModelStore
import app.rasul.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelTest {
    private val id = "a".repeat(32)
    private fun item(allowSession: Boolean = true, allowPermanent: Boolean = true, requestId: String = id) =
        ApprovalItemDto(requestId, "sk", "default", "shell", "cmd", "desc", allowSession, allowPermanent, 4_000_000_000.0)

    private class FakeApprovals : ApprovalsApi {
        val answers = mutableListOf<Pair<String, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: ApprovalsError? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun list() = emptyList<ApprovalItemDto>()
        override fun events(): Flow<ApprovalsEvent> = emptyFlow()
        override suspend fun answer(requestId: String, choice: String): ApprovalAnsweredDto {
            answers += requestId to choice
            started.complete(Unit)
            gate?.await()
            failure?.let { throw ApprovalsException(it) }
            return ApprovalAnsweredDto(requestId, "answered")
        }
    }

    private fun group(
        bridgeId: String = "bridge-1",
        profiles: List<BridgeProfile> = listOf(BridgeProfile("default", "default", "Research", true, true)),
        loading: Boolean = false,
        error: app.rasul.data.HermesError? = null,
    ) = BridgeGroup(bridgeId, "Bridge", profiles, loading, error)

    private suspend fun TestScope.exercise(
        feed: MutableStateFlow<ApprovalsRepository.Feed?> = MutableStateFlow(null),
        direct: Boolean = false,
        bots: MutableStateFlow<BridgeBotsState> = MutableStateFlow(BridgeBotsState()),
        onRefresh: () -> Unit = {},
        onRetryDiscovery: () -> Unit = {},
        block: suspend TestScope.(FakeApprovals?, ApprovalsViewModel, MutableStateFlow<BridgeBotsState>) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val api = if (direct) null else FakeApprovals()
        val vm = ApprovalsViewModel(
            feed, if (direct) null else { { api!! } }, onRefresh,
            bots = bots, bridgeId = "bridge-1", profileId = "default", retryDiscovery = onRetryDiscovery)
        store.put("approvals", vm)
        try { runCurrent(); block(api, vm, bots) }
        finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun feedItemsBecomePendingCards() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(null)
        exercise(feed) { api, vm, _ ->
            feed.value = ApprovalsRepository.Feed(listOf(item()), loading = false)
            runCurrent()
            assertEquals(listOf(id), vm.state.value.cards.map { it.item.requestId })
            assertFalse(vm.state.value.cards.single().sending)
            assertFalse(vm.state.value.loading)
        }
    }

    @Test fun choicesNotAllowedByFlagsNeverReachTheBridge() = runTest {
        val second = "b".repeat(32)
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(
            listOf(item(allowSession = false, allowPermanent = false), item(requestId = second)), loading = false))
        exercise(feed) { api, vm, _ ->
            vm.answer(id, "session"); vm.answer(id, "always")
            runCurrent()
            assertTrue(api!!.answers.isEmpty())
            vm.answer(id, "once"); vm.answer(second, "deny")
            runCurrent()
            assertEquals(listOf(id to "once", second to "deny"), api.answers)
        }
    }

    @Test fun sendingDisablesAndTheNextSnapshotWithoutItRemovesTheCard() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed) { api, vm, _ ->
            api!!.gate = CompletableDeferred()
            vm.answer(id, "once")
            api.started.await()
            assertTrue(vm.state.value.cards.single().sending)
            // A second tap while the answer is in flight does nothing.
            vm.answer(id, "deny")
            assertEquals(1, api.answers.size)
            api.gate!!.complete(Unit)
            runCurrent()
            // The recorded answer keeps the card disabled; only a snapshot without the id drops it.
            assertTrue(vm.state.value.cards.single().sending)
            assertEquals(listOf(id), vm.state.value.cards.map { it.item.requestId })
            feed.value = ApprovalsRepository.Feed(emptyList(), loading = false)
            runCurrent()
            assertTrue(vm.state.value.cards.isEmpty())
        }
    }

    @Test fun answerSuccessRefreshesTheList() = runTest {
        var refreshes = 0
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed, onRefresh = { refreshes++ }) { api, vm, _ ->
            vm.answer(id, "once")
            runCurrent()
            assertEquals(1, api!!.answers.size)
            assertEquals(1, refreshes)
        }
    }

    @Test fun recreatedLoadingFeedKeepsPendingCardsUntilTheNextSnapshot() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed) { api, vm, _ ->
            // On resume the follower recreates the feed empty and loading: not a snapshot.
            feed.value = ApprovalsRepository.Feed(loading = true)
            runCurrent()
            val card = vm.state.value.cards.single()
            assertEquals(id, card.item.requestId)
            assertFalse(card.sending)
            assertNull(card.error)
            assertFalse(vm.state.value.loading)
            // The fresh GET then confirms the still-pending item.
            feed.value = ApprovalsRepository.Feed(listOf(item()), loading = false)
            runCurrent()
            assertEquals(listOf(id), vm.state.value.cards.map { it.item.requestId })
            vm.answer(id, "once")
            runCurrent()
            assertEquals(listOf(id to "once"), api!!.answers)
        }
    }

    @Test fun snapshotWithoutTheIdWhileSendingStaysGone() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed) { api, vm, _ ->
            api!!.gate = CompletableDeferred()
            vm.answer(id, "once")
            api.started.await()
            // The bridge resolved the request before the POST response arrived.
            feed.value = ApprovalsRepository.Feed(emptyList(), loading = false)
            runCurrent()
            assertTrue(vm.state.value.cards.isEmpty())
            api.gate!!.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.cards.isEmpty())
        }
    }

    @Test fun staleGetSnapshotCannotReenableAnAnsweredCard() = runTest {
        val second = "b".repeat(32)
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed) { api, vm, _ ->
            vm.answer(id, "once")
            runCurrent()
            assertEquals(1, api!!.answers.size)
            // A GET that started before the answer still lists the request next to a new one —
            // a different value, so the flow really emits and the VM re-processes the stale list.
            feed.value = ApprovalsRepository.Feed(listOf(item(), item(requestId = second)), loading = false)
            runCurrent()
            val card = vm.state.value.cards.first { it.item.requestId == id }
            assertTrue(card.sending)
            assertFalse(vm.state.value.cards.first { it.item.requestId == second }.sending)
            // Tapping the answered card again posts nothing.
            vm.answer(id, "deny")
            runCurrent()
            assertEquals(listOf(id to "once"), api.answers)
        }
    }

    @Test fun terminalErrorLeavesTheCardDisabledWithItsMessageAndRefreshes() = runTest {
        var refreshes = 0
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed, onRefresh = { refreshes++ }) { api, vm, _ ->
            api!!.failure = ApprovalsError.Expired
            vm.answer(id, "once")
            runCurrent()
            val card = vm.state.value.cards.single()
            assertTrue(card.sending)
            assertEquals(ApprovalsError.Expired, card.error)
            assertEquals(1, refreshes)
            // A snapshot that still lists the closed request does not re-enable it either.
            feed.value = ApprovalsRepository.Feed(listOf(item()), loading = false)
            runCurrent()
            assertTrue(vm.state.value.cards.single().sending)
        }
    }

    @Test fun transientErrorReturnsTheCardToEnabledWithTheSpecificMessage() = runTest {
        var refreshes = 0
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(listOf(item()), loading = false))
        exercise(feed, onRefresh = { refreshes++ }) { api, vm, _ ->
            api!!.failure = ApprovalsError.RateLimited
            vm.answer(id, "once")
            runCurrent()
            val card = vm.state.value.cards.single()
            assertFalse(card.sending)
            assertEquals(ApprovalsError.RateLimited, card.error)
            assertEquals(0, refreshes)
        }
    }

    @Test fun feedFailuresSurfaceOnTheScreenState() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(ApprovalsRepository.Feed(error = ApprovalsError.Unreachable, loading = false))
        exercise(feed) { _, vm, _ ->
            assertEquals(ApprovalsError.Unreachable, vm.state.value.feedError)
            assertFalse(vm.state.value.loading)
        }
    }

    @Test fun missingProfileAfterRestoreReportsTheBotGone() = runTest {
        val bots = MutableStateFlow(BridgeBotsState(listOf(group(profiles = emptyList())), loaded = true))
        exercise(bots = bots) { _, vm, _ ->
            runCurrent()
            assertEquals(ApprovalsError.BotUnavailable, vm.state.value.feedError)
            assertFalse(vm.state.value.loading)
        }
    }

    @Test fun notReadyProfileAfterRestoreReportsTheBotGone() = runTest {
        val bots = MutableStateFlow(BridgeBotsState(listOf(
            group(profiles = listOf(BridgeProfile("default", "default", "Research", false, true)))), loaded = true))
        exercise(bots = bots) { _, vm, _ ->
            runCurrent()
            assertEquals(ApprovalsError.BotUnavailable, vm.state.value.feedError)
        }
    }

    @Test fun missingBridgeAfterRestoreReportsTheBridgeRemoved() = runTest {
        val bots = MutableStateFlow(BridgeBotsState(listOf(group("other-bridge")), loaded = true))
        exercise(bots = bots) { _, vm, _ ->
            runCurrent()
            assertEquals(ApprovalsError.BridgeRemoved, vm.state.value.feedError)
        }
    }

    @Test fun discoveryFailureAfterRestoreShowsErrorAndRetryRestarts() = runTest {
        var discoveries = 0
        var refreshes = 0
        val bots = MutableStateFlow(BridgeBotsState(listOf(
            group(profiles = emptyList(), error = app.rasul.data.HermesError.BridgeUnreachable)), loaded = true))
        exercise(bots = bots, onRefresh = { refreshes++ }, onRetryDiscovery = { discoveries++ }) { _, vm, _ ->
            runCurrent()
            assertEquals(ApprovalsError.Unreachable, vm.state.value.feedError)
            assertFalse(vm.state.value.loading)
            vm.reload()
            assertEquals(1, discoveries)
            assertEquals(1, refreshes)
        }
    }

    @Test fun loadingDiscoveryKeepsTheSpinner() = runTest {
        val bots = MutableStateFlow(BridgeBotsState(listOf(group(loading = true)), loaded = true))
        exercise(bots = bots) { _, vm, _ ->
            runCurrent()
            assertTrue(vm.state.value.loading)
            assertNull(vm.state.value.feedError)
        }
    }

    @Test fun directTargetExposesNothingAndNeverAnswers() = runTest {
        val feed = MutableStateFlow<ApprovalsRepository.Feed?>(null)
        exercise(feed, direct = true) { api, vm, _ ->
            assertTrue(vm.state.value.direct)
            vm.answer(id, "once"); vm.reload(); runCurrent()
            assertNull(api)
            assertTrue(vm.state.value.cards.isEmpty())
        }
    }
}
