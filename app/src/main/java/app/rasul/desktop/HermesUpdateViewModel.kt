package app.rasul.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.BridgeStatusApi
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.HermesUpdateDto
import app.rasul.bridge.desktopFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val UPDATE_POLL_MS = 5_000L
internal const val PC_INFO_BACKGROUND_MS = 6 * 60 * 60 * 1000L
internal const val PC_INFO_MIN_GAP_MS = 60_000L

data class HermesUpdateState(
    /** No Rasul Bridge on this bot: the screen shows one explanatory line. */
    val direct: Boolean = false,
    val loading: Boolean = false,
    /** A read failure while there is nothing to show yet. */
    val error: DesktopError? = null,
    val status: HermesUpdateDto? = null,
    /** A start or cancel request is in flight. */
    val busy: Boolean = false,
    val actionError: DesktopError? = null,
    /** The last poll failed; [status] is the last known one (the PC agent restarts during an update). */
    val linkLost: Boolean = false,
    /** Why the last poll failed (null when it worked); [linkLost] is set together with it. */
    val pollError: DesktopError? = null,
)

/**
 * The update screen of one PC. While the screen is visible and an update is queued or running, the status is
 * read every [pollMs]; otherwise nothing runs in the background.
 */
class HermesUpdateViewModel(
    private val service: (suspend () -> DesktopApi)?,
    private val pollMs: Long = UPDATE_POLL_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(HermesUpdateState(direct = service == null, loading = service != null))
    val state: StateFlow<HermesUpdateState> = _state
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var generation = 0

    init { refresh() }

    fun refresh() {
        val api = service ?: return
        loadJob?.cancel()
        val gen = ++generation
        _state.value = HermesUpdateState(loading = true, status = _state.value.status)
        loadJob = viewModelScope.launch {
            try {
                val status = api().hermesUpdate()
                if (gen == generation) _state.value = HermesUpdateState(status = status)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen == generation) _state.value = HermesUpdateState(error = desktopFailure(e), status = _state.value.status)
            }
        }
    }

    /** The screen came to the foreground: poll while an update is queued or running. */
    fun onVisible() {
        if (service == null || pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                pause(pollMs)
                val current = _state.value
                val status = current.status ?: continue
                if (current.busy || current.loading || !(status.isQueued || status.isRunning)) continue
                quietRead()
            }
        }
    }

    fun onHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun quietRead() {
        val api = service ?: return
        val gen = generation
        try {
            val status = api().hermesUpdate()
            if (gen == generation) _state.value = _state.value.copy(status = status, linkLost = false, pollError = null, error = null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (gen == generation) _state.value = _state.value.copy(linkLost = true, pollError = desktopFailure(e))
        }
    }

    fun updateWhenIdle() = start(HermesUpdateDto.MODE_WHEN_IDLE)

    fun updateNow() = start(HermesUpdateDto.MODE_NOW)

    /** The mode is fixed by the tapped button before the request suspends. */
    private fun start(mode: String) = act { it.startHermesUpdate(mode) }

    fun cancel() = act { it.cancelHermesUpdate() }

    private fun act(call: suspend (DesktopApi) -> HermesUpdateDto) {
        val api = service ?: return
        val current = _state.value
        if (current.busy || current.loading) return
        _state.value = current.copy(busy = true, actionError = null)
        val gen = ++generation
        viewModelScope.launch {
            try {
                val status = call(api())
                if (gen == generation) _state.value = _state.value.copy(busy = false, status = status, linkLost = false, pollError = null, error = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen != generation) return@launch
                _state.value = _state.value.copy(busy = false, actionError = desktopFailure(e))
                // The refusal usually means the stored status is stale (queued elsewhere, already up to date).
                quietRead()
            }
        }
    }

    fun dismissActionError() {
        _state.value = _state.value.copy(actionError = null)
    }
}

/** What the Hermes and Check-connection rows in More show for one PC. */
data class PcInfoState(
    val bridgeVersion: String? = null,
    val hermesVersion: String? = null,
    val updateAvailable: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Reads versions and the update badge for the active PC: when More opens (at most once a minute) and every
 * six hours while this model lives. No background job beyond that.
 */
class PcInfoViewModel(
    private val status: (suspend () -> BridgeStatusApi)?,
    private val update: (suspend () -> DesktopApi)?,
    private val now: () -> Long = System::currentTimeMillis,
    private val backgroundMs: Long = PC_INFO_BACKGROUND_MS,
    private val minGapMs: Long = PC_INFO_MIN_GAP_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(PcInfoState())
    val state: StateFlow<PcInfoState> = _state
    private var lastRead: Long? = null
    private var readJob: Job? = null

    init {
        if (status != null || update != null) {
            viewModelScope.launch {
                while (true) {
                    pause(backgroundMs)
                    read()
                }
            }
        }
        onOpened()
    }

    /** Called each time More is shown. */
    fun onOpened() {
        val last = lastRead
        if (readJob?.isActive == true || (last != null && now() - last < minGapMs)) return
        readJob = viewModelScope.launch { read() }
    }

    private suspend fun read() {
        lastRead = now()
        var next = _state.value
        status?.let { source ->
            try {
                val s = source().status()
                next = next.copy(bridgeVersion = s.bridgeVersion?.takeIf { it.isNotBlank() }, hermesVersion = s.hermes?.version?.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { next = next.copy(bridgeVersion = null, hermesVersion = null) }
        }
        update?.let { source ->
            try {
                next = next.copy(updateAvailable = source().hermesUpdate().updateAvailable)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { next = next.copy(updateAvailable = false) }
        }
        _state.value = next.copy(loaded = true)
    }
}

data class PcCheckState(
    val direct: Boolean = false,
    val loading: Boolean = false,
    val error: DesktopError? = null,
    val status: app.rasul.bridge.BridgeStatusDto? = null,
)

class PcCheckViewModel(private val service: (suspend () -> BridgeStatusApi)?) : ViewModel() {
    private val _state = MutableStateFlow(PcCheckState(direct = service == null, loading = service != null))
    val state: StateFlow<PcCheckState> = _state
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        val api = service ?: return
        job?.cancel()
        _state.value = PcCheckState(loading = true)
        job = viewModelScope.launch {
            try {
                _state.value = PcCheckState(status = api().status())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = PcCheckState(error = desktopFailure(e)) }
        }
    }
}
