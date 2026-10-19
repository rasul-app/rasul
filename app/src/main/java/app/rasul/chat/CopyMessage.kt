package app.rasul.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.rasul.R
import app.rasul.files.FileLinks

/** Android 13 and later show their own clipboard chip after a copy; an app message on top would be a second one. */
internal fun showsOwnCopyConfirmation(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU

/**
 * Long press copies [text] to the clipboard, file links reduced to their label (no download token); TalkBack gets the same as a "Copy" action. Taps pass through untouched, so
 * links and buttons next to the text keep working. [text] is read when the press happens, so a streaming message copies
 * what it shows at that moment.
 */
@Composable
internal fun Modifier.copyOnLongPress(text: String): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(text)
    val copiedMessage = stringResource(R.string.chat_copied)
    val copy = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", FileLinks.plainText(current)))
        if (!showsOwnCopyConfirmation(Build.VERSION.SDK_INT)) {
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    return this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                copy()
            })
        }
        .semantics {
            customActions = listOf(CustomAccessibilityAction(label = context.getString(R.string.chat_copy_action)) { copy(); true })
        }
}
