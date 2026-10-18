package app.rasul

import app.rasul.chat.*
import app.rasul.bridge.BridgeEndpoint
import app.rasul.data.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeChatCaptureTest {
    @Test fun suspendedSendKeepsOpenedBridgeTargetAndInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val views = ViewModelStore()
        try {
            val first = HermesTarget("", null, "", BridgeEndpoint(OkHttpClient(), "default"))
            var selected = first
            val entered = CompletableDeferred<Pair<HermesTarget, String>>()
            val reply = CompletableDeferred<HermesResult<StartedRun>>()
            var starts = 0
            val api = object : RunsApi {
                override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> {
                    starts++
                    entered.complete(target to (input as kotlinx.serialization.json.JsonPrimitive).content)
                    return reply.await()
                }
                override suspend fun status(target: HermesTarget, runId: String) = HermesResult.Ok(RunStatus("completed", null, null))
                override suspend fun stop(target: HermesTarget, runId: String) = HermesResult.Ok(Unit)
                override suspend fun steer(target: HermesTarget, runId: String, text: String) = HermesResult.Ok(Unit)
                override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?) = HermesResult.Ok(Unit)
                override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> = emptyFlow()
            }
            val memory = object : RunMemory {
                override suspend fun sessionId() = "one"
                override suspend fun activeRun(): String? = null
                override suspend fun lastEventId(): Long? = null
                override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: app.rasul.chat.Anchor) = Unit
            }
            val sessions = object : app.rasul.sessions.SessionsApi {
                override suspend fun messages(target: HermesTarget, sessionId: String) = HermesResult.Ok(emptyList<app.rasul.sessions.SessionMessageDto>())
            }
            val vm = ViewModelProvider(views, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(TargetLoader { HermesResult.Ok(selected) }, api, sessions, memory, OrderedWriter(backgroundScope),
                        follow = { _, _, _ -> emptyFlow() }) as T
            })[ChatViewModel::class.java]
            runCurrent()
            vm.onDraft("first")
            vm.send()
            val captured = entered.await()
            selected = HermesTarget("", null, "", BridgeEndpoint(OkHttpClient(), "new-profile"))
            vm.onDraft("second")
            vm.send()
            runCurrent()
            assertEquals(first, captured.first)
            assertEquals("first", captured.second)
            assertEquals(1, starts)
            reply.complete(HermesResult.Err(HermesError.DeviceRevoked))
            runCurrent()
            assertEquals(ChatFailure.Api(HermesError.DeviceRevoked), vm.state.value.messages.last().error)
        } finally { views.clear(); Dispatchers.resetMain() }
    }
}
