package app.rasul.data

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException

/** Where a chat's current answer is produced, as the bridge reports it. */
enum class BusyWhere { Desktop, App }

sealed interface BusyChatsResult {
    /** Session id -> where that chat is replying. */
    data class Ok(val busy: Map<String, BusyWhere>) : BusyChatsResult

    /** An older bridge without the route (404): the badges stay hidden, no error. */
    data object Unsupported : BusyChatsResult

    /** The bridge could not be asked right now; the last known map stays. */
    data object Failed : BusyChatsResult
}

/** `GET /v1/profiles/{id}/turns` of the bridge a chat belongs to (docs/bridge-protocol.md). */
fun interface BusyChatsApi {
    suspend fun list(): BusyChatsResult
}

fun HermesTarget.busyApi(): BusyChatsApi? = bridge?.let { endpoint ->
    BusyChatsApi {
        val url = BridgeTransport.origin.newBuilder().addPathSegments("v1/profiles")
            .addPathSegment(endpoint.profileId).addPathSegment("turns").build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            endpoint.client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> BusyChatsResult.Unsupported
                    response.code != 200 -> BusyChatsResult.Failed
                    else -> parseBusyChats(text)?.let { BusyChatsResult.Ok(it) } ?: BusyChatsResult.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BusyChatsResult.Failed
        }
    }
}

private val busyChatsJson = Json { ignoreUnknownKeys = true }

/** The bridge's reply; null when it is not a list. Rows without a known `where` or without `session_ids` are skipped. */
internal fun parseBusyChats(text: String): Map<String, BusyWhere>? {
    val rows = try { busyChatsJson.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null } ?: return null
    val out = LinkedHashMap<String, BusyWhere>()
    for (row in rows) {
        val o = try { row.jsonObject } catch (_: Exception) { continue }
        val place = when (o.text("where")) {
            "desktop" -> BusyWhere.Desktop
            "app" -> BusyWhere.App
            else -> continue
        }
        val ids = o["session_ids"] as? JsonArray ?: continue
        for (id in ids) {
            val sid = (id as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            out[sid] = place
        }
    }
    return out
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
