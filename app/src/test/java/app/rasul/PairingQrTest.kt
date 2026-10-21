package app.rasul

import android.content.res.Configuration
import app.rasul.pairing.PairingConfirmation
import app.rasul.pairing.PairingQrError
import app.rasul.pairing.PairingQrParser
import app.rasul.pairing.PairingQrResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingQrTest {
    private val now = 1_000L
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun payload() = buildJsonObject {
        put("v", 1)
        put("bridge_id", UUID.randomUUID().toString())
        put("name", "Computer")
        putJsonArray("urls") { add(JsonPrimitive("wss://example.invalid/v1/link")) }
        val certificate = HeldCertificate.Builder().ecdsa256().build().certificate
        put("pin", encode(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)))
        put("inv", UUID.randomUUID().toString().replace("-", ""))
        put("sec", encode(ByteArray(32).apply { SecureRandom().nextBytes(this) }))
        put("exp", now + 120)
    }
    private fun qr(json: String) = "rasul-pair:1:" + encode(json.toByteArray())
    private fun parse(json: JsonObject) = PairingQrParser.parse(qr(json.toString()), now)
    private fun change(key: String, value: JsonPrimitive): PairingQrResult = parse(JsonObject(payload() + (key to value)))

    @Test fun validInvitationPreservesThePinnedIdentityAndOrderedUrls() {
        val json = payload()
        val result = parse(json) as PairingQrResult.Valid
        assertEquals("Computer", result.invitation.name)
        assertEquals(listOf("wss://example.invalid/v1/link"), result.invitation.urls)
        assertEquals(32, result.invitation.pin.size)
        assertEquals(now + 120, result.invitation.expiresAt)
        assertFalse(result.toString().contains(result.invitation.secret))
    }

    @Test fun rejectsExpiredVersionHttpAndMissingPinWithSpecificErrors() {
        assertEquals(PairingQrResult.Invalid(PairingQrError.Expired), change("exp", JsonPrimitive(now - 601)))
        assertEquals(PairingQrResult.Invalid(PairingQrError.Version), change("v", JsonPrimitive(2)))
        assertEquals(PairingQrResult.Invalid(PairingQrError.Version), PairingQrParser.parse("rasul-pair:3:any", now))
        assertEquals(PairingQrResult.Invalid(PairingQrError.MissingPin), parse(JsonObject(payload() - "pin")))
        val http = payload().toString().replace("wss://example.invalid/v1/link", "http://example.invalid/v1/link")
        assertEquals(PairingQrResult.Invalid(PairingQrError.InsecureUrl), PairingQrParser.parse(qr(http), now))
    }

    @Test fun clockSkewOfTenMinutesEitherWayIsAccepted() {
        assertTrue(change("exp", JsonPrimitive(now - 600)) is PairingQrResult.Valid)
        assertTrue(change("exp", JsonPrimitive(now)) is PairingQrResult.Valid)
        assertEquals(PairingQrResult.Invalid(PairingQrError.Expired), change("exp", JsonPrimitive(now - 601)))
        assertTrue(change("exp", JsonPrimitive(now + 600)) is PairingQrResult.Valid)
        assertEquals(PairingQrResult.Invalid(PairingQrError.Invalid), change("exp", JsonPrimitive(now + 601)))
    }

    @Test fun rejectsDuplicatesUnknownFieldsAndNoncanonicalNumbersOrBase64() {
        val json = payload().toString()
        val invalid = listOf(
            json.replaceFirst("{", "{\"v\":1,"),
            json.replaceFirst("{", "{\"other\":1,"),
            json.replace("\"v\":1", "\"v\":\"1\""),
            json.replace("\"v\":1", "\"v\":1.0"),
            json + " {}",
            json.replace("\"exp\":1120", "\"exp\":1601"),
        )
        for (value in invalid) assertTrue(PairingQrParser.parse(qr(value), now) is PairingQrResult.Invalid)
        assertTrue(PairingQrParser.parse(qr(json) + "=", now) is PairingQrResult.Invalid)
        assertTrue(change("pin", JsonPrimitive(encode(ByteArray(31)))) is PairingQrResult.Invalid)
    }

    @Test fun comparisonCodeMatchesTheSpecifiedUnsignedDecimalDigest() {
        val server = HeldCertificate.Builder().ecdsa256().build().certificate.publicKey.encoded
        val device = HeldCertificate.Builder().ecdsa256().build().certificate.publicKey.encoded
        val invitation = UUID.randomUUID().toString().replace("-", "")
        val digest = MessageDigest.getInstance("SHA-256").digest(server + device + invitation.toByteArray())
        // Independent base-256 to decimal conversion, without signed BigInteger interpretation.
        var decimal = "0"
        for (byte in digest) {
            var carry = byte.toInt() and 255
            val next = StringBuilder()
            for (digit in decimal.reversed()) {
                val n = (digit - '0') * 256 + carry
                next.append(n % 10)
                carry = n / 10
            }
            while (carry > 0) { next.append(carry % 10); carry /= 10 }
            decimal = next.reverse().toString()
        }
        assertEquals(decimal.padStart(6, '0').take(6), PairingConfirmation.code(server, device, invitation))
    }

    @Test fun everyQrErrorHasItsOwnEnglishAndArabicValue() {
        val app = RuntimeEnvironment.getApplication()
        val arabic = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale("ar")) })
        val command = "\u2066rasul-bridge pair\u2069"
        val expected = listOf(
            "This pairing code is invalid or incomplete. On your PC, run rasul-bridge pair and scan its QR code." to "رمز الإقران غير صالح أو غير مكتمل. شغّل الأمر $command على حاسوبك وامسح رمز QR الجديد.",
            "This pairing code has expired. On your PC, run rasul-bridge pair and scan its new QR code." to "انتهت صلاحية رمز الإقران. شغّل الأمر $command على حاسوبك وامسح رمز QR الجديد.",
            "This pairing code uses an unsupported version." to "إصدار رمز الإقران غير مدعوم.",
            "This pairing code has no server fingerprint. On your PC, run rasul-bridge pair and scan its QR code." to "رمز الإقران بلا بصمة للخادم. شغّل الأمر $command على حاسوبك وامسح رمز QR الجديد.",
            "This pairing code has an invalid PC address. Only WS or WSS addresses are supported." to "عنوان الحاسوب في رمز الإقران غير صالح. يُسمح فقط بعناوين WS أو WSS.",
        )
        PairingQrError.entries.zip(expected).forEach { (error, messages) ->
            assertEquals(messages.first, app.getString(error.message))
            assertEquals(messages.second, arabic.getString(error.message))
        }
    }
}
