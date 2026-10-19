package app.rasul.ui

import android.net.Uri
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rasul.AppContainer
import app.rasul.BuildConfig
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.Load
import app.rasul.data.ModelOptionsDto
import app.rasul.R
import app.rasul.jobs.JobsScreen
import app.rasul.jobs.JobsViewModel
import app.rasul.sessions.SessionsScreen
import app.rasul.sessions.SessionsViewModel
import app.rasul.sessions.TranscriptScreen
import app.rasul.sessions.TranscriptViewModel
import app.rasul.settings.MoreScreen
import app.rasul.settings.AppPreferences
import app.rasul.settings.PrivacyScreen
import app.rasul.bridge.ApprovalsBanner
import app.rasul.bridge.ApprovalsError
import app.rasul.bridge.ApprovalsException
import app.rasul.bridge.ApprovalsScreen
import app.rasul.bridge.ApprovalsViewModel
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.approvalsBannerData
import app.rasul.bridge.bridgeBotId
import app.rasul.bridge.nextApprovalExpiryDelayMs
import app.rasul.chat.ChatScreen
import app.rasul.chat.ChatViewModel
import app.rasul.chat.TargetLoader
import app.rasul.chat.chatRunMemory
import app.rasul.chat.chatTargetLoader
import app.rasul.chat.newChatRunMemory
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.rasul.bridge.bridgeTargetLoader
import app.rasul.bridge.bridgeRunMemory
import app.rasul.bridge.desktopApi
import app.rasul.channels.ChannelsScreen
import app.rasul.channels.ChannelsViewModel
import app.rasul.bridge.channelsApi
import app.rasul.desktop.HermesUpdateScreen
import app.rasul.desktop.HermesUpdateViewModel
import app.rasul.desktop.LogsScreen
import app.rasul.desktop.LogsViewModel
import app.rasul.desktop.PcCheckScreen
import app.rasul.desktop.PcCheckViewModel
import app.rasul.desktop.PcInfoViewModel
import app.rasul.bridge.statusApi
import app.rasul.desktop.RunHistoryScreen
import app.rasul.desktop.RunHistoryViewModel
import app.rasul.desktop.UsageScreen
import app.rasul.desktop.UsageViewModel
import app.rasul.skills.HermesSkillsHostApi
import app.rasul.skills.SkillsToolsScreen
import app.rasul.skills.SkillsToolsViewModel
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulTab
import app.rasul.ui.components.RasulTabBar
import app.rasul.ui.ConnectionsState
import app.rasul.ui.ConnectionsViewModel
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.NewChatAction
import app.rasul.ui.SearchScreen
import app.rasul.ui.BotPickerSheet
import app.rasul.ui.BridgePickerSheet
import app.rasul.ui.theme.Rasul
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.LoadView
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val BOTS = "bots"
private const val SESSIONS = "sessions"
private const val JOBS = "jobs"
private const val MORE = "more"
private const val SEARCH = "search"
private const val PRIVACY = "privacy"
private const val HELP_URL = "https://github.com/rasul-app/rasul#readme"
private const val FEEDBACK_URL = "https://github.com/rasul-app/rasul/issues/new"
private const val SKILLS_TOOLS = "skills-tools"
private const val USAGE = "usage"
private const val HERMES_UPDATE = "hermes-update"
private const val PC_CHECK = "pc-check"
private const val LOGS = "logs"
private const val CHANNELS = "channels"
private const val RUN_HISTORY = "job-runs/{id}?src={src}&name={name}&bridge={bridge}&profile={profile}"
private const val EDIT = "edit?id={id}"
private const val TRANSCRIPT = "transcript/{id}?src={src}&sessionSource={sessionSource}"

private data class SettingsWrite(val message: Int, val save: suspend () -> Unit)

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(BOTS, R.string.tab_bots, RasulIcons.Bot),
    Tab(SESSIONS, R.string.tab_sessions, RasulIcons.Chat),
    Tab(JOBS, R.string.tab_jobs, RasulIcons.Calendar),
    Tab(MORE, R.string.tab_more, RasulIcons.Dots),
)
private const val CHAT = "chat/{id}?run_profile={run_profile}&newSession={newSession}&session={session}&rev={rev}"
private const val BRIDGE_CHAT = "bridge-chat/{bridge}/{profile}?newSession={newSession}&session={session}&rev={rev}"
private const val BRIDGE_DETAIL = "bridge-detail/{id}"
private const val INSTRUCTIONS = "instructions?bridge={bridge}&profile={profile}&name={name}"
private const val APPROVALS = "approvals/{bridge}/{profile}"

