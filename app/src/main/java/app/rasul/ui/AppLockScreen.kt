package app.rasul.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

@Composable
fun AppLockScreen(readError: Boolean = false, onRetry: () -> Unit = {}, authenticationAvailable: Boolean = true, onOpenSecuritySettings: () -> Unit = {}, onUnlock: () -> Unit) {
    val colors = Rasul.colors
    Column(
        Modifier.fillMaxSize().background(colors.bg).padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.lock_title), style = RasulType.emptyTitle, color = colors.ink)
        Text(
            stringResource(if (readError) R.string.lock_read_error else R.string.lock_body),
            style = RasulType.body,
            color = colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (readError) PrimaryButton(stringResource(R.string.retry), onRetry)
        if (authenticationAvailable) {
            PrimaryButton(stringResource(R.string.lock_unlock), onUnlock, modifier = Modifier.widthIn(min = 120.dp))
        } else {
            Text(stringResource(R.string.lock_unavailable), style = RasulType.body, color = colors.muted)
            PrimaryButton(stringResource(R.string.lock_open_settings), onOpenSecuritySettings)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun LockEn() = RasulTheme(false) { AppLockScreen {} }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun LockAr() = RasulTheme(false) { AppLockScreen {} }
