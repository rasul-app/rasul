package app.rasul.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType

/**
 * The sheet the paperclip opens: Camera, Photos and Files. A direct connection cannot send files, so Files is off there
 * with the reason; the text file that connection could always attach stays reachable as a small row under the note.
 */
@Composable
internal fun AttachSheet(
    filesViaBridge: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onTextFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    RasulSheet(
        title = stringResource(R.string.chat_attach_sheet_title),
        onDismiss = onDismiss,
        titleAction = { AttachCloseButton(onDismiss) },
    ) {
        AttachSheetBody(filesViaBridge, onCamera, onPhotos, onFiles, onTextFile)
    }
}

/** The sheet's content under the title row; the goldens draw it without the window the real sheet lives in. */
@Composable
internal fun AttachSheetBody(
    filesViaBridge: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onTextFile: () -> Unit,
) {
    val colors = Rasul.colors
    Column(Modifier.testTag("chat_attach_sheet")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachTile(
                RasulIcons.Camera, stringResource(R.string.chat_attach_camera), stringResource(R.string.chat_attach_camera_cd),
                enabled = true, onClick = onCamera, modifier = Modifier.weight(1f).testTag("chat_attach_camera"),
            )
            AttachTile(
                RasulIcons.Image, stringResource(R.string.chat_attach_photos), stringResource(R.string.chat_attach_photos_cd),
                enabled = true, onClick = onPhotos, modifier = Modifier.weight(1f).testTag("chat_attach_photos"),
            )
            AttachTile(
                RasulIcons.FileDoc, stringResource(R.string.chat_attach_files), stringResource(R.string.chat_attach_files_cd),
                enabled = filesViaBridge, onClick = onFiles, modifier = Modifier.weight(1f).testTag("chat_attach_any_file"),
                disabledReason = stringResource(R.string.chat_attach_needs_bridge),
            )
        }
        if (!filesViaBridge) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(RasulIcons.Info, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.chat_attach_needs_bridge), style = RasulType.bodySmall, color = colors.muted)
            }
            Text(
                stringResource(R.string.chat_attach_file),
                style = RasulType.buttonSmall,
                color = colors.accent,
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onTextFile)
                    .padding(vertical = 14.dp)
                    .testTag("chat_attach_text_file"),
            )
        }
    }
}

@Composable
private fun AttachTile(
    icon: ImageVector,
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
) {
    val colors = Rasul.colors
    Surface(
        modifier = modifier.heightIn(min = 92.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                if (!enabled) {
                    disabled()
                    disabledReason?.let { stateDescription = it }
                }
            },
        shape = RasulShapes.card,
        color = if (enabled) colors.bg else colors.fill,
        border = BorderStroke(1.dp, colors.cardBorder),
        enabled = enabled,
        onClick = onClick,
    ) {
        Column(
            Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) colors.ink else colors.muted, modifier = Modifier.size(26.dp))
            Text(
                label,
                style = RasulType.buttonSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = if (enabled) colors.ink else colors.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AttachCloseButton(onClick: () -> Unit) {
    val colors = Rasul.colors
    // The circle is 32 dp as drawn; the touch target is 48 dp and overflows the title row evenly.
    Box(
        Modifier.requiredSize(48.dp).clickable(role = Role.Button, onClick = onClick).testTag("chat_attach_close"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(colors.fill, RasulShapes.filter), contentAlignment = Alignment.Center) {
            Icon(RasulIcons.X, contentDescription = stringResource(R.string.chat_attach_close), tint = colors.ink, modifier = Modifier.size(16.dp))
        }
    }
}
