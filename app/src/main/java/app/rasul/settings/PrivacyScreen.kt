package app.rasul.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulType

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        RasulNavBar(stringResource(R.string.privacy_title), stringResource(R.string.back), onBack)
        Text(
            stringResource(R.string.privacy_body),
            style = RasulType.body,
            color = Rasul.colors.ink,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun PrivacyEn() = app.rasul.ui.theme.RasulTheme(false) { PrivacyScreen {} }

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun PrivacyAr() = app.rasul.ui.theme.RasulTheme(false) { PrivacyScreen {} }
