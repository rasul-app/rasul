package app.rasul.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun fingerprints(signatures: Array<Signature>?): Set<String>? =
    signatures?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
        ?.toSet()

@Suppress("DEPRECATION")
class AndroidApkSigners(private val context: Context) : ApkSigners {
    private val pm get() = context.packageManager

    private fun of(info: android.content.pm.PackageInfo?): ApkIdentity? {
        info ?: return null
        val signers = (
            if (Build.VERSION.SDK_INT >= 28) fingerprints((info.signingInfo ?: return null).apkContentsSigners)
            else fingerprints(info.signatures)
            ) ?: return null
        return ApkIdentity(info.packageName ?: return null, PackageInfoCompat.getLongVersionCode(info), signers)
    }

    private val flags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    override fun ofApk(file: File): ApkIdentity? =
        runCatching { of(pm.getPackageArchiveInfo(file.path, flags)) }.getOrNull()

    override fun ofInstalled(): ApkIdentity? =
        runCatching { of(pm.getPackageInfo(context.packageName, flags)) }.getOrNull()
}

/** Installs through a PackageInstaller session; the system asks the user to confirm. */
class SessionApkInstaller(private val context: Context) : ApkInstaller {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = try {
            installer.createSession(params)
        } catch (e: java.io.IOException) {
            throw UpdateException(UpdateError.InstallFailed("session"))
        }
        val action = context.packageName + ".UPDATE_RESULT." + id
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            runCatching { c.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(Unit)
                        }
                        else -> {
                            runCatching { c.unregisterReceiver(this) }
                            if (cont.isActive) {
                            val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                            cont.resumeWithException(UpdateException(UpdateError.InstallFailed("status $code")))
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { installer.abandonSession(id) }
            }
            try {
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("rasul.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(action).setPackage(context.packageName)
                    val pending = PendingIntent.getBroadcast(
                        context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                }
            } catch (e: java.io.IOException) {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { installer.abandonSession(id) }
                if (cont.isActive) cont.resumeWithException(UpdateException(UpdateError.InstallFailed("write")))
            }
        }
    }
}
