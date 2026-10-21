package app.rasul.pairing

import app.rasul.bridge.BridgeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class HostProtocolRange(val min: String, val max: String)

/** `GET /v2/info`; fields the app does not know, including capabilities, are ignored. */
@Serializable
data class HostInfo(
    @SerialName("host_id") val hostId: String,
    val name: String,
    @SerialName("agent_version") val agentVersion: String,
    val protocol: HostProtocolRange,
    val capabilities: List<String> = emptyList(),
    val time: Long = 0,
)

sealed interface HostProbe {
    data class V2(val info: HostInfo) : HostProbe
    /** The PC answered 404: it speaks protocol 1. */
    data object V1 : HostProbe
    /** The PC speaks protocol 2 but not a version this app can use. */
    data object Incompatible : HostProbe
    data object Failed : HostProbe
}

/** Asks a saved PC which protocol it speaks, over that PC's existing pinned link client. */
object HostInfoClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(http: OkHttpClient): HostProbe {
        val request = Request.Builder().url(BridgeTransport.origin.newBuilder().addPathSegments("v2/info").build()).get().build()
        return try {
            http.newCall(request).awaitBody { response -> response.use {
                when (it.code) {
                    404 -> HostProbe.V1
                    200 -> {
                        val source = it.body?.source()?.apply { request(16_385) } ?: return@use HostProbe.Failed
                        if (source.buffer.size > 16_384) return@use HostProbe.Failed
                        val info = runCatching { json.decodeFromString<HostInfo>(source.readUtf8()) }.getOrNull()
                            ?: return@use HostProbe.Failed
                        val min = majorMinor(info.protocol.min)
                        val max = majorMinor(info.protocol.max)
                        when {
                            min == null || max == null -> HostProbe.Failed
                            min <= SUPPORTED && SUPPORTED <= max -> HostProbe.V2(info)
                            else -> HostProbe.Incompatible
                        }
                    }
                    else -> HostProbe.Failed
                }
            } }
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { HostProbe.Failed }
    }

    private val SUPPORTED = 2L * 1_000_000 + 0

    /** "2.0" -> a comparable number; null when it is not major.minor. */
    private fun majorMinor(value: String): Long? {
        val match = Regex("([0-9]{1,6})\\.([0-9]{1,6})").matchEntire(value) ?: return null
        return match.groupValues[1].toLong() * 1_000_000 + match.groupValues[2].toLong()
    }
}