@Composable
fun RasulNav(
    container: AppContainer,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    onAppLockChanged: (Boolean) -> Unit,
    authenticateCreation: (() -> Unit, () -> Unit) -> Unit,
    nav: androidx.navigation.NavHostController = rememberNavController(),
) {
    val pendingChat by container.pendingChat.collectAsState()
    LaunchedEffect(pendingChat) {
        pendingChat?.let { nav.navigate(it) { popUpTo(BOTS); launchSingleTop = true }; container.pendingChat.value = null }
    }
    // A share opens its Send-to screen only on an unlocked app; behind the lock it waits in memory.
    val pendingShare by container.shares.pending.collectAsState()
    val shareLock by container.appLock.state.collectAsState()
    val shareOpens = app.rasul.share.shareMayOpen(shareLock, pendingShare)
    LaunchedEffect(pendingShare?.id, shareOpens, pendingShare?.route == null) {
        if (shareOpens && pendingShare?.route == null) {
            nav.navigate(app.rasul.share.SEND_TO_ROUTE) { launchSingleTop = true }
        }
    }
    val sharedOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, container.bridgeBots) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { container.bridgeBots.follow() }
    }
    LaunchedEffect(lifecycle, container.approvals) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { container.approvals.follow() }
    }
    val context = LocalContext.current
    var unavailableExternalAddress by remember { mutableStateOf<String?>(null) }
    var failedSettingsWrite by remember { mutableStateOf<SettingsWrite?>(null) }
    var settingsWriteInFlight by remember { mutableStateOf(false) }
    fun saveSettings(write: SettingsWrite) {
        if (settingsWriteInFlight) return
        settingsWriteInFlight = true
        failedSettingsWrite = null
        container.appScope.launch {
            try { write.save() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failedSettingsWrite = write }
            finally { settingsWriteInFlight = false }
        }
    }
    val preferences by container.settings.values.collectAsState(initial = AppPreferences())
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val tabs = TABS.map { RasulTab(it.route, stringResource(it.label), it.icon) }
    Scaffold(
        containerColor = Rasul.colors.bg,
        // Status bar on top, cutout on the sides; the tab bar adds the navigation bar itself.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        bottomBar = {
            if (TABS.any { it.route == route }) {
                RasulTabBar(tabs, route, onSelect = { t ->
                    nav.navigate(t.route) {
                        popUpTo(BOTS) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { outer ->
        val approvalFeeds by container.approvals.state.collectAsState()
        val approvalBots by container.bridgeBots.state.collectAsState()
        val banner = approvalsBannerData(approvalFeeds, approvalBots, approvalsNowSec(approvalFeeds))
        Column(Modifier.padding(outer).consumeWindowInsets(outer)) {
            if (banner != null && route?.startsWith("approvals/") != true) {
                ApprovalsBanner(
                    pluralStringResource(R.plurals.approvals_waiting, banner.count, banner.count, banner.botName),
                ) { nav.navigate("approvals/" + Uri.encode(banner.bridgeId) + "/" + Uri.encode(banner.profileId)) }
            }
            NavHost(
                nav, startDestination = BOTS,
                modifier = Modifier.weight(1f).imePadding(),
            ) {
            composable("bridges") {
                app.rasul.bridge.SavedBridgesScreen(container.bridges, container.bridgeBots,
                    onScan = { nav.navigate("pair-guide") }, onDirect = { nav.navigate("edit") }, onClose = { nav.popBackStack() })
            }
            composable(BRIDGE_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val bots by container.bridgeBots.state.collectAsState()
                app.rasul.bridge.SavedBridgesScreen(
                    container.bridges, container.bridgeBots,
                    onScan = { nav.navigate("pair-guide") }, onDirect = { nav.navigate("edit") }, onClose = { nav.popBackStack() },
                    selectedBridgeId = id,
                    botCount = bots.groups.firstOrNull { it.id == id }?.takeIf { !it.loading && it.error == null }?.profiles?.size,
                    onAdd = { nav.navigate("pair-guide") },
                )
            }
            composable("pair-guide") {
                app.rasul.pairing.PairingGuideScreen(
                    onBack = { nav.popBackStack() },
                    onScan = { nav.navigate("scan") },
                    onDirect = { nav.navigate("edit") },
                )
            }
            composable("scan") {
                val vm: app.rasul.pairing.PairingViewModel = viewModel(factory = viewModelFactory {
                    initializer { app.rasul.pairing.PairingViewModel(container.bridges) }
                })
                app.rasul.pairing.ScanScreen(vm) { nav.popBackStack() }
            }
            composable("bridge-create/{bridge}", arguments = listOf(navArgument("bridge") { type = NavType.StringType })) { entry ->
                val bridge = entry.arguments?.getString("bridge").orEmpty()
                val vm: app.rasul.bridge.CreateBotViewModel = viewModel(factory = viewModelFactory {
                    initializer { app.rasul.bridge.CreateBotViewModel(service = {
                        val saved = container.bridges.get(bridge)
                            ?: throw app.rasul.bridge.CreateBotException(app.rasul.bridge.CreateBotError.BridgeRemoved)
                        app.rasul.bridge.BridgeCreateBotApi(container.bridgeClients.client(saved).client)
                    }) }
                })
                val bots by container.bridgeBots.state.collectAsState()
                app.rasul.bridge.CreateBotScreen(vm, bots.groups.firstOrNull { it.id == bridge }, authenticateCreation,
                    appLockAvailable, { nav.popBackStack() }, { profile ->
                        container.activePc.selectBridge(bridge, profile)
                        nav.navigate("bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile)) {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    })
            }
            composable(BOTS) { Connections(container, nav, sharedOwner) }
            composable(app.rasul.share.SEND_TO_ROUTE) { app.rasul.share.SendToDestination(container, nav, sharedOwner) }
            composable(SESSIONS) {
                val bridgeState by container.bridgeBots.state.collectAsState()
                val vm: SessionsViewModel = viewModel(
                    viewModelStoreOwner = sharedOwner,
                    factory = viewModelFactory { initializer { SessionsViewModel(container.connections, container.activePc, container.sessions, container.runWatcher::invalidateSession, container.drafts, container.pins) } },
                )
                val connectionsVm: ConnectionsViewModel = viewModel(
                    viewModelStoreOwner = sharedOwner,
                    factory = viewModelFactory { initializer { ConnectionsViewModel(container.connections) } },
                )
                val connections by connectionsVm.state.collectAsState()
                val picker by vm.picker.collectAsState()
                ActionPickerHost(connections, bridgeState, picker.activeId, nav, container.activePc::selectBridge) { onNewChat, onNewBot ->
                    SessionsScreen(
                        vm,
                        onOpen = { session, source ->
                            val token = container.transcriptSources.put(source)
                            nav.navigate("transcript/" + Uri.encode(session.id) + "?src=" + Uri.encode(token) +
                                "&sessionSource=" + Uri.encode(session.source.orEmpty()))
                        },
                        onPairPc = { nav.navigate("pair-guide") },
                        onSearch = { nav.navigate(SEARCH) },
                        onNewChat = onNewChat,
                        onNewBot = onNewBot,
                    )
                }
            }
            composable(
                TRANSCRIPT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("src") { type = NavType.StringType; defaultValue = "" },
                    navArgument("sessionSource") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val sourceToken = entry.arguments?.getString("src").orEmpty()
                val source = container.transcriptSources.get(sourceToken)
                val sessionSource = entry.arguments?.getString("sessionSource")
                val picker by container.activePc.state.collectAsState()
                val continueScope = androidx.compose.runtime.rememberCoroutineScope()
                val vm: TranscriptViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { TranscriptViewModel(container.connections, container.sessions, id, source, container.activePc::currentTarget, sessionSource, container.runWatcher::busySession) }
                    },
                )
                TranscriptScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    connectionUnchanged = picker.target == source,
                    onContinue = {
                        val selected = source?.takeIf { picker.target == it }
                        if (selected != null) continueScope.launch {
                            if (vm.continueSession()) nav.navigate(continueChatRoute(selected))
                        }
                    },
                )
            }
            composable(JOBS) {
                val vm: JobsViewModel = viewModel(
                    viewModelStoreOwner = sharedOwner,
                    factory = viewModelFactory { initializer { JobsViewModel(container.connections, container.activePc, container.jobs) } },
                )
                JobsScreen(
                    vm, onPairPc = { nav.navigate("pair-guide") }, onSearch = { nav.navigate(SEARCH) },
                    onOpenHistory = { source, job ->
                        val token = container.transcriptSources.put(source)
                        // Stable, secret-free identity so the target survives process death: saved bridge id
                        // + bridge profile id, looked up from the same bot id the job list was read on.
                        val restore = container.bridgeBots.state.value.groups.firstNotNullOfOrNull { group ->
                            group.profiles.firstOrNull { bridgeBotId(group.id, it.id) == source.id }
                                ?.let { group.id to it.id }
                        }
                        nav.navigate(
                            "job-runs/" + Uri.encode(job.id) + "?src=" + Uri.encode(token) +
                                "&name=" + Uri.encode(job.name?.takeIf { it.isNotBlank() } ?: job.id) +
                                (restore?.let { (bridge, profile) ->
                                    "&bridge=" + Uri.encode(bridge) + "&profile=" + Uri.encode(profile)
                                } ?: ""),
                        )
                    },
                )
            }
            composable(MORE) {
                val pcState by container.activePc.state.collectAsState()
                val bridgeState by container.bridgeBots.state.collectAsState()
                val updateState by container.updates.state.collectAsState()
                val updateEnabled by container.updatePrefs.enabledFlow.collectAsState(initial = true)
                val moreTarget = pcState.target as? ActiveTarget.Ready
                val pcInfo = moreTarget?.takeIf { it.target.bridge != null }?.let { ready ->
                    val infoVm: PcInfoViewModel = viewModel(
                        key = "pc-info-" + ready.target.hashCode(),
                        factory = viewModelFactory {
                            initializer {
                                PcInfoViewModel(
                                    ready.target.statusApi()?.let { api -> { api } },
                                    ready.target.desktopApi()?.let { api -> { api } },
                                )
                            }
                        },
                    )
                    LaunchedEffect(infoVm) { infoVm.onOpened() }
                    infoVm.state.collectAsState().value
                }
                MoreScreen(
                    pcState.copy(pcs = pcState.pcs.filter { it.baseUrl.isNotEmpty() }),
                    bridgeState = bridgeState,
                    onAdd = { nav.navigate("pair-guide") },
                    onBridgeOpen = { nav.navigate("bridge-detail/" + Uri.encode(it)) },
                    onEdit = { nav.navigate("edit?id=$it") },
                    onRetry = container.activePc::retry,
                    appLockEnabled = appLockEnabled,
                    appLockAvailable = appLockAvailable,
                    onAppLockChanged = onAppLockChanged,
                    appearance = preferences.appearance,
                    hapticsEnabled = preferences.hapticsEnabled,
                    versionName = BuildConfig.VERSION_NAME,
                    onAppearanceChanged = { option ->
                        saveSettings(SettingsWrite(R.string.settings_appearance_write_error) { container.settings.setAppearance(option) })
                    },
                    onHapticsChanged = { enabled ->
                        saveSettings(SettingsWrite(R.string.settings_haptics_write_error) { container.settings.setHapticsEnabled(enabled) })
                    },
                    approvalAlertsEnabled = preferences.approvalAlerts,
                    onApprovalAlertsChanged = { enabled ->
                        saveSettings(SettingsWrite(R.string.approval_alerts_write_error) {
                            container.settings.setApprovalAlerts(enabled)
                            container.approvalAlerts.sync()
                        })
                    },
                    onHelp = { launchExternal(context, HELP_URL) { unavailableExternalAddress = it } },
                    onPrivacy = { nav.navigate(PRIVACY) },
                    onFeedback = { launchExternal(context, FEEDBACK_URL) { unavailableExternalAddress = it } },
                    client = container.client,
                    onSkillsTools = { nav.navigate(SKILLS_TOOLS) },
                    onUsage = { nav.navigate(USAGE) },
                    showAppUpdates = true,
                    updateCheckEnabled = updateEnabled,
                    updateState = updateState,
                    onUpdateCheckEnabledChanged = { value -> container.appScope.launch { container.updatePrefs.setEnabled(value) } },
                    onUpdateCheckNow = { container.appScope.launch { container.updates.checkNow() } },
                    onUpdateView = container.updates::showOffer,
                    pcInfo = pcInfo,
                    onHermesUpdate = { nav.navigate(HERMES_UPDATE) },
                    onCheckConnection = { nav.navigate(PC_CHECK) },
                    onChannels = { nav.navigate(CHANNELS) },
                    onLogs = { nav.navigate(LOGS) },
                )
            }
            composable(HERMES_UPDATE) {
                val picker by container.activePc.state.collectAsState()
                HermesUpdateDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(CHANNELS) {
                val picker by container.activePc.state.collectAsState()
                ChannelsDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(LOGS) {
                val picker by container.activePc.state.collectAsState()
                LogsDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(PC_CHECK) {
                val picker by container.activePc.state.collectAsState()
                PcCheckDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(SKILLS_TOOLS) {
                val picker by container.activePc.state.collectAsState()
                SkillsToolsDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(USAGE) {
                val picker by container.activePc.state.collectAsState()
                UsageDestination(picker, container, onBack = { nav.popBackStack() })
            }
            composable(
                RUN_HISTORY,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("src") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("bridge") { type = NavType.StringType; defaultValue = "" },
                    navArgument("profile") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val source = container.transcriptSources.get(entry.arguments?.getString("src").orEmpty())
                val bridgeId = entry.arguments?.getString("bridge").orEmpty()
                val profileId = entry.arguments?.getString("profile").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty().ifBlank { id }
                // Same process: the handed-off connection. After process death: resolve the route's saved
                // bridge + profile ids from the stores (a removed bridge yields the no-longer-saved state).
                val service: (suspend () -> app.rasul.bridge.DesktopApi?)? =
                    source?.target?.desktopApi()?.let { api -> { api } }
                        ?: if (bridgeId.isNotEmpty() && profileId.isNotEmpty()) {
                            app.rasul.desktop.runHistoryService(container.bridges, container.bridgeClients, bridgeId, profileId)
                        } else null
                val vm: RunHistoryViewModel = viewModel(
                    key = "job-runs-" + id + "-" + bridgeId + "-" + profileId,
                    factory = viewModelFactory {
                        initializer { RunHistoryViewModel(id, service) }
                    },
                )
                RunHistoryScreen(vm, name, onBack = { nav.popBackStack() })
            }
            composable(SEARCH) { SearchDestination(container, nav, sharedOwner) }
            composable(PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable(INSTRUCTIONS, arguments = listOf(
                navArgument("bridge") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("profile") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                val bridge = entry.arguments?.getString("bridge")
                val profile = entry.arguments?.getString("profile")
                val name = entry.arguments?.getString("name").orEmpty()
                val vm: app.rasul.bridge.InstructionsViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        app.rasul.bridge.InstructionsViewModel(if (bridge == null) null else {
                            {
                                val saved = container.bridges.get(bridge)
                                    ?: throw app.rasul.bridge.InstructionsException(app.rasul.bridge.InstructionsError.BridgeRemoved)
                                app.rasul.bridge.BridgeInstructionsApi(container.bridgeClients.client(saved).client,
                                    profile ?: throw app.rasul.bridge.InstructionsException(app.rasul.bridge.InstructionsError.InvalidRequest))
                            }
                        })
                    }
                })
                app.rasul.bridge.InstructionsScreen(vm, name, onBack = { nav.popBackStack() })
            }
            composable(APPROVALS, arguments = listOf(
                navArgument("bridge") { type = NavType.StringType },
                navArgument("profile") { type = NavType.StringType },
            )) { entry ->
                val bridge = entry.arguments?.getString("bridge").orEmpty()
                val profile = entry.arguments?.getString("profile").orEmpty()
                // Visible only while the screen is STARTED: stopping the activity (Home) clears it, so a
                // new approval for this bot is alerted instead of silently suppressed.
                androidx.lifecycle.compose.LifecycleStartEffect(bridge, profile) {
                    val bot = bridgeBotId(bridge, profile)
                    container.approvalsVisible.value = bot
                    onStopOrDispose { container.approvalsVisible.compareAndSet(bot, null) }
                }
                val bots by container.bridgeBots.state.collectAsState()
                val name = bots.groups.firstOrNull { it.id == bridge }?.profiles
                    ?.firstOrNull { it.id == profile }?.displayName.orEmpty()
                val vm: ApprovalsViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        ApprovalsViewModel(
                            feed = container.approvals.feed(bridge, profile),
                            service = {
                                container.approvals.api(bridge, profile)
                                    ?: throw ApprovalsException(ApprovalsError.BridgeRemoved)
                            },
                            refreshFeed = { container.approvals.refresh(bridge, profile) },
                            bots = container.bridgeBots.state,
                            bridgeId = bridge,
                            profileId = profile,
                            retryDiscovery = { container.bridgeBots.retry() },
                        )
                    }
                })
                ApprovalsScreen(vm, name, onBack = { nav.popBackStack() })
            }
            composable(BRIDGE_CHAT, arguments = listOf(
                navArgument("bridge") { type = NavType.StringType }, navArgument("profile") { type = NavType.StringType },
                navArgument("newSession") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("rev") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { entry ->
                val bridge = entry.arguments?.getString("bridge").orEmpty()
                val profile = entry.arguments?.getString("profile").orEmpty()
                val newSession = entry.arguments?.getString("newSession")
                val session = entry.arguments?.getString("session")
                val revision = entry.arguments?.getString("rev")
                val bots by container.bridgeBots.state.collectAsState()
                val group = bots.groups.firstOrNull { it.id == bridge }
                val name = group?.profiles?.firstOrNull { it.id == profile }?.displayName.orEmpty()
                val chatKey = remember(entry) { bridgeChatEntryRoute(bridge, profile, newSession, session, revision) }
                val vm by produceState<ChatViewModel?>(null, entry) {
                    value = resolveChat(container, nav, chatKey)
                }
                vm?.let { model ->
                    ClaimShare(container, chatKey, model)
                    val optionsLoader = remember(container, model) {
                        modelOptionsLoader(container, app.rasul.chat.TargetLoader {
                            model.resolvedTarget?.let { app.rasul.data.HermesResult.Ok(it) }
                                ?: app.rasul.data.HermesResult.Err(app.rasul.data.HermesError.NotFound)
                        })
                    }
                    androidx.compose.runtime.CompositionLocalProvider(
                        app.rasul.files.LocalFileBridge provides bridge,
                        app.rasul.files.LocalFileDownloads provides container.fileDownloads,
                    ) {
                    ChatScreen(vm = model, title = name, onBack = { nav.popBackStack() },
                        runs = container.runs, modelOptionsLoader = optionsLoader,
                        onInstructions = { nav.navigate("instructions?bridge=" + Uri.encode(bridge) + "&profile=" +
                            Uri.encode(profile) + "&name=" + Uri.encode(name)) },
                        onOpenFork = { forked, rev -> nav.navigate(forkChatRoute(bridge = bridge, profile = profile, session = forked, revision = rev)) },
                        onApprovals = { nav.navigate("approvals/" + Uri.encode(bridge) + "/" + Uri.encode(profile)) })
                    }
                }
            }
            composable(CHAT, arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("run_profile") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("newSession") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("rev") { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val newSession = entry.arguments?.getString("newSession")
                val session = entry.arguments?.getString("session")
                val revision = entry.arguments?.getString("rev")
                val name by produceState("") { value = container.connections.get(id)?.name.orEmpty() }
                val profile = entry.arguments?.getString("run_profile")
                val chatKey = remember(entry) { directChatEntryRoute(id, profile, newSession, session, revision) }
                val vm by produceState<ChatViewModel?>(null, entry) {
                    value = resolveChat(container, nav, chatKey)
                }
                vm?.let { model ->
                    ClaimShare(container, chatKey, model)
                    val optionsLoader = remember(container, model) {
                        modelOptionsLoader(container, app.rasul.chat.TargetLoader {
                            model.resolvedTarget?.let { app.rasul.data.HermesResult.Ok(it) }
                                ?: app.rasul.data.HermesResult.Err(app.rasul.data.HermesError.NotFound)
                        })
                    }
                    ChatScreen(vm = model, title = name, onBack = { nav.popBackStack() },
                        runs = container.runs, modelOptionsLoader = optionsLoader,
                        onInstructions = { nav.navigate("instructions?name=" + Uri.encode(name)) },
                        onOpenFork = { forked, rev -> nav.navigate(forkChatRoute(id = id, runProfile = profile, session = forked, revision = rev)) })
                }
            }
            composable(
                EDIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val vm: EditConnectionViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { EditConnectionViewModel(container.connections, container.client, id) }
                    },
                )
                EditConnectionScreen(vm = vm, onClose = { nav.popBackStack() })
            }
        }
        }
        failedSettingsWrite?.let { write ->
            AlertDialog(
                onDismissRequest = { failedSettingsWrite = null },
                text = { Text(stringResource(write.message)) },
                confirmButton = {
                    TextButton(onClick = { saveSettings(write) }) { Text(stringResource(R.string.retry)) }
                },
                dismissButton = {
                    TextButton(onClick = { failedSettingsWrite = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        unavailableExternalAddress?.let { address ->
            AlertDialog(
                onDismissRequest = { unavailableExternalAddress = null },
                title = { Text(stringResource(R.string.external_link_unavailable_title)) },
                text = {
                    app.rasul.WindowHapticFeedbackProvider {
                        SelectionContainer {
                            Text(stringResource(R.string.external_link_unavailable, address))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { unavailableExternalAddress = null }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }
    }
}

private fun modelOptionsLoader(
    container: AppContainer,
    loader: TargetLoader,
): suspend (Boolean) -> HermesResult<ModelOptionsDto> = { refresh ->
    val requestedRefresh = refresh
    when (val target = loader.load()) {
        is HermesResult.Err -> target
        is HermesResult.Ok -> container.client.modelOptions(target.value, requestedRefresh)
    }
}

@Composable
private fun SkillsToolsDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: SkillsToolsViewModel = viewModel(
            key = "skills-tools-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer {
                    SkillsToolsViewModel(
                        target, HermesSkillsHostApi(container.client),
                        target.target.desktopApi()?.let { api -> { api } },
                    )
                }
            },
        )
        SkillsToolsScreen(vm, onBack)
    } else {
        TargetLoadFallback(picker, container, R.string.ux3b_skills_tools_title, onBack)
    }
}

@Composable
private fun UsageDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: UsageViewModel = viewModel(
            key = "usage-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer {
                    UsageViewModel(
                        target.target, container.client::modelOptions,
                        target.target.desktopApi()?.let { api -> { api } },
                    )
                }
            },
        )
        UsageScreen(vm, target.name, onBack)
    } else {
        TargetLoadFallback(picker, container, R.string.usage_title, onBack)
    }
}

@Composable
private fun HermesUpdateDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: HermesUpdateViewModel = viewModel(
            key = "hermes-update-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer { HermesUpdateViewModel(target.target.desktopApi()?.let { api -> { api } }) }
            },
        )
        HermesUpdateScreen(vm, target.name, onBack)
    } else {
        TargetLoadFallback(picker, container, R.string.hupd_title, onBack)
    }
}

@Composable
private fun LogsDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: LogsViewModel = viewModel(
            key = "logs-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer { LogsViewModel(target.target.desktopApi()?.let { api -> { api } }) }
            },
        )
        LogsScreen(vm, target.name, onBack)
    } else {
        TargetLoadFallback(picker, container, R.string.logs_title, onBack)
    }
}

@Composable
private fun ChannelsDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: ChannelsViewModel = viewModel(
            key = "channels-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer { ChannelsViewModel(target.target.channelsApi()?.let { api -> { api } }) }
            },
        )
        val context = LocalContext.current
        ChannelsScreen(vm, target.name, onBack, onOpenLink = { link -> launchExternal(context, link) { vm.telegramLinkUnavailable() } })
    } else {
        TargetLoadFallback(picker, container, R.string.chan_title, onBack)
    }
}

