package app.rasul.bridge

import app.rasul.data.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
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

/** A link that had already opened broke underneath us; it is not a connect failure and not a TLS failure. */
internal class BridgeLinkDroppedException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun Throwable.failures(): List<Throwable> {
    val seen = mutableSetOf<Throwable>()
    fun visit(t: Throwable) { if (seen.add(t)) { t.cause?.let(::visit); t.suppressed.forEach(::visit) } }
    visit(this)
    return seen.toList()
}

internal fun bridgeError(failure: Throwable): HermesError {
    val causes = failure.failures()
    causes.filterIsInstance<BridgeAccessException>().firstOrNull()?.let { return it.error }
    if (causes.any { it is InterruptedIOException }) return HermesError.BridgeTimeout
    causes.filterIsInstance<BridgeHandshakeFailure>().firstOrNull()?.let {
        return if (it.cause is SSLException) HermesError.BridgeSecure else HermesError.BridgeLinkDropped
    }
    // A TLS alert, certificate error, or hostname-verification failure is a security failure, not a
    // dropped link (an outer wss hostname failure arrives as SSLPeerUnverifiedException).
    if (causes.any {
        it is SSLHandshakeException || it is SSLPeerUnverifiedException ||
            it is CertificateException || it is CertPathValidatorException
    }) return HermesError.BridgeSecure
    // A post-handshake SSLException or a truncated stream is the TLS layer reporting the link's end, not a pairing
    // problem; "unexpected end of stream" is what OkHttp's HTTP/1 codec throws on that truncation.
    if (causes.any {
        it is BridgeLinkDroppedException || it is SSLException || it is EOFException ||
            (it is ProtocolException && it.message == "unexpected end of stream")
    }) return HermesError.BridgeLinkDropped
    // The link never opened: the bridge's host does not resolve, or it refused the TCP connection.
    if (causes.any { it is UnknownHostException }) return HermesError.BridgeAddressUnknown
    if (causes.any { it is ConnectException }) return HermesError.BridgeRefused
    return HermesError.BridgeUnreachable
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

/** Reads with no server-side effect, safe to send twice even after the response is lost. A PUT can
 * commit before its response is dropped, so a blind re-send would replay stale state: the
 * revision-guarded instructions save would then answer 409 although the first save worked. */
private val SAFE_METHODS = setOf("GET", "HEAD")

/** Routes the server deduplicates by `Idempotency-Key` — proven only for POST /v1/runs, whose run
 * idempotency store replays the stored run (api_server_runs.py:679-684, :720-729). Approval answers
 * and every other POST are never retried, keyed or not. */
private val IDEMPOTENT_KEYED_ROUTES = setOf("v1/runs")

/** The upstream route: the tail after `v1/profiles/{id}/hermes/` for profile requests, else the whole path. */
private fun Request.bridgeRoute(): String {
    val s = url.pathSegments
    val start = if (s.size >= 4 && s[0] == "v1" && s[1] == "profiles" && s[3] == "hermes") 4 else 0
    return s.subList(start, s.size).joinToString("/")
}

/** Safe to send twice: a read-only method, or a keyed POST to a route the server deduplicates. */
private fun Request.retryableOnDroppedLink(): Boolean =
    method in SAFE_METHODS ||
        (method == "POST" && header("Idempotency-Key") != null && bridgeRoute() in IDEMPOTENT_KEYED_ROUTES)

/**
 * Cancellation closes blocking response reads as well as the connection attempt. One transport failure on a
 * request that is safe to send twice is transparently repeated on a fresh link (the pooled one may have died
 * while idle); sends with side effects are never repeated.
 */
internal suspend fun <T> OkHttpClient.bridgeResponse(request: Request, read: suspend (Response) -> T): T {
    var retried = false
    while (true) {
        try {
            return coroutineScope {
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
                } finally { watcher.cancel() }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            val terminal = e.failures().any { it is BridgeAccessException || it is BridgeHandshakeFailure }
            if (retried || terminal || !request.retryableOnDroppedLink()) throw e
            retried = true
            connectionPool.evictAll()
        }
    }
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
