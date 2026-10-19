package app.rasul.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import app.rasul.AppContainer
import app.rasul.data.HermesResult
import app.rasul.sessions.SessionDto
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.ConnectionsViewModel
import app.rasul.ui.chatRoute
import app.rasul.ui.forkChatRoute
import kotlinx.coroutines.launch

const val SEND_TO_ROUTE = "send-to"

/** Reads the chats the picker offers for [bot]: its saved connection or its bridge profile, whichever it is. */
internal suspend fun recentChatsOf(container: AppContainer, bot: ChatBotChoice): HermesResult<List<SessionDto>> {
    val target = if (bot.bridgeId != null && bot.profileId != null) container.bridgeBots.target(bot.bridgeId, bot.profileId)
    else container.connections.target(bot.id)
    return when (target) {
        is HermesResult.Err -> target
        is HermesResult.Ok -> container.sessions.list(target.value)
    }
}

/** The chat route for the picked destination: a new chat, or [session] of [bot] when it can be opened at its current revision. */
internal suspend fun shareChatRoute(container: AppContainer, bot: ChatBotChoice, session: SessionDto?): String {
    val fresh = chatRoute(bot, newSession = true)
    if (session == null) return fresh
    val target = (if (bot.bridgeId != null && bot.profileId != null) container.bridgeBots.target(bot.bridgeId, bot.profileId)
    else container.connections.target(bot.id)) as? HermesResult.Ok ?: return fresh
    val revision = target.value.revision
    return if (bot.bridgeId != null && bot.profileId != null) {
        forkChatRoute(session.id, revision, bridge = bot.bridgeId, profile = bot.profileId)
    } else {
        forkChatRoute(session.id, revision, id = bot.id, runProfile = target.value.profile.orEmpty())
    }
}

@Composable
fun SendToDestination(container: AppContainer, nav: NavController, sharedOwner: ViewModelStoreOwner) {
    val share by container.shares.pending.collectAsState()
    val connectionsVm: ConnectionsViewModel = viewModel(
        viewModelStoreOwner = sharedOwner,
        factory = viewModelFactory { initializer { ConnectionsViewModel(container.connections) } },
    )
    val connections by connectionsVm.state.collectAsState()
    val bridges by container.bridgeBots.state.collectAsState()
    val vm: SendToViewModel = viewModel(
        factory = viewModelFactory { initializer { SendToViewModel { recentChatsOf(container, it) } } },
    )
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val current = share
    if (current == null || current.route != null) {
        // Nothing waiting (cancelled) or already handed to a chat: leave only if this screen is still the top one.
        LaunchedEffect(current == null) {
            if (current == null && nav.currentDestination?.route == SEND_TO_ROUTE) nav.popBackStack()
        }
        return
    }
    val cancel = {
        container.shares.cancel()
        nav.popBackStack()
        Unit
    }
    fun open(session: SessionDto?) {
        val bot = state.bot ?: return
        scope.launch {
            val route = shareChatRoute(container, bot, session)
            if (bot.bridgeId != null && bot.profileId != null) container.activePc.selectBridge(bot.bridgeId, bot.profileId)
            container.shares.choose(route)
            nav.navigate(route) { popUpTo(SEND_TO_ROUTE) { inclusive = true } }
        }
    }
    SendToContent(
        share = current,
        bots = sendToBots(connections, bridges),
        botsLoading = sendToBotsLoading(connections, bridges),
        state = state,
        onBack = { if (!vm.back()) cancel() },
        onPickBot = vm::pick,
        onNewChat = { open(null) },
        onPickChat = ::open,
        onRetry = vm::retry,
        onClose = { cancel() },
    )
}