@Composable
private fun PcCheckDestination(picker: PcPicker, container: AppContainer, onBack: () -> Unit) {
    val target = picker.target as? ActiveTarget.Ready
    if (target != null) {
        val vm: PcCheckViewModel = viewModel(
            key = "pc-check-" + target.target.hashCode(),
            factory = viewModelFactory {
                initializer { PcCheckViewModel(target.target.statusApi()?.let { api -> { api } }) }
            },
        )
        PcCheckScreen(vm, target.name, onBack)
    } else {
        TargetLoadFallback(picker, container, R.string.check_title, onBack)
    }
}

@Composable
private fun TargetLoadFallback(picker: PcPicker, container: AppContainer, title: Int, onBack: () -> Unit) {
    val load = picker.pendingLoad() ?: when (val current = picker.target) {
        ActiveTarget.None -> Load.NoConnection
        is ActiveTarget.Failed -> Load.Failed(current.error)
        is ActiveTarget.NoKey -> Load.NoKey(current.name)
        is ActiveTarget.Unreadable -> Load.Failed(HermesError.Storage(current.detail))
        is ActiveTarget.Ready -> error("handled above")
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(title),
            actions = {
                IconButton(onClick = onBack) {
                    Icon(RasulIcons.Back, contentDescription = stringResource(R.string.back), tint = Rasul.colors.accent)
                }
            },
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LoadView(load, container.activePc::retry) {}
        }
    }
}
/**
 * Current epoch seconds, re-ticked when the next pending approval expires so banner and badge
 * counts clear themselves without waiting for another recomposition. Rests while nothing pending.
 */
