package app.rasul

import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.BridgeGroup
import app.rasul.bridge.BridgeProfile
import app.rasul.data.HermesError
import app.rasul.data.Load
import app.rasul.settings.PairedBridgeRow
import app.rasul.settings.MoreMode
import app.rasul.settings.moreMode
import app.rasul.ui.PcBridgeNotice
import app.rasul.ui.PcPicker
import app.rasul.ui.pendingLoad
import app.rasul.settings.pairedBridgeRows
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreBridgeStateTest {
    @Test fun moreIsAvailableAfterLocalReadWhileBridgeProfilesAreLoading() {
        val picker = PcPicker(loaded = true, targetLoaded = false)

        assertEquals(MoreMode.Content, moreMode(picker))
        assertEquals(Load.Loading, picker.pendingLoad())
    }

    @Test fun pairedBridgeStatesHaveNamedLoadsAndDoNotFallThroughToNoConnection() {
        val unreachable = PcPicker(
            loaded = true,
            targetLoaded = true,
            bridgeNotice = PcBridgeNotice.Unreachable("Studio PC"),
        )
        val noBots = PcPicker(
            loaded = true,
            targetLoaded = true,
            bridgeNotice = PcBridgeNotice.NoReadyBots("Studio PC"),
        )

        assertEquals(Load.BridgeUnreachable("Studio PC"), unreachable.pendingLoad())
        assertEquals(Load.BridgeNoReadyBots("Studio PC"), noBots.pendingLoad())
    }

    @Test fun morePcsIncludesPairedBridgesAndOnlyShowsKnownBotCounts() {
        val state = BridgeBotsState(
            groups = listOf(
                BridgeGroup("loading", "Loading PC"),
                BridgeGroup("ready", "Ready PC", listOf(BridgeProfile("default", "default", "Default", true, true)), loading = false),
                BridgeGroup("offline", "Offline PC", listOf(BridgeProfile("cached", "cached", "Cached", true, false)), loading = false, error = HermesError.BridgeUnreachable),
            ),
            loaded = true,
        )

        assertEquals(
            listOf(
                PairedBridgeRow("loading", "Loading PC", null),
                PairedBridgeRow("ready", "Ready PC", 1),
                PairedBridgeRow("offline", "Offline PC", null),
            ),
            pairedBridgeRows(state),
        )
    }
}
