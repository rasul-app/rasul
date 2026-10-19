package app.rasul.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.rasul.R

/**
 * The switch/button handler shared by the alert settings. Turning on asks for POST_NOTIFICATIONS on API 33+ and
 * only then calls [onChanged]`(true)`; a denial keeps it off and shows [deniedBody] with a link to the system
 * notification settings. Below 33 no permission is needed. Turning off always goes straight through.
 */
@Composable
internal fun rememberNotificationToggle(
    title: Int,
    deniedBody: Int,
    onChanged: (Boolean) -> Unit,
): (Boolean) -> Unit {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onChanged(true) else denied = true }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(deniedBody)) },
            confirmButton = {
                TextButton(onClick = {
                    denied = false
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.pair_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { denied = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { checked ->
        when {
            !checked -> onChanged(false)
            Build.VERSION.SDK_INT < 33 -> onChanged(true)
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED -> onChanged(true)
            else -> askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
