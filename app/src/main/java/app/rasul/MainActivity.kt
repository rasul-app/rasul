package app.rasul

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.appcompat.app.AppCompatActivity
import app.rasul.security.AppLockAuthenticator
import app.rasul.security.appLockBlocksContent
import app.rasul.ui.AppLockScreen
import app.rasul.ui.RasulNav
import app.rasul.ui.theme.RasulTheme
import app.rasul.settings.AppPreferences
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
    private var appearanceLoaded = false

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
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!appearanceLoaded) return false
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        container = (application as RasulApp).container
        openNotificationChat(intent)
        openNotificationApprovals(intent)
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
            AppAppearanceContent(container.settings.values, onAppearance = { dark ->
                applyAppAppearance(dark)
                appearanceLoaded = true
            }) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotificationChat(intent)
        openNotificationApprovals(intent)
    }

    private fun openNotificationChat(intent: Intent?) {
        val route = intent?.getStringExtra(app.rasul.notifications.CHAT_ROUTE) ?: return
        intent.removeExtra(app.rasul.notifications.CHAT_ROUTE)
        if (app.rasul.notifications.isChatRoute(route)) {
            container.appScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (container.runWatcher.resolve(route) != null) container.pendingChat.value = route
            }
        }
    }

    private fun openNotificationApprovals(intent: Intent?) {
        val route = intent?.getStringExtra(app.rasul.notifications.APPROVALS_ROUTE) ?: return
        intent.removeExtra(app.rasul.notifications.APPROVALS_ROUTE)
        if (app.rasul.notifications.isApprovalsRoute(route)) container.pendingChat.value = route
    }

    override fun onStart() {
        super.onStart()
        if (::authenticator.isInitialized) authenticationAvailable = authenticator.availability().available
        if (::container.isInitialized) {
            container.appLock.onForeground(SystemClock.elapsedRealtime())
            container.runWatcher.onForeground()
            container.appScope.launch { container.approvalAlerts.sync() }
        }
    }

    override fun onStop() {
        if (::container.isInitialized) {
            container.runWatcher.onBackground()
            container.appLock.onBackground(SystemClock.elapsedRealtime())
        }
        super.onStop()
    }

    override fun onDestroy() {
        credentialState.cancel()
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }
}

@Composable
internal fun AppHapticFeedbackProvider(enabled: Boolean, content: @Composable () -> Unit) {
    val feedback = if (enabled) LocalHapticFeedback.current else NoOpHapticFeedback
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect { view.isHapticFeedbackEnabled = enabled }
    CompositionLocalProvider(LocalAppHapticsEnabled provides enabled, LocalHapticFeedback provides feedback, content = content)
}

private object NoOpHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

internal fun applySystemBarAppearance(window: android.view.Window, dark: Boolean) {
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

@Composable
internal fun AppAppearanceContent(
    values: kotlinx.coroutines.flow.Flow<AppPreferences>,
    onAppearance: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val preferences by values.collectAsState(initial = null)
    val loaded = preferences ?: return
    val dark = loaded.appearance.isDark(isSystemInDarkTheme())
    SideEffect {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (loaded.appearance) {
            app.rasul.settings.AppearanceOption.System -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            app.rasul.settings.AppearanceOption.Light -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            app.rasul.settings.AppearanceOption.Dark -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        })
        onAppearance(dark)
    }
    AppHapticFeedbackProvider(loaded.hapticsEnabled) {
        RasulTheme(dark = dark, content = content)
    }
}

internal fun androidx.activity.ComponentActivity.applyAppAppearance(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        navigationBarStyle = androidx.activity.SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark },
    )
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
        if (dark) 0xFF111113.toInt() else android.graphics.Color.WHITE,
    ))
    applySystemBarAppearance(window, dark)
}

private val LocalAppHapticsEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
internal fun WindowHapticFeedbackProvider(content: @Composable () -> Unit) {
    AppHapticFeedbackProvider(LocalAppHapticsEnabled.current, content)
}
