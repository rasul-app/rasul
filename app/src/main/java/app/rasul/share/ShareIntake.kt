package app.rasul.share

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat
import app.rasul.data.RunInputLimits

/**
 * What a share intent carries before anything is read: the text to put in the draft, the content
 * URIs that may be read, and how many items were refused (a URI that is not `content://` or is ours,
 * or the part of the text that was cut off).
 */
data class IncomingShare(val text: String, val uris: List<Uri>, val refused: Int) {
    val isEmpty: Boolean get() = text.isEmpty() && uris.isEmpty()
}

/**
 * Reads a send / send-multiple intent. Returns null when the intent is not a share or carries
 * nothing at all. Only `content://` URIs from other apps are kept: `file://` and every other scheme
 * are refused, and so is anything served by this app's own providers (authority [ownPackage],
 * `[ownPackage].*`, or one [ownsAuthority] reports as ours), so a share can never be used to read
 * this app's private files. Text longer than [SHARE_TEXT_MAX_CHARS] is cut and counts as one refused item.
 */
fun parseShare(intent: Intent, ownPackage: String, ownsAuthority: (String) -> Boolean = { false }): IncomingShare? {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
    val full = shareText(intent)
    val text = capShareText(full)
    val raw = streamUris(intent)
    val kept = LinkedHashSet<Uri>()
    var refused = if (text.length < full.length) 1 else 0
    for (uri in raw) {
        if (isShareableUri(uri, ownPackage, ownsAuthority)) {
            if (!kept.add(uri)) continue
        } else {
            refused++
        }
    }
    if (text.isEmpty() && kept.isEmpty() && refused == 0) return null
    return IncomingShare(text, kept.toList(), refused)
}

/** Longest shared text kept: a worst-case 4 bytes per character still fits one run body. */
const val SHARE_TEXT_MAX_CHARS = RunInputLimits.MAX_RUN_BODY_BYTES / 4

/** [text] cut to [SHARE_TEXT_MAX_CHARS], never between the two halves of a surrogate pair. */
internal fun capShareText(text: String): String {
    if (text.length <= SHARE_TEXT_MAX_CHARS) return text
    val end = if (Character.isHighSurrogate(text[SHARE_TEXT_MAX_CHARS - 1])) SHARE_TEXT_MAX_CHARS - 1 else SHARE_TEXT_MAX_CHARS
    return text.substring(0, end)
}

/** The subject as first line, then the text; a subject repeating the text is not shown twice. */
private fun shareText(intent: Intent): String {
    val body = when (val extra = intent.extras?.get(Intent.EXTRA_TEXT)) {
        is CharSequence -> extra.toString()
        is List<*> -> extra.filterIsInstance<CharSequence>().joinToString("\n")
        else -> ""
    }.trim()
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
    return if (subject.isEmpty() || subject == body) body.ifEmpty { subject } else if (body.isEmpty()) subject else "$subject\n$body"
}

private fun streamUris(intent: Intent): List<Uri> {
    val fromExtra = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    }
    if (fromExtra.isNotEmpty()) return fromExtra
    val clip = intent.clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
}

/** True for a provider authority that [packageName] declares, as the content resolver would look it up. */
fun ownsProviderAuthority(packageManager: PackageManager, packageName: String): (String) -> Boolean = { authority ->
    try {
        packageManager.resolveContentProvider(authority, 0)?.packageName == packageName
    } catch (_: Exception) {
        false
    }
}

internal fun isShareableUri(uri: Uri, ownPackage: String, ownsAuthority: (String) -> Boolean = { false }): Boolean {
    if (!uri.scheme.equals("content", ignoreCase = true)) return false
    // The resolver decodes the authority and drops a "user@" prefix; do the same, so neither
    // content://0@authority/ nor content://0%40authority/ can hide one of our own providers.
    val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return false
    val lower = authority.lowercase()
    val own = ownPackage.lowercase()
    if (lower == own || lower.startsWith("$own.")) return false
    return !ownsAuthority(authority) && !ownsAuthority(lower)
}

/** Drops the shared payload from [intent] so a recreated activity never reads it a second time. */
fun clearShare(intent: Intent) {
    intent.removeExtra(Intent.EXTRA_STREAM)
    intent.removeExtra(Intent.EXTRA_TEXT)
    intent.removeExtra(Intent.EXTRA_SUBJECT)
    intent.clipData = null
    intent.action = Intent.ACTION_MAIN
}
