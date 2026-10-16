package app.rasul.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.ActiveTarget
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.activeTarget
import app.rasul.data.load
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.PcPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class JobsViewModel(
    private val store: ConnectionStore,
    private val pcs: ActivePcHolder,
    private val client: JobsClient,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<JobDto>>>(Load.Loading)
    val state: StateFlow<Load<List<JobDto>>> = _state

    /** Cause of the last failed pause/resume/run, or null. */
    private val _actionError = MutableStateFlow<HermesError?>(null)
    val actionError: StateFlow<HermesError?> = _actionError

    /** Id of the job an action is running for (its buttons are disabled meanwhile). */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val refreshes = MutableStateFlow(0)

    /** Bumped when the target changes: results of an action that started for an older target are dropped. */
    private var generation = 0

    val picker: StateFlow<PcPicker> = pcs.state
    fun selectPc(id: String) = pcs.selectPc(id)
    fun clearWriteError() = pcs.clearWriteError()

    init {
        // Loads exactly the target the holder published (one snapshot) and nothing else. A new target or Refresh
        // cancels a slower load still running; a changed target also clears the action error and busy state.
        viewModelScope.launch {
            var shown: ActiveTarget? = null
            combine(
                pcs.state.filter { it.loaded }.map { it.target to it.readError }.distinctUntilChanged(),
                refreshes,
            ) { t, _ -> t }.collectLatest { (target, err) ->
                if (target != shown) {
                    shown = target
                    generation++
                    _actionError.value = null
                    _busy.value = null
                }
                _state.value = Load.Loading
                _state.value = if (err != null) Load.Failed(HermesError.Storage(err)) else try {
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

    /** [source] is the connection the tapped row was rendered from. */
    fun togglePause(source: ActiveTarget.Ready, j: JobDto) {
        if (j.jobState.isTerminal) return
        act(source, j.id) { t -> if (j.isPaused) client.resume(t, j.id) else client.pause(t, j.id) }
    }

    fun runNow(source: ActiveTarget.Ready, j: JobDto) {
        if (j.jobState.isTerminal) return
        act(source, j.id) { t -> client.runNow(t, j.id) }
    }

    fun clearActionError() { _actionError.value = null }

    /**
     * The action goes to [source], the connection that produced the row the user tapped, never to whatever is
     * active now. If the store's active connection is no longer exactly [source] when this runs, nothing is sent:
     * the user gets a specific message and the list reloads.
     */
    private fun act(source: ActiveTarget.Ready, id: String, call: suspend (HermesTarget) -> HermesResult<JobDto>) {
        if (_busy.value != null) return
        _busy.value = id
        val gen = generation
        viewModelScope.launch {
            try {
                val now = pcs.currentTarget()
                val result = when {
                    now is ActiveTarget.Unreadable -> HermesError.Storage(now.detail)
                    now is ActiveTarget.Failed -> now.error
                    now != source -> HermesError.ConnectionChanged
                    else -> when (val r = call(source.target)) {
                        is HermesResult.Err -> r.error
                        is HermesResult.Ok -> {
                            val cur = _state.value
                            if (gen == generation && cur is Load.Ready && cur.source == source) {
                                _state.value = cur.copy(value = cur.value.map { if (it.id == id) r.value else it })
                            }
                            null
                        }
                    }
                }
                if (gen == generation && result != null) {
                    _actionError.value = result
                    if (result == HermesError.ConnectionChanged) refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _actionError.value = HermesError.Storage(e.message ?: e.javaClass.simpleName)
            } finally {
                if (gen == generation) _busy.value = null
            }
        }
    }
}
