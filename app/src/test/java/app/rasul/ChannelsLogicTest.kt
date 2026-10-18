package app.rasul

import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelState
import app.rasul.channels.canConnectTelegram
import app.rasul.channels.channelChip
import app.rasul.channels.parseUserIds
import app.rasul.ui.components.ChipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsLogicTest {
    // ---------- allowed user ids ----------

    @Test fun oneToTwentyNumericIdsAreAccepted() {
        assertEquals(listOf("123456789"), parseUserIds("123456789"))
        assertEquals(listOf("1", "22", "333"), parseUserIds("1, 22 ,333"))
        assertEquals(listOf("5"), parseUserIds(" 5, "))
        assertEquals(listOf("1".repeat(20)), parseUserIds("1".repeat(20)))
        assertEquals((1..20).map { it.toString() }, parseUserIds((1..20).joinToString(",")))
    }

    @Test fun repeatsAreDroppedKeepingOrder() {
        assertEquals(listOf("7", "8"), parseUserIds("7,8,7,8"))
    }

    @Test fun anythingThatIsNotDigitsIsRefused() {
        listOf("", "   ", ",", ",,", "abc", "12a", "-5", "1.5", "1 2", "+5", "@name", "0x10", "1;2").forEach {
            assertNull("should be refused: '$it'", parseUserIds(it))
        }
    }

    @Test fun limitsAreEnforced() {
        assertNull(parseUserIds("1".repeat(21)))
        assertNull(parseUserIds((1..21).joinToString(",")))
    }

    @Test fun arabicKeyboardDigitsAndCommaAreAcceptedAsAsciiDigits() {
        assertEquals(listOf("123", "45"), parseUserIds("١٢٣، ٤٥"))
        assertEquals(listOf("98"), parseUserIds("۹۸"))
    }

    // ---------- chips ----------

    @Test fun knownStatesGetTheirOwnChip() {
        val expected = mapOf(
            ChannelState.CONNECTED to (R.string.chan_chip_connected to ChipKind.Done),
            ChannelState.DISABLED to (R.string.chan_chip_off to ChipKind.Neutral),
            ChannelState.NOT_CONFIGURED to (R.string.chan_chip_needs_setup to ChipKind.Neutral),
            ChannelState.PENDING_RESTART to (R.string.chan_chip_restart to ChipKind.Running),
            ChannelState.GATEWAY_STOPPED to (R.string.chan_chip_stopped to ChipKind.Neutral),
            ChannelState.STARTUP_FAILED to (R.string.chan_chip_failed to ChipKind.Failed),
        )
        expected.forEach { (state, pair) ->
            val chip = channelChip(state)
            assertEquals(pair.first, chip.label)
            assertEquals(pair.second, chip.kind)
            assertNull(chip.raw)
        }
    }

    @Test fun anUnknownStateIsShownAsSent() {
        val chip = channelChip("reconnecting")
        assertNull(chip.label)
        assertEquals("reconnecting", chip.raw)
        assertEquals(R.string.chan_chip_unknown, channelChip(null).label)
        assertEquals(R.string.chan_chip_unknown, channelChip("").label)
    }

    // ---------- the Telegram entry ----------

    @Test fun connectTelegramIsOfferedOnlyForAnUnconfiguredTelegram() {
        assertTrue(ChannelDto("telegram", "Telegram", configured = false).canConnectTelegram())
        assertFalse(ChannelDto("telegram", "Telegram", configured = true).canConnectTelegram())
        assertFalse(ChannelDto("discord", "Discord", configured = false).canConnectTelegram())
    }
}
