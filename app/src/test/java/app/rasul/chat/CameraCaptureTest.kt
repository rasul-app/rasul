package app.rasul.chat

import android.content.ActivityNotFoundException
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The camera rules without a phone: permission first, specific messages, nothing left behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraCaptureTest {
    @get:Rule val temp = TemporaryFolder()

    private var granted = false
    private var asked = 0
    private var opened = mutableListOf<Uri>()
    private var pending: String? = null
    private val failures = mutableListOf<ChatFailure>()
    private val photos = mutableListOf<Uri>()
    private var afterLoad: (() -> Unit)? = null
    private var openError: Throwable? = null
    private var clock = 10_000_000L

    private fun folder() = File(temp.root, "camera")

    private fun capture() = CameraCapture(
        folder = folder(),
        uriFor = { Uri.parse("content://app.files/camera/${it.name}") },
        pendingPath = { pending },
        setPendingPath = { pending = it },
        hasPermission = { granted },
        askPermission = { asked++ },
        openCamera = { opened += it; openError?.let { e -> throw e } },
        onPhoto = { uri, after -> photos += uri; afterLoad = after },
        onFailure = { failures += it },
        now = { clock },
    )

    private fun shot(c: CameraCapture): File {
        granted = true
        c.start()
        return File(pending!!)
    }

    @Test fun withoutPermissionItAsksFirstAndDoesNotOpenTheCamera() {
        val c = capture()
        c.start()
        assertEquals(1, asked)
        assertTrue(opened.isEmpty())
    }

    @Test fun aRefusedPermissionGivesTheSpecificMessage() {
        val c = capture()
        c.start()
        c.permissionResult(false)
        assertEquals(listOf<ChatFailure>(ChatFailure.CameraDenied), failures)
        assertTrue(opened.isEmpty())
        assertNull(pending)
    }

    @Test fun aGrantedPermissionOpensTheCameraOnAPrivateFile() {
        val c = capture()
        c.start()
        c.permissionResult(true)
        assertEquals(1, opened.size)
        assertEquals(folder().canonicalFile, File(pending!!).parentFile?.canonicalFile)
    }

    @Test fun aCancelledShotAttachesNothingAndDeletesTheFile() {
        val c = capture()
        val file = shot(c)
        file.writeBytes(byteArrayOf(1))
        c.result(false)
        assertTrue(photos.isEmpty())
        assertFalse(file.exists())
        assertNull(pending)
    }

    @Test fun aTakenPhotoIsAttachedAndTheFileGoesWhenItHasBeenRead() {
        val c = capture()
        val file = shot(c)
        file.writeBytes(byteArrayOf(1, 2, 3))
        c.result(true)
        assertEquals(1, photos.size)
        assertTrue(file.exists())
        afterLoad!!.invoke()
        assertFalse(file.exists())
    }

    @Test fun anEmptyFileIsNotAttached() {
        val c = capture()
        val file = shot(c)
        c.result(true)
        assertTrue(photos.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun aPhoneWithoutACameraAppGivesTheSpecificMessageAndKeepsNoFile() {
        openError = ActivityNotFoundException()
        val c = capture()
        granted = true
        c.start()
        assertEquals(listOf<ChatFailure>(ChatFailure.CameraMissing), failures)
        assertEquals(0, folder().listFiles()?.size ?: 0)
        assertNull(pending)
    }

    @Test fun aSecurityExceptionWhenOpeningGivesTheDeniedMessageAndKeepsNoFile() {
        openError = SecurityException()
        val c = capture()
        granted = true
        c.start()
        assertEquals(1, opened.size)
        assertEquals(listOf<ChatFailure>(ChatFailure.CameraDenied), failures)
        assertEquals(0, folder().listFiles()?.size ?: 0)
        assertNull(pending)
        assertTrue(photos.isEmpty())
    }

    @Test fun aResultForAFileOutsideTheFolderIsIgnored() {
        val c = capture()
        val stray = temp.newFile("stray.jpg").also { it.writeBytes(byteArrayOf(1)) }
        pending = stray.path
        c.result(true)
        assertTrue(photos.isEmpty())
        assertTrue(stray.exists())
    }

    @Test fun oldLeftoversAreSweptWhenTheCameraOpens() {
        folder().mkdirs()
        val old = File(folder(), "shot_old.jpg").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(1_000L) }
        val c = capture()
        shot(c)
        assertFalse(old.exists())
    }
}
