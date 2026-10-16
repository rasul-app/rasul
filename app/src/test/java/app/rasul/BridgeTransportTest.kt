package app.rasul

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeStreamClient
import app.rasul.bridge.DeviceIdentity
import app.rasul.bridge.SpkiTrustManager
import app.rasul.bridge.WebSocketSocketFactory
import app.rasul.data.HermesClient
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BridgeTransportTest {
    private fun pin(certificate: HeldCertificate) =
        MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded)

    @Test fun pinnedTrustAcceptsOnlyMatchingSpki() {
        val server = HeldCertificate.Builder().ecdsa256().build()
        val other = HeldCertificate.Builder().ecdsa256().build()
        val verified = mutableListOf<ByteArray>()
        val trust = SpkiTrustManager(pin(server)) { verified.add(it) }
        trust.checkServerTrusted(arrayOf(server.certificate), "EC")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other.certificate), "EC") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "EC") }
        assertThrows(CertificateException::class.java) { trust.checkClientTrusted(arrayOf(server.certificate), "EC") }
        assertEquals(1, verified.size)
        assertArrayEquals(server.certificate.publicKey.encoded, verified.single())
    }

    @Test fun webSocketAdapterPreservesTheByteStreamAndTimeout() {
        val outer = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
                }))
                server.start()
                val factory = WebSocketSocketFactory(listOf(server.url("/").toString().replace("http:", "ws:")), outer)
                factory.createSocket("example.invalid", 443).use { socket ->
                    socket.soTimeout = 1000
                    val bytes = ByteArray(8192) { it.toByte() }
                    socket.getOutputStream().write(bytes, 17, 4000)
                    val echoed = socket.getInputStream().readNBytes(4000)
                    assertArrayEquals(bytes.copyOfRange(17, 4017), echoed)
                    socket.soTimeout = 20
                    assertThrows(java.net.SocketTimeoutException::class.java) { socket.getInputStream().read() }
                }
                assertEquals("/v1/link", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
        } finally { close(outer) }
    }

    @Test fun urlsFallBackInOrderAndRememberTheWorkingRoute() {
        val outer = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            MockWebServer().use { bad ->
                MockWebServer().use { good ->
                    bad.enqueue(MockResponse().setResponseCode(503))
                    repeat(2) { good.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})) }
                    bad.start(); good.start()
                    val first = bad.url("/").toString().replace("http:", "ws:")
                    val second = good.url("/").toString().replace("http:", "ws:")
                    val remembered = mutableListOf<String>()
                    val factory = WebSocketSocketFactory(listOf(first, second), outer, onWorkingUrl = { remembered.add(it) })
                    repeat(2) { factory.createSocket("example.invalid", 443).close() }
                    assertEquals(1, bad.requestCount)
                    assertEquals(2, good.requestCount)
                    assertEquals(listOf(second, second), remembered)
                }
            }
        } finally { close(outer) }
    }

    @Test fun binaryFramesDrainBeforeRemoteEof() {
        val outer = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            MockWebServer().use { server ->
                val bytes = ByteArray(257).apply { java.security.SecureRandom().nextBytes(this) }
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(bytes.toByteString(0, 111))
                        webSocket.send(bytes.toByteString(111, bytes.size - 111))
                        webSocket.close(1000, null)
                    }
                }))
                server.start()
                val factory = WebSocketSocketFactory(listOf(server.url("/").toString().replace("http:", "ws:")), outer)
                factory.createSocket("example.invalid", 443).use { socket ->
                    socket.soTimeout = 1000
                    assertArrayEquals(bytes, socket.getInputStream().readAllBytes())
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
        } finally { close(outer) }
    }

    @Test fun tls13AndMutualAuthenticationWorkThroughTheAdapterWithUnchangedHermesClient() = runBlocking {
        val serverKey = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("example.invalid").build()
        val deviceKey = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = deviceKey.keyPair.private
            override val certificateChain = arrayOf(deviceKey.certificate)
        }
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(serverKey)
            .addTrustedCertificate(deviceKey.certificate).build()
        val pipes = CopyOnWriteArrayList<Socket>()
        val workers = Executors.newCachedThreadPool()
        try {
            MockWebServer().use { inner ->
                inner.useHttps(serverCertificates.sslSocketFactory(), false)
                inner.requireClientAuth()
                inner.enqueue(MockResponse().setBody("{}"))
                inner.start()
                MockWebServer().use { outer ->
                    outer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private lateinit var pipe: Socket
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            pipe = Socket(InetAddress.getLoopbackAddress(), inner.port)
                            pipes.add(pipe)
                            workers.submit {
                                try {
                                    val bytes = ByteArray(8192)
                                    while (true) {
                                        val n = pipe.getInputStream().read(bytes)
                                        if (n == -1) break
                                        if (!webSocket.send(bytes.toByteString(0, n))) break
                                    }
                                } finally { pipe.close(); webSocket.close(1000, null) }
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            pipe.getOutputStream().write(bytes.toByteArray())
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (::pipe.isInitialized) pipe.close() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { pipe.close(); webSocket.close(code, null) }
                    }))
                    outer.start()
                    BridgeTransport(listOf(outer.url("/").toString().replace("http:", "ws:")), pin(serverKey), identity, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).use { transport ->
                        val result = HermesClient(transport.client).health(
                            HermesTarget("https://example.invalid/v1/profiles/default/hermes/", null, java.util.UUID.randomUUID().toString()),
                        )
                        assertTrue(result.toString(), result is HermesResult.Ok)
                        val request = inner.takeRequest(1, TimeUnit.SECONDS)!!
                        assertEquals("/v1/profiles/default/hermes/health", request.path)
                        assertNull(request.getHeader("Authorization"))
                        assertEquals("TLSv1.3", request.handshake!!.tlsVersion.javaName)
                        assertEquals(deviceKey.certificate, request.handshake!!.peerCertificates.first())
                    }
                }
            }
        } finally {
            pipes.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test fun fixedAuthorityAndTransportUrlsAreRestricted() {
        val outer = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            for (url in listOf("http://example.invalid", "ws://example.invalid/?id=1", "ws://device@example.invalid", "wss://example.invalid/path")) {
                assertThrows(IllegalArgumentException::class.java) { WebSocketSocketFactory(listOf(url), outer) }
            }
            val factory = WebSocketSocketFactory(listOf("wss://example.invalid"), outer)
            assertThrows(IllegalArgumentException::class.java) { factory.createSocket("other.example.invalid", 443) }
            factory.createSocket().use { socket ->
                assertThrows(IllegalArgumentException::class.java) { socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7)), 443)) }
            }
        } finally { close(outer) }
    }

    @Test fun oversizedMessagesAreRejectedBeforeEnteringTheAdapterQueue() {
        val outer = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .minWebSocketMessageToCompress(Long.MAX_VALUE).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(ByteArray(1024 * 1024 + 1).toByteString())
                    }
                }))
                server.start()
                WebSocketSocketFactory(listOf(server.url("/").toString().replace("http:", "ws:")), outer)
                    .createSocket("example.invalid", 443).use { socket ->
                        socket.soTimeout = 5000
                        val failure = assertThrows(java.net.ProtocolException::class.java) { socket.getInputStream().read() }
                        assertEquals("Bridge receive buffer exceeded", failure.message)
                        assertEquals(0, socket.getInputStream().available())
                    }
            }
        } finally { close(outer) }
    }

    @Test fun ordinaryCallsAndSilentInnerStreamsUseScaledProductionBounds() = runBlocking {
        val production = app.rasul.bridge.BridgeTimeouts()
        assertEquals(30_000L, production.requestMs)
        assertEquals(45_000L, production.streamIdleMs)
        val scaled = production.copy(requestMs = production.requestMs / 30, streamIdleMs = production.streamIdleMs / 30)
        withSilentBridge(scaled) { inner, transport ->
            assertEquals(scaled.requestMs, transport.client.callTimeoutMillis.toLong())
            assertEquals(scaled.streamIdleMs, transport.client.readTimeoutMillis.toLong())
            inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            try {
                app.rasul.bridge.BridgeCreateBotApi(transport.client).create(app.rasul.bridge.CreateBotRequest(java.util.UUID.randomUUID().toString(), "new-bot", "blank"))
                fail("expected request deadline")
            } catch (e: app.rasul.bridge.CreateBotException) { assertEquals(app.rasul.bridge.CreateBotError.Timeout, e.error) }
            inner.enqueue(MockResponse().setBody("""[{"id":"blank","label":"Blank"}]"""))
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
            val retained = androidx.lifecycle.ViewModelStore()
            try {
                val vm = app.rasul.bridge.CreateBotViewModel({ app.rasul.bridge.BridgeCreateBotApi(transport.client) })
                retained.put("create", vm)
                kotlinx.coroutines.withTimeout(5000) { vm.state.first { !it.loading } }
                inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                vm.name("new-bot")
                vm.submit { success, _ -> success() }
                val failed = kotlinx.coroutines.withTimeout(5000) { vm.state.first { it.phase == app.rasul.bridge.CreateBotPhase.Failed } }
                assertEquals(app.rasul.bridge.CreateBotError.Timeout, failed.error)
                assertFalse(failed.busy)
            } finally { retained.clear(); kotlinx.coroutines.Dispatchers.resetMain() }
            inner.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(": keepalive\n\n", 64)
                .setBodyDelay(scaled.streamIdleMs * 3, TimeUnit.MILLISECONDS))
            val stream = transport.client.bridgeStreamClient()
            assertEquals(0, stream.callTimeoutMillis)
            try {
                kotlinx.coroutines.withTimeout(10_000) { app.rasul.bridge.BridgeProfiles(transport.client).events().collect {} }
                fail("expected stream inactivity")
            } catch (e: java.io.IOException) { assertEquals(app.rasul.data.HermesError.BridgeTimeout, app.rasul.bridge.bridgeError(e)) }
        }
    }

    @Test fun rejectedPairedCertificateOverConnectedOuterTransportBecomesRevoked() = runBlocking {
        val key = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = key.keyPair.private
            override val certificateChain = arrayOf(key.certificate)
        }
        withSilentBridge(app.rasul.bridge.BridgeTimeouts(), identity) { inner, transport ->
            repeat(2) { inner.enqueue(MockResponse().setBody("{}")) }
            repeat(2) { attempt ->
                try { app.rasul.bridge.BridgeProfiles(transport.client).list(); fail("expected certificate rejection") }
                catch (e: java.io.IOException) {
                    assertEquals(if (attempt == 0) app.rasul.data.HermesError.BridgeUnreachable else app.rasul.data.HermesError.DeviceRevoked,
                        app.rasul.bridge.bridgeError(e))
                }
            }
        }
    }

    private suspend fun withSilentBridge(timeouts: app.rasul.bridge.BridgeTimeouts, identity: DeviceIdentity? = null, block: suspend (MockWebServer, BridgeTransport) -> Unit) {
        val serverKey = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("example.invalid").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(serverKey).addTrustedCertificate(HeldCertificate.Builder().ecdsa256().build().certificate).build()
        val pipes = CopyOnWriteArrayList<Socket>()
        val workers = Executors.newCachedThreadPool()
        try {
            MockWebServer().use { inner ->
                inner.useHttps(certificates.sslSocketFactory(), false)
                if (identity != null) inner.requireClientAuth()
                inner.start()
                MockWebServer().use { outer ->
                    repeat(4) { outer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private lateinit var pipe: Socket
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            pipe = Socket(InetAddress.getLoopbackAddress(), inner.port)
                            pipes.add(pipe)
                            workers.submit {
                                try {
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = pipe.getInputStream().read(buffer)
                                        if (n < 0 || !webSocket.send(buffer.toByteString(0, n))) break
                                    }
                                } catch (_: java.io.IOException) { }
                                finally { pipe.close(); webSocket.close(1000, null) }
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { pipe.getOutputStream().write(bytes.toByteArray()) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (::pipe.isInitialized) pipe.close() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (::pipe.isInitialized) pipe.close(); webSocket.cancel() }
                    })) }
                    outer.start()
                    BridgeTransport(listOf(outer.url("/").toString().replace("http:", "ws:")), pin(serverKey), identity,
                        newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }, timeouts = timeouts).use { block(inner, it) }
                }
            }
        } finally { pipes.forEach { runCatching { it.close() } }; workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun close(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
