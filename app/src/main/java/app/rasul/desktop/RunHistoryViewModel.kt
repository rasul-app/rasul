package app.rasul.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.BridgeClients
import app.rasul.bridge.BridgeDesktopApi
import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.JobRunDto
import app.rasul.bridge.desktopFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RunHistoryState(
    /** No Rasul Bridge on this bot: the bridge-only row set is replaced by one explanatory line. */
    val direct: Boolean = false,
    /** The PC this history was opened for is no longer among the saved bridges (e.g. unpaired since). */
    val removed: Boolean = false,
    val loading: Boolean = false,
    val error: DesktopError? = null,
    val runs: List<JobRunDto> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Rebuilds the run-history service after process death, when the in-memory TranscriptSources token is gone.
 * The route carries only the saved bridge id and the bridge profile id — never secrets or URLs. A service
 * that resolves to null means that PC is no longer saved; the screen then shows the removed state.
 */
internal fun runHistoryService(
    bridges: BridgeStore,
    clients: BridgeClients,
    bridgeId: String,
    profileId: String,
    api: (BridgeEndpoint) -> DesktopApi = ::BridgeDesktopApi,
): suspend () -> DesktopApi? = {
    val saved = bridges.get(bridgeId)
    if (saved == null) null else api(BridgeEndpoint(clients.client(saved).client, profileId))
}

/** The service is bound to the exact connection the job list was read from, or restored from the route ids. */
class RunHistoryViewModel(
    private val jobId: String,
    private val service: (suspend () -> DesktopApi?)?,
) : ViewModel() {
    private val _state = MutableStateFlow(RunHistoryState(direct = service == null, loading = service != null))
    val state: StateFlow<RunHistoryState> = _state
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        val api = service ?: return
        job?.cancel()
        _state.value = RunHistoryState(loading = true)
        job = viewModelScope.launch {
            try {
                val desktop = api()
                if (desktop == null) {
                    _state.value = RunHistoryState(removed = true)
                    return@launch
                }
                _state.value = RunHistoryState(runs = desktop.jobRuns(jobId), loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = RunHistoryState(error = desktopFailure(e)) }
        }
    }
}