@Composable
private fun approvalsNowSec(feeds: Map<String, app.rasul.bridge.ApprovalsRepository.Feed>): Long {
    val now by produceState(System.currentTimeMillis() / 1000, feeds) {
        while (true) {
            val t = System.currentTimeMillis() / 1000
            value = t
            val wait = nextApprovalExpiryDelayMs(
                feeds.values.flatMap { it.items }.map { it.expiresAt }, t) ?: return@produceState
            delay(wait)
        }
    }
    return now
}

@Composable
private fun Connections(container: AppContainer, nav: androidx.navigation.NavController, sharedOwner: androidx.lifecycle.ViewModelStoreOwner) {
    val vm: ConnectionsViewModel = viewModel(
        viewModelStoreOwner = sharedOwner,
        factory = viewModelFactory { initializer { ConnectionsViewModel(container.connections) } },
    )
    val bridges by container.bridgeBots.state.collectAsState()
    val connectionState by vm.state.collectAsState()
    val picker by container.activePc.state.collectAsState()
    val approvalFeeds by container.approvals.state.collectAsState()
    val nowSec = approvalsNowSec(approvalFeeds)
    val pendingApprovals = approvalFeeds.mapNotNull { (key, feed) ->
        if (feed.unsupported) null else feed.items.count { it.expiresAt > nowSec }.takeIf { it > 0 }?.let { key to it }
    }.toMap()
    ActionPickerHost(connectionState, bridges, picker.activeId, nav, container.activePc::selectBridge) { onNewChat, onNewBot ->
        ConnectionsScreen(
            vm = vm,
            bridges = bridges,
            pendingApprovals = pendingApprovals,
            onApprovals = { bridge, profile ->
                nav.navigate("approvals/" + Uri.encode(bridge) + "/" + Uri.encode(profile))
            },
            onBridgeCreate = { nav.navigate("bridge-create/" + Uri.encode(it)) },
            onBridgeOpen = { bridge, profile ->
                container.activePc.selectBridge(bridge, profile)
                nav.navigate("bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile))
            },
            onAdd = { nav.navigate("pair-guide") },
            onEdit = { nav.navigate("edit?id=$it") },
            onOpen = { nav.navigate("chat/" + Uri.encode(it)) },
            onSearch = { nav.navigate(SEARCH) },
            onNewChat = onNewChat,
            onNewBot = onNewBot,
        )
    }
}

@Composable
internal fun ActionPickerHost(
    connections: ConnectionsState,
    bridges: BridgeBotsState,
    activeBotId: String?,
    nav: androidx.navigation.NavController,
    onSelectBridge: (String, String) -> Unit = { _, _ -> },
    content: @Composable (onNewChat: () -> Unit, onNewBot: () -> Unit) -> Unit,
) {
    var showBotPicker by rememberSaveable { mutableStateOf(false) }
    var showBridgePicker by rememberSaveable { mutableStateOf(false) }
    var newChatNotice by remember { mutableStateOf<NewChatAction?>(null) }
    val choices = buildList {
        (connections as? ConnectionsState.Loaded)?.items?.forEach { add(ChatBotChoice(it.id, it.name)) }
        bridges.groups.filter { it.error == null && !it.loading }.forEach { group ->
            group.profiles.filter { it.ready }.forEach { profile ->
                add(ChatBotChoice(bridgeBotId(group.id, profile.id), profile.displayName, group.id, profile.id, group.name))
            }
        }
    }
    val groups = bridges.groups.filter { it.error == null }
    val openChoice: (ChatBotChoice) -> Unit = { choice ->
        openBotChoice(nav, choice, onSelectBridge, newSession = true)
    }
    val startNewChat: () -> Unit = {
        when (val action = app.rasul.ui.newChatAction(choices,
            bridgesLoading = connections is ConnectionsState.Loading || !bridges.loaded || bridges.groups.any { it.loading },
            unavailable = connections is ConnectionsState.Failed || bridges.storageError || bridges.groups.any { it.error != null },
            error = bridges.groups.firstNotNullOfOrNull { it.error },
        )) {
            NewChatAction.Loading, is NewChatAction.Unavailable -> newChatNotice = action
            NewChatAction.NoBots -> nav.navigate("pair-guide")
            is NewChatAction.Open -> openChoice(action.bot)
            is NewChatAction.Choose -> showBotPicker = true
        }
    }
    val startNewBot: () -> Unit = {
        val activeBridge = bridges.groups.firstOrNull { group ->
            activeBotId != null && activeBotId == bridgeBotId(group.id, activeBotId.substringAfterLast(':'))
        }
        when {
            activeBridge != null -> nav.navigate("bridge-create/" + Uri.encode(activeBridge.id))
            activeBotId?.startsWith("bridge:") == true -> newChatNotice = NewChatAction.Unavailable(null)
            activeBotId != null -> nav.navigate("edit")
            groups.size == 1 -> nav.navigate("bridge-create/" + Uri.encode(groups.single().id))
            groups.size > 1 -> showBridgePicker = true
            (connections as? ConnectionsState.Loaded)?.items?.isNotEmpty() == true -> nav.navigate("edit")
            else -> nav.navigate("pair-guide")
        }
    }
    newChatNotice?.let { notice ->
        app.rasul.ui.components.NoticeDialog(
            when (notice) {
                NewChatAction.Loading -> stringResource(R.string.search_loading_bots)
                is NewChatAction.Unavailable -> notice.error?.let { it.text() } ?: stringResource(R.string.search_error_bots)
                else -> ""
            }, stringResource(R.string.ok), { newChatNotice = null },
        )
    }
    content(startNewChat, startNewBot)
    if (showBotPicker) {
        BotPickerSheet(choices, onDismiss = { showBotPicker = false }) { choice ->
            showBotPicker = false
            openChoice(choice)
        }
    }
    if (showBridgePicker) {
        BridgePickerSheet(groups, onDismiss = { showBridgePicker = false }) { group ->
            showBridgePicker = false
            nav.navigate("bridge-create/" + Uri.encode(group.id))
        }
    }
}

@Composable
private fun SearchDestination(container: AppContainer, nav: androidx.navigation.NavController, sharedOwner: androidx.lifecycle.ViewModelStoreOwner) {
    val connectionsVm: ConnectionsViewModel = viewModel(viewModelStoreOwner = sharedOwner, factory = viewModelFactory {
        initializer { ConnectionsViewModel(container.connections) }
    })
    val sessionsVm: SessionsViewModel = viewModel(viewModelStoreOwner = sharedOwner, factory = viewModelFactory {
        initializer { SessionsViewModel(container.connections, container.activePc, container.sessions, container.runWatcher::invalidateSession, container.drafts, container.pins) }
    })
    val jobsVm: JobsViewModel = viewModel(viewModelStoreOwner = sharedOwner, factory = viewModelFactory {
        initializer { JobsViewModel(container.connections, container.activePc, container.jobs) }
    })
    val connections by connectionsVm.state.collectAsState()
    val bridges by container.bridgeBots.state.collectAsState()
    val sessions by sessionsVm.state.collectAsState()
    val jobs by jobsVm.state.collectAsState()
    SearchScreen(
        connections = connections,
        bridges = bridges,
        sessions = sessions,
        jobs = jobs,
        onBack = { nav.popBackStack() },
        onOpenChat = { id, source, sessionSource ->
            val token = container.transcriptSources.put(source)
            nav.navigate("transcript/" + Uri.encode(id) + "?src=" + Uri.encode(token) +
                "&sessionSource=" + Uri.encode(sessionSource.orEmpty()))
        },
        onOpenBot = { choice -> openBotChoice(nav, choice, container.activePc::selectBridge) },
        onOpenJobs = { nav.openJobsFromSearch() },
    )
}

internal fun openBotChoice(
    nav: androidx.navigation.NavController,
    choice: ChatBotChoice,
    onSelectBridge: (String, String) -> Unit,
    newSession: Boolean = false,
) {
    choice.bridgeId?.let { bridge -> choice.profileId?.let { onSelectBridge(bridge, it) } }
    nav.navigate(chatRoute(choice, newSession))
}

/** The route string a bridge chat entry resolves its model by (also what a pending share is matched against). */
private fun bridgeChatEntryRoute(bridge: String, profile: String, newSession: String?, session: String?, revision: String?): String {
    val query = buildList {
        if (newSession != null) add("newSession=" + Uri.encode(newSession))
        if (session != null) add("session=" + Uri.encode(session))
        if (revision != null) add("rev=" + Uri.encode(revision))
    }
    return "bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile) + if (query.isEmpty()) "" else query.joinToString("&", "?")
}

private fun directChatEntryRoute(id: String, profile: String?, newSession: String?, session: String?, revision: String?): String {
    val query = buildList {
        if (profile != null) add("run_profile=" + Uri.encode(profile))
        if (newSession != null) add("newSession=" + Uri.encode(newSession))
        if (session != null) add("session=" + Uri.encode(session))
        if (revision != null) add("rev=" + Uri.encode(revision))
    }
    return "chat/" + Uri.encode(id) + if (query.isEmpty()) "" else query.joinToString("&", "?")
}

/** Resolves the chat model for [route]; when a share was sent there and the chat cannot be opened, the share returns to Send-to. */
private suspend fun resolveChat(container: AppContainer, nav: androidx.navigation.NavController, route: String): ChatViewModel? {
    val model = container.runWatcher.resolve(route)
    if (model == null && container.shares.release(route)) nav.popBackStack()
    return model
}

/** A chat opened from the Send-to screen takes its share (text into the draft, items as chips) once, and never sends it. */
@Composable
private fun ClaimShare(container: AppContainer, route: String, model: ChatViewModel) {
    LaunchedEffect(model, route) {
        container.shares.claim(route)?.let { model.acceptShare(it.text, it.items, it.failure, it.dropped) }
    }
}

internal fun chatRoute(choice: ChatBotChoice, newSession: Boolean = false): String {
    val route = if (choice.bridgeId != null && choice.profileId != null) {
        "bridge-chat/" + Uri.encode(choice.bridgeId) + "/" + Uri.encode(choice.profileId)
    } else {
        "chat/" + Uri.encode(choice.id)
    }
    return if (newSession) "$route?newSession=" + Uri.encode("rasul-" + java.util.UUID.randomUUID()) else route
}

/** The chat route for a forked session: the same bot, bound to [session] at connection [revision]. */
internal fun forkChatRoute(
    session: String,
    revision: String,
    id: String? = null,
    runProfile: String? = null,
    bridge: String? = null,
    profile: String? = null,
): String {
    val tail = "session=" + Uri.encode(session) + "&rev=" + Uri.encode(revision)
    return if (bridge != null && profile != null) {
        "bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile) + "?" + tail
    } else {
        "chat/" + Uri.encode(checkNotNull(id)) + "?" + (if (runProfile != null) "run_profile=" + Uri.encode(runProfile) + "&" else "") + tail
    }
}

internal fun continueChatRoute(source: ActiveTarget.Ready): String {
    val bridge = source.target.bridge
    if (bridge != null) {
        val bridgeId = source.id.removePrefix("bridge:").substringBeforeLast(":")
        return "bridge-chat/${Uri.encode(bridgeId)}/${Uri.encode(bridge.profileId)}"
    }
    return "chat/${Uri.encode(source.id)}?run_profile=" + Uri.encode(source.target.profile.orEmpty())
}

internal fun launchExternal(context: Context, address: String, onUnavailable: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
    } catch (_: ActivityNotFoundException) {
        onUnavailable(address)
    }
}

internal fun androidx.navigation.NavController.openJobsFromSearch() {
    popBackStack(SEARCH, inclusive = true)
    navigate(JOBS) {
        popUpTo(BOTS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun bridgeChatTargetLoader(repository: app.rasul.bridge.BridgeBotsRepository, bridge: String, profile: String) =
    app.rasul.chat.TargetLoader {
        repository.state.first { state ->
            state.loaded && (state.storageError || state.groups.firstOrNull { it.id == bridge }?.loading != true)
        }
        bridgeTargetLoader(repository, bridge, profile).load()
    }

internal fun bridgeSessionMemory(container: AppContainer, bridge: String, profile: String): app.rasul.chat.RunMemory =
    bridgeRunMemory(container.connections, bridge, profile)
