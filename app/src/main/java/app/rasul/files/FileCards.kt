package app.rasul.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.rasul.R
import app.rasul.chat.ChatFailure
import app.rasul.chat.failureText
import app.rasul.data.decodeSampled
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saved bridge id of the open chat. Null (direct connections, previews): no file cards, links stay plain text. */
val LocalFileBridge = compositionLocalOf<String?> { null }
val LocalFileDownloads = staticCompositionLocalOf<FileDownloads?> { null }

/** Largest image decoded for a thumbnail. Bigger ones keep the plain card. */
internal const val THUMB_MAX_BYTES = 8L * 1024 * 1024
private const val THUMB_EDGE = 640
private const val PICTURE_EDGE = 1280
private val PICTURE_MAX_HEIGHT = 360.dp
private val PICTURE_MIN_EDGE = 48.dp

/** A picture the card fetches by itself: an image link whose size is known and within [THUMB_MAX_BYTES]. */
internal fun autoDownloadsPicture(link: FileLink): Boolean {
    val size = FileLinks.sizeBytes(link.sizeText) ?: return false
    return FileLinks.isInlineImage(link.name) && size <= THUMB_MAX_BYTES
}

/** Result of decoding a received picture; [bitmap] is null when the platform cannot decode it. */
internal class Decoded(val bitmap: ImageBitmap?)

/**
 * The decoded picture or thumbnail of a finished download. It is null while a new file is being decoded (the key
 * changed), so the card shows the loading placeholder first and never flashes the plain Done card.
 */
@Composable
internal fun rememberDecoded(
    done: FileState.Done?,
    picture: Boolean,
    decode: suspend (File, Int) -> ImageBitmap? = { file, edge -> decodeSampled(file.readBytes(), edge)?.asImageBitmap() },
): State<Decoded?> = produceState<Decoded?>(null, done?.file) {
    value = null
    if (done == null) return@produceState
    val edge = if (picture) PICTURE_EDGE else THUMB_EDGE
    value = if ((picture || FileLinks.hasThumbnail(done.name)) && done.size <= THUMB_MAX_BYTES) {
        withContext(Dispatchers.Default) { Decoded(runCatching { decode(done.file, edge) }.getOrNull()) }
    } else Decoded(null)
}

/**
 * Assistant message text. With a bridge chat and a `rasul-file://` link in the text it becomes text plus file cards;
 * in every other case it is the same single Text the chat always showed.
 */
@Composable
fun AssistantText(text: String, style: TextStyle, color: Color) {
    val bridge = LocalFileBridge.current
    val downloads = LocalFileDownloads.current
    if (bridge == null || downloads == null || !FileLinks.hasFileLink(text)) {
        Text(text, style = style, color = color)
        return
    }
    val parts = remember(text) { FileLinks.split(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> Text(part.text.trim('\n'), style = style, color = color)
                is MessagePart.File -> FileCardHost(bridge, downloads, part.link)
                is MessagePart.Unavailable -> Text(
                    if (part.name == null) stringResource(R.string.file_not_available)
                    else stringResource(R.string.file_not_available_named, part.name),
                    style = RasulType.bodySmall,
                    color = Rasul.colors.muted,
                )
            }
        }
    }
}

