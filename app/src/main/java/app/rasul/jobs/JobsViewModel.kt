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
import app.rasul.ui.pendingLoad
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
    private val client: JobsApi,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<JobDto>>>(Load.Loading)
    val state: StateFlow<Load<List<JobDto>>> = _state

    /** Cause of the last failed pause/resume/run, or null. */
    private val _actionError = MutableStateFlow<HermesError?>(null)
    val actionError: StateFlow<HermesError?> = _actionError

    /** Error from creating or editing a job; shown inside the open form. */
    private val _formError = MutableStateFlow<HermesError?>(null)
    val formError: StateFlow<HermesError?> = _formError

    private val _deleteError = MutableStateFlow<HermesError?>(null)
    val deleteError: StateFlow<HermesError?> = _deleteError

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
                pcs.state.filter { it.loaded }.map { it.target to it.pendingLoad() }.distinctUntilChanged(),
                refreshes,
            ) { t, _ -> t }.collectLatest { (target, pending) ->
                if (target != shown) {
                    shown = target
                    generation++
                    _actionError.value = null
                    _busy.value = null
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

    fun clearFormError() { _formError.value = null }

    fun clearDeleteError() { _deleteError.value = null }

    /** Save the exact values supplied by the open form, then replace or append its row in place. */
    fun save(
        source: ActiveTarget.Ready,
        job: JobDto?,
        name: String,
        schedule: String,
        prompt: String,
        onSaved: () -> Unit = {},
    ) {
        if (_busy.value != null || name.isBlank() || schedule.isBlank()) return
        val capturedSource = source
        val capturedJob = job
        val capturedName = name.trim()
        val capturedSchedule = schedule.trim()
        val capturedPrompt = prompt
        val capturedTarget = source.target
        val capturedCompletion = onSaved
        val updateName = capturedJob?.let { existing ->
            capturedName.takeIf { it != existing.name.orEmpty().trim() }
        }
        val updateSchedule = capturedJob?.let { existing ->
            capturedSchedule.takeIf { it != existing.scheduleInput.trim() }
        }
        val updatePrompt = capturedJob?.let { existing ->
            capturedPrompt.takeIf { it != existing.prompt.orEmpty() }
        }
        if (capturedJob != null && updateName == null && updateSchedule == null && updatePrompt == null) {
            _formError.value = null
            _actionError.value = null
            capturedCompletion()
            return
        }
        val gen = generation
        _formError.value = null
        _actionError.value = null
        _busy.value = capturedJob?.id ?: "create"
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
                    if (gen == generation) _formError.value = error
                    if (error == HermesError.ConnectionChanged) refresh()
                    return@launch
                }
                val response = if (capturedJob == null) {
                    client.create(capturedTarget, capturedName, capturedSchedule, capturedPrompt)
                } else {
                    client.update(capturedTarget, capturedJob.id, updateName, updateSchedule, updatePrompt)
                }
                when (response) {
                    is HermesResult.Err -> if (gen == generation) _formError.value = response.error
                    is HermesResult.Ok -> {
                        val current = _state.value
                        if (gen == generation && current is Load.Ready && current.source == capturedSource) {
                            val rows = if (capturedJob == null) {
                                current.value + response.value
                            } else {
                                current.value.map { if (it.id == capturedJob.id) response.value else it }
                            }
                            _state.value = current.copy(value = rows)
                            capturedCompletion()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) _formError.value = HermesError.Storage(e.message ?: e.javaClass.simpleName)
            } finally {
                if (gen == generation) _busy.value = null
            }
        }
    }

    /** Delete the row rendered from [source] and remove it without clearing the current list. */
    fun delete(source: ActiveTarget.Ready, job: JobDto, onDeleted: () -> Unit = {}) {
        if (_busy.value != null) return
        val capturedSource = source
        val capturedJobId = job.id
        val capturedTarget = source.target
        val capturedCompletion = onDeleted
        val gen = generation
        _actionError.value = null
        _deleteError.value = null
        _busy.value = capturedJobId
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
                when (val response = client.delete(capturedTarget, capturedJobId)) {
                    is HermesResult.Err -> if (gen == generation) _deleteError.value = response.error
                    is HermesResult.Ok -> {
                        val current = _state.value
                        if (gen == generation && current is Load.Ready && current.source == capturedSource) {
                            _state.value = current.copy(value = current.value.filterNot { it.id == capturedJobId })
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
