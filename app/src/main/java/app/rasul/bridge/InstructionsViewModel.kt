package app.rasul.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class InstructionsState(
    val direct: Boolean = false,
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val text: String = "",
    val savedText: String = "",
    val revision: String? = null,
    val saving: Boolean = false,
    val error: InstructionsError? = null,
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = loaded && text != savedText
    val busy: Boolean get() = loading || saving
    val canSave: Boolean get() = !direct && loaded && dirty && !busy && error != InstructionsError.Conflict
}

/** The service is bound to the chat's bridge and profile, independent of active-PC changes. */
class InstructionsViewModel(private val service: (suspend () -> InstructionsApi)?) : ViewModel() {
    private val mutable = MutableStateFlow(InstructionsState(direct = service == null, loading = false))
    val state: StateFlow<InstructionsState> = mutable

    init { reload() }

    fun reload() {
        val loadService = service ?: return
        if (state.value.busy) return
        mutable.value = state.value.copy(loading = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                val value = loadService().load()
                mutable.value = InstructionsState(loading = false, loaded = true, text = value.text,
                    savedText = value.text, revision = value.revision)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(loading = false, error = failure(e)) }
        }
    }

    fun edit(text: String) {
        val current = state.value
        if (current.direct || !current.loaded || current.busy) return
        mutable.value = current.copy(text = text, saved = false,
            error = current.error.takeIf { it == InstructionsError.Conflict })
    }

    fun save() {
        val current = state.value
        val saveService = service ?: return
        if (!current.canSave) return
        val revision = current.revision ?: return
        val document = InstructionsDocument(current.text, revision)
        if (document.text.toByteArray(Charsets.UTF_8).size > MAX_INSTRUCTIONS_BYTES) {
            mutable.value = current.copy(error = InstructionsError.TooLarge)
            return
        }
        mutable.value = current.copy(saving = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                val savedRevision = saveService().save(document)
                mutable.value = state.value.copy(saving = false, savedText = document.text,
                    revision = savedRevision, saved = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(saving = false, error = failure(e)) }
        }
    }

    private fun failure(error: Exception) = (error as? InstructionsException)?.error ?: instructionsTransportError(error)
}
