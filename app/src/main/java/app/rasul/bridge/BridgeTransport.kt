package app.rasul.bridge

import javax.net.ssl.SSLContext
import org.conscrypt.Conscrypt
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class BridgeTimeouts(val requestMs: Long = REQUEST_MS, val streamIdleMs: Long = STREAM_IDLE_MS, val linkStaleMs: Long = LINK_STALE_MS) {
    init { require(requestMs > 0 && streamIdleMs > 0 && linkStaleMs > 0) }
    companion object {
        const val REQUEST_MS = 30_000L
        const val STREAM_IDLE_MS = 45_000L
        /** Below the ~100 s idle limit of a tunnelled WebSocket link. */
        const val LINK_STALE_MS = 90_000L
    }
}

/** Own one instance per bridge, and close it on disconnect. Enrollment supplies no identity. */
class BridgeTransport(
    urls: List<String>,
    pin: ByteArray,
    identity: DeviceIdentity?,
    onWorkingUrl: (String) -> Unit = {},
    newContext: () -> SSLContext = { SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()) },
    onServerSpki: (ByteArray) -> Unit = {},
    timeouts: BridgeTimeouts = BridgeTimeouts(),
) : AutoCloseable {
    companion object {
        private const val INNER_AUTHORITY = "example.invalid"
        val origin: okhttp3.HttpUrl = okhttp3.HttpUrl.Builder().scheme("https").host(INNER_AUTHORITY).build()
    }
    private val rejections = BridgeRejectionTracker(identity != null)
    private val outer = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .minWebSocketMessageToCompress(Long.MAX_VALUE)
        .pingInterval(15, TimeUnit.SECONDS).build()
    @Volatile var lastWorkingUrl: String? = null
        private set
    private val trust = SpkiTrustManager(pin) { spki ->
        rejections.serverVerified()
        onServerSpki(spki)
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(WebSocketSocketFactory(urls, outer, { url ->
            lastWorkingUrl = url
            onWorkingUrl(url)
        }, confirmOnConnect = false, onConnectFailure = rejections::reset, onOuterConnected = rejections::outerConnected,
        staleAfterMs = timeouts.linkStaleMs))
        .sslSocketFactory(BridgeTlsSocketFactory(trust, identity, newContext, rejections), trust)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != INNER_AUTHORITY) throw UnknownHostException("Unexpected inner authority")
                return listOf(InetAddress.getLoopbackAddress())
            }
        })
        .proxy(Proxy.NO_PROXY)
        .hostnameVerifier { host, _ -> host == INNER_AUTHORITY }
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(timeouts.requestMs, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(timeouts.streamIdleMs, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            require(request.url.scheme == "https" && request.url.host == INNER_AUTHORITY && request.url.port == 443)
            rejections.beginAttempt()
            try {
                chain.proceed(request.newBuilder().removeHeader("Authorization").build()).also { rejections.reset() }
            } catch (failure: java.io.IOException) { throw rejections.callFailed(failure) }
        }.build()

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        outer.dispatcher.cancelAll()
        outer.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        outer.dispatcher.executorService.shutdown()
    }
}
