package app.rasul

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.rasul.settings.LanguageOption
import app.rasul.settings.MoreContent
import app.rasul.ui.theme.RasulTheme
import app.rasul.update.ReleaseInfo
import app.rasul.update.SemVer
import app.rasul.update.UpdateError
import app.rasul.update.UpdatePhase
import app.rasul.update.UpdateSettingsSheet
import app.rasul.update.UpdateSheet
import app.rasul.update.UpdateState
import app.rasul.update.messageArg
import app.rasul.update.messageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()

    private val release = ReleaseInfo(SemVer(0, 2, 6), "## New\n- one [thing](https://example.invalid) done", "", "")

    private fun localized(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    @Test fun everyErrorHasItsOwnSpecificMessageInBothLanguages() {
        val errors = listOf(
            UpdateError.Offline, UpdateError.RateLimited, UpdateError.Http(502), UpdateError.BadResponse,
            UpdateError.NoAsset("0.2.6"), UpdateError.BadChecksumFile, UpdateError.ChecksumMismatch,
            UpdateError.SignerMismatch, UpdateError.SignerUnreadable, UpdateError.TooLarge,
            UpdateError.InstallBlocked, UpdateError.InstallFailed("status 5"),
            UpdateError.WrongPackage, UpdateError.NotNewer, UpdateError.InstallNotConfirmed,
        )
        for (tag in listOf("en", "ar")) {
            val context = localized(tag)
            val texts = errors.map { e ->
                val arg = e.messageArg()
                if (arg == null) context.getString(e.messageRes()) else context.getString(e.messageRes(), arg)
            }
            texts.forEach { assertTrue(it.isNotBlank()) }
            assertEquals("$tag messages must all differ", errors.size, texts.toSet().size)
            assertTrue(texts[2].contains("502") || texts[2].contains("٥٠٢"))
            assertTrue(texts[4].contains("0.2.6"))
            assertFalse(texts.any { it.contains("went wrong", ignoreCase = true) })
        }
        assertTrue(localized("ar").getString(R.string.update_err_signer_mismatch).any { it in '؀'..'ۿ' })
    }

    @Test fun offerShowsNotesAsPlainTextAndButtonsWork() {
        var updated: ReleaseInfo? = null
        var later = 0
        compose.setContent {
            RasulTheme(false) { UpdateSheet(UpdateState.Available(release, true), { updated = it }, { later++ }, {}, {}, {}) }
        }
        compose.onNodeWithText("Rasul 0.2.6 is available").assertExists()
        compose.onNodeWithText("New").assertExists()
        compose.onNodeWithText("•  one thing done").assertExists()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update").performClick()
        assertEquals(1, later)
        assertEquals(release, updated)
    }

    @Test fun progressSheetOffersCancelUntilInstalling() {
        var cancelled = 0
        compose.setContent {
            RasulTheme(false) {
                UpdateSheet(UpdateState.Working(release, UpdatePhase.Downloading, 0.4f), {}, {}, { cancelled++ }, {}, {})
            }
        }
        compose.onNodeWithText("Downloading… 40%").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun installingStateStillOffersCancel() {
        var cancelled = 0
        compose.setContent {
            RasulTheme(false) {
                UpdateSheet(UpdateState.Working(release, UpdatePhase.Installing, null), {}, {}, { cancelled++ }, {}, {})
            }
        }
        compose.onNodeWithText("Opening the installer…").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun blockedInstallPointsToTheSettingAndOtherFailuresRetry() {
        var opened = 0
        var retried: ReleaseInfo? = null
        compose.setContent {
            RasulTheme(false) {
                UpdateSheet(UpdateState.UpdateFailed(release, UpdateError.InstallBlocked), { retried = it }, {}, {}, {}, { opened++ })
            }
        }
        compose.onNodeWithText("Open setting").performClick()
        assertEquals(1, opened)
        assertEquals(null, retried)
    }

    @Test fun failureOtherThanBlockedOffersTryAgain() {
        var retried: ReleaseInfo? = null
        compose.setContent {
            RasulTheme(false) {
                UpdateSheet(UpdateState.UpdateFailed(release, UpdateError.SignerMismatch), { retried = it }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(release, retried)
    }

    @Test fun settingsSheetDisablesCheckNowWhenOff() {
        compose.setContent { RasulTheme(false) { UpdateSettingsSheet(false, UpdateState.Idle, {}, {}, {}, {}) } }
        compose.onNodeWithText("Update checks are off").assertExists()
        compose.onNodeWithText("Check now").assertIsNotEnabled()
    }

    @Test fun settingsSheetCheckNowRunsWhenOn() {
        var checks = 0
        compose.setContent { RasulTheme(false) { UpdateSettingsSheet(true, UpdateState.UpToDate, {}, { checks++ }, {}, {}) } }
        compose.onNodeWithText("Check now").assertIsEnabled().performClick()
        assertEquals(1, checks)
    }

    @Test fun settingsSheetOffersViewWhenAReleaseIsWaiting() {
        var viewed = 0
        compose.setContent { RasulTheme(false) { UpdateSettingsSheet(true, UpdateState.Available(release, false), {}, {}, { viewed++ }, {}) } }
        compose.onNodeWithText("Rasul 0.2.6 is available").assertExists()
        compose.onNodeWithText("View").performClick()
        assertEquals(1, viewed)
    }

    @Test fun moreShowsTheOneRowOnlyWhenEnabled() {
        var opened = 0
        compose.setContent {
            RasulTheme(false) {
                MoreContent(emptyList(), null, LanguageOption.System, {}, {}, {}, showAppUpdates = true, updateSummary = "Rasul is up to date", onAppUpdates = { opened++ })
            }
        }
        compose.onNodeWithText("App updates").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun moreWithoutTheFlagIsUnchanged() {
        compose.setContent { RasulTheme(false) { MoreContent(emptyList(), null, LanguageOption.System, {}, {}, {}) } }
        compose.onNodeWithText("App updates").assertDoesNotExist()
    }
}
