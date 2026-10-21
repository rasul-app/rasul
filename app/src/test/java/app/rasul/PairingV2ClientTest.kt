package app.rasul

import app.rasul.bridge.SavedBridge
import app.rasul.pairing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class PairingV2ClientTest {
    private val dispatcher = okhttp3.Dispatcher()
    @org.junit.After fun closeNetworkWorkers() { dispatcher.executorService.shutdownNow() }
    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun body() = PairingRequestV2(id(), "secret-value", "Phone", HeldCertificate.Builder().ecdsa256().build().certificatePem(), "android", "9.9.9")
    private fun http(status: Int, text: String, observe: (okhttp3.Request) -> Unit = {}) = OkHttpClient.Builder().dispatcher(dispatcher)
        .addInterceptor(Interceptor { chain ->
            observe(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("response")
                .body(text.toResponseBody()).build()
        }).build()

    @Test fun v2EnrollPostsTheContractBodyToV2PairAndParsesTheRole() = runBlocking {
        val request = body()
        val device = id()
        val result = PairingClient.postV2(http(200, "{\"device_id\":\"$device\",\"role\":\"owner\",\"later\":1}") {
            assertEquals("/v2/pair", it.url.encodedPath)
            assertEquals("POST", it.method)
            val buffer = okio.Buffer(); it.body!!.writeTo(buffer)
            val fields = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(setOf("inv", "sec", "device_name", "device_cert_pem", "platform", "app_version"), fields.keys)
            assertEquals(request.inv, fields.getValue("inv").jsonPrimitive.content)
            assertEquals("android", fields.getValue("platform").jsonPrimitive.content)
            assertEquals("9.9.9", fields.getValue("app_version").jsonPrimitive.content)
        }, request)
        assertEquals(PairingResultV2(device, "owner"), result)
    }

    @Test fun anUnknownOrMissingRoleIsAViewer() = runBlocking {
        val device = id()
        assertEquals("viewer", PairingClient.postV2(http(200, "{\"device_id\":\"$device\",\"role\":\"admin\"}"), body()).role)
        assertEquals("viewer", PairingClient.postV2(http(200, "{\"device_id\":\"$device\"}"), body()).role)
        assertEquals("viewer", PairingClient.postV2(http(200, "{\"device_id\":\"$device\",\"role\":\"viewer\"}"), body()).role)
    }

    @Test fun v2ErrorsMapByStatusFromTheHostBodies() = runBlocking {
        suspend fun check(status: Int, text: String, failure: PairingFailure) {
            try { PairingClient.postV2(http(status, text), body()); fail("expected failure") }
            catch (e: PairingException) { assertEquals(failure, e.failure) }
        }
        check(403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"Pairing denied\"}}", PairingFailure.V2Refused)
        check(400, "{\"error\":{\"code\":\"invalid\",\"message\":\"missing key\"}}", PairingFailure.V2Invalid)
        check(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"slow down\"}}", PairingFailure.V2RateLimit)
        check(404, "{\"error\":\"Route not found\"}", PairingFailure.Route)
        check(200, "{\"device_id\":\"short\"}", PairingFailure.Response)
        check(200, "{}", PairingFailure.Response)
    }

    @Test fun theV2BodyKeepsEveryFieldWithinTheHostLimits() {
        val invitation = PairingInvitation("host", "PC", emptyList(), ByteArray(32), id(), "secret-value", 1L, 2)
        val request = PairingClient.pairingRequestV2(invitation, "pem", "9".repeat(40))
        assertEquals(32, request.appVersion.length)
        assertEquals("android", request.platform)
        assertTrue(request.deviceName.length <= 64)
        val fields = Json.encodeToJsonElement(PairingRequestV2.serializer(), request).jsonObject
        assertEquals(setOf("inv", "sec", "device_name", "device_cert_pem", "platform", "app_version"), fields.keys)
    }

    @Test fun v2FailureWordingPointsAtThePcWindow() {
        assertEquals(R.string.pair_v2_qr_expired, PairingFailure.Expired.messageFor(2))
        assertEquals(R.string.pair_qr_expired, PairingFailure.Expired.messageFor(1))
        assertEquals(R.string.pair_v2_timeout, PairingFailure.Timeout.messageFor(2))
        assertEquals(R.string.pair_v2_storage, PairingFailure.Storage.messageFor(2))
        assertEquals(R.string.pair_v2_unavailable, PairingFailure.Unavailable.messageFor(2))
        assertEquals(R.string.pair_timeout, PairingFailure.Timeout.messageFor(1))
    }

    @Test fun hostInfoOnACompatibleHostIsV2AndIgnoresUnknownFields() = runBlocking {
        val text = "{\"host_id\":\"h1\",\"name\":\"Computer\",\"agent_version\":\"1.0\",\"protocol\":{\"min\":\"2.0\",\"max\":\"2.3\",\"x\":1}," +
            "\"capabilities\":[\"chat\",\"never-heard-of\"],\"time\":1700000000000,\"extra\":{\"a\":1}}"
        val probe = HostInfoClient.probe(http(200, text) {
            assertEquals("/v2/info", it.url.encodedPath)
            assertEquals("GET", it.method)
        })
        val info = (probe as HostProbe.V2).info
        assertEquals("h1", info.hostId)
        assertEquals(listOf("chat", "never-heard-of"), info.capabilities)
    }

    @Test fun hostInfoMapsOtherAnswers() = runBlocking {
        fun info(min: String, max: String) = "{\"host_id\":\"h1\",\"name\":\"C\",\"agent_version\":\"1\",\"protocol\":{\"min\":\"$min\",\"max\":\"$max\"},\"capabilities\":[],\"time\":1}"
        assertEquals(HostProbe.V1, HostInfoClient.probe(http(404, "{}")))
        assertEquals(HostProbe.Failed, HostInfoClient.probe(http(500, "{}")))
        assertEquals(HostProbe.Failed, HostInfoClient.probe(http(200, "not json")))
        assertEquals(HostProbe.Failed, HostInfoClient.probe(http(200, info("two", "2.0"))))
        assertEquals(HostProbe.Incompatible, HostInfoClient.probe(http(200, info("3.0", "3.1"))))
        assertEquals(HostProbe.Incompatible, HostInfoClient.probe(http(200, info("1.0", "1.9"))))
        assertTrue(HostInfoClient.probe(http(200, info("1.9", "2.0"))) is HostProbe.V2)
        assertTrue(HostInfoClient.probe(http(200, info("2.0", "10.0"))) is HostProbe.V2)
        val dropped = OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor(Interceptor { throw IOException("down") }).build()
        assertEquals(HostProbe.Failed, HostInfoClient.probe(dropped))
    }

    @Test fun oldSavedJsonWithoutProtocolOrRoleReadsAsProtocolOne() {
        // Written by the code before these fields existed (SavedBridge at the base commit).
        val saved = "{\"bridgeId\":\"b1\",\"name\":\"PC\",\"urls\":[\"wss://example.invalid\"],\"pin\":\"p\",\"deviceId\":\"d\",\"keyAlias\":\"k\",\"lastWorkingUrl\":\"wss://example.invalid\"}"
        val row = Json.decodeFromString<SavedBridge>(saved)
        assertEquals(1, row.protocol)
        assertNull(row.role)
        assertEquals("b1", row.bridgeId)
    }
}
