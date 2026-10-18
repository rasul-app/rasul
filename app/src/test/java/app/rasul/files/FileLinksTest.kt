package app.rasul.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLinksTest {
    private val token = "A".repeat(20) + "b_-" + "9".repeat(20) // 43 characters
    private val link = "[report.pdf (1.2 MB)](rasul-file://$token)"

    @Test fun tokenFixtureIsExactly43Characters() = assertEquals(43, token.length)

    @Test fun validLinkBecomesFileCardWithNameAndSize() {
        val parts = FileLinks.split(link)
        assertEquals(listOf<MessagePart>(MessagePart.File(FileLink(token, "report.pdf", "1.2 MB"))), parts)
    }

    @Test fun rtlTextAroundTheLinkIsKept() {
        val parts = FileLinks.split("تفضل الملف:\n$link\nمع التحية")
        assertEquals(3, parts.size)
        assertEquals("تفضل الملف:\n", (parts[0] as MessagePart.Text).text)
        assertEquals("report.pdf", (parts[1] as MessagePart.File).link.name)
        assertEquals("\nمع التحية", (parts[2] as MessagePart.Text).text)
    }

    @Test fun badTokensAndOtherFormsStayPlainText() {
        val bad = listOf(
            "[a.pdf (1 KB)](rasul-file://${token.drop(1)})", // 42
            "[a.pdf (1 KB)](rasul-file://${token}x)", // 44
            "[a.pdf (1 KB)](rasul-file://${token.dropLast(1)}.)", // bad character
            "[a.pdf (1 KB)](rasul-file://${token.dropLast(1)}/)",
            "[a.pdf (1 KB)](rasul-file:$token)",
            "[a.pdf (1 KB)](rasul-file:/$token)",
            "[a.pdf (1 KB)](rasul-files://$token)",
            "[a.pdf (1 KB)](http://example.com/rasul-file://$token)",
            "[a.pdf (1 KB)](RASUL-FILE://$token)",
            "![a.png (1 KB)](rasul-file://$token)",
            "rasul-file://$token",
        )
        for (text in bad) assertEquals(text, listOf<MessagePart>(MessagePart.Text(text)), FileLinks.split(text))
    }

    @Test fun nestedMarkdownAroundTheLink() {
        val parts = FileLinks.split("**$link**")
        assertEquals(listOf("**", "**"), parts.filterIsInstance<MessagePart.Text>().map { it.text })
        assertEquals(1, parts.filterIsInstance<MessagePart.File>().size)
    }

    @Test fun twoLinksAndBlankSeparators() {
        val other = "z".repeat(43)
        val parts = FileLinks.split("$link\n\n[b.zip (3 KB)](rasul-file://$other)")
        assertEquals(listOf(token, other), parts.filterIsInstance<MessagePart.File>().map { it.link.token })
        assertEquals(2, parts.size)
    }

    @Test fun nameWithParenthesesKeepsOnlyTheSizeSuffix() {
        val f = (FileLinks.split("[notes (v2).txt (4 B)](rasul-file://$token)").single() as MessagePart.File).link
        assertEquals("notes (v2).txt", f.name)
        assertEquals("4 B", f.sizeText)
        val bare = (FileLinks.split("[notes.txt](rasul-file://$token)").single() as MessagePart.File).link
        assertEquals(null, bare.sizeText)
    }

    @Test fun notAvailableNotesAreUnavailablePartsAndFileStaysText() {
        assertEquals(listOf<MessagePart>(MessagePart.Unavailable("x.pdf")), FileLinks.split("[file not available: x.pdf]"))
        assertEquals(listOf<MessagePart>(MessagePart.Unavailable(null)), FileLinks.split("[file not available]"))
        assertEquals(listOf<MessagePart>(MessagePart.Text("[file]")), FileLinks.split("[file]"))
        assertFalse(FileLinks.hasFileLink("[file]"))
        assertTrue(FileLinks.hasFileLink("[file not available]"))
    }

    @Test fun kindsFollowTheExtension() {
        val expected = mapOf(
            "a.PDF" to FileKind.Pdf, "a.docx" to FileKind.Document, "a.xlsx" to FileKind.Sheet, "a.csv" to FileKind.Sheet,
            "a.zip" to FileKind.Archive, "a.txt" to FileKind.Text, "a.png" to FileKind.Image, "a.mp3" to FileKind.Audio,
            "a.mp4" to FileKind.Video, "a.bin" to FileKind.Other, "noext" to FileKind.Other,
        )
        for ((name, kind) in expected) assertEquals(name, kind, FileLinks.kindOf(name))
    }

    @Test fun activeContentIsNeverOpenable() {
        for (name in listOf("a.html", "a.HTM", "a.svg", "a.xml", "a.js", "a.xhtml")) {
            assertTrue(name, FileLinks.isActiveContent(name))
            assertFalse(name, canOpen(name))
            assertEquals(name, "application/octet-stream", FileLinks.mimeFor("text/html", name))
        }
        assertEquals("application/octet-stream", FileLinks.mimeFor("image/svg+xml", "photo.png"))
        assertTrue(canOpen("a.pdf"))
        assertFalse(FileLinks.hasThumbnail("a.svg"))
        assertTrue(FileLinks.hasThumbnail("a.JPG"))
    }

    @Test fun mimeUsesContentTypeThenExtension() {
        assertEquals("application/pdf", FileLinks.mimeFor("application/pdf; charset=binary", "x"))
        assertEquals("application/pdf", FileLinks.mimeFor("application/octet-stream", "x.pdf"))
        assertEquals("application/pdf", FileLinks.mimeFor(null, "x.pdf"))
        assertEquals("application/octet-stream", FileLinks.mimeFor("nonsense", "x.unknown"))
    }

    @Test fun sanitisingNames() {
        assertEquals("_.._etc_passwd", FileLinks.sanitiseName("../../etc/passwd"))
        assertFalse(FileLinks.sanitiseName("a\\b:c.txt").let { it.contains('\\') || it.contains(':') })
        assertEquals("hidden.txt", FileLinks.sanitiseName("...hidden.txt"))
        assertEquals("a_b.txt", FileLinks.sanitiseName("a\nb.txt"))
        assertEquals("file", FileLinks.sanitiseName(""))
        assertEquals("file", FileLinks.sanitiseName("..."))
        assertEquals("file", FileLinks.sanitiseName("///"))
        val long = FileLinks.sanitiseName("n".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("تقرير.pdf", FileLinks.sanitiseName("تقرير.pdf"))
    }

    @Test fun formatCharactersNeverDisguiseTheType() {
        for (c in listOf('‮', '⁦', '‏', '﻿', '​')) {
            val name = "invoice${c}fdp.html"
            val clean = FileLinks.sanitiseName(name)
            assertEquals("invoice_fdp.html", clean)
            assertEquals("html", FileLinks.extension(clean))
            assertEquals("invoice_fdp.html", FileLinks.cleanFormat(name))
        }
        val card = FileLinks.split("[invoice‮fdp.html (1 KB)](rasul-file://${"A".repeat(43)})").single() as MessagePart.File
        assertEquals("invoice_fdp.html", card.link.name)
    }

    @Test fun installersAreSaveAndShareOnly() {
        for (n in listOf("a.apk", "a.APKS", "a.xapk", "a.aab")) {
            assertTrue(n, FileLinks.isActiveContent(n))
            assertFalse(n, canOpen(n))
        }
        assertEquals("application/octet-stream", FileLinks.mimeFor("application/vnd.android.package-archive", "x.bin"))
        assertFalse(canOpen("x.bin", FileLinks.mimeFor("application/vnd.android.package-archive", "x.bin")))
        assertTrue(canOpen("a.pdf", "application/pdf"))
    }

    @Test fun nameIsCappedInUtf8BytesWithoutSplittingPairs() {
        val cjk = FileLinks.sanitiseName("文".repeat(100) + ".pdf")
        assertTrue(cjk.toByteArray().size <= 200)
        assertTrue(cjk.endsWith(".pdf"))
        val face = "😀"
        val emoji = FileLinks.sanitiseName(face.repeat(100) + ".txt")
        assertTrue(emoji.toByteArray().size <= 200)
        assertTrue(emoji.endsWith(".txt"))
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) // no lone surrogate
        assertEquals("", emoji.removeSuffix(".txt").replace(face, ""))
    }

    @Test fun neverRenderTypesAreBlocked() {
        for (t in listOf("application/xml", "text/xml", "application/rss+xml", "application/atom+xml", "image/svg+xml", "application/xhtml+xml",
            "text/html", "application/javascript", "text/ecmascript", "application/x-javascript", "multipart/related", "message/rfc822")) {
            assertEquals(t, "application/octet-stream", FileLinks.mimeFor(t, "f.bin"))
            assertFalse(t, canOpen("f.bin", FileLinks.mimeFor(t, "f.bin")))
        }
        assertEquals("image/png", FileLinks.mimeFor("image/png", "f.bin"))
    }

    @Test fun notificationTextDropsTheTokenAndKeepsTheName() {
        val t = "A".repeat(43)
        assertEquals("Here: report.pdf (2 MB) ok", FileLinks.plainText("Here: [report.pdf (2 MB)](rasul-file://$t) ok"))
        assertEquals("plain", FileLinks.plainText("plain"))
        assertFalse(FileLinks.plainText("[a (1 B)](rasul-file://$t)").contains("rasul-file"))
    }

    @Test fun moreMediaTypesGetTheirRealType() {
        val expected = mapOf(
            "a.bmp" to "image/bmp", "a.HEIC" to "image/heic", "a.heif" to "image/heif", "a.mov" to "video/quicktime",
            "a.3gp" to "video/3gpp", "a.aac" to "audio/aac", "a.flac" to "audio/flac",
        )
        for ((name, mime) in expected) assertEquals(name, mime, FileLinks.mimeFor(null, name))
    }

    @Test fun notificationTextDropsAnUnfinishedLinkWhileStreaming() {
        val t = "A".repeat(43)
        val full = "[report.pdf (2 MB)](rasul-file://$t)"
        for (cut in listOf("[report.pdf (2 MB)](", "[report.pdf (2 MB)](rasul-f", "[report.pdf (2 MB)](rasul-file:/", "[report.pdf (2 MB)](rasul-file://", "[report.pdf (2 MB)](rasul-file://${t.take(20)}", full.dropLast(1))) {
            assertEquals(cut, "Here it is: ", FileLinks.plainText("Here it is: $cut"))
        }
        assertEquals("Here it is: report.pdf (2 MB)", FileLinks.plainText("Here it is: $full"))
        assertEquals("See [docs](https://example.org", FileLinks.plainText("See [docs](https://example.org"))
    }

    @Test fun notificationTextHasNoFormatCharacters() {
        assertEquals("invoicefdp.pdf", FileLinks.plainText("invoice\u202Efdp\u200B.pdf\uFEFF"))
        assertEquals("مرحبا", FileLinks.plainText("مر\u200Fحبا"))
    }
}
