package app.rasul.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntakeTest {
    private val own = "app.rasul"

    private fun send(text: String? = null, subject: String? = null, stream: Uri? = null) = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    private fun sendMany(vararg streams: Uri, text: String? = null) = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams.toList()))
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
    }

    @Test fun plainText() {
        val share = parseShare(send(text = "https://example.invalid/page"), own)!!
        assertEquals("https://example.invalid/page", share.text)
        assertTrue(share.uris.isEmpty())
        assertEquals(0, share.refused)
    }

    @Test fun subjectIsTheFirstLine() {
        assertEquals("Title\nBody text", parseShare(send(text = "Body text", subject = "Title"), own)!!.text)
    }

    @Test fun subjectAloneIsKeptAndRepeatedSubjectIsNot() {
        assertEquals("Only subject", parseShare(send(subject = "Only subject", stream = Uri.parse("content://media/1")), own)!!.text)
        assertEquals("Same", parseShare(send(text = "Same", subject = "Same"), own)!!.text)
    }

    @Test fun oneImage() {
        val share = parseShare(send(stream = Uri.parse("content://media/external/images/1")), own)!!
        assertEquals("", share.text)
        assertEquals(listOf(Uri.parse("content://media/external/images/1")), share.uris)
    }

    @Test fun manyImagesKeepTheirOrderAndDropDuplicates() {
        val a = Uri.parse("content://media/1")
        val b = Uri.parse("content://media/2")
        val share = parseShare(sendMany(a, b, a), own)!!
        assertEquals(listOf(a, b), share.uris)
        assertEquals(0, share.refused)
    }

    @Test fun mixedTextAndStreams() {
        val share = parseShare(sendMany(Uri.parse("content://media/1"), Uri.parse("content://docs/2"), text = "look"), own)!!
        assertEquals("look", share.text)
        assertEquals(2, share.uris.size)
    }

    @Test fun listOfTextsIsJoinedByLines() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf("one", "two"))
        }
        assertEquals("one\ntwo", parseShare(intent, own)!!.text)
    }

    @Test fun fileUrisAreRefused() {
        val share = parseShare(send(stream = Uri.parse("file:///sdcard/secret.txt")), own)!!
        assertTrue(share.uris.isEmpty())
        assertEquals(1, share.refused)
        assertTrue(share.isEmpty)
    }

    @Test fun otherSchemesAreRefused() {
        val share = parseShare(sendMany(Uri.parse("http://example.invalid/a.png"), Uri.parse("android.resource://app.rasul/1"), Uri.parse("content://media/1")), own)!!
        assertEquals(listOf(Uri.parse("content://media/1")), share.uris)
        assertEquals(2, share.refused)
    }

    @Test fun ownProviderIsRefused() {
        val share = parseShare(
            sendMany(
                Uri.parse("content://app.rasul.files/cache/x.txt"),
                Uri.parse("content://app.rasul/anything"),
                Uri.parse("content://0@app.rasul.files/cache/x.txt"),
                Uri.parse("content://APP.RASUL.FILES/cache/x.txt"),
                Uri.parse("content://app.rasulother/ok"),
            ),
            own,
        )!!
        assertEquals(listOf(Uri.parse("content://app.rasulother/ok")), share.uris)
        assertEquals(4, share.refused)
    }

    @Test fun ownProviderIsRefusedWhateverTheAuthorityIsEncodedAs() {
        val refused = listOf(
            "content://0@app.rasul.files/received/a.txt",
            "content://0%40app.rasul.files/received/a.txt",
            "content://0%40app%2Erasul%2Efiles/received/a.txt",
            "content://0%40APP.Rasul.FILES/received/a.txt",
            "content://APP.RASUL.FILES/received/a.txt",
            "content://a%40b%40app.rasul.files/received/a.txt",
            "content://0@app.rasul/received/a.txt",
        )
        for (uri in refused) assertFalse(uri, isShareableUri(Uri.parse(uri), own))
        val share = parseShare(sendMany(*refused.map(Uri::parse).toTypedArray()), own)
        assertNull(share!!.uris.firstOrNull())
        assertEquals(refused.size, share.refused)
    }

    @Test fun otherAppProvidersAreStillAccepted() {
        for (uri in listOf("content://media/external/images/1", "content://0@media/external/images/1", "content://0%40com.other.files/x", "content://app.rasulother/ok")) {
            assertTrue(uri, isShareableUri(Uri.parse(uri), own))
        }
    }

    @Test fun anAuthorityOneOfOurProvidersDeclaresIsRefused() {
        val ours = { authority: String -> authority == "files.other.name" }
        assertFalse(isShareableUri(Uri.parse("content://files.other.name/x"), own, ours))
        assertFalse(isShareableUri(Uri.parse("content://0%40files%2Eother%2Ename/x"), own, ours))
        assertFalse(isShareableUri(Uri.parse("content://FILES.Other.Name/x"), own, ours))
        assertTrue(isShareableUri(Uri.parse("content://media/1"), own, ours))
        assertEquals(1, parseShare(send(stream = Uri.parse("content://files.other.name/x")), own, ours)!!.refused)
    }

    @Test fun theResolverLookupFindsOurOwnDeclaredProvider() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val info = android.content.pm.ProviderInfo().apply {
            packageName = context.packageName
            name = "x.Provider"
            authority = "declared.by.us"
        }
        org.robolectric.Shadows.shadowOf(pm).addOrUpdateProvider(info)
        val ours = ownsProviderAuthority(pm, context.packageName)
        assertTrue(ours("declared.by.us"))
        assertFalse(ours("not.declared"))
        assertFalse(isShareableUri(Uri.parse("content://0%40declared%2Eby%2Eus/x"), "other.pkg", ours))
    }

    @Test fun longTextIsCutAndCountedAsDropped() {
        val share = parseShare(send(text = "a".repeat(SHARE_TEXT_MAX_CHARS + 500)), own)!!
        assertEquals(SHARE_TEXT_MAX_CHARS, share.text.length)
        assertEquals(1, share.refused)
        assertTrue(share.text.toByteArray().size <= app.rasul.data.RunInputLimits.MAX_RUN_BODY_BYTES)
    }

    @Test fun textCutNeverSplitsASurrogatePair() {
        val emoji = "\uD83D\uDE00"
        val text = "a".repeat(SHARE_TEXT_MAX_CHARS - 1) + emoji + "tail"
        val share = parseShare(send(text = text), own)!!
        assertEquals(SHARE_TEXT_MAX_CHARS - 1, share.text.length)
        assertFalse(Character.isHighSurrogate(share.text.last()))
        assertEquals(1, share.refused)
    }

    @Test fun textAtTheLimitIsKeptWhole() {
        val share = parseShare(send(text = "a".repeat(SHARE_TEXT_MAX_CHARS)), own)!!
        assertEquals(SHARE_TEXT_MAX_CHARS, share.text.length)
        assertEquals(0, share.refused)
    }

    @Test fun contentUriWithoutAuthorityIsRefused() {
        assertFalse(isShareableUri(Uri.parse("content:///path"), own))
    }

    @Test fun emptyShareIsNull() {
        assertNull(parseShare(Intent(Intent.ACTION_SEND), own))
        assertNull(parseShare(send(text = "   "), own))
        assertNull(parseShare(Intent(Intent.ACTION_SEND_MULTIPLE), own))
    }

    @Test fun otherActionsAreNotShares() {
        assertNull(parseShare(Intent(Intent.ACTION_MAIN).putExtra(Intent.EXTRA_TEXT, "x"), own))
        assertNull(parseShare(Intent(Intent.ACTION_VIEW, Uri.parse("content://media/1")), own))
    }

    @Test fun clipDataIsUsedWhenNoStreamExtra() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("shared", Uri.parse("content://media/9"))
        }
        assertEquals(listOf(Uri.parse("content://media/9")), parseShare(intent, own)!!.uris)
    }

    @Test fun clearingRemovesThePayload() {
        val intent = send(text = "x", stream = Uri.parse("content://media/1"))
        clearShare(intent)
        assertNull(parseShare(intent, own))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }
}
