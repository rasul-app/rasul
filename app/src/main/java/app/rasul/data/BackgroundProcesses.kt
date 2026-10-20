package app.rasul.data

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException

/** One background process the agent still has running on the PC, as the bridge lists it. */
data class BridgeProcess(val id: String, val command: String, val startedAtMillis: Long?, val owner: String?)

sealed interface ProcessesResult {
    data class Ok(val items: List<BridgeProcess>) : ProcessesResult

    /** An older bridge without the route (404): the feature stays hidden. */
    data object Unsupported : ProcessesResult

    /** The bridge could not be asked right now; the last answer stays. */
    data object Failed : ProcessesResult
}

/** `GET /v1/profiles/{id}/processes` of the bridge a chat belongs to (docs/bridge-protocol.md). */
fun interface ProcessesApi {
    suspend fun list(): ProcessesResult
}

fun HermesTarget.processesApi(): ProcessesApi? = bridge?.let { endpoint ->
    ProcessesApi {
        val url = BridgeTransport.origin.newBuilder().addPathSegments("v1/profiles")
            .addPathSegment(endpoint.profileId).addPathSegment("processes").build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            endpoint.client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> ProcessesResult.Unsupported
                    response.code != 200 -> ProcessesResult.Failed
                    else -> parseProcesses(text)?.let { ProcessesResult.Ok(it) } ?: ProcessesResult.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ProcessesResult.Failed
        }
    }
}

private val processesJson = Json { ignoreUnknownKeys = true }

/** The bridge's reply; null when it is not a list. A row without an id is skipped. */
internal fun parseProcesses(text: String): List<BridgeProcess>? {
    val rows = try { processesJson.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null } ?: return null
    return rows.mapNotNull { row ->
        val o = try { row.jsonObject } catch (_: Exception) { return@mapNotNull null }
        val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BridgeProcess(
            id = id,
            command = o.text("command").orEmpty(),
            startedAtMillis = (o["started_at"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
            owner = o.text("owner"),
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
