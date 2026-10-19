package app.rasul.chat

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.PendingAttachment
import app.rasul.data.SIZE_UNKNOWN
import app.rasul.data.UploadState
import app.rasul.files.FileIcons
import app.rasul.files.FileLinks
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType

/**
 * [name] cut to at most [max] characters with the middle replaced by an ellipsis, so the start and the extension both
 * stay readable ("quarterly…l-v2.pdf"). Names that fit are returned as they are.
 */
internal fun middleEllipsize(name: String, max: Int = 18): String {
    if (name.length <= max || max < 5) return name
    val keepEnd = (max - 1) / 2
    val keepStart = max - 1 - keepEnd
    // Both cuts fall between code points: a pair (an emoji) is kept whole or left out whole.
    val head = if (name[keepStart - 1].isHighSurrogate() && name[keepStart].isLowSurrogate()) keepStart - 1 else keepStart
    val tail = name.length - keepEnd
    val from = if (name[tail - 1].isHighSurrogate() && name[tail].isLowSurrogate()) tail + 1 else tail
    return name.substring(0, head).trimEnd() + "…" + name.substring(from).trimStart()
}

/** The percentage of an upload that is done, 0..100. */
internal fun uploadPercent(sent: Long, size: Long): Int =
    if (size <= 0) 0 else ((sent.coerceIn(0, size) * 100) / size).toInt()

/** One bridge file above the composer: icon, name, size, a progress bar while it uploads, a check when it is ready. */
@Composable
internal fun UploadFileChip(
    file: PendingAttachment.UploadFile,
    onRemove: (Long) -> Unit,
    onRetry: (Long) -> Unit,
) {
    val colors = Rasul.colors
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, file.size)
    val shownName = middleEllipsize(file.name)
    val state = file.state
    val description = when (state) {
        is UploadState.Uploading -> stringResource(R.string.chat_upload_desc_uploading, file.name, size, uploadPercent(state.sent, file.size))
        is UploadState.Ready -> stringResource(R.string.chat_upload_desc_ready, file.name, size)
        is UploadState.Failed -> stringResource(R.string.chat_upload_desc_failed, file.name, size)
    }
    val removeLabel = stringResource(R.string.chat_attachment_remove_named, file.name)
    Row(
        Modifier.width(264.dp).clip(RasulShapes.chip).background(colors.fill).padding(start = 12.dp)
            .testTag("chat_attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                FileIcons.forKind(FileLinks.kindOf(file.name)),
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shownName, style = RasulType.chip, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (state) {
                    is UploadState.Uploading -> {
                        Text(
                            stringResource(R.string.chat_upload_progress, size, uploadPercent(state.sent, file.size)),
                            style = RasulType.bodySmall, color = colors.muted, maxLines = 1,
                        )
                        LinearProgressIndicator(
                            progress = { if (file.size > 0) (state.sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().testTag("chat_upload_progress"),
                            color = colors.accent,
                            trackColor = colors.hairline,
                        )
                    }
                    is UploadState.Ready -> Text(size, style = RasulType.bodySmall, color = colors.muted, maxLines = 1)
                    is UploadState.Failed -> Text(
                        stringResource(R.string.chat_upload_failed),
                        style = RasulType.bodySmall, color = colors.dangerText, maxLines = 2,
                    )
                }
            }
            if (state is UploadState.Ready) {
                Icon(RasulIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp).testTag("chat_upload_done"))
            }
        }
        if (state is UploadState.Failed) {
            val retryLabel = stringResource(R.string.chat_upload_retry_named, file.name)
            Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = retryLabel }
                    .clickable(role = Role.Button) { onRetry(file.key) }
                    .testTag("chat_upload_retry"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RasulIcons.Retry, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = removeLabel }
                .clickable(role = Role.Button) { onRemove(file.key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RasulIcons.X, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
        }
    }
}

/** The reason Send is off while a file uploads. */
@Composable
internal fun UploadingNote() {
    val colors = Rasul.colors
    val label = stringResource(R.string.chat_uploading)
    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp).testTag("chat_uploading_note"), contentAlignment = Alignment.Center) {
        Text(label, style = RasulType.chip, color = colors.muted)
    }
}

/** A file the user sent, on the sent bubble: type icon, name and size, no download link. */
@Composable
internal fun SentFileCard(name: String, size: Long, mine: Boolean) {
    val colors = Rasul.colors
    val context = LocalContext.current
    val sizeText = if (size == SIZE_UNKNOWN) null else Formatter.formatShortFileSize(context, size)
    val description = if (sizeText == null) name else stringResource(R.string.chat_sent_file_desc, name, sizeText)
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }.testTag("chat_sent_file"),
        shape = RasulShapes.card,
        color = colors.bg,
        border = BorderStroke(1.dp, colors.cardBorder),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FileIcons.forKind(FileLinks.kindOf(name)), contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = RasulType.rowTitle, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sizeText != null) Text(sizeText, style = RasulType.bodySmall, color = colors.muted)
            }
        }
    }
}
