package app.rasul.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The only API address the update check may call. */
const val RELEASES_URL = "https://api.github.com/repos/rasul-app/rasul/releases?per_page=10"
const val INSTALL_TIMEOUT_MS = 2L * 60 * 1000
const val MAX_RELEASES_BODY_BYTES = 1_000_000L
const val MAX_CHECKSUM_BYTES = 4_096L
const val MAX_APK_BYTES = 100L * 1024 * 1024
const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
const val SNOOZE_MS = 24L * 60 * 60 * 1000
const val MAX_NOTES_CHARS = 4_000

/** Parsed `X.Y.Z`; compares numerically. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val STRICT = Regex("^([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})$")
        private val LENIENT = Regex("^([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})(?:[-+][0-9A-Za-z.+-]*)?$")

        /** `X.Y.Z` with canonical numbers (no leading zeros); anything else is null. */
        fun parse(text: String): SemVer? = from(STRICT.matchEntire(text))

        /** The installed versionName may carry a build suffix such as `-debug`. */
        fun parseInstalled(text: String): SemVer? = from(LENIENT.matchEntire(text))

        /** Release tag `vX.Y.Z` only. */
        fun parseTag(tag: String): SemVer? = if (tag.startsWith("v")) parse(tag.substring(1)) else null

        private fun from(m: MatchResult?): SemVer? {
            val g = m?.groupValues ?: return null
            val parts = g.drop(1)
            if (parts.any { it != it.toInt().toString() }) return null
            return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    val draft: Boolean = false,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

data class ReleaseInfo(
    val version: SemVer,
    val notes: String,
    val apkUrl: String,
    val shaUrl: String,
) {
    val apkName get() = "rasul-$version.apk"
}

sealed interface UpdateError {
    data object Offline : UpdateError
    data object RateLimited : UpdateError
    data class Http(val code: Int) : UpdateError
    data object BadResponse : UpdateError
    data class NoAsset(val version: String) : UpdateError
    data object BadChecksumFile : UpdateError
    data object ChecksumMismatch : UpdateError
    data object SignerMismatch : UpdateError
    data object SignerUnreadable : UpdateError
    data object TooLarge : UpdateError
    data object InstallBlocked : UpdateError
    data object WrongPackage : UpdateError
    data object NotNewer : UpdateError
    data object InstallNotConfirmed : UpdateError
    data class InstallFailed(val detail: String) : UpdateError
}

class UpdateException(val error: UpdateError) : Exception(error.toString())

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

fun parseReleases(body: String): List<GithubRelease>? =
    runCatching { json.decodeFromString<List<GithubRelease>>(body) }.getOrNull()

/**
 * Only this repository's download address over https is ever fetched: exactly
 * `/rasul-app/rasul/releases/download/<tag>/<asset>`, judged on the parsed path segments.
 */
fun isAllowedAssetUrl(url: String): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    if (!u.isHttps || u.host != "github.com" || u.port != 443) return false
    if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return false
    val s = u.encodedPathSegments
    if (s.size != 6 || s.take(4) != listOf("rasul-app", "rasul", "releases", "download")) return false
    return s.drop(4).all { it.isNotEmpty() && it != "." && it != ".." && '%' !in it && '\\' !in it }
}

/**
 * The highest `vX.Y.Z` release (pre-releases included, drafts skipped) above [installed].
 * Returns null when nothing is newer, or an error when the newest one lacks its two assets.
 */
fun pickRelease(releases: List<GithubRelease>, installed: SemVer): Result<ReleaseInfo?> {
    val best = releases.asSequence()
        .filter { !it.draft }
        .mapNotNull { r -> SemVer.parseTag(r.tag)?.let { it to r } }
        .filter { it.first > installed }
        .maxByOrNull { it.first } ?: return Result.success(null)
    val (version, release) = best
    val apkName = "rasul-$version.apk"
    val apk = release.assets.firstOrNull { it.name == apkName && isAllowedAssetUrl(it.url) }
    val sha = release.assets.firstOrNull { it.name == "$apkName.sha256" && isAllowedAssetUrl(it.url) }
    if (apk == null || sha == null) return Result.failure(UpdateException(UpdateError.NoAsset(version.toString())))
    return Result.success(ReleaseInfo(version, (release.body ?: "").take(MAX_NOTES_CHARS), apk.url, sha.url))
}

/** `<64 hex> *rasul-X.Y.Z.apk` (the star or a second space is optional); returns lowercase hex. */
fun parseChecksumFile(text: String, apkName: String): String? {
    val m = Regex("^([0-9a-fA-F]{64}) [ *]?" + Regex.escape(apkName) + "\\s*$").matchEntire(text.trim()) ?: return null
    return m.groupValues[1].lowercase()
}

// ---- release notes as simple text ----

data class NoteSpan(val text: String, val bold: Boolean = false)

enum class NoteKind { Heading, Bullet, Paragraph }

data class NoteLine(val kind: NoteKind, val spans: List<NoteSpan>)

private val LINK = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")

/** Headings, bullets and bold only. A link shows its label as plain text; nothing is clickable. */
fun renderNotes(markdown: String): List<NoteLine> {
    val lines = markdown.replace("\r\n", "\n").lines()
    val out = mutableListOf<NoteLine>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val heading = Regex("^#{1,6}\\s+(.*)$").matchEntire(line)
        val bullet = Regex("^[-*+]\\s+(.*)$").matchEntire(line)
        when {
            heading != null -> out += NoteLine(NoteKind.Heading, inline(heading.groupValues[1]).map { it.copy(bold = true) })
            bullet != null -> out += NoteLine(NoteKind.Bullet, inline(bullet.groupValues[1]))
            else -> out += NoteLine(NoteKind.Paragraph, inline(line))
        }
    }
    return out
}

private fun inline(text: String): List<NoteSpan> {
    val plain = LINK.replace(text) { it.groupValues[1] }.replace("`", "")
    val spans = mutableListOf<NoteSpan>()
    plain.split("**").forEachIndexed { i, part -> if (part.isNotEmpty()) spans += NoteSpan(part, bold = i % 2 == 1) }
    return spans
}