@Composable
private fun FileCardHost(bridgeId: String, downloads: FileDownloads, link: FileLink) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by downloads.states.collectAsState()
    LaunchedEffect(link.token) {
        withContext(Dispatchers.IO) { downloads.probe(link.token) }
        downloads.startAuto(bridgeId, link)
    }
    val state = states[link.token] ?: FileState.Idle
    val done = state as? FileState.Done
    val saveMime = done?.mime ?: "application/octet-stream"
    val saveName = done?.name ?: link.name
    val saver = rememberLauncherForActivityResult(remember(saveMime) { ActivityResultContracts.CreateDocument(saveMime) }) { uri ->
        if (uri != null && done != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                saveCopy(
                    done.file,
                    discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
                ) { context.contentResolver.openOutputStream(uri) }
            }
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }
    val picture = autoDownloadsPicture(link)
    val decoded by rememberDecoded(done, picture)
    val bitmap = decoded?.bitmap
    // The 24 h clean-up can remove a copy while the chat is still open: fall back to the Download button.
    fun withFile(action: (FileState.Done) -> Unit) {
        val current = done ?: return
        if (!current.file.isFile) {
            downloads.cancel(link.token)
            Toast.makeText(context, R.string.file_gone, Toast.LENGTH_SHORT).show()
        } else action(current)
    }
    val onOpen = { withFile { openFile(context, it) } }
    val onSave = { withFile { saver.launch(FileLinks.sanitiseName(saveName)) } }
    val onShare = { withFile { shareFile(context, it) } }
    // A picture that is still being decoded keeps its place; one the platform cannot decode falls back to the card.
    if (picture && done != null && decoded == null) {
        PicturePlaceholder(done.name)
    } else if (picture && done != null && bitmap != null) {
        PictureCard(done.name, bitmap, onOpen, onSave, onShare)
    } else {
        FileCard(
            link = link,
            state = state,
            thumbnail = bitmap,
            onDownload = { downloads.start(bridgeId, link) },
            onCancel = { downloads.cancel(link.token) },
            onOpen = onOpen,
            onSave = onSave,
            onShare = onShare,
        )
    }
}

private fun uriFor(context: Context, file: File) =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

private fun openFile(context: Context, done: FileState.Done) {
    // Active content (html, svg, xml, js) is never handed to a viewer; the card does not offer Open for it either.
    if (!canOpen(done.name, done.mime)) return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, done.file), done.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.file_no_viewer, Toast.LENGTH_LONG).show()
    }
}

private fun shareFile(context: Context, done: FileState.Done) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(done.mime)
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, done.file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.file_no_viewer, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun failureMessage(failure: FileFailure): String = when (failure) {
    FileFailure.NotFound -> stringResource(R.string.file_err_not_found)
    FileFailure.Expired -> stringResource(R.string.file_err_expired)
    FileFailure.Changed -> stringResource(R.string.file_err_changed)
    FileFailure.Unavailable -> stringResource(R.string.file_err_unavailable)
    FileFailure.RateLimited -> stringResource(R.string.file_err_rate_limited)
    FileFailure.Forbidden -> stringResource(R.string.bridge_revoked)
    FileFailure.TooLarge -> stringResource(R.string.file_err_too_large)
    FileFailure.ShortBody -> stringResource(R.string.file_err_short)
    FileFailure.Storage -> stringResource(R.string.file_err_storage)
    is FileFailure.Server -> stringResource(R.string.err_server, failure.code)
    is FileFailure.Transport -> failureText(ChatFailure.Api(failure.error))
}

/** A failed download can be tried again only when the cause may pass; an expired or changed file cannot. */
internal fun canRetry(failure: FileFailure): Boolean = when (failure) {
    FileFailure.NotFound, FileFailure.Expired, FileFailure.Changed, FileFailure.Unavailable,
    FileFailure.Forbidden, FileFailure.TooLarge -> false
    else -> true
}

/** Buttons the card offers for a finished download. Active content gets Save and Share only. */
// An octet-stream type means the real type was blocked or is unknown: there is nothing safe to open it with.
internal fun canOpen(name: String, mime: String = ""): Boolean =
    !FileLinks.isActiveContent(name) && mime != "application/octet-stream" && !FileLinks.neverRender(mime)

