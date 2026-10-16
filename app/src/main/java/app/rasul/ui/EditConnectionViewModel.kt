package app.rasul.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.AddressPolicy
import app.rasul.data.AddressVerdict
import app.rasul.data.CapabilitiesDto
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SecretEncryptionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val caps: CapabilitiesDto) : TestState
    data class Failure(val error: HermesError) : TestState
}

/** Why a save failed: the Keystore could not encrypt the key, or the storage write failed. */
enum class SaveError { Encryption, Storage }

data class EditState(
    val loading: Boolean = false,
    val loadError: String? = null,
    val name: String = "",
    val url: String = "",
    val profile: String = "",
    val key: String = "",
    val hasStoredKey: Boolean = false,
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
    val saveError: SaveError? = null,
    val saved: Boolean = false,
) {
    val verdict: AddressVerdict? get() = if (url.isBlank()) null else AddressPolicy.check(url)
    val urlOk: Boolean get() = verdict is AddressVerdict.Allowed || verdict is AddressVerdict.AllowedPublicHttps
    val canTest: Boolean get() = urlOk && (key.isNotEmpty() || hasStoredKey) && test != TestState.Running
    val canSave: Boolean get() = urlOk && name.isNotBlank() && (key.isNotEmpty() || hasStoredKey) && !saving
}

class EditConnectionViewModel(
    private val store: ConnectionStore,
    private val client: HermesClient,
    private val id: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        EditState(loading = id != null),
    )
    val state: StateFlow<EditState> = _state
    private var testJob: Job? = null

    init {
        if (id != null) viewModelScope.launch {
            try {
                val c = store.get(id)
                val hasKey = c != null && store.apiKey(id) != null
                _state.update {
                    if (c == null) it.copy(loading = false)
                    else it.copy(
                        loading = false, name = c.name, url = c.baseUrl, profile = c.profile.orEmpty(),
                        hasStoredKey = hasKey,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun edit(name: String? = null, url: String? = null, profile: String? = null, key: String? = null) {
        // A test still running was started for the old inputs: drop it so its result cannot land on the new ones.
        testJob?.cancel()
        testJob = null
        _state.update {
            it.copy(
                name = name ?: it.name, url = url ?: it.url, profile = profile ?: it.profile, key = key ?: it.key,
                test = TestState.Idle, saveError = null,
            )
        }
    }

    fun test() {
        val s = _state.value
        if (!s.canTest) return
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Running) }
        testJob = viewModelScope.launch {
            val result = try {
                val key = s.key.ifEmpty { id?.let { store.apiKey(it) }.orEmpty() }
                val target = HermesTarget(s.url, s.profile, key)
                when (val h = client.health(target)) {
                    is HermesResult.Err -> h
                    is HermesResult.Ok -> client.capabilities(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HermesResult.Err(HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
            }
            _state.update {
                it.copy(
                    test = when (result) {
                        is HermesResult.Ok -> TestState.Success(result.value)
                        is HermesResult.Err -> TestState.Failure(result.error)
                    },
                )
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                store.save(Connection(id.orEmpty(), s.name, s.url, s.profile), s.key.ifEmpty { null })
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecretEncryptionException) {
                _state.update { it.copy(saving = false, saveError = SaveError.Encryption) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, saveError = SaveError.Storage) }
            }
        }
    }
}
