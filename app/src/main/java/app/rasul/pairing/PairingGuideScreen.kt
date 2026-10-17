package app.rasul.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

@Composable
fun PairingGuideScreen(onBack: () -> Unit, onScan: () -> Unit, onDirect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Rasul.colors.bg).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RasulNavBar(stringResource(R.string.pair_guide_title), stringResource(R.string.back), onBack, horizontalPadding = 40.dp, leadingAlignment = Alignment.Center)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PairingStep(1, stringResource(R.string.pair_guide_step_1))
            PairingStep(2, stringResource(R.string.pair_guide_step_2))
            PairingStep(3, stringResource(R.string.pair_guide_step_3))
            PairingStep(4, stringResource(R.string.pair_guide_step_4))
            Text(stringResource(R.string.pair_guide_privacy), style = RasulType.bodySmall, color = Rasul.colors.muted, modifier = Modifier.padding(bottom = 2.dp))
            PrimaryButton(stringResource(R.string.pair_guide_scan), onScan, modifier = Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().heightIn(min = 21.dp).clickable(role = Role.Button, onClick = onDirect), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pair_guide_direct), color = Rasul.colors.accent, style = RasulType.body)
            }
        }
    }
}

@Composable
private fun PairingStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(stringResource(R.string.pair_guide_number, number), style = RasulType.rowTitle, color = Rasul.colors.accent)
        Text(text, style = RasulType.body, color = Rasul.colors.ink, modifier = Modifier.weight(1f).padding(bottom = 2.dp))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun PairingGuideEn() = RasulTheme(false) { PairingGuideScreen({}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun PairingGuideAr() = RasulTheme(false) { PairingGuideScreen({}, {}, {}) }
