package app.rasul.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

enum class AppearanceOption {
    System,
    Light,
    Dark,
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

data class AppPreferences(
    val appearance: AppearanceOption = AppearanceOption.System,
    val hapticsEnabled: Boolean = true,
    val approvalAlerts: Boolean = false,
    val jobAlerts: Boolean = false,
    val jobAlertsHintDismissed: Boolean = false,
)

private val APPEARANCE = stringPreferencesKey("appearance_v1")
private val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled_v1")
private val APPROVAL_ALERTS = booleanPreferencesKey("approval_alerts_v1")
private val JOB_ALERTS = booleanPreferencesKey("job_alerts_v1")
private val JOB_ALERTS_HINT_DISMISSED = booleanPreferencesKey("job_alerts_hint_dismissed_v1")

class AppSettings(private val dataStore: DataStore<Preferences>) {
    private val writes = MutableStateFlow(0L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val values: Flow<AppPreferences> = writes.flatMapLatest {
        dataStore.data.map { preferences ->
            AppPreferences(
                appearance = AppearanceOption.entries.firstOrNull { it.name == preferences[APPEARANCE] }
                    ?: AppearanceOption.System,
                hapticsEnabled = preferences[HAPTICS_ENABLED] ?: true,
                approvalAlerts = preferences[APPROVAL_ALERTS] ?: false,
                jobAlerts = preferences[JOB_ALERTS] ?: false,
                jobAlertsHintDismissed = preferences[JOB_ALERTS_HINT_DISMISSED] ?: false,
            )
        }.catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            emit(AppPreferences())
        }
    }

    suspend fun setAppearance(option: AppearanceOption) {
        dataStore.edit { it[APPEARANCE] = option.name }
        writes.update { it + 1 }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[HAPTICS_ENABLED] = enabled }
        writes.update { it + 1 }
    }

    suspend fun setApprovalAlerts(enabled: Boolean) {
        dataStore.edit { it[APPROVAL_ALERTS] = enabled }
        writes.update { it + 1 }
    }

    suspend fun setJobAlerts(enabled: Boolean) {
        dataStore.edit { it[JOB_ALERTS] = enabled }
        writes.update { it + 1 }
    }

    /** The Jobs hint's "Turn on": alerts on, and the hint is done for good (it must not return after turning them off). */
    suspend fun turnOnJobAlerts() {
        dataStore.edit {
            it[JOB_ALERTS] = true
            it[JOB_ALERTS_HINT_DISMISSED] = true
        }
        writes.update { it + 1 }
    }

    suspend fun setJobAlertsHintDismissed(dismissed: Boolean) {
        dataStore.edit { it[JOB_ALERTS_HINT_DISMISSED] = dismissed }
        writes.update { it + 1 }
    }
}
