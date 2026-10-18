package app.rasul.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Who an APK says it is: package name, version code and signing-certificate fingerprints (SHA-256 hex). */
data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Identity of an APK file and of the installed app; null when it cannot be read. */
interface ApkSigners {
    fun ofApk(file: File): ApkIdentity?
    fun ofInstalled(): ApkIdentity?
}

interface ApkInstaller {
    fun canInstall(): Boolean

    /** Hands [file] to the system installer; throws [UpdateException] when it fails. */
    suspend fun install(file: File)
}

enum class UpdatePhase { Downloading, Verifying, Installing }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class CheckFailed(val error: UpdateError) : UpdateState
    data class Available(val release: ReleaseInfo, val showSheet: Boolean) : UpdateState
    data class Working(val release: ReleaseInfo, val phase: UpdatePhase, val progress: Float?) : UpdateState
    data class UpdateFailed(val release: ReleaseInfo, val error: UpdateError) : UpdateState
}

/** Checks GitHub Releases, then downloads, verifies and installs a newer build. */
class UpdateController(
    private val source: UpdateSource,
    private val prefs: UpdatePrefs,
    private val signers: ApkSigners,
    private val installer: ApkInstaller,
    private val installedVersion: String,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val installTimeoutMs: Long = INSTALL_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutable
    private var job: Job? = null

    /** Automatic check: at most once per 12 hours. */
    suspend fun checkIfDue() = check(manual = false)

    /** "Check now": ignores the 12-hour spacing and the snooze. */
    suspend fun checkNow() = check(manual = true)

    private suspend fun check(manual: Boolean) {
        if (!prefs.enabled()) return // setting off: no network call at all
        val current = mutable.value
        if (current is UpdateState.Working || current is UpdateState.Checking) return
        val t = now()
        val last = prefs.lastCheck()
        if (!manual && t - last in 0 until CHECK_INTERVAL_MS) return
        val installed = SemVer.parseInstalled(installedVersion) ?: return
        cacheDir.deleteRecursively()
        mutable.value = UpdateState.Checking
        try {
            val releases = source.fetchReleases()
            prefs.setLastCheck(t)
            pickRelease(releases, installed).fold(
                onSuccess = { info ->
                    mutable.value = if (info == null) {
                        UpdateState.UpToDate
                    } else {
                        val snooze = prefs.snooze()
                        val snoozed = snooze != null && snooze.version == info.version.toString() && t < snooze.untilMs
                        UpdateState.Available(info, showSheet = manual || !snoozed)
                    }
                },
                onFailure = { e ->
                    mutable.value = UpdateState.CheckFailed((e as? UpdateException)?.error ?: UpdateError.BadResponse)
                },
            )
        } catch (e: UpdateException) {
            if (e.error != UpdateError.Offline) prefs.setLastCheck(t)
            mutable.value = UpdateState.CheckFailed(e.error)
        } catch (e: CancellationException) {
            mutable.value = UpdateState.Idle
            throw e
        }
    }

    /** "Later": hide this version for 24 hours. */
    suspend fun later() {
        val s = mutable.value as? UpdateState.Available ?: return
        prefs.setSnooze(s.release.version.toString(), now() + SNOOZE_MS)
        mutable.value = s.copy(showSheet = false)
    }

    fun showOffer() {
        (mutable.value as? UpdateState.Available)?.let { mutable.value = it.copy(showSheet = true) }
    }

    /** Dismiss an error sheet and go back to the offer. */
    fun dismissFailure() {
        (mutable.value as? UpdateState.UpdateFailed)?.let { mutable.value = UpdateState.Available(it.release, showSheet = false) }
    }

    fun startUpdate(release: ReleaseInfo) {
        if (job?.isActive == true) return
        job = scope.launch { update(release) }
    }

    fun cancelUpdate() { job?.cancel() }

    suspend fun update(release: ReleaseInfo) {
        val file = File(cacheDir, release.apkName)
        try {
            if (!installer.canInstall()) throw UpdateException(UpdateError.InstallBlocked)
            mutable.value = UpdateState.Working(release, UpdatePhase.Downloading, null)
            val expected = parseChecksumFile(source.fetchChecksum(release.shaUrl), release.apkName)
                ?: throw UpdateException(UpdateError.BadChecksumFile)
            val actual = source.download(release.apkUrl, file) { read, total ->
                mutable.value = UpdateState.Working(
                    release, UpdatePhase.Downloading, if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null,
                )
            }
            mutable.value = UpdateState.Working(release, UpdatePhase.Verifying, null)
            if (actual != expected) throw UpdateException(UpdateError.ChecksumMismatch)
            val apk = signers.ofApk(file)
            val own = signers.ofInstalled()
            if (apk == null || own == null || apk.signers.isEmpty() || own.signers.isEmpty()) {
                throw UpdateException(UpdateError.SignerUnreadable)
            }
            if (apk.signers != own.signers) throw UpdateException(UpdateError.SignerMismatch)
            if (apk.packageName != own.packageName) throw UpdateException(UpdateError.WrongPackage)
            if (apk.versionCode <= own.versionCode) throw UpdateException(UpdateError.NotNewer)
            mutable.value = UpdateState.Working(release, UpdatePhase.Installing, null)
            // The system installer may never report back; do not stay on "Installing" forever.
            withTimeoutOrNull(installTimeoutMs) { installer.install(file); true }
                ?: throw UpdateException(UpdateError.InstallNotConfirmed)
            mutable.value = UpdateState.Idle
        } catch (e: UpdateException) {
            mutable.value = UpdateState.UpdateFailed(release, e.error)
        } catch (e: CancellationException) {
            mutable.value = UpdateState.Available(release, showSheet = true)
            throw e
        } finally {
            file.delete()
        }
    }
}