/** Stateless card: every state is a parameter so previews and tests drive it directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileCard(
    link: FileLink,
    state: FileState,
    thumbnail: ImageBitmap? = null,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = Rasul.colors
    val context = LocalContext.current
    val name = (state as? FileState.Done)?.name ?: FileLinks.cleanFormat(link.name)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RasulShapes.card,
        color = colors.bg,
        border = BorderStroke(1.dp, colors.cardBorder),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FileIcons.forKind(FileLinks.kindOf(name)), contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = RasulType.rowTitle, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when (state) {
                        FileState.Idle -> link.sizeText?.let { Text(it, style = RasulType.bodySmall, color = colors.muted) }
                        is FileState.Downloading -> Text(
                            if (state.total != null) stringResource(R.string.file_progress, Formatter.formatShortFileSize(context, state.done), Formatter.formatShortFileSize(context, state.total))
                            else stringResource(R.string.file_progress_unknown, Formatter.formatShortFileSize(context, state.done)),
                            style = RasulType.bodySmall, color = colors.muted,
                        )
                        is FileState.Done -> Text(Formatter.formatShortFileSize(context, state.size), style = RasulType.bodySmall, color = colors.muted)
                        is FileState.Failed -> Text(failureMessage(state.failure), style = RasulType.bodySmall, color = colors.dangerText)
                    }
                }
            }
            if (state is FileState.Downloading) {
                val total = state.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.fill,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.fill)
                }
            }
            if (state is FileState.Done && thumbnail != null) {
                Image(
                    thumbnail,
                    contentDescription = stringResource(R.string.file_thumb_desc, name),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    FileState.Idle -> CardAction(stringResource(R.string.file_download), stringResource(R.string.file_download_named, name), onDownload)
                    is FileState.Downloading -> CardAction(stringResource(R.string.file_cancel), stringResource(R.string.file_cancel_named, name), onCancel)
                    is FileState.Done -> {
                        if (canOpen(state.name, state.mime)) CardAction(stringResource(R.string.file_open), stringResource(R.string.file_open_named, name), onOpen)
                        CardAction(stringResource(R.string.file_save), stringResource(R.string.file_save_named, name), onSave)
                        CardAction(stringResource(R.string.file_share), stringResource(R.string.file_share_named, name), onShare)
                    }
                    is FileState.Failed -> if (canRetry(state.failure)) {
                        CardAction(stringResource(R.string.file_retry), stringResource(R.string.file_retry_named, name), onDownload)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(label: String, description: String, onClick: () -> Unit) {
    val accent = Rasul.colors.accent
    Box(
        Modifier.heightIn(min = 48.dp)
            .clip(RasulShapes.filter)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = RasulType.buttonSmall, color = accent, maxLines = 1,
            modifier = Modifier.border(1.dp, accent, RasulShapes.filter).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Shown while a finished picture is being decoded, so the bubble does not jump. */
@Composable
internal fun PicturePlaceholder(name: String) {
    val colors = Rasul.colors
    val label = stringResource(R.string.file_picture_loading, name)
    Box(
        Modifier.fillMaxWidth().height(160.dp).clip(RasulShapes.card).background(colors.fill)
            .border(1.dp, colors.cardBorder, RasulShapes.card).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent, strokeWidth = 2.dp)
    }
}

/**
 * A received picture in the bubble: as wide as the bubble allows, at most [PICTURE_MAX_HEIGHT] high, aspect ratio kept.
 * Tap opens it; the overflow button and a long press offer Save and Share.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PictureCard(name: String, bitmap: ImageBitmap, onOpen: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    val colors = Rasul.colors
    var menu by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.file_more_named, name)
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fitWidth = minOf(maxWidth, PICTURE_MAX_HEIGHT * ratio)
        // A very thin picture keeps a box that can be tapped; it is cropped to that box instead of shrunk to a line.
        val width = fitWidth.coerceAtLeast(PICTURE_MIN_EDGE)
        val height = (width / ratio).coerceIn(PICTURE_MIN_EDGE, PICTURE_MAX_HEIGHT)
        val cropped = width != fitWidth || height != width / ratio
        Box(Modifier.size(width, height).clip(RasulShapes.card)) {
            Image(
                bitmap,
                contentDescription = stringResource(R.string.file_thumb_desc, name),
                modifier = Modifier.fillMaxSize().combinedClickable(
                    onClickLabel = stringResource(R.string.file_open_named, name),
                    onClick = onOpen,
                    onLongClickLabel = moreLabel,
                    onLongClick = { menu = true },
                ),
                contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit,
            )
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true }
                        .semantics { contentDescription = moreLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(FileIcons.more, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_save), style = RasulType.rowTitle, color = colors.ink) },
                        onClick = { menu = false; onSave() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_share), style = RasulType.rowTitle, color = colors.ink) },
                        onClick = { menu = false; onShare() },
                    )
                }
            }
        }
    }
}
