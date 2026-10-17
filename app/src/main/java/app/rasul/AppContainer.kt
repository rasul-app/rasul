package app.rasul

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.rasul.chat.OrderedWriter
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.KeystoreSecretCipher
import app.rasul.security.AppLockController
import app.rasul.security.AppLockSettings
import app.rasul.jobs.JobsClient
import app.rasul.sessions.SessionsClient
import app.rasul.data.RunsClient
import app.rasul.settings.AppSettings
import app.rasul.ui.ActivePcHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.dataStore by preferencesDataStore(name = "rasul")

/** Manual DI: one place that wires the app. */
class AppContainer(
    context: Context,
    val settings: AppSettings = AppSettings(context.dataStore),
    /** Shared by the active-PC holder and ordered run writer. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val client = HermesClient()
    val sessions = SessionsClient(client)
    val jobs = JobsClient(client)
    val connections = ConnectionStore(context.dataStore, KeystoreSecretCipher())

    val bridges = app.rasul.bridge.BridgeStore(context.dataStore, KeystoreSecretCipher())
    val bridgeClients = app.rasul.bridge.BridgeClients(bridges, appScope)
    val appLockSettings = AppLockSettings(context.dataStore)
    val appLock = AppLockController(appLockSettings, appScope)
    val bridgeBots = app.rasul.bridge.BridgeBotsRepository(bridges, bridgeClients)
    val activePc = ActivePcHolder(connections, appScope, bridgeBots)
    val transcriptSources = app.rasul.sessions.TranscriptSources()
    val pendingChat = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val runs = RunsClient(usageStore = connections, scope = appScope)
    val runWatcher = app.rasul.notifications.RunWatcher(
        this, context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /** The single ordered queue for those writes, shared by every chat screen. */
    val runWriter = OrderedWriter(appScope)
}

class RasulApp : Application() {
    val container by lazy { AppContainer(this) }
}
