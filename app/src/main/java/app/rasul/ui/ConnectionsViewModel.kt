package app.rasul.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionsState {
    data object Loading : ConnectionsState
    data class Loaded(val items: List<Connection>) : ConnectionsState
    data class Failed(val detail: String) : ConnectionsState
}

class ConnectionsViewModel(private val store: ConnectionStore) : ViewModel() {
    private val _state = MutableStateFlow<ConnectionsState>(ConnectionsState.Loading)
    val state: StateFlow<ConnectionsState> = _state

    /** Cause of the last failed delete, or null. */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError

    init {
        viewModelScope.launch {
            try {
                store.connections.collect { _state.value = ConnectionsState.Loaded(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionsState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                store.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _deleteError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }
}
