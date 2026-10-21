package app.rasul.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.DeviceIdentity
import app.rasul.bridge.KeystoreDeviceIdentity
import app.rasul.bridge.SavedBridge
import app.rasul.bridge.deleteDeviceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.util.Base64
import java.util.UUID

sealed interface PairingState {
    data object Scanning : PairingState
    data class Sending(val name: String, val code: String? = null) : PairingState
    data class Failed(val message: Int) : PairingState
    data class Paired(val name: String) : PairingState
}

class PairingViewModel(
    private val store: BridgeStore,
    private val service: PairingService = PairingClient(),
    private val identity: (String) -> DeviceIdentity = ::KeystoreDeviceIdentity,
    private val deleteKey: (String) -> Unit = ::deleteDeviceKey,
    private val v2Enabled: Boolean = app.rasul.BuildConfig.RASUL_V2,
) : ViewModel() {
    private val mutable = MutableStateFlow<PairingState>(PairingState.Scanning)
    val state: StateFlow<PairingState> = mutable
    fun cameraFailed() { if (mutable.value == PairingState.Scanning) mutable.value = PairingState.Failed(app.rasul.R.string.pair_camera_failed) }
    fun reset() { if (mutable.value !is PairingState.Sending) mutable.value = PairingState.Scanning }
    fun scan(raw: String) {
        if (mutable.value != PairingState.Scanning) return
        when (val parsed = PairingQrParser.parse(raw)) {
            is PairingQrResult.Invalid -> mutable.value = PairingState.Failed(
                parsed.error.messageFor(if (raw.startsWith("rasul-pair:2:")) 2 else 1))
            is PairingQrResult.Valid -> {
                val invitation = parsed.invitation
                if (invitation.protocol == 2 && !v2Enabled) {
                    mutable.value = PairingState.Failed(app.rasul.R.string.pair_newer_host)
                    return
                }
                val alias = "bridge-device-" + UUID.randomUUID()
                mutable.value = PairingState.Sending(invitation.name)
                viewModelScope.launch {
                    var saved = false
                    var failure = PairingFailure.Identity
                    try {
                        if (store.get(invitation.bridgeId) != null) {
                            mutable.value = PairingState.Failed(
                                if (invitation.protocol == 2) app.rasul.R.string.pair_v2_already_saved else app.rasul.R.string.pair_already_saved)
                            return@launch
                        }
                        val device = withContext(Dispatchers.IO) { identity(alias) }
                        failure = PairingFailure.Connection
                        val enrollment = service.enroll(invitation, device) { code ->
                            mutable.value = PairingState.Sending(invitation.name, code)
                        }
                        failure = PairingFailure.Storage
                        withContext(kotlinx.coroutines.NonCancellable) {
                            store.save(SavedBridge(invitation.bridgeId, invitation.name, invitation.urls,
                                Base64.getUrlEncoder().withoutPadding().encodeToString(invitation.pin), enrollment.deviceId, alias, enrollment.lastWorkingUrl,
                                invitation.protocol, enrollment.role))
                            saved = true
                        }
                        mutable.value = PairingState.Paired(invitation.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.value = PairingState.Failed(when (e) {
                            is PairingException -> e.failure
                            is InterruptedIOException -> PairingFailure.Timeout
                            else -> failure
                        }.messageFor(invitation.protocol))
                    } finally {
                        if (!saved) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { deleteKey(alias) }
                    }
                }
            }
        }
    }
}
