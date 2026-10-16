package app.rasul

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.appcompat.app.AppCompatActivity
import app.rasul.security.AppLockAuthenticator
import app.rasul.security.appLockBlocksContent
import app.rasul.ui.AppLockScreen
import app.rasul.ui.RasulNav
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.launch

class CredentialConfirmation : androidx.lifecycle.ViewModel() {
    private var success: (() -> Unit)? = null
    private var cancelled: (() -> Unit)? = null
    fun begin(onSuccess: () -> Unit, onCancel: () -> Unit) { cancel(); success = onSuccess; cancelled = onCancel }
    fun complete(confirmed: Boolean) {
        val callback = if (confirmed) success else cancelled
        success = null
        cancelled = null
        callback?.invoke()
    }
    fun cancel() = complete(false)
}

class MainActivity : AppCompatActivity() {
    private lateinit var container: AppContainer
    private lateinit var authenticator: AppLockAuthenticator
    private lateinit var credentialLauncher: ActivityResultLauncher<Intent>
    private val credentialState by lazy { androidx.lifecycle.ViewModelProvider(this)[CredentialConfirmation::class.java] }
    private var authenticationAvailable by mutableStateOf(false)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) container.appLock.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            credentialState.complete(result.resultCode == RESULT_OK)
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        container = (application as RasulApp).container
        authenticator = AppLockAuthenticator(this) { credentialIntent, onSuccess, onCancel ->
            credentialState.begin(onSuccess, onCancel)
            credentialLauncher.launch(credentialIntent)
        }
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            val lockState by container.appLock.state.collectAsState()
            SideEffect {
                if (lockState.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            RasulTheme {
                when {
                    !lockState.loaded -> Box(Modifier.fillMaxSize().background(app.rasul.ui.theme.Rasul.colors.bg), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    appLockBlocksContent(lockState) -> AppLockScreen(
                        readError = lockState.readError,
                        authenticationAvailable = authenticationAvailable,
                        onOpenSecuritySettings = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                        onRetry = { container.appLock.retryRead() },
                    ) {
                        authenticator.authenticate { container.appLock.unlock() }
                    }
                    else -> RasulNav(
                        container = container,
                        appLockEnabled = lockState.enabled,
                        appLockAvailable = authenticator.availability().available,
                        authenticateCreation = { success, cancel -> authenticator.authenticate(success, cancel) },
                        onAppLockChanged = { enabled ->
                            if (enabled) {
                                container.appScope.launch { runCatching { container.appLock.setEnabled(true) } }
                            } else {
                                authenticator.authenticate {
                                    container.appScope.launch { runCatching { container.appLock.setEnabled(false) } }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::authenticator.isInitialized) authenticationAvailable = authenticator.availability().available
        if (::container.isInitialized) container.appLock.onForeground(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        if (::container.isInitialized) container.appLock.onBackground(SystemClock.elapsedRealtime())
        super.onStop()
    }

    override fun onDestroy() {
        credentialState.cancel()
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}
