package app.rasul.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class CreateBotPhase { Editing, Confirming, Creating, WaitingForBot, Failed, Complete }

data class CreateBotState(
    val name: String = "",
    val templates: List<ProfileTemplate> = emptyList(),
    val template: String? = null,
    val loading: Boolean = true,
    val phase: CreateBotPhase = CreateBotPhase.Editing,
    val error: CreateBotError? = null,
    val profileId: String? = null,
    val hasAttempt: Boolean = false,
) {
    val editable: Boolean get() = phase == CreateBotPhase.Editing && !hasAttempt
    val busy: Boolean get() = phase in setOf(CreateBotPhase.Confirming, CreateBotPhase.Creating, CreateBotPhase.WaitingForBot)
    val canSubmit: Boolean get() = !loading && !busy && phase != CreateBotPhase.Complete &&
        validBotName(name) && templates.any { it.id == template }
}

fun createBotPollDelay(poll: Int): Long = 500L shl poll.coerceIn(0, 4)

class CreateBotViewModel(
    private val service: suspend () -> CreateBotApi,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutable = MutableStateFlow(CreateBotState())
    val state: StateFlow<CreateBotState> = mutable
    private var attempt: CreateBotRequest? = null
    private var operationId: String? = null
    private var closed = false
    private var confirmation: Any? = null

    init { loadTemplates() }

    fun loadTemplates() {
        if (state.value.busy || state.value.hasAttempt) return
        mutable.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val templates = service().templates()
                mutable.value = state.value.copy(templates = templates, template = templates.firstOrNull()?.id, loading = false, error = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(loading = false, error = failure(e)) }
        }
    }

    fun refreshTemplates() {
        if (state.value.busy) return
        editDetails()
        loadTemplates()
    }

    fun name(value: String) { if (state.value.editable) mutable.value = state.value.copy(name = value, error = null) }
    fun template(id: String) {
        if (state.value.editable && state.value.templates.any { it.id == id }) mutable.value = state.value.copy(template = id, error = null)
    }
    fun editDetails() {
        if (state.value.busy || state.value.phase == CreateBotPhase.Complete) return
        attempt = null
        operationId = null
        mutable.value = state.value.copy(phase = CreateBotPhase.Editing, hasAttempt = false, error = null)
    }

    /** Snapshot all choices before confirmation or suspension; retries retain this exact request. */
    fun submit(authenticate: (() -> Unit, () -> Unit) -> Unit) {
        val current = state.value
        if (!current.canSubmit) return
        val request = attempt ?: CreateBotRequest(newId(), current.name, current.template!!).also { attempt = it }
        val prompt = Any().also { confirmation = it }
        mutable.value = current.copy(phase = CreateBotPhase.Confirming, hasAttempt = true, error = null)
        authenticate({
            if (!closed && confirmation === prompt && state.value.phase == CreateBotPhase.Confirming && attempt === request) {
                confirmation = null
                mutable.value = state.value.copy(phase = CreateBotPhase.Creating)
                viewModelScope.launch {
                    try {
                        val api = service()
                        val id = operationId ?: api.create(request).also { operationId = it }
                        var poll = 0
                        while (true) {
                            val operation = api.operation(id)
                            when (operation.state) {
                                "done" -> {
                                    mutable.value = state.value.copy(phase = CreateBotPhase.WaitingForBot, profileId = operation.profileId)
                                    return@launch
                                }
                                "failed" -> {
                                    val error = if (operation.error == "Outcome uncertain; inspect profiles locally") CreateBotError.OutcomeUncertain else CreateBotError.CreationFailed
                                    mutable.value = state.value.copy(phase = CreateBotPhase.Failed, error = error)
                                    return@launch
                                }
                            }
                            wait(createBotPollDelay(poll++))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutable.value = state.value.copy(phase = CreateBotPhase.Failed, error = failure(e)) }
                }
            }
        }, {
            if (!closed && confirmation === prompt && state.value.phase == CreateBotPhase.Confirming && attempt === request) {
                confirmation = null
                if (!current.hasAttempt) attempt = null
                mutable.value = state.value.copy(phase = if (operationId == null) CreateBotPhase.Editing else CreateBotPhase.Failed,
                    hasAttempt = current.hasAttempt)
            }
        })
    }

    fun authenticationUnavailable() {
        if (!state.value.busy) mutable.value = state.value.copy(error = CreateBotError.AuthenticationUnavailable)
    }
    fun botVisible(profiles: List<BridgeProfile>) {
        val current = state.value
        if (current.phase == CreateBotPhase.WaitingForBot && profiles.any { it.id == current.profileId && it.ready }) {
            mutable.value = current.copy(phase = CreateBotPhase.Complete)
        }
    }
    override fun onCleared() { closed = true; confirmation = null }
    private fun failure(e: Exception) = (e as? CreateBotException)?.error ?: createTransportError(e)
}
