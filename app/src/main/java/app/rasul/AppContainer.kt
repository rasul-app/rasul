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

/** Drafts and pins live in their own file: up to 200 drafts of 20 000 characters must never slow a run breadcrumb write. */
private val Context.chatExtrasDataStore by preferencesDataStore(name = "rasul_chat_extras")

/** Manual DI: one place that wires the app. */
class AppContainer(
    context: Context,
    val settings: AppSettings = AppSettings(context.dataStore),
    /** Shared by the active-PC holder and ordered run writer. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val client = HermesClient()
    val sessions = SessionsClient(client)
    val sessionControl = app.rasul.sessions.SessionControlClient(client)
    val jobs: app.rasul.jobs.JobsApi = JobsClient(client)
    val drafts = app.rasul.data.DraftStore(context.chatExtrasDataStore, KeystoreSecretCipher())
    /** Draft writes queue apart from [runWriter], so a slow draft write never delays a run breadcrumb. */
    val draftWriter = OrderedWriter(appScope)
    val pins = app.rasul.data.PinStore(context.chatExtrasDataStore)
    /** A deleted PC or bridge takes its drafts and pins with it; editing a PC keeps them. */
    private val botRemoved: suspend (String) -> Unit = { prefix -> drafts.clearBot(prefix); pins.clearBot(prefix) }

    val connections = ConnectionStore(context.dataStore, KeystoreSecretCipher(), botRemoved)

    val bridges = app.rasul.bridge.BridgeStore(context.dataStore, KeystoreSecretCipher(), botRemoved)
    val bridgeClients = app.rasul.bridge.BridgeClients(bridges, appScope)
    val appLockSettings = AppLockSettings(context.dataStore)
    val appLock = AppLockController(appLockSettings, appScope)
    val bridgeBots = app.rasul.bridge.BridgeBotsRepository(bridges, bridgeClients)
    val approvals = app.rasul.bridge.ApprovalsRepository(
        bridges, bridgeClients, bridgeBots.state, bridgeApi = { app.rasul.bridge.BridgeWideApprovalsApi(it) },
    )
    /** Files the agent sends (rasul-file:// links): downloads into cacheDir/received, one source per saved bridge. */
    val fileDownloads = app.rasul.files.FileDownloads(
        java.io.File(context.cacheDir, "received"), appScope,
        { bridgeId -> bridges.get(bridgeId)?.let { app.rasul.files.BridgeFileSource(bridgeClients.client(it).client) } },
    )
    val activePc = ActivePcHolder(connections, appScope, bridgeBots)
    val transcriptSources = app.rasul.sessions.TranscriptSources()
    val pendingChat = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val shares = app.rasul.share.ShareInbox()
    val runs = RunsClient(usageStore = connections, scope = appScope)
    val runWatcher = app.rasul.notifications.RunWatcher(
        this, context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    /** Bot key (`bridge:<bridgeId>:<profileId>`) of the open approvals screen, if any. */
    val approvalsVisible = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val approvalAlerts = app.rasul.notifications.ApprovalAlerts(this, context.applicationContext)
    val jobSeen = app.rasul.notifications.JobSeenStore(context.dataStore)
    val updatePrefs = app.rasul.update.UpdatePrefs(context.dataStore)
    val updateInstaller = app.rasul.update.SessionApkInstaller(context.applicationContext)
    val updates = app.rasul.update.UpdateController(
        source = app.rasul.update.HttpUpdateSource(versionName = BuildConfig.VERSION_NAME),
        prefs = updatePrefs,
        signers = app.rasul.update.AndroidApkSigners(context.applicationContext),
        installer = updateInstaller,
        installedVersion = BuildConfig.VERSION_NAME,
        cacheDir = java.io.File(context.cacheDir, "updates"),
        scope = appScope,
    )

    /** The single ordered queue for those writes, shared by every chat screen. */
    val runWriter = OrderedWriter(appScope)

    /** The one place a chat is wired to the app's clients, including fork (see AppContainerWiringTest). */
    internal fun newChatModel(scope: CoroutineScope, loader: app.rasul.chat.TargetLoader, memory: app.rasul.chat.RunMemory, attachLoader: app.rasul.chat.AttachmentLoader? = null) =
        app.rasul.chat.ChatViewModel(loader, runs, sessions, memory, runWriter, scope = scope, attachLoader = attachLoader, control = sessionControl)
}

class RasulApp : Application() {
    val container by lazy { AppContainer(this) }
}
