package app.rasul.data

import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeError
import app.rasul.bridge.bridgeStatus
import app.rasul.bridge.bridgeStreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * One event of `GET /v1/profiles/{profile}/live/{session_id}` (docs/bridge-protocol.md, "Watching a chat
 * live"): a read-only mirror of a reply the desktop application is writing on the PC. Payload field names
 * are the bridge's (`_project`, bridge/rasul_bridge/live.py:91-127); anything else the stream carries is
 * dropped.
 */
sealed interface LiveEvent {
    /** Once after the attach; [turnStartedAtMs] is the backend's `turn_started_at` (unix seconds) in ms. */
    data class Snapshot(val running: Boolean, val turnStartedAtMs: Long?) : LiveEvent
    data object MessageStart : LiveEvent
    data class MessageDelta(val text: String) : LiveEvent
    data class MessageComplete(val status: String?, val text: String?, val error: String?) : LiveEvent
    data class ToolStart(val toolId: String, val name: String) : LiveEvent
    data class ToolComplete(val toolId: String, val name: String, val summary: String?) : LiveEvent
    data class StatusUpdate(val text: String) : LiveEvent

    /** Terminal: the desktop backend is not running, or it does not serve this profile. */
    data class Unavailable(val reason: String?) : LiveEvent

    /** Terminal: the chat is not live on that backend, or it was reclaimed mid-stream. */
    data object Idle : LiveEvent

    /** Terminal: the backend socket closed, stopped answering, or a replay failed; reload and reconnect. */
    data object Reset : LiveEvent
}

/** A parsed live event with its SSE `id:` (`<epoch>:<seq>`) kept verbatim for `Last-Event-ID`. */
data class LiveFrame(val id: String?, val event: LiveEvent)

/** Why a live stream could not be opened or broke. */
sealed interface LiveError {
    /** The bridge answered 404: an older bridge without the live route. */
    data object Unsupported : LiveError

    /** 429 `live_stream_limited`; [retryAfterMs] comes from `Retry-After`. */
    data class Limited(val retryAfterMs: Long) : LiveError

    /** Any other HTTP or transport failure. */
    data class Failed(val error: HermesError) : LiveError
}

class LiveStreamException(val error: LiveError) : Exception(error.toString())

/**
 * `GET /v1/profiles/{profile}/live/{session_id}` of the bridge a chat belongs to. Emits frames until the
 * bridge ends the stream (after a terminal event, or on a drop); throws [LiveStreamException] on a refusal
 * or a transport failure. [lastEventId] is resent verbatim as `Last-Event-ID` so the bridge replays the
 * missed events (live.py:517-555).
 */
fun interface LiveApi {
    fun events(sessionId: String, lastEventId: String?): Flow<LiveFrame>
}

/** The bridge the target is bound to, or null on a direct connection (no live view, ever). */
fun HermesTarget.liveApi(): LiveApi? = bridge?.let { endpoint ->
    LiveApi { sessionId, lastEventId -> endpoint.liveEvents(sessionId, lastEventId) }
}

private fun BridgeEndpoint.liveEvents(sessionId: String, lastEventId: String?): Flow<LiveFrame> = channelFlow {
    // Same URL shape as BusyChats.kt:36-38, one extra segment; the stream client drops the call timeout
    // (BridgeEndpoint.kt:100) like the run-events call in RunsClient.kt:289.
    val url = BridgeTransport.origin.newBuilder().addPathSegments("v1/profiles")
        .addPathSegment(profileId).addPathSegment("live").addPathSegment(sessionId).build()
    val request = Request.Builder().url(url).header("Accept", "text/event-stream")
        .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }.build()
    val call = client.bridgeStreamClient().newCall(request)
    // A blocking read ignores coroutine cancellation, so cancel the call when this flow is cancelled
    // (the watcher pattern of RunsClient.kt:291-297).
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
                    when {
                        resp.code == 404 -> throw LiveStreamException(LiveError.Unsupported)
                        resp.code == 429 -> throw LiveStreamException(LiveError.Limited(retryAfterMs(resp)))
                        resp.code != 200 -> throw LiveStreamException(
                            LiveError.Failed(bridgeStatus(resp.code) ?: HermesError.BridgeUnreachable),
                        )
                    }
                    val source = resp.body?.source()
                        ?: throw LiveStreamException(LiveError.Failed(HermesError.BridgeUnreachable))
                    for (frame in source.sseFrames()) {
                        parseLiveFrame(frame)?.let { send(it) }
                    }
                }
            } catch (e: LiveStreamException) {
                throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext()[Job]!!.isActive) throw CancellationException("cancelled")
                throw LiveStreamException(LiveError.Failed(bridgeError(e)))
            }
        }
    } finally {
        watcher.cancel()
    }
}

/** The bridge always sends `Retry-After: 30` (live.py:370); a missing or non-integer one falls back to it. */
private fun retryAfterMs(response: Response): Long {
    val raw = response.header("Retry-After")
    if (raw != null && RETRY_AFTER_SECONDS.matches(raw)) return raw.toLong() * 1000
    return DEFAULT_RETRY_AFTER_MS
}

private val RETRY_AFTER_SECONDS = Regex("[0-9]{1,9}")
private const val DEFAULT_RETRY_AFTER_MS = 30_000L

private val liveJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.liveText(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * One SSE frame -> a [LiveFrame]. A frame without `event:` or whose `data:` is not a JSON object is
 * ignored, as is every event name the projection does not emit (live.py:127 drops them before writing).
 * `snapshot`/`idle`/`reset`/`unavailable` carry no `id:` on the wire (live.py:140-145).
 */
internal fun parseLiveFrame(frame: SseFrame): LiveFrame? {
    val name = frame.event ?: return null
    val obj = try { liveJson.parseToJsonElement(frame.data).jsonObject } catch (_: Exception) { return null }
    val event = when (name) {
        "snapshot" -> LiveEvent.Snapshot(
            running = (obj["running"] as? JsonPrimitive)?.booleanOrNull == true,
            turnStartedAtMs = (obj["turn_started_at"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
        )
        "message.start" -> LiveEvent.MessageStart
        "message.delta" -> LiveEvent.MessageDelta(obj.liveText("text") ?: return null)
        "message.complete" -> LiveEvent.MessageComplete(
            status = obj.liveText("status"),
            text = obj.liveText("text"),
            error = obj.liveText("error"),
        )
        "tool.start" -> LiveEvent.ToolStart(
            toolId = obj.liveText("tool_id") ?: return null,
            name = obj.liveText("name") ?: return null,
        )
        "tool.complete" -> LiveEvent.ToolComplete(
            toolId = obj.liveText("tool_id") ?: return null,
            name = obj.liveText("name") ?: return null,
            summary = obj.liveText("summary"),
        )
        "status.update" -> LiveEvent.StatusUpdate(obj.liveText("text") ?: return null)
        "unavailable" -> LiveEvent.Unavailable(obj.liveText("reason"))
        "idle" -> LiveEvent.Idle
        "reset" -> LiveEvent.Reset
        else -> return null
    }
    return LiveFrame(frame.id, event)
}
