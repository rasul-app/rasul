package app.rasul

import androidx.lifecycle.ViewModelStore
import app.rasul.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CreateBotViewModelTest {
    private class Fake : CreateBotApi {
        val requests = mutableListOf<CreateBotRequest>()
        val polled = mutableListOf<String>()
        val operationId = UUID.randomUUID().toString().replace("-", "")
        var createFailure = false
        var createError: CreateBotError? = null
        var templateValues = listOf(ProfileTemplate("blank", "Blank"), ProfileTemplate("approved", "Approved"))
        var pollFailure = false
        var blocker: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val responses = ArrayDeque<CreateBotOperation>()
        override suspend fun templates() = templateValues
        override suspend fun create(request: CreateBotRequest): String {
            requests += request
            started.complete(Unit)
            blocker?.await()
            createError?.let { throw CreateBotException(it) }
            if (createFailure) throw CreateBotException(CreateBotError.Unreachable)
            return operationId
        }
        override suspend fun operation(id: String): CreateBotOperation {
            polled += id
            if (pollFailure) throw CreateBotException(CreateBotError.Unreachable)
            return if (responses.isEmpty()) CreateBotOperation("done", "new-profile") else responses.removeFirst()
        }
    }
    private val confirm: (() -> Unit, () -> Unit) -> Unit = { success, _ -> success() }
    private suspend fun TestScope.exercise(block: suspend TestScope.(Fake, CreateBotViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val api = Fake()
        val vm = CreateBotViewModel({ api })
        store.put("create", vm)
        try { runCurrent(); vm.name("new-profile"); block(api, vm) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun recreatedCredentialPromptCancelsWithoutSendingAndIgnoresLateSuccess() = runTest { exercise { api, vm ->
        val retained = CredentialConfirmation()
        vm.submit(retained::begin)
        assertEquals(CreateBotPhase.Confirming, vm.state.value.phase)
        retained.cancel() // Activity destruction; replacement has no live credential prompt.
        retained.complete(true)
        runCurrent()
        assertTrue(vm.state.value.editable)
        assertFalse(vm.state.value.busy)
        assertTrue(api.requests.isEmpty())
        assertTrue(api.polled.isEmpty())
    } }

    @Test fun timeoutLeavesCreationWithBackEnabledAndSpecificError() = runTest { exercise { api, vm ->
        api.createError = CreateBotError.Timeout
        vm.submit(confirm); runCurrent()
        assertEquals(CreateBotPhase.Failed, vm.state.value.phase)
        assertFalse(vm.state.value.busy)
        assertEquals(CreateBotError.Timeout, vm.state.value.error)
    } }

    @Test fun retriesReuseTheRequestAndFreshAttemptsGenerateAnotherUuid() = runTest { exercise { api, vm ->
        api.createFailure = true
        vm.submit(confirm); runCurrent()
        vm.submit(confirm); runCurrent()
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0], api.requests[1])
        assertEquals(api.requests[0].requestId, UUID.fromString(api.requests[0].requestId).toString())
        vm.editDetails(); vm.name("other-profile")
        vm.submit(confirm); runCurrent()
        assertNotEquals(api.requests[0].requestId, api.requests[2].requestId)
        assertEquals("other-profile", api.requests[2].name)
    } }
    @Test fun cancellationSendsNothingAndASecondTapCannotBypassConfirmation() = runTest { exercise { api, vm ->
        var cancelled: (() -> Unit)? = null
        var prompts = 0
        vm.submit { _, cancel -> prompts++; cancelled = cancel }
        vm.submit(confirm)
        vm.name("other-profile"); vm.template("approved")
        assertEquals(CreateBotPhase.Confirming, vm.state.value.phase)
        cancelled!!(); runCurrent()
        assertEquals(1, prompts)
        assertTrue(api.requests.isEmpty())
        assertTrue(api.polled.isEmpty())
        assertEquals("new-profile", vm.state.value.name)
        assertEquals("blank", vm.state.value.template)
    } }
    @Test fun suspendedCreateCapturesChoicesAndBlocksAllEditsAndDuplicateSends() = runTest { exercise { api, vm ->
        api.blocker = CompletableDeferred()
        vm.submit(confirm)
        api.started.await()
        vm.name("other-profile"); vm.template("approved"); vm.editDetails(); vm.submit(confirm)
        assertEquals("new-profile", vm.state.value.name)
        assertEquals("blank", vm.state.value.template)
        assertEquals(1, api.requests.size)
        assertEquals("new-profile", api.requests.single().name)
        assertEquals("blank", api.requests.single().template)
        api.blocker!!.complete(Unit); runCurrent()
        assertEquals(CreateBotPhase.WaitingForBot, vm.state.value.phase)
        vm.botVisible(listOf(BridgeProfile("new-profile", "new-profile", "new-profile", false, false)))
        assertEquals(CreateBotPhase.WaitingForBot, vm.state.value.phase)
        vm.botVisible(listOf(BridgeProfile("other-profile", "other-profile", "other-profile", true, false)))
        assertEquals(CreateBotPhase.WaitingForBot, vm.state.value.phase)
        vm.botVisible(listOf(BridgeProfile("new-profile", "new-profile", "new-profile", true, false)))
        assertEquals(CreateBotPhase.Complete, vm.state.value.phase)
    } }
    @Test fun pollingUsesBackoffUntilDoneAndRetriesOnlyTheKnownOperation() = runTest { exercise { api, vm ->
        api.responses += CreateBotOperation("running")
        api.responses += CreateBotOperation("running")
        vm.submit(confirm); runCurrent()
        assertEquals(1, api.polled.size)
        advanceTimeBy(499); runCurrent(); assertEquals(1, api.polled.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, api.polled.size)
        advanceTimeBy(1000); runCurrent(); assertEquals(3, api.polled.size)
        assertEquals(CreateBotPhase.WaitingForBot, vm.state.value.phase)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 8000L), (0..5).map(::createBotPollDelay))
    } }
    @Test fun pollTransportFailureKeepsTheOperationAndRequiresFreshConfirmation() = runTest { exercise { api, vm ->
        api.pollFailure = true
        vm.submit(confirm); runCurrent()
        assertEquals(CreateBotError.Unreachable, vm.state.value.error)
        api.pollFailure = false
        var success: (() -> Unit)? = null
        vm.submit { yes, _ -> success = yes }; runCurrent()
        assertEquals(1, api.polled.size)
        success!!(); runCurrent()
        assertEquals(1, api.requests.size)
        assertEquals(listOf(api.operationId, api.operationId), api.polled)
    } }
    @Test fun pollingStopsAtEachFailureBodyWithSpecificTextMarker() = runTest { exercise { api, vm ->
        for ((body, expected) in listOf("Profile creation failed; inspect locally" to CreateBotError.CreationFailed,
            "Outcome uncertain; inspect profiles locally" to CreateBotError.OutcomeUncertain)) {
            api.responses += CreateBotOperation("failed", error = body)
            vm.submit(confirm); runCurrent()
            assertEquals(CreateBotPhase.Failed, vm.state.value.phase)
            assertEquals(expected, vm.state.value.error)
            vm.editDetails()
        }
        assertEquals(2, api.polled.size)
    } }
    @Test fun refreshingAnUnknownTemplateExplicitlyStartsANewAttempt() = runTest { exercise { api, vm ->
        api.createError = CreateBotError.TemplateUnknown
        vm.submit(confirm); runCurrent()
        val oldId = api.requests.single().requestId
        api.templateValues = listOf(ProfileTemplate("approved", "Approved"))
        vm.refreshTemplates(); runCurrent()
        assertFalse(vm.state.value.hasAttempt)
        assertEquals("approved", vm.state.value.template)
        vm.submit(confirm); runCurrent()
        assertNotEquals(oldId, api.requests.last().requestId)
        assertEquals("approved", api.requests.last().template)
    } }
    @Test fun invalidNamesNeverRequestConfirmationOrSend() = runTest { exercise { api, vm ->
        vm.name("default")
        vm.submit { _, _ -> fail("unexpected confirmation") }; runCurrent()
        assertTrue(api.requests.isEmpty())
    } }
    @Test fun staleConfirmationFromACancelledPromptCannotAuthorizeARetry() = runTest { exercise { api, vm ->
        var oldSuccess: (() -> Unit)? = null
        vm.submit { success, cancel -> oldSuccess = success; cancel() }
        vm.submit { _, _ -> }
        oldSuccess!!(); runCurrent()
        assertTrue(api.requests.isEmpty())
    } }
}
