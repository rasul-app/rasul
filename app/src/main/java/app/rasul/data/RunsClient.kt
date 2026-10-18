package app.rasul.data

import app.rasul.bridge.bridgeResponse
import app.rasul.bridge.bridgeError
import app.rasul.bridge.bridgeStreamClient
import app.rasul.bridge.bridgeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The SSE connection failed or broke; [error] says why. */
class RunStreamException(val error: HermesError) : Exception(error.toString())

/** What the chat needs from the server; the real client is [RunsClient], tests use a fake whose replies can be held. */
interface RunsApi {
    /**
     * [input] is the finished wire `input` element: a JsonPrimitive holding the typed text, or the
     * one-message parts list from [runInputElement] when the send carries attachments. The caller
     * builds it once on a worker thread; the client posts it untouched.
     */
    suspend fun startRun(
        target: HermesTarget,
        input: JsonElement,
        sessionId: String?,
        idempotencyKey: String? = null,
    ): HermesResult<StartedRun>
    suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus>
    suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit>
    suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit>
    suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String? = null): HermesResult<Unit>
    fun events(target: HermesTarget, runId: String, lastSeq: Long? = null): Flow<RunFrame>
}

private data class PendingRunSelection(val idempotencyKey: String, val selection: RunModelSelection?)

/**
 * Runs API (docs/reference "Runs API"; routes api_server_runs.py:236-240). Same rules as [HermesClient]:
 * address policy checked before each request, redirects never followed, key never logged or echoed.
 * A 404 on a run route means the run is gone (NotFound), not an unknown profile.
 */
