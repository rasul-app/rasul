package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.update.ApkIdentity
import app.rasul.update.ApkInstaller
import app.rasul.update.ApkSigners
import app.rasul.update.CHECK_INTERVAL_MS
import app.rasul.update.GithubAsset
import app.rasul.update.GithubRelease
import app.rasul.update.HttpUpdateSource
import app.rasul.update.NoteKind
import app.rasul.update.ReleaseInfo
import app.rasul.update.SNOOZE_MS
import app.rasul.update.SemVer
import app.rasul.update.UpdateController
import app.rasul.update.UpdateError
import app.rasul.update.UpdateException
import app.rasul.update.UpdatePhase
import app.rasul.update.UpdatePrefs
import app.rasul.update.UpdateSource
import app.rasul.update.UpdateState
import app.rasul.update.isAllowedAssetUrl
import app.rasul.update.parseChecksumFile
import app.rasul.update.parseReleases
import app.rasul.update.pickRelease
import app.rasul.update.renderNotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

private const val DL = "https://github.com/rasul-app/rasul/releases/download/"

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun release(tag: String, draft: Boolean = false, withAssets: Boolean = true, body: String? = "notes"): GithubRelease {
    val v = tag.removePrefix("v")
    return GithubRelease(
        tag, draft, body,
        if (withAssets) listOf(
            GithubAsset("rasul-$v.apk", "$DL$tag/rasul-$v.apk"),
            GithubAsset("rasul-$v.apk.sha256", "$DL$tag/rasul-$v.apk.sha256"),
        ) else emptyList(),
    )
}

class UpdatePickTest {
    private val installed = SemVer(0, 2, 5)

    private fun pick(vararg r: GithubRelease) = pickRelease(r.toList(), installed)

    @Test fun picksHighestNumericallyAndIncludesPreReleases() {
        // "prerelease" is not a field we filter on: GitHub pre-releases arrive like any other release.
        val info = pick(release("v0.2.6"), release("v0.10.0"), release("v0.9.0")).getOrThrow()!!
        assertEquals(SemVer(0, 10, 0), info.version)
        assertEquals("${DL}v0.10.0/rasul-0.10.0.apk", info.apkUrl)
        assertEquals("${DL}v0.10.0/rasul-0.10.0.apk.sha256", info.shaUrl)
    }

    @Test fun parsesPreReleaseFlagJsonAndSkipsDraftsFromRealShape() {
        val json = """[{"tag_name":"v0.2.7","draft":true,"prerelease":false,"body":"x","assets":[]},
            {"tag_name":"v0.2.6","draft":false,"prerelease":true,"body":"## New\n- a","extra":1,"assets":[
            {"name":"rasul-0.2.6.apk","browser_download_url":"${DL}v0.2.6/rasul-0.2.6.apk","size":5},
            {"name":"rasul-0.2.6.apk.sha256","browser_download_url":"${DL}v0.2.6/rasul-0.2.6.apk.sha256"}]}]"""
        val info = pickRelease(parseReleases(json)!!, installed).getOrThrow()!!
        assertEquals(SemVer(0, 2, 6), info.version)
        assertEquals("## New\n- a", info.notes)
    }

    @Test fun draftsAreSkipped() {
        assertNull(pick(release("v0.3.0", draft = true)).getOrThrow())
    }

    @Test fun lowerAndEqualVersionsAreIgnored() {
        assertNull(pick(release("v0.2.5"), release("v0.2.4"), release("v0.1.9")).getOrThrow())
    }

    @Test fun malformedTagsAreIgnored() {
        assertNull(pick(release("0.3.0"), release("v01.3.0"), release("v0.3.0-rc1"), release("v0.3"), release("v 0.3.0")).getOrThrow())
    }

    @Test fun newestWithoutAssetsIsAnError() {
        val error = (pick(release("v0.2.6"), release("v0.2.7", withAssets = false)).exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NoAsset("0.2.7"), error)
    }

    @Test fun missingShaAssetIsAnError() {
        val r = release("v0.2.6").let { it.copy(assets = it.assets.filter { a -> !a.name.endsWith(".sha256") }) }
        assertTrue(pick(r).isFailure)
    }

