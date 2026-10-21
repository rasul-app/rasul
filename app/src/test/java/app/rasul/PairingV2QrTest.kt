package app.rasul

import app.rasul.pairing.PairingQrError
import app.rasul.pairing.PairingQrParser
import app.rasul.pairing.PairingQrResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingV2QrTest {
    private val now = 1_000L
    private val urls = listOf("ws://192.0.2.10:8443", "wss://example.invalid", "wss://tunnel.example.invalid/v1/link")
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun random() = encode(ByteArray(32).apply { SecureRandom().nextBytes(this) })
    private fun payload(list: List<String> = urls) = buildJsonObject {
        put("v", 2)
        put("host_id", "host-" + UUID.randomUUID())
        put("name", "Computer")
        putJsonArray("urls") { list.forEach { add(JsonPrimitive(it)) } }
        put("pin", random())
        put("inv", UUID.randomUUID().toString().replace("-", ""))
        put("sec", random())
        put("exp", now + 120)
    }
    private fun parse(json: JsonObject, prefix: String = "rasul-pair:2:") =
        PairingQrParser.parse(prefix + encode(json.toString().toByteArray()), now)
    private fun invalid(error: PairingQrError) = PairingQrResult.Invalid(error)

    @Test fun validV2CodeCarriesProtocolTwoHostIdAndTheGivenUrlOrder() {
        val json = payload()
        val result = parse(json) as PairingQrResult.Valid
        assertEquals(2, result.invitation.protocol)
        assertEquals(json.getValue("host_id").let { (it as JsonPrimitive).content }, result.invitation.bridgeId)
        assertEquals(urls, result.invitation.urls)
        assertEquals(now + 120, result.invitation.expiresAt)
        assertFalse(result.toString().contains(result.invitation.secret))
    }

    @Test fun v1CodeStaysProtocolOne() {
        val v1 = JsonObject(payload() - "host_id" + ("bridge_id" to JsonPrimitive("bridge-1")) + ("v" to JsonPrimitive(1)))
        val result = parse(v1, "rasul-pair:1:") as PairingQrResult.Valid
        assertEquals(1, result.invitation.protocol)
        assertEquals("bridge-1", result.invitation.bridgeId)
    }

    @Test fun v2CodeWithTheV1KeySetOrWrongKeysIsInvalid() {
        val v1Keys = JsonObject(payload() - "host_id" + ("bridge_id" to JsonPrimitive("bridge-1")))
        assertEquals(invalid(PairingQrError.Invalid), parse(v1Keys))
        assertEquals(invalid(PairingQrError.Invalid), parse(JsonObject(payload() - "host_id")))
        assertEquals(invalid(PairingQrError.Invalid), parse(JsonObject(payload() - "name")))
        assertEquals(invalid(PairingQrError.Invalid), parse(JsonObject(payload() + ("extra" to JsonPrimitive("x")))))
        assertEquals(invalid(PairingQrError.Invalid), parse(JsonObject(payload() + ("bridge_id" to JsonPrimitive("bridge-1")))))
        assertEquals(invalid(PairingQrError.MissingPin), parse(JsonObject(payload() - "pin")))
        assertEquals(invalid(PairingQrError.Invalid), parse(JsonObject(payload() + ("host_id" to JsonPrimitive("bad id!")))))
    }

    @Test fun v2CodeWithAnotherVersionNumberIsAVersionError() {
        assertEquals(invalid(PairingQrError.Version), parse(JsonObject(payload() + ("v" to JsonPrimitive(1)))))
        assertEquals(invalid(PairingQrError.Version), parse(JsonObject(payload() + ("v" to JsonPrimitive(3)))))
    }

    @Test fun v2CodeWithBadUrlsIsRejectedLikeV1() {
        assertEquals(invalid(PairingQrError.InsecureUrl), parse(payload(listOf("http://example.invalid"))))
        assertEquals(invalid(PairingQrError.InsecureUrl), parse(payload(emptyList())))
        assertEquals(invalid(PairingQrError.Invalid), parse(payload(listOf("wss://example.invalid", "wss://example.invalid"))))
    }

    @Test fun expiredV2CodeIsExpired() {
        assertEquals(invalid(PairingQrError.Expired), parse(JsonObject(payload() + ("exp" to JsonPrimitive(now - 601)))))
    }

    @Test fun v2ClockSkewOfTenMinutesEitherWayIsAccepted() {
        fun at(exp: Long) = parse(JsonObject(payload() + ("exp" to JsonPrimitive(exp))))
        assertTrue(at(now - 600) is PairingQrResult.Valid)
        assertEquals(invalid(PairingQrError.Expired), at(now - 601))
        assertTrue(at(now + 600) is PairingQrResult.Valid)
        assertEquals(invalid(PairingQrError.Invalid), at(now + 601))
    }

    private val hostVector = "rasul-pair:2:eyJ2IjoyLCJob3N0X2lkIjoiaG9zdC1hIiwibmFtZSI6Im15LXBjIiwidXJscyI6WyJ3czovLzE5Mi4wLjIuNTo5L3YxL2xpbmsiXSwicGluIjoiSUNFaUl5UWxKaWNvS1NvckxDMHVMekF4TWpNME5UWTNPRGs2T3p3OVBqOCIsImludiI6IjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmIiwic2VjIjoiQUFFQ0F3UUZCZ2NJQ1FvTERBME9EeEFSRWhNVUZSWVhHQmthR3h3ZEhoOCIsImV4cCI6MTcwMDAwMDAwMH0"

    @Test fun theHostVectorParsesToTheExpectedInvitation() {
        val result = PairingQrParser.parse(hostVector, 1_700_000_000L - 60) as PairingQrResult.Valid
        val invitation = result.invitation
        assertEquals("host-a", invitation.bridgeId)
        assertEquals("my-pc", invitation.name)
        assertEquals(2, invitation.protocol)
        assertEquals(listOf("ws://192.0.2.5:9/v1/link"), invitation.urls)
        assertEquals("0123456789abcdef0123456789abcdef", invitation.invitation)
        assertEquals(1_700_000_000L, invitation.expiresAt)
        assertArrayEquals(ByteArray(32) { (0x20 + it).toByte() }, invitation.pin)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(invitation.secret))
        assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd", digest.joinToString("") { "%02x".format(it) })
    }

    @Test fun theHostVectorIsAcceptedFromTwoMinutesBeforeIssueOnward() {
        assertTrue(PairingQrParser.parse(hostVector, 1_700_000_000L - 121) is PairingQrResult.Valid)
    }

    @Test fun comparisonCodesMatchTheHost() {
        assertEquals("120928", app.rasul.pairing.PairingConfirmation.code("server-spki-a".toByteArray(), "device-spki-a".toByteArray(), "0123456789abcdef0123456789abcdef"))
        assertEquals("167615", app.rasul.pairing.PairingConfirmation.code("server-spki-b".toByteArray(), "device-spki-b".toByteArray(), "ffffffffffffffffffffffffffffffff"))
        assertEquals("634621", app.rasul.pairing.PairingConfirmation.code(byteArrayOf(0), byteArrayOf(0), "00000000000000000000000000000000"))
    }

    @Test fun aV2CodeFailureUsesTheWordingForThePcWindow() {
        assertEquals(R.string.pair_v2_qr_expired, PairingQrError.Expired.messageFor(2))
        assertEquals(R.string.pair_qr_expired, PairingQrError.Expired.messageFor(1))
        assertEquals(R.string.pair_v2_qr_invalid, PairingQrError.Invalid.messageFor(2))
        assertEquals(R.string.pair_v2_qr_missing_pin, PairingQrError.MissingPin.messageFor(2))
    }

    @Test fun anUnknownFuturePrefixIsAVersionError() {
        assertEquals(invalid(PairingQrError.Version), PairingQrParser.parse("rasul-pair:3:" + encode(payload().toString().toByteArray()), now))
    }
}
