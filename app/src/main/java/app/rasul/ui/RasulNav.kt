package app.rasul.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import app.rasul.R
import app.rasul.jobs.JobsScreen
import app.rasul.jobs.JobsViewModel
import app.rasul.sessions.SessionsScreen
import app.rasul.sessions.SessionsViewModel
import app.rasul.sessions.TranscriptScreen
import app.rasul.sessions.TranscriptViewModel
import app.rasul.settings.MoreScreen
import app.rasul.chat.ChatScreen
import app.rasul.chat.ChatViewModel
import app.rasul.chat.chatRunMemory
import app.rasul.chat.chatTargetLoader
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.rasul.bridge.bridgeTargetLoader
import app.rasul.bridge.bridgeRunMemory
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulTab
import app.rasul.ui.components.RasulTabBar
import app.rasul.ui.theme.Rasul

private const val BOTS = "bots"
private const val SESSIONS = "sessions"
private const val JOBS = "jobs"
private const val MORE = "more"
private const val EDIT = "edit?id={id}"
private const val TRANSCRIPT = "transcript/{id}?src={src}"

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(BOTS, R.string.tab_bots, RasulIcons.Bot),
    Tab(SESSIONS, R.string.tab_sessions, RasulIcons.Chat),
    Tab(JOBS, R.string.tab_jobs, RasulIcons.Calendar),
    Tab(MORE, R.string.tab_more, RasulIcons.Dots),
)
private const val CHAT = "chat/{id}"
private const val BRIDGE_CHAT = "bridge-chat/{bridge}/{profile}"

@Composable
fun RasulNav(
    container: AppContainer,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    onAppLockChanged: (Boolean) -> Unit,
    authenticateCreation: (() -> Unit, () -> Unit) -> Unit,
) {
    val nav = rememberNavController()
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
        NavHost(
            nav, startDestination = BOTS,
            modifier = Modifier.padding(outer).consumeWindowInsets(outer).imePadding(),
        ) {
            composable("bridges") {
                app.rasul.bridge.SavedBridgesScreen(container.bridges, container.bridgeClients,
                    onScan = { nav.navigate("scan") }, onDirect = { nav.navigate("edit") }, onClose = { nav.popBackStack() })
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
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle, container.bridgeBots) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { container.bridgeBots.follow() }
                }
                val bots by container.bridgeBots.state.collectAsState()
                app.rasul.bridge.CreateBotScreen(vm, bots.groups.firstOrNull { it.id == bridge }, authenticateCreation,
                    appLockAvailable, { nav.popBackStack() }, { profile ->
                        container.activePc.selectBridge(bridge, profile)
                        nav.navigate("bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile)) {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    })
            }
            composable(BOTS) { Connections(container, nav) }
            composable(SESSIONS) {
                val vm: SessionsViewModel = viewModel(
                    factory = viewModelFactory { initializer { SessionsViewModel(container.connections, container.activePc, container.sessions) } },
                )
                SessionsScreen(vm, onOpen = { id, source ->
                    val token = container.transcriptSources.put(source)
                    nav.navigate("transcript/" + Uri.encode(id) + "?src=" + Uri.encode(token))
                })
            }
            composable(
                TRANSCRIPT,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("src") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val source = container.transcriptSources.get(entry.arguments?.getString("src").orEmpty())
                val vm: TranscriptViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { TranscriptViewModel(container.connections, container.sessions, id, source, container.activePc::currentTarget) }
                    },
                )
                TranscriptScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(JOBS) {
                val vm: JobsViewModel = viewModel(
                    factory = viewModelFactory { initializer { JobsViewModel(container.connections, container.activePc, container.jobs) } },
                )
                JobsScreen(vm)
            }
            composable(MORE) {
                val pcState by container.activePc.state.collectAsState()
                MoreScreen(
                    pcState.copy(pcs = pcState.pcs.filter { it.baseUrl.isNotEmpty() }),
                    onAdd = { nav.navigate("bridges") },
                    onEdit = { nav.navigate("edit?id=$it") },
                    onRetry = container.activePc::retry,
                    appLockEnabled = appLockEnabled,
                    appLockAvailable = appLockAvailable,
                    onAppLockChanged = onAppLockChanged,
                )
            }
            composable(BRIDGE_CHAT, arguments = listOf(
                navArgument("bridge") { type = NavType.StringType }, navArgument("profile") { type = NavType.StringType },
            )) { entry ->
                val bridge = entry.arguments?.getString("bridge").orEmpty()
                val profile = entry.arguments?.getString("profile").orEmpty()
                val bots by container.bridgeBots.state.collectAsState()
                val group = bots.groups.firstOrNull { it.id == bridge }
                val name = group?.profiles?.firstOrNull { it.id == profile }?.displayName.orEmpty()
                val vm: ChatViewModel = viewModel(factory = viewModelFactory {
                    initializer { ChatViewModel(bridgeTargetLoader(container.bridgeBots, bridge, profile), container.runs,
                        container.sessions, bridgeRunMemory(container.connections, bridge, profile), container.runWriter) }
                })
                ChatScreen(vm = vm, title = name, onBack = { nav.popBackStack() })
            }
            composable(CHAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val name by produceState("") { value = container.connections.get(id)?.name.orEmpty() }
                val vm: ChatViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ChatViewModel(chatTargetLoader(container, id), container.runs, container.sessions, chatRunMemory(container, id), container.runWriter)
                        }
                    },
                )
                ChatScreen(vm = vm, title = name, onBack = { nav.popBackStack() })
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
}

@Composable
private fun Connections(container: AppContainer, nav: androidx.navigation.NavController) {
    val vm: ConnectionsViewModel = viewModel(
        factory = viewModelFactory { initializer { ConnectionsViewModel(container.connections) } },
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, container.bridgeBots) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { container.bridgeBots.follow() }
    }
    val bridges by container.bridgeBots.state.collectAsState()
    ConnectionsScreen(
        vm = vm,
        bridges = bridges,
        onBridgeCreate = { nav.navigate("bridge-create/" + Uri.encode(it)) },
        onBridgeOpen = { bridge, profile ->
            container.activePc.selectBridge(bridge, profile)
            nav.navigate("bridge-chat/" + Uri.encode(bridge) + "/" + Uri.encode(profile))
        },
        onAdd = { nav.navigate("bridges") },
        onEdit = { nav.navigate("edit?id=$it") },
        onOpen = { nav.navigate("chat/$it") },
    )
}
