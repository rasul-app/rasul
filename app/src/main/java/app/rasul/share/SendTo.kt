package app.rasul.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.bridgeBotId
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.sessions.SessionDto
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.ConnectionsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** How many of a bot's latest chats the picker offers. */
const val SEND_TO_RECENT_CHATS = 20

/**
 * The bots a share can go to: every saved direct bot (it opens the same chat, with the same attach
 * pipeline) and every ready bot of a bridge that is loaded without error. Same rule as the New chat picker.
 */
fun sendToBots(connections: ConnectionsState, bridges: BridgeBotsState): List<ChatBotChoice> = buildList {
    (connections as? ConnectionsState.Loaded)?.items?.forEach { add(ChatBotChoice(it.id, it.name)) }
    bridges.groups.filter { it.error == null && !it.loading }.forEach { group ->
        group.profiles.filter { it.ready }.forEach { profile ->
            add(ChatBotChoice(bridgeBotId(group.id, profile.id), profile.displayName, group.id, profile.id, group.name))
        }
    }
}

/** True while the bot list cannot be trusted yet: saved bots or a bridge are still loading. */
fun sendToBotsLoading(connections: ConnectionsState, bridges: BridgeBotsState): Boolean =
    connections is ConnectionsState.Loading || !bridges.loaded || bridges.groups.any { it.loading }

private val JsonElement?.seconds: Double? get() = (this as? JsonPrimitive)?.doubleOrNull

/** A bot's chats for the picker: job runs left out, newest activity first, at most [SEND_TO_RECENT_CHATS]. */
fun recentChats(sessions: List<SessionDto>): List<SessionDto> =
    sessions.filterNot { it.isCronRun }
        .sortedByDescending { it.lastActive.seconds ?: it.startedAt.seconds ?: 0.0 }
        .take(SEND_TO_RECENT_CHATS)

sealed interface RecentChats {
    data object Loading : RecentChats
    data class Failed(val error: HermesError) : RecentChats
    data class Ready(val chats: List<SessionDto>) : RecentChats
}

/** [bot] null = choosing the bot; otherwise choosing New chat or one of [recents]. */
data class SendToState(val bot: ChatBotChoice? = null, val recents: RecentChats = RecentChats.Loading)

/** Holds the two-step picker. [load] reads one bot's chats; it is called again on retry and after a bot change. */
class SendToViewModel(
    private val load: suspend (ChatBotChoice) -> HermesResult<List<SessionDto>>,
) : ViewModel() {
    private val _state = MutableStateFlow(SendToState())
    val state: StateFlow<SendToState> = _state
    private var job: Job? = null

    fun pick(bot: ChatBotChoice) {
        _state.value = SendToState(bot, RecentChats.Loading)
        fetch(bot)
    }

    fun retry() {
        val bot = _state.value.bot ?: return
        _state.update { it.copy(recents = RecentChats.Loading) }
        fetch(bot)
    }

    /** Back from the chat list to the bot list; false when already on the bot list. */
    fun back(): Boolean {
        if (_state.value.bot == null) return false
        job?.cancel()
        _state.value = SendToState()
        return true
    }

    private fun fetch(bot: ChatBotChoice) {
        job?.cancel()
        job = viewModelScope.launch {
            val result = try {
                load(bot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
            }
            _state.update {
                if (it.bot != bot) it else it.copy(
                    recents = when (result) {
                        is HermesResult.Ok -> RecentChats.Ready(recentChats(result.value))
                        is HermesResult.Err -> RecentChats.Failed(result.error)
                    },
                )
            }
        }
    }
}
