package app.rasul

import androidx.lifecycle.ViewModelStore
import app.rasul.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstructionsViewModelTest {
    private class Fake : InstructionsApi {
        var value = InstructionsDocument("Original", instructionsRevision("Original"))
        var failure: InstructionsError? = null
        var blocker: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val requests = mutableListOf<InstructionsDocument>()
        var loads = 0
        override suspend fun load(): InstructionsDocument { loads++; failure?.let { throw InstructionsException(it) }; return value }
        override suspend fun save(document: InstructionsDocument): String {
            requests += document
            started.complete(Unit)
            blocker?.await()
            failure?.let { throw InstructionsException(it) }
            value = InstructionsDocument(document.text, instructionsRevision(document.text))
            return value.revision
        }
    }
    private suspend fun TestScope.exercise(block: suspend TestScope.(Fake, InstructionsViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val api = Fake()
        val vm = InstructionsViewModel { api }
        store.put("instructions", vm)
        try { runCurrent(); block(api, vm) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun loadsExistingInstructionsAndSavesWithTheLoadedRevision() = runTest { exercise { api, vm ->
        assertEquals("Original", vm.state.value.text)
        assertFalse(vm.state.value.dirty)
        vm.edit("Edited\nأجب بوضوح.")
        assertTrue(vm.state.value.canSave)
        vm.save(); runCurrent()
        assertEquals(listOf(InstructionsDocument("Edited\nأجب بوضوح.", instructionsRevision("Original"))), api.requests)
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.saved)
    } }
    @Test fun saveCapturesTextAndRevisionAndBlocksChangesWhileSuspended() = runTest { exercise { api, vm ->
        api.blocker = CompletableDeferred()
        vm.edit("Captured")
        vm.save()
        api.started.await()
        vm.edit("Late edit"); vm.reload(); vm.save()
        assertEquals("Captured", vm.state.value.text)
        assertEquals(1, api.loads)
        assertEquals(1, api.requests.size)
        api.blocker!!.complete(Unit); runCurrent()
        assertEquals("Captured", api.value.text)
        assertFalse(vm.state.value.dirty)
    } }
    @Test fun conflictKeepsDraftAndRequiresReloadBeforeSavingAgain() = runTest { exercise { api, vm ->
        vm.edit("Draft")
        api.failure = InstructionsError.Conflict
        vm.save(); runCurrent()
        assertEquals(InstructionsError.Conflict, vm.state.value.error)
        assertEquals("Draft", vm.state.value.text)
        assertTrue(vm.state.value.dirty)
        assertFalse(vm.state.value.canSave)
        api.failure = null
        api.value = InstructionsDocument("Changed on PC", instructionsRevision("Changed on PC"))
        vm.reload(); runCurrent()
        assertEquals(api.value.text, vm.state.value.text)
        assertFalse(vm.state.value.dirty)
    } }
    @Test fun failedLoadCanRetryWithoutEnablingAnEmptyEditor() = runTest { exercise { api, vm ->
        api.failure = InstructionsError.Unavailable
        vm.reload(); runCurrent()
        assertEquals(InstructionsError.Unavailable, vm.state.value.error)
        api.failure = null
        vm.reload(); runCurrent()
        assertNull(vm.state.value.error)
    } }
    @Test fun directBotNeverRequestsBridgeInstructions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val vm = InstructionsViewModel(null)
        store.put("instructions", vm)
        try {
            vm.reload(); vm.edit("ignored"); vm.save(); runCurrent()
            assertTrue(vm.state.value.direct)
            assertFalse(vm.state.value.loaded)
            assertFalse(vm.state.value.canSave)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun byteLimitCountsArabicAndKeepsTheDraftEditable() = runTest { exercise { _, vm ->
        vm.edit("أ".repeat(10001)); vm.save(); runCurrent()
        assertEquals(InstructionsError.TooLarge, vm.state.value.error)
        vm.edit("Shorter")
        assertTrue(vm.state.value.canSave)
    } }
}
