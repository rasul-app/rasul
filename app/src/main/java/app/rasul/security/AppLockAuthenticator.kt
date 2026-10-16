package app.rasul.security

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import app.rasul.R

data class AppLockAvailability(val available: Boolean, val deviceCredentialAvailable: Boolean)

class AppLockAuthenticator(
    private val activity: FragmentActivity,
    private val launchDeviceCredential: (Intent, () -> Unit, () -> Unit) -> Unit,
) {
    private val biometricManager = BiometricManager.from(activity)
    private val keyguard: KeyguardManager? get() = activity.getSystemService()

    fun availability(): AppLockAvailability {
        val strongBiometric = biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val deviceCredential = keyguard?.isDeviceSecure == true
        val available = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            strongBiometric || deviceCredential
        }
        return AppLockAvailability(available, deviceCredential)
    }

    fun authenticate(onSuccess: () -> Unit) = authenticate(onSuccess, {})

    fun authenticate(onSuccess: () -> Unit, onCancel: () -> Unit) {
        val availability = availability()
        if (!availability.available) { onCancel(); return }

        val deviceCredentialIntent = {
            val intent = keyguard?.createConfirmDeviceCredentialIntent(
                activity.getString(R.string.lock_auth_title),
                activity.getString(R.string.lock_auth_subtitle),
            )
            if (intent != null) launchDeviceCredential(intent, onSuccess, onCancel) else onCancel()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_auth_title))
                .setSubtitle(activity.getString(R.string.lock_auth_subtitle))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            biometricPrompt(onSuccess, onCancel).authenticate(promptInfo)
            return
        }

        val strongBiometric = biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (!strongBiometric) {
            deviceCredentialIntent()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_auth_title))
            .setSubtitle(activity.getString(R.string.lock_auth_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(
                activity.getString(if (availability.deviceCredentialAvailable) R.string.lock_auth_device else R.string.lock_auth_cancel),
            )
            .build()
        biometricPrompt(onSuccess, onCancel, deviceCredentialIntent.takeIf { availability.deviceCredentialAvailable })
            .authenticate(promptInfo)
    }

    private fun biometricPrompt(onSuccess: () -> Unit, onCancel: () -> Unit, fallback: (() -> Unit)? = null) =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val mayUseDeviceCredential = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                if (mayUseDeviceCredential && fallback != null) fallback() else onCancel()
            }
        })
}
