package app.rasul

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeError
import app.rasul.bridge.bridgeResponse
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    @Test fun rejectedPairedCertificateOverConnectedOuterTransportBecomesRevoked() {
        val serverKey = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("example.invalid").build()
        val deviceKey = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = deviceKey.keyPair.private
            override val certificateChain = arrayOf(deviceKey.certificate)
        }
        val certificates = HandshakeCertificates.Builder().heldCertificate(serverKey)
            .addTrustedCertificate(HeldCertificate.Builder().ecdsa256().build().certificate).build()
        val tracker = app.rasul.bridge.BridgeRejectionTracker(true)
        val trust = SpkiTrustManager(pin(serverKey)) { tracker.serverVerified() }
        val tls = app.rasul.bridge.BridgeTlsSocketFactory(trust, identity,
            { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }, tracker)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val pipes = CopyOnWriteArrayList<Socket>()
        val workers = Executors.newCachedThreadPool()
        try {
            // A plain listener keeps the accepted TCP socket reachable. Closing the SSL socket
            // while the client's still-arriving flight bytes sit unread sends RST through the
            // relay and can drop the alert before it is forwarded; the raw socket instead stays
            // open until teardown, with FIN emitted explicitly once the rejection is recorded.
            java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { inner ->
                val serverTls = certificates.sslContext().socketFactory
                val rejectedAttempts = List(2) { java.util.concurrent.CountDownLatch(1) }
                val rejected = workers.submit {
                    repeat(2) { attempt ->
                        val raw = inner.accept()
                        pipes.add(raw)
                        try {
                            val socket = serverTls.createSocket(raw, "example.invalid", 443, false)
                                as javax.net.ssl.SSLSocket
                            pipes.add(socket)
                            socket.useClientMode = false
                            socket.needClientAuth = true
                            socket.enabledProtocols = arrayOf("TLSv1.3")
                            raw.soTimeout = 5000
                            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) { socket.startHandshake() }
                            rejectedAttempts[attempt].countDown()
                            raw.shutdownOutput()
                        } catch (t: Throwable) {
                            runCatching { raw.close() }
                            throw t
                        }
                    }
                }
                MockWebServer().use { outer ->
                    val events = CopyOnWriteArrayList<String>()
                    val forwarded = CopyOnWriteArrayList<java.util.concurrent.Future<*>>()
                    val closed = List(2) { java.util.concurrent.CountDownLatch(1) }
                    for ((index, close) in closed.withIndex()) {
                        outer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            private lateinit var pipe: Socket
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                pipe = Socket(InetAddress.getLoopbackAddress(), inner.localPort)
                                pipes.add(pipe)
                                forwarded.add(workers.submit {
                                    try {
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val n = pipe.getInputStream().read(buffer)
                                            events.add("$index forward read=$n")
                                            if (n < 0) break
                                            assertTrue("$index forwarding failed: $events", webSocket.send(buffer.toByteString(0, n)))
                                        }
                                    } catch (t: Throwable) {
                                        events.add("$index forward failure=$t")
                                        throw t
                                    } finally {
                                        synchronized(pipe) { pipe.close() }
                                        events.add("$index close queued=${webSocket.close(1000, null)}")
                                    }
                                })
                            }
                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                synchronized(pipe) {
                                    // Late handshake frames must not abort the outer close handshake.
                                    if (pipe.isClosed) {
                                        assertTrue("$index closed without TLS rejection: $events",
                                            rejectedAttempts[index].await(5, TimeUnit.SECONDS))
                                        events.add("$index inbound discarded after rejection")
                                        return
                                    }
                                    try {
                                        pipe.getOutputStream().write(bytes.toByteArray())
                                    } catch (failure: java.io.IOException) {
                                        events.add("$index inbound failure=$failure")
                                        assertTrue("$index write failed without TLS rejection: $events",
                                            rejectedAttempts[index].await(5, TimeUnit.SECONDS))
                                    }
                                }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.add("$index acknowledged"); close.countDown() }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                events.add("$index websocket failure=$t")
                                if (::pipe.isInitialized) synchronized(pipe) { pipe.close() }
                            }
                        }))
                    }
                    outer.start()
                    val factory = WebSocketSocketFactory(listOf(outer.url("/").toString().replace("http:", "ws:")),
                        client, confirmOnConnect = false, onConnectFailure = tracker::reset, onOuterConnected = tracker::outerConnected)
                    repeat(2) { attempt ->
                        tracker.beginAttempt()
                        factory.createSocket("example.invalid", 443).use { raw ->
                            tls.createSocket(raw, "example.invalid", 443, true).use { socket ->
                                socket.soTimeout = 5000
                                // The close reply proves the adapter processed the peer close before this write.
                                if (!closed[attempt].await(5, TimeUnit.SECONDS)) {
                                    events.add("server done=${rejected.isDone}; forwarders=${forwarded.map { it.isDone }}")
                                    if (rejected.isDone) runCatching { rejected.get() }.exceptionOrNull()?.let { events.add("server failure=$it") }
                                    forwarded.filter { it.isDone }.forEach { future ->
                                        runCatching { future.get() }.exceptionOrNull()?.let { events.add("forwarder failure=$it") }
                                    }
                                    fail("attempt=$attempt peer close was not acknowledged: $events")
                                }
                                assertTrue("$attempt missing TLS rejection: $events", rejectedAttempts[attempt].await(5, TimeUnit.SECONDS))
                                forwarded[attempt].get(5, TimeUnit.SECONDS)
                                val failure = assertThrows(javax.net.ssl.SSLException::class.java) {
                                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.invalid\r\n\r\n".toByteArray())
                                    socket.getInputStream().read()
                                }
                                assertEquals(failure.stackTraceToString(),
                                    if (attempt == 0) app.rasul.data.HermesError.BridgeSecure else app.rasul.data.HermesError.DeviceRevoked,
                                    app.rasul.bridge.bridgeError(tracker.callFailed(failure)))
                            }
                        }
                    }
                    rejected.get(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            pipes.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            close(client)
        }
    }

    @Test fun plainNetworkDropIsLinkDropped() {
        val tracker = app.rasul.bridge.BridgeRejectionTracker(true)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        try {
            repeat(2) {
                MockWebServer().use { outer ->
                    outer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                    outer.start()
                    val factory = WebSocketSocketFactory(listOf(outer.url("/").toString().replace("http:", "ws:")),
                        client, onConnectFailure = tracker::reset, onOuterConnected = tracker::outerConnected)
                    tracker.beginAttempt()
                    factory.createSocket("example.invalid", 443).use { socket ->
                        socket.soTimeout = 5000
                        tracker.serverVerified()
                        outer.shutdown()
                        val failure = assertThrows(java.io.IOException::class.java) { socket.getInputStream().read() }
                        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped, app.rasul.bridge.bridgeError(tracker.callFailed(failure)))
                        assertTrue(socket.isInputShutdown)
                        assertTrue(socket.isOutputShutdown)
                    }
                }
            }
        } finally { close(client) }
    }

    /** A request on a link the peer closed is transparently repeated on a fresh link (the owner-reported symptom). */
    @Test fun aLinkClosedByThePeerIsReopenedForIdempotentGets() = runBlocking {
        withBridgeLinks { inner, outer, transport, links ->
            inner.enqueue(MockResponse().setBody("{}"))
            inner.enqueue(MockResponse().setBody("{}"))
            // The GET may already be on the closing link and reach the inner server before the close lands; its
            // answer is then lost with the link and the transport re-sends it, so the inner server sees up to
            // three requests. Without a third answer that re-send waits on an empty queue.
            inner.enqueue(MockResponse().setBody("{}"))
            val api = HermesClient(transport.client)
            val target = HermesTarget("", null, "", app.rasul.bridge.BridgeEndpoint(transport.client, "default"))
            assertTrue(api.health(target) is HermesResult.Ok)
            assertEquals(1, outer.requestCount)
            assertTrue(links.single().close(1000, null))
            assertTrue(kotlinx.coroutines.withTimeout(10_000) { api.health(target) } is HermesResult.Ok)
            assertEquals(2, outer.requestCount)
        }
    }

    /** A link killed mid-response repeats an idempotent GET once on a re-opened link. */
    @Test fun aLinkKilledMidResponseIsRetriedOnceForGets() = runBlocking {
        withBridgeLinks { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setBody("partial body").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            inner.enqueue(MockResponse().setBody("{}"))
            val api = HermesClient(transport.client)
            val target = HermesTarget("", null, "", app.rasul.bridge.BridgeEndpoint(transport.client, "default"))
            assertTrue(kotlinx.coroutines.withTimeout(10_000) { api.health(target) } is HermesResult.Ok)
            assertEquals(2, outer.requestCount)
        }
    }

    /** A non-idempotent POST is never resent; the error names the dropped link. */
    @Test fun aPostWithoutIdempotencyIsNeverResent() = runBlocking {
        withBridgeLinks { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val api = HermesClient(transport.client)
            val target = HermesTarget("", null, "", app.rasul.bridge.BridgeEndpoint(transport.client, "default"))
            val result = kotlinx.coroutines.withTimeout(10_000) {
                api.send(target, "api/jobs", post = true)
            }
            assertEquals(HermesResult.Err(app.rasul.data.HermesError.BridgeLinkDropped), result)
            assertEquals(1, outer.requestCount)
        }
    }

    /** A keyed POST /v1/runs (the route the server deduplicates) is repeated once on a fresh link,
     * and the retry carries the same key. */
    @Test fun aPostWithIdempotencyKeyIsRetriedOnce() = runBlocking {
        withBridgeLinks { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            inner.enqueue(MockResponse().setBody("{}"))
            val request = Request.Builder()
                .url(BridgeTransport.origin.newBuilder()
                    .addPathSegments("v1/profiles/default/hermes/v1/runs").build())
                .header("Idempotency-Key", "key-1")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val body = kotlinx.coroutines.withTimeout(10_000) {
                transport.client.bridgeResponse(request) { it.body?.string() }
            }
            assertEquals("{}", body)
            assertEquals(2, outer.requestCount)
            val first = inner.takeRequest(5, TimeUnit.SECONDS)
            val second = inner.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("key-1", first?.getHeader("Idempotency-Key"))
            assertEquals("key-1", second?.getHeader("Idempotency-Key"))
            assertEquals("/v1/profiles/default/hermes/v1/runs", second?.path)
        }
    }

    /** A keyed POST to a route the server does not deduplicate (an approval answer) is never resent. */
    @Test fun aKeyedPostToANonDeduplicatedRouteIsNeverResent() = runBlocking {
        withBridgeLinks { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val request = Request.Builder()
                .url(BridgeTransport.origin.newBuilder()
                    .addPathSegments("v1/profiles/default/hermes/v1/runs/run_1/approval").build())
                .header("Idempotency-Key", "key-1")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val failure: java.io.IOException = try {
                kotlinx.coroutines.withTimeout(10_000) {
                    transport.client.bridgeResponse(request) { it.body?.string() }
                }
                throw AssertionError("expected an IOException")
            } catch (e: java.io.IOException) { e }
            assertEquals(app.rasul.data.HermesError.BridgeLinkDropped, app.rasul.bridge.bridgeError(failure))
            assertEquals(1, outer.requestCount)
        }
    }

    /** A PUT can commit on the bridge before its response is lost (the revision-guarded instructions
     * save then answers 409 on a re-send), so a PUT is never resent; the error names the dropped link. */
    @Test fun aPutWithDroppedResponseIsNeverResent() = runBlocking {
        withBridgeLinks { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            inner.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            val request = Request.Builder()
                .url(BridgeTransport.origin.newBuilder()
                    .addPathSegments("v1/profiles/default/instructions").build())
                .put("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val failure: java.io.IOException = try {
                kotlinx.coroutines.withTimeout(10_000) {
                    transport.client.bridgeResponse(request) { it.body?.string() }
                }
                throw AssertionError("expected an IOException")
            } catch (e: java.io.IOException) { e }
            assertEquals(app.rasul.data.HermesError.BridgeLinkDropped, app.rasul.bridge.bridgeError(failure))
            assertEquals(1, outer.requestCount)
        }
    }

    /** A pooled link idle longer than the tunnel's limit is never reused (resume, network change, Doze).
     * The threshold must exceed local request latency — it only declares a link stale after real idleness. */
    @Test fun aStaleIdleLinkIsReopened() = runBlocking {
        // The link clock is the test's: real time never ages the link, so a slow handshake cannot trip the
        // stale check, and the idle period is stepped instead of slept.
        val aged = java.util.concurrent.atomic.AtomicLong()
        val timeouts = app.rasul.bridge.BridgeTimeouts(linkStaleMs = 60_000, nanoTime = { System.nanoTime() + aged.get() })
        withBridgeLinks(timeouts = timeouts) { inner, outer, transport, _ ->
            inner.enqueue(MockResponse().setBody("{}"))
            inner.enqueue(MockResponse().setBody("{}"))
            val api = HermesClient(transport.client)
            val target = HermesTarget("", null, "", app.rasul.bridge.BridgeEndpoint(transport.client, "default"))
            assertTrue(api.health(target) is HermesResult.Ok)
            aged.addAndGet(TimeUnit.SECONDS.toNanos(61))
            assertTrue(api.health(target) is HermesResult.Ok)
            assertEquals(2, outer.requestCount)
        }
    }

    /** A link that can never be reopened reports the bridge as unreachable after exactly one retry. */
    @Test fun aRefusedLinkIsBridgeUnreachable() = runBlocking {
        withBridgeLinks(upgrades = 1) { inner, outer, transport, links ->
            inner.enqueue(MockResponse().setBody("{}"))
            val api = HermesClient(transport.client)
            val target = HermesTarget("", null, "", app.rasul.bridge.BridgeEndpoint(transport.client, "default"))
            assertTrue(api.health(target) is HermesResult.Ok)
            assertEquals(1, outer.requestCount)
            assertTrue(links.single().close(1000, null))
            outer.enqueue(MockResponse().setResponseCode(503))
            outer.enqueue(MockResponse().setResponseCode(503))
            val result = kotlinx.coroutines.withTimeout(10_000) { api.health(target) }
            assertEquals(HermesResult.Err(app.rasul.data.HermesError.BridgeUnreachable), result)
            // The dropped link fails attempt 1; exactly one refused re-open proves the single retry ran.
            assertEquals(2, outer.requestCount)
        }
    }

    @Test fun bridgeErrorSplitsLinkTlsRefusedAndTimeout() {
        val err = ::bridgeError
        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped,
            err(java.io.IOException("write", app.rasul.bridge.BridgeLinkDroppedException("gone"))))
        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped,
            err(javax.net.ssl.SSLException("unexpected end of stream")))
        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped,
            err(java.io.EOFException("eof")))
        assertEquals(app.rasul.data.HermesError.BridgeSecure,
            err(app.rasul.bridge.BridgeHandshakeFailure(javax.net.ssl.SSLException("bad_certificate"))))
        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped,
            err(app.rasul.bridge.BridgeHandshakeFailure(java.net.SocketException("reset"))))
        assertEquals(app.rasul.data.HermesError.BridgeSecure,
            err(javax.net.ssl.SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertEquals(app.rasul.data.HermesError.BridgeLinkDropped,
            err(java.net.ProtocolException("unexpected end of stream")))
        assertEquals(app.rasul.data.HermesError.BridgeUnreachable,
            err(java.net.ProtocolException("some other protocol problem")))
        assertEquals(app.rasul.data.HermesError.BridgeTimeout,
            err(java.io.IOException("io", java.net.SocketTimeoutException("t"))))
        assertEquals(app.rasul.data.HermesError.BridgeRefused,
            err(java.io.IOException("No bridge transport available", java.net.ConnectException("refused"))))
        assertEquals(app.rasul.data.HermesError.BridgeRefused, err(java.net.ConnectException("refused")))
        assertEquals(app.rasul.data.HermesError.BridgeAddressUnknown,
            err(java.io.IOException("No bridge transport available", java.net.UnknownHostException("bridge.invalid"))))
        assertEquals(app.rasul.data.HermesError.BridgeAddressUnknown, err(java.net.UnknownHostException("bridge.invalid")))
        assertEquals(app.rasul.data.HermesError.BridgeSecure,
            err(java.io.IOException("ws", javax.net.ssl.SSLPeerUnverifiedException("hostname not verified"))))
        assertEquals(app.rasul.data.HermesError.BridgeSecure,
            err(java.security.cert.CertificateException("certificate expired")))
        assertEquals(app.rasul.data.HermesError.BridgeSecure,
            err(java.io.IOException("tls", java.security.cert.CertPathValidatorException("no path"))))
        assertEquals(app.rasul.data.HermesError.DeviceRevoked,
            err(app.rasul.bridge.BridgeAccessException(app.rasul.data.HermesError.DeviceRevoked)))
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
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            try { pipe.getOutputStream().write(bytes.toByteArray()) } catch (_: java.io.IOException) { }
                        }
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

    /** Same two-server setup as [withSilentBridge], but the server end of each WebSocket link is captured in `links`
     * so a test can close or kill the link underneath the transport, the way a tunnel idle drop does. */
    private suspend fun withBridgeLinks(
        timeouts: app.rasul.bridge.BridgeTimeouts = app.rasul.bridge.BridgeTimeouts(),
        upgrades: Int = 4,
        block: suspend (inner: MockWebServer, outer: MockWebServer, transport: BridgeTransport, links: List<WebSocket>) -> Unit,
    ) {
        val serverKey = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("example.invalid").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(serverKey).addTrustedCertificate(HeldCertificate.Builder().ecdsa256().build().certificate).build()
        val pipes = CopyOnWriteArrayList<Socket>()
        val links = CopyOnWriteArrayList<WebSocket>()
        val workers = Executors.newCachedThreadPool()
        try {
            MockWebServer().use { inner ->
                inner.useHttps(certificates.sslSocketFactory(), false)
                inner.start()
                MockWebServer().use { outer ->
                    repeat(upgrades) { outer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private lateinit var pipe: Socket
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            links.add(webSocket)
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
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            try { pipe.getOutputStream().write(bytes.toByteArray()) } catch (_: java.io.IOException) { }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (::pipe.isInitialized) pipe.close() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (::pipe.isInitialized) pipe.close(); webSocket.cancel() }
                    })) }
                    outer.start()
                    BridgeTransport(listOf(outer.url("/").toString().replace("http:", "ws:")), pin(serverKey), null,
                        newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }, timeouts = timeouts).use {
                        block(inner, outer, it, links)
                    }
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
