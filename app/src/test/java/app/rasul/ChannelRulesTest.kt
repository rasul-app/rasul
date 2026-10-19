package app.rasul

import android.graphics.Color
import app.rasul.bridge.ChannelFieldDto
import app.rasul.bridge.NumbersProblem
import app.rasul.bridge.SettingProblem
import app.rasul.bridge.WhatsappNumbers
import app.rasul.bridge.looksMasked
import app.rasul.bridge.normalizeListSetting
import app.rasul.bridge.parseWhatsappNumbers
import app.rasul.bridge.settingValueProblem
import app.rasul.bridge.settingsDiff
import app.rasul.channels.QR_QUIET_ZONE
import app.rasul.channels.qrBitmap
import app.rasul.channels.qrMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelRulesTest {
    // ---------- WhatsApp numbers ----------

    private fun numbers(text: String) = parseWhatsappNumbers(text)

    @Test fun anEmptyTextMeansNoNumbers() {
        assertEquals(WhatsappNumbers.Ok(emptyList()), numbers(""))
        assertEquals(WhatsappNumbers.Ok(emptyList()), numbers("  ,  "))
    }

    @Test fun numbersAreTrimmedDeduplicatedAndKeepTheirPlus() {
        assertEquals(WhatsappNumbers.Ok(listOf("+10000000000", "10000000001")), numbers(" +10000000000 , 10000000001,+10000000000"))
    }

    @Test fun arabicDigitsAndCommaAreAccepted() {
        assertEquals(WhatsappNumbers.Ok(listOf("+10000000000", "10000000001")), numbers("+١٠٠٠٠٠٠٠٠٠٠،١٠٠٠٠٠٠٠٠٠١"))
    }

    @Test fun badNumbersAreAFormatProblem() {
        listOf("abc", "+", "++1", "1+1", "12 34", "1".repeat(21), "+1-000").forEach {
            assertEquals(it, WhatsappNumbers.Invalid(NumbersProblem.Format), numbers(it))
        }
    }

    @Test fun twentyNumbersPassAndTwentyOneAreTooMany() {
        val twenty = (1..20).joinToString(",") { "1000000000$it" }
        assertEquals(20, (numbers(twenty) as WhatsappNumbers.Ok).numbers.size)
        assertEquals(WhatsappNumbers.Invalid(NumbersProblem.TooMany), numbers("$twenty,10000000099"))
        // repeats do not count twice
        assertEquals(20, (numbers("$twenty,10000000001") as WhatsappNumbers.Ok).numbers.size)
    }

    // ---------- settings values ----------

    @Test fun maskedLookingValuesAreRecognised() {
        listOf("abc****def", "abc…def", "***", " *** ", "«redacted»", "abcd...wxyz", " abcd...wxyz ").forEach {
            assertTrue(it, looksMasked(it))
        }
        listOf("abcd...wxy", "abcde...wxyz", "plain-token_123", "**").forEach { assertFalse(it, looksMasked(it)) }
    }

    @Test fun aMaskedPreviewIsMaskedEvenWhenItHoldsNonAsciiText() {
        assertEquals(SettingProblem.Masked, settingValueProblem("BOT_TOKEN", "abcd…wxyz"))
        assertEquals(SettingProblem.NotAscii, settingValueProblem("BOT_TOKEN", "café"))
    }

    @Test fun valuesAreSentAsTypedWithoutTrimming() {
        val diff = settingsDiff(fields, mapOf("HOME_CHANNEL" to "  x  "), emptySet())
        assertEquals(mapOf("HOME_CHANNEL" to "  x  "), diff.values)
        assertTrue(settingsDiff(fields, mapOf("HOME_CHANNEL" to " 	 "), emptySet()).isEmpty)
    }

    @Test fun valueProblemsAreSpecific() {
        assertNull(settingValueProblem("BOT_TOKEN", "123456:abcDEF-ghi_JKL"))
        assertNull(settingValueProblem("BOT_TOKEN", "a".repeat(4096)))
        assertEquals(SettingProblem.TooLong, settingValueProblem("BOT_TOKEN", "a".repeat(4097)))
        assertEquals(SettingProblem.NotAscii, settingValueProblem("BOT_TOKEN", "café"))
        assertEquals(SettingProblem.NotAscii, settingValueProblem("BOT_TOKEN", "a\nb"))
        assertEquals(SettingProblem.NotAscii, settingValueProblem("BOT_TOKEN", "a\u007fb"))
        assertEquals(SettingProblem.Masked, settingValueProblem("BOT_TOKEN", "abc****"))
        assertEquals(SettingProblem.BadKey, settingValueProblem("bot_token", "x"))
        assertEquals(SettingProblem.BadKey, settingValueProblem("1TOKEN", "x"))
        assertEquals(SettingProblem.BadKey, settingValueProblem("A".repeat(65), "x"))
        assertNull(settingValueProblem("A".repeat(64), "x"))
    }

    // ---------- settings diff ----------

    private val fields = listOf(
        ChannelFieldDto("BOT_TOKEN", required = true, isSet = true),
        ChannelFieldDto("ALLOWED_USERS", isSet = true, isList = true),
        ChannelFieldDto("HOME_CHANNEL"),
    )

    @Test fun onlyTypedAndClearedKeysAreSent() {
        val diff = settingsDiff(fields, mapOf("HOME_CHANNEL" to "  general  ", "BOT_TOKEN" to "   "), setOf("ALLOWED_USERS"))
        assertEquals(mapOf("HOME_CHANNEL" to "  general  "), diff.values)
        assertEquals(listOf("ALLOWED_USERS"), diff.clear)
        assertTrue(diff.sendable)
    }

    @Test fun nothingTypedMeansNothingToSend() {
        val diff = settingsDiff(fields, emptyMap(), emptySet())
        assertTrue(diff.isEmpty)
        assertFalse(diff.sendable)
    }

    @Test fun aClearedKeyWinsOverTypedText() {
        val diff = settingsDiff(fields, mapOf("BOT_TOKEN" to "new-token"), setOf("BOT_TOKEN"))
        assertTrue(diff.values.isEmpty())
        assertEquals(listOf("BOT_TOKEN"), diff.clear)
    }

    @Test fun aMaskedLookingValueIsRefusedAndNothingIsSent() {
        val diff = settingsDiff(fields, mapOf("BOT_TOKEN" to "1234...abcd", "HOME_CHANNEL" to "ok"), emptySet())
        assertEquals(mapOf("BOT_TOKEN" to SettingProblem.Masked), diff.problems)
        assertFalse(diff.sendable)
        assertFalse(diff.toString().contains("1234"))
    }

    @Test fun listFieldsAreNormalised() {
        assertEquals("a,b,c", normalizeListSetting(" a , b،c ,, "))
        val diff = settingsDiff(fields, mapOf("ALLOWED_USERS" to "u1 , u2،u3"), emptySet())
        assertEquals("u1,u2,u3", diff.values["ALLOWED_USERS"])
    }

    @Test fun moreThanEightChangesIsRefused() {
        val many = (1..9).map { ChannelFieldDto("K$it") }
        val diff = settingsDiff(many, many.associate { it.key to "v" }, emptySet())
        assertTrue(diff.tooMany)
        assertFalse(diff.sendable)
        val eight = settingsDiff(many.take(8), many.take(8).associate { it.key to "v" }, emptySet())
        assertFalse(eight.tooMany)
        assertTrue(eight.sendable)
    }

    // ---------- QR ----------

    @Test fun payloadEncodesToAMatrixWithAQuietZone() {
        val matrix = qrMatrix("2@fake-payload,AAAA,BBBB,1")
        assertNotNull(matrix)
        matrix!!
        assertTrue(matrix.width >= 21 + 2 * QR_QUIET_ZONE)
        assertEquals(matrix.width, matrix.height)
        // the quiet zone is empty on every side
        for (i in 0 until matrix.width) for (edge in 0 until QR_QUIET_ZONE) {
            assertFalse(matrix.get(i, edge)); assertFalse(matrix.get(i, matrix.height - 1 - edge))
            assertFalse(matrix.get(edge, i)); assertFalse(matrix.get(matrix.width - 1 - edge, i))
        }
        var dark = 0
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) dark++
        assertTrue(dark > 0)
    }

    @Test fun longestPayloadStillEncodes() {
        assertNotNull(qrMatrix("a".repeat(1024)))
    }

    @Test fun differentPayloadsGiveDifferentMatrices() {
        assertNotEquals(qrMatrix("payload-one")!!.toString(), qrMatrix("payload-two")!!.toString())
    }

    @Test fun bitmapIsBlackOnWhiteAndLargeEnoughToScan() {
        val bitmap = qrBitmap("2@fake-payload,AAAA,BBBB,1")!!
        assertTrue(bitmap.width >= 600)
        assertEquals(bitmap.width, bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
        val colours = HashSet<Int>()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) colours += bitmap.getPixel(x, y)
        assertEquals(setOf(Color.BLACK, Color.WHITE), colours)
    }

    private fun assertNotEquals(a: Any, b: Any) = org.junit.Assert.assertNotEquals(a, b)
}
