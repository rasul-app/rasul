package app.rasul

import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import app.rasul.security.AppLockController
import app.rasul.security.AppLockSettings
import app.rasul.security.AppLockState
import app.rasul.security.appLockBlocksContent
import app.rasul.security.shouldLockOnForeground
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockAndPairingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun credentialConfirmationCancelsWhenActivityIsRecreated() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val original = controller.get()
        val state = androidx.lifecycle.ViewModelProvider(original)[CredentialConfirmation::class.java]
        var sent = 0
        var cancelled = 0
        state.begin({ sent++ }, { cancelled++ })
        controller.configurationChange(Configuration(original.resources.configuration).apply {
            orientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        })
        org.junit.Assert.assertNotSame(original, controller.get())
        val replacement = androidx.lifecycle.ViewModelProvider(controller.get())[CredentialConfirmation::class.java]
        replacement.complete(true)
        assertEquals(0, sent)
        assertEquals(1, cancelled)
        controller.pause().stop().destroy()
    }

    @Test fun appLockTimeoutStartsAtSixtySeconds() {
        assertFalse(shouldLockOnForeground(true, 10_000L, 69_999L))
        assertTrue(shouldLockOnForeground(true, 10_000L, 70_000L))
        assertTrue(shouldLockOnForeground(true, null, 0L))
    }

    @Test fun appLockOffKeepsTheExistingContentPath() {
        assertFalse(appLockBlocksContent(AppLockState(loaded = true, enabled = false, locked = true)))
    }

    @Test fun screenOffLocksImmediatelyAndDisabledLockKeepsContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temp.root.resolve("screen-off.preferences_pb")
        }
        try {
            val settings = AppLockSettings(dataStore)
            val controller = AppLockController(settings, scope)
            val observed = controller.state.first { it.loaded }
            assertTrue(observed.enabled)
            controller.unlock()
            controller.onBackground(10)
            controller.onScreenOff()
            assertTrue(controller.state.value.locked)
            controller.onForeground(20)
            assertTrue(controller.state.value.locked)
            controller.setEnabled(false)
            controller.onScreenOff()
            assertFalse(appLockBlocksContent(controller.state.value))
        } finally {
            scope.cancel()
        }
    }

    @Test fun appLockReadFailureFailsClosedAndRetryReadsTheRecoveredSetting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataStore = RecoverableReadDataStore()
        try {
            val controller = AppLockController(AppLockSettings(dataStore), scope)
            val failed = controller.state.value
            assertTrue(failed.loaded)
            assertTrue(failed.enabled)
            assertTrue(failed.locked)
            assertTrue(failed.readError)
            assertTrue(appLockBlocksContent(failed))

            dataStore.recover()
            controller.retryRead()
            val recovered = controller.state.value
            assertTrue(recovered.enabled)
            assertTrue(recovered.locked)
            assertFalse(recovered.readError)
            assertTrue(appLockBlocksContent(recovered))

            controller.unlock()
            assertFalse(controller.state.value.locked)
            assertFalse(controller.state.value.readError)
        } finally {
            scope.cancel()
        }
    }

    @Test fun appLockReadErrorHasSpecificEnglishAndArabicMessages() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(
            "Could not read app lock settings. Retry or unlock with your device authentication.",
            app.getString(R.string.lock_read_error),
        )
        assertEquals("Set up phone screen lock", app.getString(R.string.lock_open_settings))
        val arabic = Configuration(app.resources.configuration).apply { setLocale(Locale("ar")) }
        assertEquals("إعداد قفل شاشة الهاتف", app.createConfigurationContext(arabic).getString(R.string.lock_open_settings))
        assertEquals(
            "تعذّرت قراءة إعدادات قفل التطبيق. أعد المحاولة أو افتح القفل بمصادقة جهازك.",
            app.createConfigurationContext(arabic).getString(R.string.lock_read_error),
        )
    }

    @Test fun appLockEnableAndDisablePersistInDataStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temp.root.resolve("app-lock.preferences_pb")
        }
        val first = AppLockSettings(dataStore)
        assertTrue(first.enabled.first())
        first.setEnabled(true)
        assertTrue(first.enabled.first())

        val reopened = AppLockSettings(dataStore)
        assertTrue(reopened.enabled.first())
        reopened.setEnabled(false)
        assertFalse(first.enabled.first())
        scope.cancel()
    }

    private class RecoverableReadDataStore : DataStore<Preferences> {
        private var shouldFail = true

        override val data: Flow<Preferences>
            get() = flow {
                if (shouldFail) throw IOException("settings read failed")
                emit(preferencesOf(booleanPreferencesKey("app_lock_enabled_v1") to true))
            }

        fun recover() {
            shouldFail = false
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("Read-only test store")
    }

}
