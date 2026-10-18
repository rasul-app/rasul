package app.rasul.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled_v1")

class AppLockSettings(private val dataStore: DataStore<Preferences>) {
    val enabled = dataStore.data.map { it[APP_LOCK_ENABLED] ?: true }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[APP_LOCK_ENABLED] = value }
    }
}

data class AppLockState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val locked: Boolean = true,
    val readError: Boolean = false,
)

const val APP_LOCK_BACKGROUND_TIMEOUT_MS = 60_000L

fun shouldLockOnForeground(enabled: Boolean, backgroundedAtMs: Long?, foregroundAtMs: Long): Boolean {
    if (!enabled) return false
    if (backgroundedAtMs == null) return true
    return foregroundAtMs - backgroundedAtMs >= APP_LOCK_BACKGROUND_TIMEOUT_MS
}

fun appLockBlocksContent(state: AppLockState): Boolean = state.enabled && state.locked

/** Notifications show only their public text while the lock is on or its state is not yet read. */
fun AppLockState.publicOnly(): Boolean = !loaded || enabled

class AppLockController(private val settings: AppLockSettings, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AppLockState())
    val state: StateFlow<AppLockState> = _state.asStateFlow()
    private var backgroundedAtMs: Long? = null

    init {
        retryRead()
    }

    fun retryRead() {
        scope.launch {
            settings.enabled.catch {
                _state.update { current ->
                    current.copy(loaded = true, enabled = true, locked = true, readError = true)
                }
            }.collect { enabled ->
                _state.update { current ->
                    current.copy(
                        loaded = true,
                        enabled = enabled,
                        locked = if (!current.loaded) enabled else if (enabled) current.locked else false,
                        readError = false,
                    )
                }
            }
        }
    }

    fun onBackground(elapsedRealtimeMs: Long) {
        backgroundedAtMs = elapsedRealtimeMs
    }

    fun onForeground(elapsedRealtimeMs: Long) {
        val shouldLock = shouldLockOnForeground(_state.value.enabled, backgroundedAtMs, elapsedRealtimeMs)
        backgroundedAtMs = null
        if (shouldLock) _state.update { it.copy(locked = true) }
    }

    fun onScreenOff() {
        if (_state.value.enabled) _state.update { it.copy(locked = true) }
    }

    fun unlock() {
        _state.update { if (it.loaded && it.enabled) it.copy(locked = false) else it }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        _state.update { current ->
            current.copy(
                loaded = true,
                enabled = enabled,
                locked = if (enabled) current.locked else false,
                readError = false,
            )
        }
    }
}
