package app.rasul.bridge

import app.rasul.data.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** The transport is shared with profile discovery and every routed request. */
data class BridgeEndpoint(val client: OkHttpClient, val profileId: String) {
    init { require(Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(profileId)) }
    fun url(path: String): HttpUrl = BridgeTransport.origin.newBuilder()
        .addPathSegments("v1/profiles").addPathSegment(profileId).addPathSegment("hermes")
        .addPathSegments(path).build()
}

internal fun bridgeStatus(code: Int, missing: HermesError = HermesError.ResourceNotFound): HermesError? = when {
    code in 200..299 -> null
    code == 403 -> HermesError.DeviceRevoked
    code == 503 -> HermesError.ProfileNotReady
    code == 404 -> missing
    else -> HermesError.BridgeUnreachable
}

class BridgeAccessException(val error: HermesError) : IOException()
internal class BridgeHandshakeFailure(cause: IOException) : IOException(cause)

private fun Throwable.failures(): List<Throwable> {
    val seen = mutableSetOf<Throwable>()
    fun visit(t: Throwable) { if (seen.add(t)) { t.cause?.let(::visit); t.suppressed.forEach(::visit) } }
    visit(this)
    return seen.toList()
}

internal fun bridgeError(failure: Throwable): HermesError {
    val causes = failure.failures()
    return causes.filterIsInstance<BridgeAccessException>().firstOrNull()?.error
        ?: if (causes.any { it is InterruptedIOException }) HermesError.BridgeTimeout else HermesError.BridgeUnreachable
}

/** Only used for an already paired identity, after the outer connection has opened. */
class BridgeRejectionTracker(private val paired: Boolean) {
    private var consecutive = 0
    private val opened = ThreadLocal<Boolean>()
    private val verified = ThreadLocal<Boolean>()
    fun beginAttempt() { opened.set(false); verified.set(false) }
    fun outerConnected() { opened.set(true); verified.set(false) }
    fun serverVerified() { verified.set(true) }
    fun callFailed(failure: IOException): IOException {
        if (failure.failures().any { it is BridgeHandshakeFailure }) return failure
        if (opened.get() == true) return rejected(failure)
        reset()
        return failure
    }
    @Synchronized fun reset() { consecutive = 0 }
    @Synchronized fun rejected(failure: Exception): IOException {
        val alert = failure is SSLException && failure.failures().any {
            Regex("(?<![a-z])(bad[_ ]certificate|certificate[_ ]unknown|unknown[_ ]ca|access[_ ]denied)(?![a-z])")
                .containsMatchIn(it.message.orEmpty().lowercase())
        }
        consecutive = if (paired && opened.get() == true && verified.get() == true && alert) consecutive + 1 else 0
        return if (consecutive >= 2) BridgeAccessException(HermesError.DeviceRevoked) else failure as? IOException ?: IOException(failure)
    }
}

internal fun OkHttpClient.bridgeStreamClient(): OkHttpClient = newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

/** Cancellation closes blocking response reads as well as the connection attempt. */
internal suspend fun <T> OkHttpClient.bridgeResponse(request: Request, read: suspend (Response) -> T): T = coroutineScope {
    val call = newCall(request)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            try { call.execute().use { read(it) } }
            catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            }
        }
    }
    finally { watcher.cancel() }
}

internal suspend fun BridgeEndpoint.send(
    path: String, post: Boolean, body: String, query: Map<String, String>,
): HermesResult<String> {
    val url = url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    val request = Request.Builder().url(url).header("Accept", "application/json")
        .apply { if (post) post(body.toRequestBody("application/json".toMediaType())) }.build()
    return try {
        client.bridgeResponse(request) { response ->
            bridgeStatus(response.code)?.let { HermesResult.Err(it) }
                ?: HermesResult.Ok(response.body?.string().orEmpty())
        }
    } catch (e: CancellationException) { throw e }
    catch (e: IOException) { HermesResult.Err(bridgeError(e)) }
}
