package app.rasul.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val ENABLED = booleanPreferencesKey("update_check_enabled_v1")
private val LAST_CHECK = longPreferencesKey("update_last_check_v1")
private val SNOOZE_VERSION = stringPreferencesKey("update_snooze_version_v1")
private val SNOOZE_UNTIL = longPreferencesKey("update_snooze_until_v1")

data class Snooze(val version: String, val untilMs: Long)

/** Update-check setting (on by default), last check time and the per-version snooze. */
class UpdatePrefs(private val dataStore: DataStore<Preferences>) {
    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: true }.catch { emit(true) }

    suspend fun enabled(): Boolean = enabledFlow.first()
    suspend fun setEnabled(value: Boolean) { dataStore.edit { it[ENABLED] = value } }

    suspend fun lastCheck(): Long = dataStore.data.map { it[LAST_CHECK] ?: 0L }.catch { emit(0L) }.first()
    suspend fun setLastCheck(ms: Long) { dataStore.edit { it[LAST_CHECK] = ms } }

    suspend fun snooze(): Snooze? = dataStore.data.map { p ->
        val v = p[SNOOZE_VERSION]
        if (v == null) null else Snooze(v, p[SNOOZE_UNTIL] ?: 0L)
    }.catch { emit(null) }.first()

    suspend fun setSnooze(version: String, untilMs: Long) {
        dataStore.edit { it[SNOOZE_VERSION] = version; it[SNOOZE_UNTIL] = untilMs }
    }
}
