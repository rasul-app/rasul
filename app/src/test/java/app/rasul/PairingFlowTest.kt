package app.rasul

import app.rasul.pairing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PairingFlowTest {
    private val dispatcher = okhttp3.Dispatcher()
    @org.junit.After fun closeNetworkWorkers() { dispatcher.executorService.shutdownNow() }
    private fun body() = PairingRequest(UUID.randomUUID().toString().replace("-", ""),
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }),
        "Phone", HeldCertificate.Builder().ecdsa256().build().certificatePem())
    private fun http(status: Int, text: String, observe: (okhttp3.Request) -> Unit = {}) = OkHttpClient.Builder().dispatcher(dispatcher)
        .addInterceptor(Interceptor { chain ->
            observe(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("response")
                .body(text.toResponseBody()).build()
        }).build()

    @Test fun requestFieldsAndSuccessfulResponseMatchEnrollment() = runBlocking {
        val request = body()
        val id = UUID.randomUUID().toString().replace("-", "")
        val result = PairingClient.post(http(200, "{\"device_id\":\"$id\"}") {
            assertEquals("/v1/pair", it.url.encodedPath)
            assertEquals("POST", it.method)
            val buffer = okio.Buffer(); it.body!!.writeTo(buffer)
            val fields = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(setOf("inv", "sec", "device_name", "device_cert_pem"), fields.keys)
            assertEquals(request.inv, fields.getValue("inv").jsonPrimitive.content)
            assertEquals(request.sec, fields.getValue("sec").jsonPrimitive.content)
            assertEquals(request.deviceName, fields.getValue("device_name").jsonPrimitive.content)
            assertEquals(request.deviceCertPem, fields.getValue("device_cert_pem").jsonPrimitive.content)
        }, request)
        assertEquals(id, result)
    }
    @Test fun deniedExpiredRateLimitAndMalformedResponsesAreSpecific() = runBlocking {
        suspend fun check(status: Int, text: String, expired: Boolean, failure: PairingFailure) {
            try { PairingClient.post(http(status, text), body()) { expired }; fail("expected failure") }
            catch (e: PairingException) { assertEquals(failure, e.failure) }
        }
        check(403, "{\"error\":\"Pairing denied\"}", false, PairingFailure.Denied)
        check(403, "{\"error\":\"Pairing denied\"}", true, PairingFailure.Expired)
        check(429, "{\"error\":\"Pairing rate limit\"}", false, PairingFailure.RateLimit)
        check(400, "{\"error\":\"Invalid pairing request\"}", false, PairingFailure.Invalid)
        check(200, "{}", false, PairingFailure.Response)
    }
    @Test fun expiredInvitationNeverCreatesATransportOrReadsTheDeviceIdentity() = runBlocking {
        var opened = false
        val service = PairingClient(transport = { _, _ -> opened = true; error("not expected") }, now = { 100 + 601 })
        val body = body()
        val invitation = PairingInvitation(UUID.randomUUID().toString(), "Bridge", emptyList(), ByteArray(32), body.inv, body.sec, 100)
        val identity = object : app.rasul.bridge.DeviceIdentity {
            override val privateKey: java.security.PrivateKey get() = error("not expected")
            override val certificateChain: Array<java.security.cert.X509Certificate> get() = error("not expected")
        }
        try { service.enroll(invitation, identity) {}; fail("expected expiry") }
        catch (e: PairingException) { assertEquals(PairingFailure.Expired, e.failure) }
        assertFalse(opened)
    }
    @Test fun innerHttpErrorsHaveSpecificResults() = runBlocking {
        val cases = listOf(
            Triple(431, "Headers too large", PairingFailure.HeaderSize),
            Triple(400, "Invalid header", PairingFailure.Header),
            Triple(400, "Duplicate header", PairingFailure.DuplicateHeader),
            Triple(400, "Invalid request", PairingFailure.Request),
            Triple(400, "Unsupported framing", PairingFailure.Framing),
            Triple(400, "Invalid content length", PairingFailure.Length),
            Triple(413, "Request body too large", PairingFailure.BodySize),
            Triple(403, "Registered device certificate required", PairingFailure.DeviceRequired),
            Triple(404, "Route not found", PairingFailure.Route),
            Triple(502, "Upstream unavailable", PairingFailure.Unavailable),
        )
        for ((status, token, expected) in cases) {
            try { PairingClient.post(http(status, "{\"error\":\"$token\"}"), body()); fail("expected error") }
            catch (e: PairingException) { assertEquals(expected, e.failure) }
        }
    }

}