class RunsClient(
    base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val usageStore: ConnectionStore? = null,
    scope: CoroutineScope? = null,
) : RunsApi {
    private val http = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    // The server sends a keepalive comment every 10 s (docs/reference, "All SSE streams"); 45 s of silence is a dead link.
    private val sseHttp = http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val mutableSelectedModels = MutableStateFlow<Map<String, RunModelSelection>>(emptyMap())

    /** The model picked per chat (session id). With a [usageStore] it is kept on the phone and survives a restart. */
    val chatModels: StateFlow<Map<String, RunModelSelection>> = mutableSelectedModels
    private val modelsLoaded = CompletableDeferred<Unit>()
    private val modelWrites = Channel<Pair<String, RunModelSelection?>>(Channel.UNLIMITED)
    private val pendingSelectionBySession = ConcurrentHashMap<String, PendingRunSelection>()
    private val sessionByRun = ConcurrentHashMap<String, String>()
    private val recordedUsageRuns = ConcurrentHashMap.newKeySet<String>()
    private val mutableUsageBySession = MutableStateFlow<Map<String, TokenUsageSummary>>(emptyMap())
    val usageBySession: StateFlow<Map<String, TokenUsageSummary>> = mutableUsageBySession
    private val usageScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (usageStore == null) modelsLoaded.complete(Unit)
        usageStore?.let { store ->
            usageScope.launch {
                try {
                    val saved = store.chatModels()
                    mutableSelectedModels.update { saved + it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A broken store leaves chats on the server default; picks still work for this app session.
                } finally {
                    modelsLoaded.complete(Unit)
                }
                for ((session, selection) in modelWrites) {
                    try {
                        store.setChatModel(session, selection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
            usageScope.launch {
                store.runUsageBySession
                    .catch { mutableUsageBySession.value = emptyMap() }
                    .collect { mutableUsageBySession.value = it }
            }
        }
    }

    fun selectModelForChat(sessionId: String, selection: RunModelSelection?) {
        require(sessionId.isNotBlank())
        mutableSelectedModels.update { current ->
            if (selection == null) current - sessionId else current + (sessionId to selection)
        }
        modelWrites.trySend(sessionId to selection)
    }

    fun selectedModelForChat(sessionId: String): RunModelSelection? = mutableSelectedModels.value[sessionId]

    /**
     * POST /v1/runs. [idempotencyKey] goes in `Idempotency-Key`, so a retry after a lost reply returns the same run
     * (api_server_runs.py:636-642, 470-478). Body `input` (+ `session_id`), reply 202 `{"run_id"}` (api_server_runs.py:650-657, 466-467).
     * [input] is the already-built element (plain string for text-only, parts list with attachments). */
    override suspend fun startRun(
        target: HermesTarget,
        input: JsonElement,
        sessionId: String?,
        idempotencyKey: String?,
    ): HermesResult<StartedRun> {
        modelsLoaded.await()
        val selectedNow = sessionId?.let(::selectedModelForChat)
        val pendingSelection = if (sessionId != null && idempotencyKey != null) {
            pendingSelectionBySession.compute(sessionId) { _, current ->
                if (current?.idempotencyKey == idempotencyKey) current
                else PendingRunSelection(idempotencyKey, selectedNow)
            }
        } else null
        val selection = if (pendingSelection == null) selectedNow else pendingSelection.selection
        val body = buildJsonObject {
            put("input", input)
            if (sessionId != null) put("session_id", sessionId)
            if (selection != null) {
                put("provider", selection.provider)
                put("model", selection.model)
                selection.effort?.let { effort ->
                    put("model_options", buildJsonObject {
                        put("reasoning", buildJsonObject {
                            put("enabled", true)
                            put("effort", effort)
                        })
                    })
                }
            }
        }
        return when (val r = post(target, "v1/runs", body, idempotencyKey)) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> {
                val id = try {
                    (json.parseToJsonElement(r.value).jsonObject["run_id"] as? JsonPrimitive)?.contentOrNull
                } catch (e: Exception) {
                    null
                }
                if (id.isNullOrBlank()) HermesResult.Err(HermesError.Incompatible(listOf("run_id")))
                else {
                    sessionId?.let { session ->
                        if (usageStore == null) sessionByRun[id] = session
                        else {
                            try {
                                usageStore.rememberRunSession(id, session)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Usage tracking must not prevent the accepted run id from reaching the caller.
                            }
                        }
                    }
                    if (sessionId != null && pendingSelection != null) {
                        pendingSelectionBySession.remove(sessionId, pendingSelection)
                    }
                    HermesResult.Ok(StartedRun(id))
                }
            }
        }
    }

    /** GET /v1/runs/{id}: the run's pollable status (api_server_runs.py:1057). 404 = the server does not know the run. */
    override suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus> =
        when (val r = get(target, "v1/runs/$runId")) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> try {
                val o = json.parseToJsonElement(r.value).jsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val st = str("status")
                if (st.isNullOrBlank()) HermesResult.Err(HermesError.Incompatible(listOf("status")))
                else {
                    val usage = RunTokenUsage.from(o["usage"])
                    if (st == "completed") recordCompletedUsage(runId, usage)
                    HermesResult.Ok(RunStatus(st, str("output"), str("error"), o.steer(), approvalOf(o["approval"]), usage))
                }
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Incompatible(listOf("status")))
            }
        }

    private fun approvalOf(e: kotlinx.serialization.json.JsonElement?): RunEvent.ApprovalRequest? =
        (e as? JsonObject)?.let { parseRunFrame(SseFrame(null, "approval.request", it.toString())).event as? RunEvent.ApprovalRequest }

    /** POST /v1/runs/{id}/stop (api_server_runs.py:1253). */
    override suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit> =
        post(target, "v1/runs/$runId/stop", JsonObject(emptyMap())).unit()

    /** POST /v1/runs/{id}/steer, body `input` (api_server_runs.py:1235). 409 when the run is not running. */
    override suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit> =
        post(target, "v1/runs/$runId/steer", buildJsonObject { put("input", text) }).unit()

    /** POST /v1/runs/{id}/approval, body `choice` (+ `request_id`) (api_server_runs.py:1177-1180). */
    override suspend fun approve(
        target: HermesTarget,
        runId: String,
        choice: ApprovalChoice,
        requestId: String?,
    ): HermesResult<Unit> = post(
        target,
        "v1/runs/$runId/approval",
        buildJsonObject {
            put("choice", choice.wire)
            if (requestId != null) put("request_id", requestId)
        },
    ).unit()

    /**
     * One SSE connection (GET /v1/runs/{id}/events). Completes when the server closes the stream; throws
     * [RunStreamException] on a refused or broken connection. [lastSeq] asks the server to replay from there
     * (`Last-Event-ID`, api_server_runs.py:1088-1090).
     */
    override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> = channelFlow {
        val request = when (val p = prepare(target, "v1/runs/$runId/events")) {
            is Prepared.Bad -> throw RunStreamException(p.error)
            is Prepared.Ok -> Request.Builder().url(p.url)
                .apply { if (target.bridge == null) header("Authorization", "Bearer ${target.apiKey}") }
                .header("Accept", "text/event-stream")
                .apply { if (lastSeq != null) header("Last-Event-ID", lastSeq.toString()) }
                .build()
        }
        val call = (target.bridge?.client?.bridgeStreamClient() ?: sseHttp).newCall(request)
        // A blocking read ignores coroutine cancellation, so cancel the call when this flow is cancelled.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { resp ->
                        (if (target.bridge != null) bridgeStatus(resp.code, HermesError.NotFound) else mapStatus(resp.code, resp.isRedirect))?.let { throw RunStreamException(it) }
                        val source = resp.body?.source()
                            ?: throw RunStreamException(HermesError.Unreachable("empty response body"))
                        for (frame in source.sseFrames()) {
                            val parsed = parseRunFrame(frame)
                            if (parsed.event is RunEvent.Completed) recordCompletedUsage(runId, parsed.event.usage)
                            send(parsed)
                        }
                    }
                } catch (e: IOException) {
                    if (!currentCoroutineContext()[Job]!!.isActive) throw CancellationException("cancelled")
                    throw RunStreamException(if (target.bridge != null) bridgeError(e) else HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun recordCompletedUsage(runId: String, usage: RunTokenUsage?) {
        usageStore?.let { store ->
            val updated = try {
                store.recordCompletedRunUsage(runId, usage)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            updated?.let { mutableUsageBySession.value = it }
            return
        }
        val sessionId = sessionByRun[runId] ?: return
        if (!recordedUsageRuns.add(runId)) return
        mutableUsageBySession.update { current ->
            val summary = (current[sessionId] ?: TokenUsageSummary()).include(usage)
            current + (sessionId to summary)
        }
    }

    private sealed interface Prepared {
        data class Ok(val url: HttpUrl) : Prepared
        data class Bad(val error: HermesError) : Prepared
    }

    private fun prepare(target: HermesTarget, path: String): Prepared {
        target.bridge?.let { return Prepared.Ok(it.url(path)) }
        val base = target.baseUrl.trim().toHttpUrlOrNull()
            ?: return Prepared.Bad(HermesError.InvalidAddress(target.baseUrl))
        if (AddressPolicy.check(target.baseUrl) is AddressVerdict.Rejected) return Prepared.Bad(HermesError.AddressRejected)
        // Printable ASCII only (no space, CR, LF): anything else cannot be a header value.
        if (target.apiKey.isEmpty() || !target.apiKey.all { it in '!'..'~' }) return Prepared.Bad(HermesError.InvalidKey)
        val profile = target.profile?.trim()?.takeIf { it.isNotEmpty() }
        return Prepared.Ok(
            base.newBuilder().apply {
                if (profile != null) addPathSegment("p").addPathSegment(profile)
                addPathSegments(path)
            }.build(),
        )
    }

    private fun mapStatus(code: Int, redirect: Boolean): HermesError? = when {
        redirect -> HermesError.Redirected(code)
        code in 200..299 -> null
        code == 401 || code == 403 -> HermesError.Unauthorized
        code == 404 -> HermesError.NotFound
        else -> HermesError.Server(code)
    }

    private suspend fun post(target: HermesTarget, path: String, body: JsonObject, idempotencyKey: String? = null): HermesResult<String> =
        withContext(Dispatchers.IO) {
            val url = when (val p = prepare(target, path)) {
                is Prepared.Bad -> return@withContext HermesResult.Err(p.error)
                is Prepared.Ok -> p.url
            }
            try {
                val request = Request.Builder().url(url)
                    .apply { if (target.bridge == null) header("Authorization", "Bearer ${target.apiKey}") }
                    .header("Accept", "application/json")
                    .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                if (target.bridge != null) return@withContext target.bridge.client.bridgeResponse(request) { resp ->
                    val err = bridgeStatus(resp.code, HermesError.NotFound)
                    if (err != null) HermesResult.Err(err) else HermesResult.Ok(resp.body?.string().orEmpty())
                }
                http.newCall(request).execute().use { resp ->
                    val err = mapStatus(resp.code, resp.isRedirect)
                    if (err != null) HermesResult.Err(err) else HermesResult.Ok(resp.body?.string().orEmpty())
                }
            } catch (e: IllegalArgumentException) {
                HermesResult.Err(HermesError.InvalidKey)
            } catch (e: IOException) {
                HermesResult.Err(if (target.bridge != null) bridgeError(e) else HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
            }
        }

    private suspend fun get(target: HermesTarget, path: String): HermesResult<String> =
        withContext(Dispatchers.IO) {
            val url = when (val p = prepare(target, path)) {
                is Prepared.Bad -> return@withContext HermesResult.Err(p.error)
                is Prepared.Ok -> p.url
            }
            try {
                val request = Request.Builder().url(url)
                    .apply { if (target.bridge == null) header("Authorization", "Bearer ${target.apiKey}") }
                    .header("Accept", "application/json")
                    .build()
                if (target.bridge != null) return@withContext target.bridge.client.bridgeResponse(request) { resp ->
                    val err = bridgeStatus(resp.code, HermesError.NotFound)
                    if (err != null) HermesResult.Err(err) else HermesResult.Ok(resp.body?.string().orEmpty())
                }
                http.newCall(request).execute().use { resp ->
                    val err = mapStatus(resp.code, resp.isRedirect)
                    if (err != null) HermesResult.Err(err) else HermesResult.Ok(resp.body?.string().orEmpty())
                }
            } catch (e: IllegalArgumentException) {
                HermesResult.Err(HermesError.InvalidKey)
            } catch (e: IOException) {
                HermesResult.Err(if (target.bridge != null) bridgeError(e) else HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
            }
        }

    private fun HermesResult<String>.unit(): HermesResult<Unit> = when (this) {
        is HermesResult.Err -> this
        is HermesResult.Ok -> HermesResult.Ok(Unit)
    }
}
