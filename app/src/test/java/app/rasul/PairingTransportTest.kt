package app.rasul

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.DeviceIdentity
import app.rasul.bridge.SpkiTrustManager
import app.rasul.bridge.WebSocketSocketFactory
import app.rasul.data.HermesClient
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
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

class PairingTransportTest {
    private fun pin(certificate: HeldCertificate) = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.publicKey.encoded)
    @Test fun anonymousEnrollmentUsesPinnedTlsAndShowsTheMatchingCode() = runBlocking {
        val serverKey = HeldCertificate.Builder().ecdsa256().addSubjectAlternativeName("example.invalid").build()
        val deviceKey = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = deviceKey.keyPair.private
            override val certificateChain = arrayOf(deviceKey.certificate)
        }
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(serverKey)
            .addTrustedCertificate(deviceKey.certificate).build()
        val deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
        val invitationId = java.util.UUID.randomUUID().toString().replace("-", "")
        val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val shownCode = java.util.concurrent.atomic.AtomicReference<String>()
        val pipes = CopyOnWriteArrayList<Socket>()
        val workers = Executors.newCachedThreadPool()
        try {
            MockWebServer().use { inner ->
                inner.useHttps(serverCertificates.sslSocketFactory(), false)
                inner.requestClientAuth()
                inner.enqueue(MockResponse().setBody("{\"device_id\":\"$deviceId\"}"))
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
                    val invitation = app.rasul.pairing.PairingInvitation(
                        java.util.UUID.randomUUID().toString(), "Bridge",
                        listOf(outer.url("/").toString().replace("http:", "ws:")), pin(serverKey), invitationId, secret,
                        System.currentTimeMillis() / 1000 + 120,
                    )
                    val service = app.rasul.pairing.PairingClient(transport = { value, verified ->
                        BridgeTransport(value.urls, value.pin, null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }, onServerSpki = verified)
                    })
                    val enrollment = service.enroll(invitation, identity) { shownCode.set(it) }
                    assertEquals(deviceId, enrollment.deviceId)
                    assertEquals(invitation.urls.single(), enrollment.lastWorkingUrl)
                    assertEquals(app.rasul.pairing.PairingConfirmation.code(serverKey.certificate.publicKey.encoded,
                        deviceKey.certificate.publicKey.encoded, invitationId), shownCode.get())
                    val request = inner.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("/v1/pair", request.path)
                    assertEquals("POST", request.method)
                    assertNull(request.getHeader("Authorization"))
                    assertEquals("TLSv1.3", request.handshake!!.tlsVersion.javaName)
                    assertTrue(request.handshake!!.peerCertificates.isEmpty())
                    val wire = kotlinx.serialization.json.Json.decodeFromString<app.rasul.pairing.PairingRequest>(request.body.readUtf8())
                    assertEquals(invitationId, wire.inv)
                    assertEquals(secret, wire.sec)
                    assertEquals("Phone", wire.deviceName)
                    assertEquals(deviceKey.certificatePem(), wire.deviceCertPem)

                }
            }
        } finally {
            pipes.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }


    @Test fun enrollmentNeverTouchesTheTransportOnTheCallingThread() {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "ui-thread") }
        val used = java.util.concurrent.atomic.AtomicReference<String>()
        val key = HeldCertificate.Builder().ecdsa256().build()
        val identity = object : DeviceIdentity {
            override val privateKey = key.keyPair.private
            override val certificateChain = arrayOf(key.certificate)
        }
        val invitation = app.rasul.pairing.PairingInvitation("b", "PC", listOf("wss://example.invalid/v1/link"), ByteArray(32), "i", "s",
            System.currentTimeMillis() / 1000 + 60)
        val client = app.rasul.pairing.PairingClient(transport = { _, _ ->
            used.set(Thread.currentThread().name); throw java.io.IOException("no network in this test")
        })
        try {
            runBlocking(caller.asCoroutineDispatcher()) { runCatching { client.enroll(invitation, identity) {} } }
        } finally { caller.shutdown() }
        assertNotNull(used.get())
        assertNotEquals("ui-thread", used.get())
    }
}
