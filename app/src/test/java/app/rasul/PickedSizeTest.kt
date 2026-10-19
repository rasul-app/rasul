package app.rasul

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.rasul.chat.ChatAttachmentLoader
import app.rasul.chat.PickedFile
import app.rasul.chat.pickedSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A stream of [length] zero bytes that counts what was read from it. */
private class Zeros(private val length: Long) : InputStream() {
    var read = 0L
    override fun read(): Int = if (read >= length) -1 else { read++; 0 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= length) return -1
        val n = minOf(len.toLong(), length - read).toInt()
        read += n
        return n
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickedSizeTest {
    @Test fun aReportedSizeIsBelievedWithoutReadingTheFile() {
        var opened = 0
        assertEquals(1234L, pickedSize(1234L, 50L) { opened++; Zeros(1) })
        assertEquals(0, opened)
    }

    @Test fun aProviderThatGivesNoSizeIsAskedByReadingTheFile() {
        for (reported in listOf(null, -1L, 0L)) {
            assertEquals("$reported", 5L, pickedSize(reported, 50L) { Zeros(5) })
        }
    }

    @Test fun aReallyEmptyFileCountsZero() {
        assertEquals(0L, pickedSize(null, 50L) { Zeros(0) })
        assertEquals(0L, pickedSize(0L, 50L) { Zeros(0) })
    }

    @Test fun aFileOverTheLimitIsSeenAsTooBigAfterOneByteMore() {
        val stream = Zeros(1_000_000)
        assertEquals(51L, pickedSize(null, 50L) { stream })
        assertEquals(51L, stream.read)
    }

    @Test fun aFileThatCannotBeOpenedOrReadIsNull() {
        assertNull(pickedSize(null, 50L) { null })
        assertNull(pickedSize(-1L, 50L) { object : InputStream() { override fun read(): Int = throw IOException("gone") } })
    }

    @Test fun aProviderThatRefusesGivesNoSizeInsteadOfCrashing() {
        assertNull(pickedSize(null, 50L) { throw SecurityException("no access") })
        assertNull(pickedSize(-1L, 50L) { object : InputStream() { override fun read(): Int = throw SecurityException("revoked") } })
    }

    @Test fun describeFileCountsAFileWhoseProviderGivesNoSize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://no.size.provider/docs/report.pdf")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(300)))
        val picked = ChatAttachmentLoader(context).describeFile(uri)
        assertEquals(PickedFile("report.pdf", 300, "application/octet-stream"), picked)
    }
}