    @Test fun assetFromAnotherAddressIsRefused() {
        val r = release("v0.2.6").let { it.copy(assets = it.assets.map { a -> a.copy(url = "https://evil.example/" + a.name) }) }
        assertTrue(pick(r).isFailure)
        assertFalse(isAllowedAssetUrl("https://github.com/rasul-app/rasul/releases/download2/x"))
        assertFalse(isAllowedAssetUrl("http://github.com/rasul-app/rasul/releases/download/v1/x"))
        assertFalse(isAllowedAssetUrl("${DL}v1/x?token=1"))
        assertTrue(isAllowedAssetUrl("${DL}v1/x"))
        // Judged on parsed path segments, not on a string prefix.
        assertFalse(isAllowedAssetUrl("${DL}v1/../../../other/x"))
        assertFalse(isAllowedAssetUrl("${DL}v1/%2e%2e/x"))
        assertFalse(isAllowedAssetUrl("${DL}v1%2Fx"))
        assertFalse(isAllowedAssetUrl("${DL}v1/x%2Fy"))
        assertFalse(isAllowedAssetUrl("${DL}v1/a/b"))
        assertFalse(isAllowedAssetUrl("${DL}v1/"))
        assertFalse(isAllowedAssetUrl("${DL}./x"))
        assertFalse(isAllowedAssetUrl("https://user@github.com/rasul-app/rasul/releases/download/v1/x"))
        assertFalse(isAllowedAssetUrl("https://github.com:8443/rasul-app/rasul/releases/download/v1/x"))
        assertFalse(isAllowedAssetUrl("https://github.com/rasul-app/rasul-evil/releases/download/v1/x"))
    }

    @Test fun versionParsingIsStrict() {
        assertEquals(SemVer(0, 2, 5), SemVer.parseInstalled("0.2.5-debug"))
        assertNull(SemVer.parse(" 0.2.5"))
        assertNull(SemVer.parse("0.2.5 "))
        assertNull(SemVer.parse("0.02.5"))
        assertNull(SemVer.parseTag("V0.2.5"))
        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9))
    }

    @Test fun checksumFileFormat() {
        val hex = "ab".repeat(32)
        assertEquals(hex, parseChecksumFile("$hex *rasul-0.2.6.apk\n", "rasul-0.2.6.apk"))
        assertEquals(hex, parseChecksumFile(hex.uppercase() + "  rasul-0.2.6.apk", "rasul-0.2.6.apk"))
        assertNull(parseChecksumFile("$hex *rasul-0.2.5.apk", "rasul-0.2.6.apk"))
        assertNull(parseChecksumFile("$hex *rasul-0.2.6.apk extra", "rasul-0.2.6.apk"))
        assertNull(parseChecksumFile("${hex}00 *rasul-0.2.6.apk", "rasul-0.2.6.apk"))
        assertNull(parseChecksumFile("", "rasul-0.2.6.apk"))
    }

    @Test fun notesShowLinksAsPlainText() {
        val lines = renderNotes("## Title\r\n- fixed [the chat](https://example.invalid/x) bug\n\nSome **bold** text with `code`")
        assertEquals(NoteKind.Heading, lines[0].kind)
        assertTrue(lines[0].spans.all { it.bold })
        assertEquals(NoteKind.Bullet, lines[1].kind)
        assertEquals("fixed the chat bug", lines[1].spans.joinToString("") { it.text })
        assertEquals(NoteKind.Paragraph, lines[2].kind)
        assertEquals("Some bold text with code", lines[2].spans.joinToString("") { it.text })
        assertEquals(listOf(false, true, false), lines[2].spans.map { it.bold })
        assertFalse(lines.flatMap { it.spans }.any { it.text.contains("https") })
    }
}

class UpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 1_000_000_000L

    @After fun stop() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private class FakeSource : UpdateSource {
        var releases = listOf(release("v0.2.6"))
        var releaseCalls = 0
        var checksumCalls = 0
        var downloadCalls = 0
        var apk = "apk-bytes".toByteArray()
        var checksumText: (String) -> String = { name -> sha256(apk) + " *" + name + "\n" }
        var reportedSha: String? = null
        var failCheck: UpdateError? = null

        override suspend fun fetchReleases(): List<GithubRelease> {
            releaseCalls++
            failCheck?.let { throw UpdateException(it) }
            return releases
        }

        override suspend fun fetchChecksum(url: String): String {
            checksumCalls++
            return checksumText(url.substringAfterLast('/').removeSuffix(".sha256"))
        }

        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): String {
            downloadCalls++
            dest.parentFile?.mkdirs()
            dest.writeBytes(apk)
            onProgress(apk.size.toLong(), apk.size.toLong())
            return reportedSha ?: sha256(apk)
        }
    }

    private class FakeSigners(
        var apk: ApkIdentity? = ApkIdentity("app.rasul", 6, setOf("aa")),
        var own: ApkIdentity? = ApkIdentity("app.rasul", 5, setOf("aa")),
    ) : ApkSigners {
        override fun ofApk(file: File) = apk
        override fun ofInstalled() = own
    }

    private class FakeInstaller(var allowed: Boolean = true, var hang: Boolean = false) : ApkInstaller {
        var installed = 0
        var existedAtInstall = false
        override fun canInstall() = allowed
        override suspend fun install(file: File) {
            installed++
            existedAtInstall = file.exists()
            if (hang) awaitCancellation() // the system never reports a status
        }
    }

    private val source = FakeSource()
    private val signers = FakeSigners()
    private val installer = FakeInstaller()
    private val cache get() = File(tmp.root, "updates")
    private val prefs by lazy {
        UpdatePrefs(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "p.preferences_pb") })
    }
    private val controller by lazy {
        UpdateController(source, prefs, signers, installer, "0.2.5", cache, scope, installTimeoutMs = 100) { clock }
    }

    private fun info(): ReleaseInfo = pickRelease(source.releases, SemVer(0, 2, 5)).getOrThrow()!!

    @Test fun settingOffMakesNoNetworkCallAtAll() = runBlocking {
        prefs.setEnabled(false)
        controller.checkIfDue()
        controller.checkNow()
        assertEquals(0, source.releaseCalls)
        assertEquals(UpdateState.Idle, controller.state.value)
    }

    @Test fun settingIsOnByDefault() = runBlocking {
        assertTrue(prefs.enabled())
    }

    @Test fun automaticCheckIsSpacedTwelveHoursButCheckNowIsNot() = runBlocking {
        controller.checkIfDue()
        assertEquals(1, source.releaseCalls)
        clock += CHECK_INTERVAL_MS - 1
        controller.checkIfDue()
        assertEquals(1, source.releaseCalls)
        controller.checkNow()
        assertEquals(2, source.releaseCalls)
        clock += CHECK_INTERVAL_MS
        controller.checkIfDue()
        assertEquals(3, source.releaseCalls)
    }

    @Test fun availableReleaseShowsTheSheetAndUpToDateDoesNot() = runBlocking {
        controller.checkNow()
        assertEquals(UpdateState.Available(info(), true), controller.state.value)
        source.releases = listOf(release("v0.2.5"))
        controller.checkNow()
        assertEquals(UpdateState.UpToDate, controller.state.value)
    }

    @Test fun laterSnoozesThatVersionForTwentyFourHours() = runBlocking {
        controller.checkIfDue()
        controller.later()
        assertEquals(UpdateState.Available(info(), false), controller.state.value)
        clock += CHECK_INTERVAL_MS // 12 h: a new automatic check runs but the version stays snoozed
        controller.checkIfDue()
        assertEquals(UpdateState.Available(info(), false), controller.state.value)
        controller.checkNow() // the user asked: show it
        assertEquals(UpdateState.Available(info(), true), controller.state.value)
        clock += SNOOZE_MS
        controller.checkIfDue()
        assertEquals(UpdateState.Available(info(), true), controller.state.value)
    }

    @Test fun snoozeDoesNotHideANewerVersion() = runBlocking {
        controller.checkIfDue()
        controller.later()
        source.releases = listOf(release("v0.2.7"))
        clock += CHECK_INTERVAL_MS
        controller.checkIfDue()
        assertEquals(true, (controller.state.value as UpdateState.Available).showSheet)
    }

    @Test fun checkFailureIsSpecificAndOfflineDoesNotUseUpTheInterval() = runBlocking {
        source.failCheck = UpdateError.Offline
        controller.checkIfDue()
        assertEquals(UpdateState.CheckFailed(UpdateError.Offline), controller.state.value)
        source.failCheck = null
        controller.checkIfDue() // offline did not record a check, so this one runs at once
        assertEquals(2, source.releaseCalls)
        assertTrue(controller.state.value is UpdateState.Available)
    }

    @Test fun newestReleaseWithoutAssetsIsReported() = runBlocking {
        source.releases = listOf(release("v0.2.6", withAssets = false))
        controller.checkNow()
        assertEquals(UpdateState.CheckFailed(UpdateError.NoAsset("0.2.6")), controller.state.value)
    }

    @Test fun goodUpdateInstallsAndDeletesTheFile() = runBlocking {
        source.releases = listOf(release("v0.2.6"))
        controller.update(info())
        assertEquals(1, installer.installed)
        assertTrue(installer.existedAtInstall)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
        assertEquals(UpdateState.Idle, controller.state.value)
    }

    @Test fun checksumMismatchDeletesAndNeverInstalls() = runBlocking {
        source.reportedSha = "00".repeat(32)
        controller.update(info())
        assertEquals(0, installer.installed)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
        assertEquals(UpdateError.ChecksumMismatch, (controller.state.value as UpdateState.UpdateFailed).error)
    }

    @Test fun signerMismatchDeletesAndNeverInstalls() = runBlocking {
        signers.apk = ApkIdentity("app.rasul", 6, setOf("bb"))
        controller.update(info())
        assertEquals(0, installer.installed)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
        assertEquals(UpdateError.SignerMismatch, (controller.state.value as UpdateState.UpdateFailed).error)
    }

    @Test fun unreadableSignerNeverInstalls() = runBlocking {
        signers.apk = null
        controller.update(info())
        assertEquals(0, installer.installed)
        assertEquals(UpdateError.SignerUnreadable, (controller.state.value as UpdateState.UpdateFailed).error)
        signers.apk = ApkIdentity("app.rasul", 6, setOf("aa"))
        signers.own = ApkIdentity("app.rasul", 5, emptySet())
        controller.update(info())
        assertEquals(0, installer.installed)
    }

    @Test fun apkOfAnotherPackageIsDeletedAndNeverInstalled() = runBlocking {
        signers.apk = ApkIdentity("com.other.app", 6, setOf("aa"))
        controller.update(info())
        assertEquals(0, installer.installed)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
        assertEquals(UpdateError.WrongPackage, (controller.state.value as UpdateState.UpdateFailed).error)
    }

    @Test fun apkNotNewerThanInstalledIsDeletedAndNeverInstalled() = runBlocking {
        for (code in listOf(5L, 4L)) { // equal, then older
            signers.apk = ApkIdentity("app.rasul", code, setOf("aa"))
            controller.update(info())
            assertEquals(0, installer.installed)
            assertFalse(File(cache, "rasul-0.2.6.apk").exists())
            assertEquals(UpdateError.NotNewer, (controller.state.value as UpdateState.UpdateFailed).error)
        }
    }

    @Test fun installerThatNeverReportsEndsInNotConfirmedAndCheckNowWorksAgain() = runBlocking {
        installer.hang = true
        controller.checkNow()
        val release = info()
        controller.update(release)
        assertEquals(1, installer.installed)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
        assertEquals(UpdateError.InstallNotConfirmed, (controller.state.value as UpdateState.UpdateFailed).error)
        val calls = source.releaseCalls
        controller.checkNow()
        assertEquals(calls + 1, source.releaseCalls)
        assertTrue(controller.state.value is UpdateState.Available)
    }

    @Test fun cancelWhileInstallingLeavesTheOfferAndNothingInstalled() = runBlocking {
        installer.hang = true
        controller.startUpdate(info())
        while (controller.state.value !is UpdateState.Working || (controller.state.value as UpdateState.Working).phase != UpdatePhase.Installing) {
            kotlinx.coroutines.delay(5)
        }
        controller.cancelUpdate()
        while (controller.state.value is UpdateState.Working) kotlinx.coroutines.delay(5)
        assertEquals(UpdateState.Available(info(), true), controller.state.value)
        assertFalse(File(cache, "rasul-0.2.6.apk").exists())
    }

    @Test fun malformedChecksumFileNeverDownloads() = runBlocking {
        source.checksumText = { "not a checksum" }
        controller.update(info())
        assertEquals(0, source.downloadCalls)
        assertEquals(UpdateError.BadChecksumFile, (controller.state.value as UpdateState.UpdateFailed).error)
    }

    @Test fun blockedInstallSourcesStopsBeforeAnyDownload() = runBlocking {
        installer.allowed = false
        controller.update(info())
        assertEquals(0, source.downloadCalls)
        assertEquals(UpdateError.InstallBlocked, (controller.state.value as UpdateState.UpdateFailed).error)
    }

    @Test fun dismissedFailureReturnsToTheHiddenOffer() = runBlocking {
        installer.allowed = false
        controller.update(info())
        controller.dismissFailure()
        assertEquals(UpdateState.Available(info(), false), controller.state.value)
    }
}

