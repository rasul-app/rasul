package app.rasul

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.settings.AppPreferences
import app.rasul.settings.AppSettings
import app.rasul.settings.AppearanceOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.test.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSettingsTest {
    @org.junit.After fun restoreWindowMode() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    @Config(sdk = [35], qualifiers = "night")
    fun previewNightModeUsesApplicationDarkPalette() {
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
                app.rasul.ui.theme.RasulTheme(false) {
                    val colors = app.rasul.ui.theme.DarkRasulColors
                    assertEquals(colors, app.rasul.ui.theme.Rasul.colors)
                    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
                    assertEquals(colors.cardBorder, scheme.outline)
                    assertEquals(colors.bg, scheme.surfaceContainer)
                    assertEquals(colors.fill, scheme.surfaceContainerHighest)
                    assertEquals(colors.accentSoft, scheme.secondaryContainer)
                    assertEquals(colors.dangerText, scheme.onErrorContainer)
                    assertEquals(colors.ink, androidx.compose.material3.LocalContentColor.current)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "night")
    fun explicitLightThemeStaysLightOutsidePreview() {
        compose.setContent {
            app.rasul.ui.theme.RasulTheme(false) {
                assertEquals(app.rasul.ui.theme.LightRasulColors, app.rasul.ui.theme.Rasul.colors)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun hapticFeedbackProviderSuppressesOffAndPassesThroughWhenOn() {
        val recorded = mutableListOf<HapticFeedbackType>()
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                recorded += hapticFeedbackType
            }
        }
        var enabled by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                AppHapticFeedbackProvider(enabled = enabled) {
                    val feedback = LocalHapticFeedback.current
                    LaunchedEffect(enabled) {
                        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, recorded.size)
            enabled = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.LongPress), recorded)
        }
    }

    @Test
    fun firstContentUsesStoredDarkAppearanceAndWindowMode() {
        val values = kotlinx.coroutines.flow.MutableSharedFlow<AppPreferences>(replay = 1)
        val colors = mutableListOf<androidx.compose.ui.graphics.Color>()
        compose.setContent {
            AppAppearanceContent(values) {
                val color = app.rasul.ui.theme.Rasul.colors.bg
                androidx.compose.runtime.SideEffect { colors += color }
            }
        }
        compose.runOnIdle { assertEquals(emptyList<androidx.compose.ui.graphics.Color>(), colors) }
        runBlocking { values.emit(AppPreferences(AppearanceOption.Dark, false)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(app.rasul.ui.theme.DarkRasulColors.bg, colors.first())
            assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES, androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode())
        }
    }

    @Test
    fun sheetKeepsDisabledFeedbackFromApplication() {
        var outer: HapticFeedback? = null
        var inner: HapticFeedback? = null
        compose.setContent {
            AppHapticFeedbackProvider(false) {
                outer = LocalHapticFeedback.current
                app.rasul.ui.theme.RasulTheme(false) {
                    app.rasul.ui.components.RasulSheet("Appearance", {}) {
                        inner = LocalHapticFeedback.current
                        androidx.compose.material3.Text("Dark")
                    }
                }
            }
        }
        compose.runOnIdle { org.junit.Assert.assertSame(outer, inner) }
    }

    @Test
    @Config(sdk = [28], qualifiers = "night")
    fun lightAppearanceUsesLightNavigationScrimOnDarkPhone() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        try {
            controller.get().applyAppAppearance(false)
            assertEquals(true, androidx.core.graphics.ColorUtils.calculateLuminance(controller.get().window.navigationBarColor) > 0.5)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun mainActivityAppliesStoredWindowAppearanceAndHaptics() {
        val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<RasulApp>()
        runBlocking {
            application.container.settings.setAppearance(AppearanceOption.Dark)
            application.container.settings.setHapticsEnabled(false)
        }
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        fun views(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            compose.waitUntil(timeoutMillis = 5000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                views(controller.get().window.decorView).any { it.javaClass.simpleName == "AndroidComposeView" && !it.isHapticFeedbackEnabled }
            }
            assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES, androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode())
            val background = controller.get().window.decorView.background as android.graphics.drawable.ColorDrawable
            assertEquals(0xFF111113.toInt(), background.color)
        } finally {
            controller.pause().stop().destroy()
            runBlocking {
                application.container.settings.setAppearance(AppearanceOption.System)
                application.container.settings.setHapticsEnabled(true)
            }
        }
    }

    @Test
    fun applicationContentUsesStoredHapticsSetting() {
        val values = kotlinx.coroutines.flow.flowOf(AppPreferences(AppearanceOption.Light, false))
        var feedback: HapticFeedback? = null
        var view: android.view.View? = null
        compose.setContent {
            AppAppearanceContent(values) {
                feedback = LocalHapticFeedback.current
                view = androidx.compose.ui.platform.LocalView.current
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(false, view?.isHapticFeedbackEnabled)
            org.junit.Assert.assertNotNull(feedback)
        }
    }

    @Test
    fun settingsReadErrorUsesDefaults() = runBlocking {
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> { throw java.io.IOException("unavailable") }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = error("unused")
        }
        assertEquals(AppPreferences(), AppSettings(store).values.first())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun sameCollectorObservesDarkAfterReadErrorAndSuccessfulWrite() = kotlinx.coroutines.test.runTest {
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        var readFails = true
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow {
                if (readFails) throw java.io.IOException()
                snapshots.collect { emit(it) }
            }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                snapshots.value = transform(snapshots.value)
                readFails = false
                return snapshots.value
            }
        }
        val settings = AppSettings(store)
        val observed = mutableListOf<AppPreferences>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            settings.values.collect { observed += it }
        }
        assertEquals(listOf(AppPreferences()), observed)
        settings.setAppearance(AppearanceOption.Dark)
        runCurrent()
        assertEquals(AppPreferences(AppearanceOption.Dark), observed.last())
    }

    @Test fun appearanceWriteFailureShowsMessageAndRetryAppliesCapturedSelection() = assertSettingsWriteRetry(true)

    @Test fun hapticsWriteFailureShowsMessageAndRetryAppliesCapturedSelection() = assertSettingsWriteRetry(false)

    private fun assertSettingsWriteRetry(appearance: Boolean) {
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        var writeFails = true
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = snapshots
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                writes++
                if (writeFails) throw java.io.IOException()
                entered.complete(Unit)
                release.await()
                snapshots.value = transform(snapshots.value)
                return snapshots.value
            }
        }
        val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> uncaught += error })
        val settings = AppSettings(store)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context, settings, scope)
        lateinit var nav: androidx.navigation.NavHostController
        try {
            compose.setContent {
                nav = androidx.navigation.compose.rememberNavController()
                app.rasul.ui.theme.RasulTheme(false) {
                    app.rasul.ui.RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav)
                }
            }
            compose.runOnIdle { nav.navigate("more") }
            if (appearance) {
                compose.onNodeWithText("Appearance").performScrollTo().performClick()
                compose.onNodeWithText("Dark").performClick()
            } else {
                compose.onNodeWithContentDescription("Haptics").performScrollTo().performClick()
            }
            compose.waitForIdle()
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            val message = if (appearance) "Could not save appearance. Retry to apply your selection." else "Could not save haptics. Retry to apply your selection."
            compose.onNodeWithText(message).assertExists()
            assertEquals(AppPreferences(), runBlocking { settings.values.first() })
            compose.runOnIdle { writeFails = false }
            compose.onNodeWithText("Retry").performClick()
            runBlocking { kotlinx.coroutines.withTimeout(5000) { entered.await() } }
            if (appearance) {
                compose.onNodeWithText("Appearance").performScrollTo().performClick()
                compose.onNodeWithText("Light").performClick()
            } else {
                compose.onNodeWithContentDescription("Haptics").performScrollTo().performClick()
            }
            compose.runOnIdle { assertEquals(2, writes); release.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithText(message).assertDoesNotExist()
            assertEquals(if (appearance) AppPreferences(AppearanceOption.Dark) else AppPreferences(hapticsEnabled = false), runBlocking { settings.values.first() })
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally { scope.cancel() }
    }

    @Test fun settingsWriteMessagesAreSpecificInEnglishAndArabic() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("ar")) }
        val arabic = context.createConfigurationContext(configuration)
        assertEquals("Could not save appearance. Retry to apply your selection.", context.getString(R.string.settings_appearance_write_error))
        assertEquals("Could not save haptics. Retry to apply your selection.", context.getString(R.string.settings_haptics_write_error))
        assertEquals("تعذّر حفظ المظهر. أعد المحاولة لتطبيق اختيارك.", arabic.getString(R.string.settings_appearance_write_error))
        assertEquals("تعذّر حفظ الاستجابة اللمسية. أعد المحاولة لتطبيق اختيارك.", arabic.getString(R.string.settings_haptics_write_error))
    }

    @Test
    fun appearanceAndHapticsPreferencesPersist() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temporaryFolder.newFile("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            val settings = AppSettings(store)
            assertEquals(AppPreferences(), settings.values.first())
            settings.setHapticsEnabled(false)
            for (option in AppearanceOption.entries) {
                settings.setAppearance(option)
                assertEquals(AppPreferences(option, false), settings.values.first())
            }
        } finally {
            scope.cancel()
        }
    }
}
