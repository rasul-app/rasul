package app.rasul.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.ActiveTarget
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
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
import kotlinx.coroutines.flow.MutableStateFlow
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
                }
                _state.value = Load.Loading
                _state.value = pending ?: try {
                    target.load { client.list(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed(HermesError.Storage(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Screen Retry: restart the shared PC observation too, then reload. */
    fun retry() {
        pcs.retry()
        refresh()
    }

    fun refresh() { refreshes.update { it + 1 } }

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
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<SessionMessageDto>>>(Load.Loading)
    val state: StateFlow<Load<List<SessionMessageDto>>> = _state
    private var job: Job? = null

    init { refresh() }

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