class HttpUpdateSourceTest {
    private val server = MockWebServer()

    @After fun stop() { runCatching { server.shutdown() } }

    private fun source(versionName: String = "0.2.5") = HttpUpdateSource(
        versionName = versionName,
        releasesUrl = server.url("/releases").toString(),
        hostAllowed = { true },
        assetAllowed = { true },
    )

    private fun errorOf(block: suspend () -> Unit): UpdateError {
        try {
            runBlocking { block() }
        } catch (e: UpdateException) {
            return e.error
        }
        fail("no error")
        throw AssertionError()
    }

    @Test fun releasesRequestCarriesUserAgentAndParses() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setBody("""[{"tag_name":"v0.2.6","assets":[]}]"""))
        val releases = source().fetchReleases()
        assertEquals("v0.2.6", releases.single().tag)
        val request = server.takeRequest()
        assertEquals("Rasul/0.2.5", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun rateLimitAndHttpErrorsAreSpecific() {
        server.start()
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateError.RateLimited, errorOf { source().fetchReleases() })
        assertEquals(UpdateError.RateLimited, errorOf { source().fetchReleases() })
        assertEquals(UpdateError.Http(500), errorOf { source().fetchReleases() })
    }

    @Test fun garbageAndOversizeBodiesAreBadResponses() {
        server.start()
        server.enqueue(MockResponse().setBody("not json"))
        server.enqueue(MockResponse().setBody("[" + " ".repeat(1_000_100) + "]"))
        assertEquals(UpdateError.BadResponse, errorOf { source().fetchReleases() })
        assertEquals(UpdateError.BadResponse, errorOf { source().fetchReleases() })
    }

    @Test fun unreachableServerIsOffline() {
        server.start()
        val s = source()
        server.shutdown()
        assertEquals(UpdateError.Offline, errorOf { s.fetchReleases() })
    }

    @Test fun defaultAddressPolicyRefusesOtherHosts() {
        server.start()
        server.enqueue(MockResponse().setBody("[]"))
        val s = HttpUpdateSource(releasesUrl = server.url("/releases").toString())
        assertEquals(UpdateError.BadResponse, errorOf { s.fetchReleases() })
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectToAForbiddenHostIsRefused() {
        server.start()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/x"))
        val allowedOnlyThisServer = HttpUpdateSource(
            releasesUrl = server.url("/releases").toString(),
            hostAllowed = { it.port == server.port },
        )
        assertEquals(UpdateError.BadResponse, errorOf { allowedOnlyThisServer.fetchReleases() })
    }

    @Test fun checksumTextIsCapped() {
        server.start()
        server.enqueue(MockResponse().setBody("x".repeat(5000)))
        assertEquals(UpdateError.BadResponse, errorOf { source().fetchChecksum(server.url("/a").toString()).let { } })
    }

    @Test fun downloadStreamsReportsProgressAndHashes() = runBlocking {
        server.start()
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val dest = File.createTempFile("dlfile", ".apk")
        val seen = mutableListOf<Long>()
        try {
            val sha = source().download(server.url("/a").toString(), dest) { read, total ->
                seen += read
                assertEquals(bytes.size.toLong(), total)
            }
            assertEquals(sha256(bytes), sha)
            assertEquals(bytes.size.toLong(), dest.length())
            assertEquals(bytes.size.toLong(), seen.last())
            assertTrue(seen.size > 1)
        } finally {
            dest.delete()
        }
    }

    @Test fun downloadOverTheLimitIsRefusedByLength() {
        server.start()
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 100L * 1024 * 1024 + 1))
        val dest = File.createTempFile("dlfile", ".apk")
        try {
            // A body larger than the cap is refused as soon as the header says so.
            val error = errorOf { source().download(server.url("/a").toString(), dest) { _, _ -> } }
            assertEquals(UpdateError.TooLarge, error)
        } finally {
            dest.delete()
        }
    }

    @Test fun downloadWithoutContentLengthIsStoppedAtTheCap() {
        server.start()
        // Chunked: no Content-Length, so only the running byte count can stop it.
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(5_000)), 512))
        val dest = File.createTempFile("dlfile", ".apk")
        try {
            val s = HttpUpdateSource(
                versionName = "0.2.5", hostAllowed = { true }, assetAllowed = { true }, maxApkBytes = 1_000,
            )
            assertEquals(UpdateError.TooLarge, errorOf { s.download(server.url("/a").toString(), dest) { _, _ -> } })
        } finally {
            dest.delete()
        }
    }

    @Test fun fifthRedirectIsFollowedButTheSixthIsRefused() {
        server.start()
        repeat(5) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/n$it")) }
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<GithubRelease>(), runBlocking { source().fetchReleases() })
        assertEquals(6, server.requestCount)
    }

    @Test fun sixthRedirectIsRefusedWithoutAnotherRequest() {
        server.start()
        repeat(7) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/n$it")) }
        assertEquals(UpdateError.BadResponse, errorOf { source().fetchReleases() })
        assertEquals(6, server.requestCount)
    }

    @Test fun downloadHttpErrorIsSpecific() {
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = File.createTempFile("dlfile", ".apk")
        try {
            assertEquals(UpdateError.Http(404), errorOf { source().download(server.url("/a").toString(), dest) { _, _ -> } })
        } finally {
            dest.delete()
        }
    }
}
