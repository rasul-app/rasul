package app.rasul.sessions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.ActiveTarget
import app.rasul.data.BusyChatsApi
import app.rasul.data.BusyChatsResult
import app.rasul.data.BusyWhere
import app.rasul.data.busyApi
import app.rasul.data.ConnectionStore
import app.rasul.data.DraftStore
import app.rasul.data.PinStore
import app.rasul.data.HermesError
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.HermesResult
import app.rasul.data.load
import app.rasul.data.loadActive
import app.rasul.data.activeTarget
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.PcPicker
import app.rasul.ui.pendingLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SessionsViewModel(
    private val store: ConnectionStore,
    private val pcs: ActivePcHolder,
    private val client: SessionsManagementApi,
    private val onSessionDeleted: suspend (String) -> Unit = {},
    private val drafts: DraftStore? = null,
    private val pins: PinStore? = null,
    /** The bridge's turn-lease list; null on a direct target keeps every badge off. */
    private val busyApiFor: (HermesTarget) -> BusyChatsApi? = { it.busyApi() },
    private val pollMs: Long = LIST_POLL_MS,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<SessionDto>>>(Load.Loading)
    val state: StateFlow<Load<List<SessionDto>>> = _state
    private val _renameError = MutableStateFlow<HermesError?>(null)
    val renameError: StateFlow<HermesError?> = _renameError
    private val _deleteError = MutableStateFlow<HermesError?>(null)
    val deleteError: StateFlow<HermesError?> = _deleteError
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy
    private val refreshes = MutableStateFlow(0)
    private var generation = 0
    private var pollJob: Job? = null
    /** A bridge that answered 404 keeps the feature off until the shown target changes. */
    private var busyUnsupported = false

    /** Chat id -> where it is replying, from the bridge's turn leases; empty on a direct target or an old bridge. */
    private val _busyChats = MutableStateFlow<Map<String, BusyWhere>>(emptyMap())
    val busyChats: StateFlow<Map<String, BusyWhere>> = _busyChats

    /**
     * The shown bot's hidden forever chat ("Bot Chat" on the server, "Laptop chat" on screen), or null when it
     * has none, was not read yet, or its read failed. It belongs to the shown source: a switch clears it first.
     */
    private val _botChat = MutableStateFlow<SessionDto?>(null)
    val botChat: StateFlow<SessionDto?> = _botChat
    /** One debug line per shown source is enough for a look-up that runs on each load or refresh. */
    private var botChatLogged = false

    /** The bot and profile the shown list belongs to: the key of its pins and drafts. */
    private val bot: Flow<Pair<String, String?>?> = _state.map { load ->
        (load as? Load.Ready)?.source?.let { it.id to (it.target.profile ?: it.target.bridge?.profileId) }
    }.distinctUntilChanged()

    /** Pinned session ids of the shown bot, in pin order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pinned: StateFlow<List<String>> = bot.flatMapLatest { b ->
        if (b == null || pins == null) flowOf(emptyList()) else pins.pinned(b.first, b.second)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First line of the unsent draft of each chat of the shown bot, by session id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val draftLines: StateFlow<Map<String, String>> = bot.flatMapLatest { b ->
        if (b == null || drafts == null) flowOf(emptyMap()) else drafts.firstLines(b.first, b.second)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _pinLimit = MutableStateFlow(false)
    /** True after a pin was refused because the bot already has [PinStore.MAX_PER_BOT]. */
    val pinLimit: StateFlow<Boolean> = _pinLimit
    fun clearPinLimit() { _pinLimit.value = false }

    /** Pins [session] of the list shown, or unpins it when it is pinned. */
    fun togglePin(source: ActiveTarget.Ready, session: SessionDto) {
        val store = pins ?: return
        val profile = source.target.profile ?: source.target.bridge?.profileId
        val shown = (_state.value as? Load.Ready)?.value?.map { it.id }?.toSet()
        viewModelScope.launch {
            try {
                if (session.id in pinned.value) store.unpin(source.id, profile, session.id)
                else if (!store.pin(source.id, profile, session.id, shown)) _pinLimit.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The pin list stays as it was.
            }
        }
    }

    val picker: StateFlow<PcPicker> = pcs.state
    fun selectPc(id: String) = pcs.selectPc(id)
    fun clearWriteError() = pcs.clearWriteError()

    init {
        // Loads exactly the target the holder published (one snapshot). A new target or Refresh cancels a slower load.
        viewModelScope.launch {
            var shown: ActiveTarget? = null
            combine(
                pcs.state.filter { it.loaded }.map { it.target to it.pendingLoad() }.distinctUntilChanged(),
                refreshes,
            ) { t, _ -> t }.collectLatest { (target, pending) ->
                if (target != shown) {
                    shown = target
                    generation++
                    _busy.value = null
                    _renameError.value = null
                    _deleteError.value = null
                    _busyChats.value = emptyMap()
                    _botChat.value = null
                    busyUnsupported = false
                    botChatLogged = false
                }
                _state.value = Load.Loading
                val loaded = pending ?: try {
                    target.load { client.list(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed(HermesError.Storage(e.message ?: e.javaClass.simpleName))
                }
                _state.value = loaded
                // The hidden forever chat rides the same load the list just did.
                (loaded as? Load.Ready)?.let { fetchBotChat(it.source) }
                // Pins of chats beyond the newest SESSIONS_LIST_LIMIT are not fetched by id: that read also returns rows the list hides, so a chat could show twice.
                dropPinsOfMissingChats(loaded)
            }
        }
    }

    /**
     * A list shorter than the limit holds every chat of the bot, so a pin whose chat is not in it points at a chat that
     * is gone (deleted elsewhere): drop it, or it would count toward the cap with no row to unpin. A full list may be
     * cut, so nothing is dropped then; an empty one is not trusted either (a hiccup must not wipe the pins).
     */
    private suspend fun dropPinsOfMissingChats(load: Load<List<SessionDto>>) {
        val store = pins ?: return
        val ready = load as? Load.Ready ?: return
        if (ready.value.isEmpty() || ready.value.size >= SESSIONS_LIST_LIMIT) return
        try {
            store.retain(ready.source.id, ready.source.target.profile ?: ready.source.target.bridge?.profileId, ready.value.map { it.id }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The pins stay as they were.
        }
    }

    /** Screen Retry: restart the shared PC observation too, then reload. */
    fun retry() {
        pcs.retry()
        refresh()
    }

    fun refresh() { refreshes.update { it + 1 } }

    /** The Sessions screen is visible (lifecycle STARTED): while it is, the list and the busy map
     * refresh every [pollMs]; when it stops, the refresh stops with it. */
    fun onVisible() {
        if (pollJob?.isActive != true) pollJob = viewModelScope.launch { pollLoop() }
    }

    fun onHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollLoop() {
        while (true) {
            delay(pollMs)
            refreshShownQuietly()
        }
    }

    /**
     * The background refresh: never writes Loading, keeps the shown list and scroll on a failure,
     * and never surfaces an error (a pull-to-refresh failure still does). Only a Ready list is
     * touched, and only while it still shows the target it was read from.
     */
    private suspend fun refreshShownQuietly() {
        val current = _state.value as? Load.Ready ?: return
        val source = current.source
        val listed = try {
            when (val r = client.list(source.target)) {
                is HermesResult.Ok -> current.copy(value = r.value)
                is HermesResult.Err -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (listed != null && (_state.value as? Load.Ready)?.source == source) {
            _state.value = listed
            dropPinsOfMissingChats(listed)
        }
        val api = if (busyUnsupported) null else busyApiFor(source.target)
        if (api == null) {
            if (_busyChats.value.isNotEmpty()) _busyChats.value = emptyMap()
            return
        }
        try {
            when (val r = api.list()) {
                is BusyChatsResult.Ok ->
                    if ((_state.value as? Load.Ready)?.source == source) _busyChats.value = r.busy
                BusyChatsResult.Unsupported -> {
                    if ((_state.value as? Load.Ready)?.source == source) {
                        busyUnsupported = true
                        _busyChats.value = emptyMap()
                    }
                }
                BusyChatsResult.Failed -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The last known map stays.
        }
    }

    /**
     * Re-reads the shown source's hidden forever chat. Any failure maps to null (the row simply hides) and is
     * logged once per source at debug level, never changing the list's Load state, error or retry. A late
     * answer is applied only while its source is still the shown one.
     */
    private suspend fun fetchBotChat(source: ActiveTarget.Ready) {
        val chat = try {
            when (val r = client.botChat(source.target)) {
                is HermesResult.Ok -> r.value
                is HermesResult.Err -> {
                    logBotChatOnce()
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logBotChatOnce()
            null
        }
        if ((_state.value as? Load.Ready)?.source == source) _botChat.value = chat
    }

    private fun logBotChatOnce() {
        if (!botChatLogged) {
            botChatLogged = true
            Log.d("SessionsViewModel", "bot chat lookup failed")
        }
    }

    fun clearRenameError() { _renameError.value = null }

    fun clearDeleteError() { _deleteError.value = null }

    /** Rename the exact session and update its existing row without a list reload. */
    fun rename(
        source: ActiveTarget.Ready,
        session: SessionDto,
        title: String,
        onRenamed: () -> Unit = {},
    ) {
        if (_busy.value != null || title.isBlank()) return
        val capturedSource = source
        val capturedSessionId = session.id
        val capturedTitle = title.trim()
        val capturedTarget = source.target
        val capturedCompletion = onRenamed
        val gen = generation
        _renameError.value = null
        _deleteError.value = null
        _busy.value = capturedSessionId
        viewModelScope.launch {
            try {
                val now = pcs.currentTarget()
                val error = when {
                    now is ActiveTarget.Unreadable -> HermesError.Storage(now.detail)
                    now is ActiveTarget.Failed -> now.error
                    now != capturedSource -> HermesError.ConnectionChanged
                    else -> null
                }
                if (error != null) {
                    if (gen == generation) _renameError.value = error
                    if (error == HermesError.ConnectionChanged) refresh()
                    return@launch
                }
                when (val response = client.rename(capturedTarget, capturedSessionId, capturedTitle)) {
                    is HermesResult.Err -> if (gen == generation) _renameError.value = response.error
                    is HermesResult.Ok -> {
                        val current = _state.value
                        if (gen == generation && current is Load.Ready && current.source == capturedSource) {
                            _state.value = current.copy(
                                value = current.value.map { if (it.id == capturedSessionId) response.value else it },
                            )
                            capturedCompletion()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _renameError.value = HermesError.Storage(e.message ?: e.javaClass.simpleName)
            } finally {
                if (gen == generation) _busy.value = null
            }
        }
    }

    /** Delete a chat from the rendered source and remove it without clearing the list. */
    fun delete(source: ActiveTarget.Ready, session: SessionDto, onDeleted: () -> Unit = {}) {
        if (_busy.value != null) return
        val capturedSource = source
        val capturedSessionId = session.id
        val capturedTarget = source.target
        val capturedCompletion = onDeleted
        val gen = generation
        _renameError.value = null
        _deleteError.value = null
        _busy.value = capturedSessionId
        viewModelScope.launch {
            try {
                val now = pcs.currentTarget()
                val error = when {
                    now is ActiveTarget.Unreadable -> HermesError.Storage(now.detail)
                    now is ActiveTarget.Failed -> now.error
                    now != capturedSource -> HermesError.ConnectionChanged
                    else -> null
                }
                if (error != null) {
                    if (gen == generation) _deleteError.value = error
                    if (error == HermesError.ConnectionChanged) refresh()
                    return@launch
                }
                when (val response = client.delete(capturedTarget, capturedSessionId)) {
                    is HermesResult.Err -> if (gen == generation) _deleteError.value = response.error
                    is HermesResult.Ok -> {
                        val pointerError = try {
                            onSessionDeleted(capturedSessionId)
                            store.clearSessionPointers(capturedSessionId)
                            drafts?.clearSession(capturedSessionId)
                            pins?.removeSession(capturedSessionId)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            HermesError.Storage(e.message ?: e.javaClass.simpleName)
                        }
                        val current = _state.value
                        if (gen == generation && current is Load.Ready && current.source == capturedSource) {
                            _state.value = current.copy(value = current.value.filterNot { it.id == capturedSessionId })
                            if (pointerError != null) _deleteError.value = pointerError
                            capturedCompletion()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _deleteError.value = HermesError.Storage(e.message ?: e.javaClass.simpleName)
            } finally {
                if (gen == generation) _busy.value = null
            }
        }
    }

    companion object {
        /** How often the visible list refreshes itself and re-asks the bridge which chats reply elsewhere. */
        const val LIST_POLL_MS = 5_000L
    }
}

/**
 * In-memory hand-off of the connection a session list was read from. The route carries only an opaque token,
 * so the API key never goes into navigation arguments.
 * The map keeps one source token per transcript opened during the current app process.
 */
class TranscriptSources {
    private val byToken = ConcurrentHashMap<String, ActiveTarget.Ready>()
    fun put(source: ActiveTarget.Ready): String = UUID.randomUUID().toString().also { byToken[it] = source }
    fun get(token: String): ActiveTarget.Ready? = byToken[token]
}

internal fun canContinueSession(source: ActiveTarget.Ready?, sessionSource: String?): Boolean =
    source != null && !isCronSessionSource(sessionSource)

/**
 * Read-only transcript of one session. [source] is the exact connection the session list came from (id, name,
 * address, profile, key). Any change to it since then -> ConnectionChanged, nothing is requested. A null
 * [source] (the hand-off was lost, e.g. process death) is refused the same way.
 */
class TranscriptViewModel(
    private val store: ConnectionStore,
    private val client: SessionsClient,
    private val sessionId: String,
    private val source: ActiveTarget.Ready?,
    private val currentTarget: suspend () -> ActiveTarget = { store.activeTarget() },
    private val sessionSource: String? = null,
    private val busySession: suspend (connectionId: String, profileId: String?) -> String? = { _, _ -> null },
) : ViewModel() {
    private val _continuing = MutableStateFlow(false)
    val continuing: StateFlow<Boolean> = _continuing
    val isCronSession: Boolean get() = isCronSessionSource(sessionSource)
    val canContinue: Boolean get() = canContinueSession(source, sessionSource)
    private val _state = MutableStateFlow<Load<List<SessionMessageDto>>>(Load.Loading)
    val state: StateFlow<Load<List<SessionMessageDto>>> = _state
    private var job: Job? = null

    init { refresh() }

    suspend fun continueSession(): Boolean {
        val selected = source ?: return false
        if (!canContinue || state.value !is Load.Ready || _continuing.value) return false
        val profile = selected.target.bridge?.profileId ?: selected.target.profile
        _continuing.value = true
        return try {
            val busy = busySession(selected.id, profile)
            if (busy != null && busy != sessionId) {
                _state.value = Load.Failed(HermesError.ChatBusy)
                return false
            }
            val result = store.continueSession(selected.id, profile, selected.target.revision, sessionId)
            if (result != ConnectionStore.ContinueResult.Selected) {
                _state.value = Load.Failed(if (result == ConnectionStore.ContinueResult.Busy) HermesError.ChatBusy else HermesError.ConnectionChanged)
            }
            result == ConnectionStore.ContinueResult.Selected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = Load.Failed(HermesError.Storage(e.message ?: e.javaClass.simpleName))
            false
        } finally {
            _continuing.value = false
        }
    }

    fun refresh() {
        job?.cancel()
        _state.value = Load.Loading
        job = viewModelScope.launch {
            _state.value = try {
                if (source == null) {
                    Load.Failed(HermesError.ConnectionChanged)
                } else {
                    // Same full comparison as JobsViewModel.act: ActiveTarget.Ready equality covers id, name, address, profile, key.
                    currentTarget().load(accept = { it == source }) { client.messages(it, sessionId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(HermesError.Storage(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
