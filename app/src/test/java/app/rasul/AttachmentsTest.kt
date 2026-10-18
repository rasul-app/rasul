package app.rasul

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.rasul.chat.ChatFailure
import app.rasul.chat.PhotoResult
import app.rasul.chat.decodeRemoteThumb
import app.rasul.chat.reencodePhoto
import app.rasul.data.PendingAttachment
import app.rasul.data.RunInputLimits
import app.rasul.data.RunInputPlan
import app.rasul.data.SentAttachment
import app.rasul.data.isTextFile
import app.rasul.data.planRunInput
import app.rasul.data.runInputElement
import app.rasul.sessions.SessionMessageDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class AttachmentsTest {

    private fun photo(byteCount: Int) =
        PendingAttachment.Photo(PendingAttachment.newKey(), ByteArray(byteCount) { it.toByte() }, ByteArray(8) { it.toByte() })

    // ---- request builder ----

    @Test fun textOnlyInputStaysAPlainString() {
        val el = runInputElement("hello", emptyList())
        assertTrue(el is JsonPrimitive)
        assertEquals("hello", (el as JsonPrimitive).content)
        assertTrue(planRunInput("hello", emptyList()) is RunInputPlan.TextOnly)
    }

    @Test fun photoBecomesAnImageUrlDataJpegPart() {
        val plan = planRunInput("cap", listOf(photo(1024))) as RunInputPlan.WithParts
        val part = plan.parts.single()
        assertEquals("image_url", (part["type"] as JsonPrimitive).content)
        val url = ((part["image_url"] as JsonObject)["url"] as JsonPrimitive).content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))

        val el = runInputElement("cap", plan.parts) as JsonArray
        val message = el.single() as JsonObject
        assertEquals("user", (message["role"] as JsonPrimitive).content)
        val content = message["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals("cap", ((content[0] as JsonObject)["text"] as JsonPrimitive).content)
        assertEquals(part, content[1])
    }

    @Test fun textFileBecomesANamedTextPart() {
        val plan = planRunInput("", listOf(PendingAttachment.TextFile(1, "notes.md", "line1\nline2"))) as RunInputPlan.WithParts
        val part = plan.parts.single()
        assertEquals("text", (part["type"] as JsonPrimitive).content)
        assertEquals("notes.md:\nline1\nline2", (part["text"] as JsonPrimitive).content)
        val content = ((runInputElement("", plan.parts) as JsonArray).single() as JsonObject)["content"] as JsonArray
        assertEquals(1, content.size)
    }

    @Test fun oversizedRequestIsRefused() {
        assertTrue(planRunInput("x", listOf(photo(720 * 1024))) is RunInputPlan.TooLarge)
    }

    // ---- size budget: 1, 2 and 4 realistic photos must all send ----

    /** A photo-like bitmap: smooth gradient plus per-pixel texture noise, real camera size. */
    private fun photoJpeg(width: Int, height: Int, quality: Int = 90): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(42)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val n = rnd.nextInt(12)
            android.graphics.Color.rgb(
                (x * 255 / width + n) % 256,
                (y * 255 / height + n) % 256,
                ((x + y) * 255 / (width + height) + n) % 256,
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return jpeg(bitmap, quality)
    }

    private fun staged(jpegBytes: ByteArray) =
        PendingAttachment.Photo(PendingAttachment.newKey(), jpegBytes, ByteArray(8) { it.toByte() })

    private fun pickPhotos(count: Int) = List(count) { photoJpeg(2048, 1536) }
        .map { (reencodePhoto(it, RunInputLimits.photoBudget(count)) as PhotoResult.Ok).jpeg }
        .map(::staged)

    @Test fun fourRealisticPhotosAllSend() {
        assertTrue(planRunInput("", pickPhotos(4)) is RunInputPlan.WithParts)
    }

    @Test fun twoRealisticPhotosAllSend() {
        assertTrue(planRunInput("", pickPhotos(2)) is RunInputPlan.WithParts)
    }

    @Test fun onePhotoNeverGetsTheFewerPhotosError() {
        assertTrue(planRunInput("cap", pickPhotos(1)) is RunInputPlan.WithParts)
    }

    /** The send path posts [RunInputPlan.WithParts.input] verbatim — the plan carries the finished element. */
    @Test fun planCarriesTheFinishedInputElement() {
        val plan = planRunInput("cap", listOf(photo(64))) as RunInputPlan.WithParts
        assertEquals(runInputElement("cap", plan.parts), plan.input)
    }

    @Test fun textFileDetectionAcceptsTextAndRejectsBinary() {
        assertTrue(isTextFile("text/plain", "a.txt"))
        assertTrue(isTextFile("application/json", "data.json"))
        assertTrue(isTextFile(null, "main.kt"))
        assertTrue(isTextFile("application/octet-stream", "run.log"))
        assertTrue(isTextFile(null, "README.md"))
        assertFalse(isTextFile("application/pdf", "doc.pdf"))
        assertFalse(isTextFile("image/png", "pic.png"))
        assertFalse(isTextFile(null, "archive.zip"))
        assertFalse(isTextFile(null, null))
    }

    @Test fun sessionMessageDtoKeepsImagePartsAsRemotePhotos() {
        val dto = SessionMessageDto(role = "user", content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "cap") })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,AA") })
            })
        })
        assertEquals("cap", dto.text())
        assertEquals(listOf(SentAttachment.RemotePhoto("data:image/jpeg;base64,AA")), dto.attachments())

        val plain = SessionMessageDto(role = "user", content = JsonPrimitive("hi"))
        assertEquals("hi", plain.text())
        assertTrue(plain.attachments().isEmpty())
    }

    @Test fun historyFilePartShowsANameChip() {
        val dto = SessionMessageDto(role = "user", content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "notes.md:\nline1\nline2") })
        })
        assertEquals(listOf(SentAttachment.NamedFile("notes.md")), dto.attachments())
    }

    // ---- photo codec ----

    private fun jpeg(bitmap: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun containsAscii(bytes: ByteArray, needle: String): Boolean {
        val n = needle.encodeToByteArray()
        outer@ for (i in 0..bytes.size - n.size) {
            for (j in n.indices) if (bytes[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    /**
     * A JPEG with an APP1 EXIF segment holding a GPS IFD (tag 0x8825 -> GPSLatitude 0x0002),
     * spliced in after the SOI marker.
     */
    private fun jpegWithGpsExif(jpeg: ByteArray): ByteArray {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x25, 0x88.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x1A, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x02, 0x00, 0x05, 0x00, 0x03, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x33, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x1E, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        )
        val exif = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff
        val len = exif.size + 2
        return jpeg.copyOfRange(0, 2) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            exif +
            jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun reencodeStripsExifAndGpsByConstruction() {
        val src = jpeg(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        val tagged = jpegWithGpsExif(src)
        assertTrue(containsAscii(tagged, "Exif"))
        val r = reencodePhoto(tagged) as PhotoResult.Ok
        assertFalse(containsAscii(r.jpeg, "Exif"))
        assertFalse(containsAscii(r.thumb, "Exif"))
        assertNotNull(BitmapFactory.decodeByteArray(r.jpeg, 0, r.jpeg.size))
    }

    @Test fun reencodeNeverThrowsOnABrokenImage() {
        // A PNG header with valid bounds but a corrupt body: the decoder raises instead of returning null.
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) +
            "IHDR".toByteArray() + java.nio.ByteBuffer.allocate(8).putInt(4000).putInt(3000).array() +
            byteArrayOf(8, 6, 0, 0, 0, 0x11, 0x11, 0x11, 0x11) + ByteArray(64) { 0x55 }
        assertTrue(reencodePhoto(header) is PhotoResult.Unreadable)
    }

    @Test fun reencodeDownscalesToTheLongEdgeCap() {
        val src = jpeg(Bitmap.createBitmap(3200, 2000, Bitmap.Config.ARGB_8888))
        val r = reencodePhoto(src) as PhotoResult.Ok
        val out = BitmapFactory.decodeByteArray(r.jpeg, 0, r.jpeg.size)
        assertNotNull(out)
        assertTrue(maxOf(out.width, out.height) <= RunInputLimits.PHOTO_LONG_EDGE)
        assertTrue(r.jpeg.size <= RunInputLimits.MAX_PHOTO_BYTES)
        assertTrue(r.thumb.isNotEmpty())
    }

    @Test fun decodeRemoteThumbReadsDataUrlsOnly() {
        val bytes = jpeg(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        assertNotNull(decodeRemoteThumb(url))
        assertNull(decodeRemoteThumb("data:image/jpeg;base64,%%%"))
        assertNull(decodeRemoteThumb("https://example.invalid/x.jpg"))
    }

    // ---- every new failure has its own EN + AR message ----

    private fun localized(app: android.content.Context, language: String, id: Int): String {
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(language))
        return app.createConfigurationContext(config).getString(id)
    }

    @Test fun eachAttachmentFailureMapsToASpecificEnglishAndArabicMessage() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val arConfig = android.content.res.Configuration(ctx.resources.configuration)
            .also { it.setLocale(java.util.Locale.forLanguageTag("ar")) }
        val ar = ctx.createConfigurationContext(arConfig)
        val map = mapOf(
            ChatFailure.AttachmentTooLarge to R.string.chat_err_attach_too_large,
            ChatFailure.FileTypeUnsupported to R.string.chat_err_attach_file_type,
            ChatFailure.FileTooLarge to R.string.chat_err_attach_file_too_large,
            ChatFailure.PhotoTooLarge to R.string.chat_err_attach_photo_too_large,
            ChatFailure.PhotoUnreadable to R.string.chat_err_attach_photo_unreadable,
            ChatFailure.FileUnreadable to R.string.chat_err_attach_file_unreadable,
            ChatFailure.AttachmentKindMixed to R.string.chat_err_attach_mixed,
            ChatFailure.AttachmentTooManyPhotos to R.string.chat_err_attach_too_many,
            ChatFailure.AttachmentRunActive to R.string.chat_err_attach_run_active,
        )
        map.forEach { (failure, id) ->
            assertEquals(localized(ctx, "en", id), app.rasul.notifications.failureText(ctx, failure))
            assertEquals(localized(ctx, "ar", id), app.rasul.notifications.failureText(ar, failure))
        }
    }

    @Test fun specMessagesMatchTheirLiteralText() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals("Too large to send. Try fewer photos.", localized(ctx, "en", R.string.chat_err_attach_too_large))
        assertEquals("This file type can't be sent yet. Photos and text files work.", localized(ctx, "en", R.string.chat_err_attach_file_type))
        assertEquals("الإرسال كبير جدًا. جرّب صورًا أقل.", localized(ctx, "ar", R.string.chat_err_attach_too_large))
        assertEquals("لا يمكن إرسال هذا النوع من الملفات بعد. الصور والملفات النصية تعمل.", localized(ctx, "ar", R.string.chat_err_attach_file_type))
    }
}
