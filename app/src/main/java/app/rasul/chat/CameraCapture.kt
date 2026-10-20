package app.rasul.chat

import android.content.ActivityNotFoundException
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Takes one photo with the system camera into a file in the app's private cache folder [folder] (exposed to the camera
 * app through the app's FileProvider as [uriFor]) and hands it on like a picked photo. The platform calls are lambdas so
 * the rules are testable without a phone: CAMERA is asked for first (declared but not granted, the capture intent throws),
 * a refusal or a phone without a camera app gives a specific message, a cancelled shot leaves nothing behind, and the file
 * is deleted as soon as the photo has been read ([onPhoto] gets the callback to run then).
 */
internal class CameraCapture(
    private val folder: File,
    private val uriFor: (File) -> Uri,
    private val pendingPath: () -> String?,
    private val setPendingPath: (String?) -> Unit,
    private val hasPermission: () -> Boolean,
    private val askPermission: () -> Unit,
    /** Opens the camera for the file's Uri; may throw [ActivityNotFoundException] or [SecurityException]. */
    private val openCamera: (Uri) -> Unit,
    private val onPhoto: (Uri, afterLoad: () -> Unit) -> Unit,
    private val onFailure: (ChatFailure) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun start() {
        if (hasPermission()) open() else askPermission()
    }

    fun permissionResult(granted: Boolean) {
        if (granted) open() else onFailure(ChatFailure.CameraDenied)
    }

    private fun open() {
        sweep()
        val file = try {
            folder.mkdirs()
            File.createTempFile("shot_", ".jpg", folder)
        } catch (_: IOException) {
            onFailure(ChatFailure.PhotoUnreadable)
            return
        }
        setPendingPath(file.path)
        try {
            openCamera(uriFor(file))
        } catch (_: ActivityNotFoundException) {
            drop(file)
            onFailure(ChatFailure.CameraMissing)
        } catch (_: SecurityException) {
            drop(file)
            onFailure(ChatFailure.CameraDenied)
        }
    }

    /** The camera app came back: [taken] is false when the user cancelled. */
    fun result(taken: Boolean) {
        val file = pendingPath()?.let(::File)
        setPendingPath(null)
        if (file == null || file.parentFile?.canonicalFile != folder.canonicalFile) return
        if (!taken || !file.isFile || file.length() == 0L) {
            file.delete()
            return
        }
        onPhoto(uriFor(file)) { file.delete() }
    }

    private fun drop(file: File) {
        file.delete()
        setPendingPath(null)
    }

    /** A shot left behind by a process that died while the camera was open. */
    private fun sweep() {
        folder.listFiles()?.forEach { if (now() - it.lastModified() > STALE_MS) it.delete() }
    }

    private companion object {
        const val STALE_MS = 60 * 60 * 1000L
    }
}
