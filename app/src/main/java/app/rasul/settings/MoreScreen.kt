package app.rasul.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.Connection
import app.rasul.data.HermesError
import app.rasul.data.Load
import app.rasul.ui.LoadView
import app.rasul.ui.PcPicker
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

@Composable
internal fun LanguageOption.label(): String = stringResource(
    when (this) {
        LanguageOption.System -> R.string.language_system
        LanguageOption.English -> R.string.language_english
        LanguageOption.Arabic -> R.string.language_arabic
    },
)

sealed interface MoreMode {
    data object Blank : MoreMode
    data class Error(val detail: String) : MoreMode
    data object Content : MoreMode
}

fun moreMode(p: PcPicker): MoreMode = when {
    !p.loaded -> MoreMode.Blank
    p.readError != null -> MoreMode.Error(p.readError)
    else -> MoreMode.Content
}

/** More screen and language picker. The PCs group lists saved connections and an add action. */
@Composable
fun MoreScreen(
    pcState: PcPicker,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onRetry: () -> Unit,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    onAppLockChanged: (Boolean) -> Unit,
) {
    var current by remember { mutableStateOf(LanguageOption.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())) }
    var sheet by remember { mutableStateOf(false) }
    // Nothing until the first read ends, so a failure or a slow read never looks like "no PCs".
    when (val mode = moreMode(pcState)) {
        MoreMode.Blank -> Box(Modifier.fillMaxSize().background(Rasul.colors.bg))
        is MoreMode.Error -> Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
            ScreenHeader(stringResource(R.string.tab_more))
            LoadView(Load.Failed(HermesError.Storage(mode.detail)), onRetry) {}
        }
        MoreMode.Content -> MoreContent(
            pcState.pcs, pcState.activeId, current, onAdd, onEdit,
            onLanguageRow = { sheet = true },
            showAppLockSetting = true,
            appLockEnabled = appLockEnabled,
            appLockAvailable = appLockAvailable,
            onAppLockChanged = onAppLockChanged,
        )
    }
    if (sheet) {
        LanguageSheet(current, onDismiss = { sheet = false }) { o ->
            current = o
            sheet = false
            AppCompatDelegate.setApplicationLocales(o.toLocales())
        }
    }
}

@Composable
fun MoreContent(
    pcs: List<Connection>,
    activeId: String?,
    language: LanguageOption,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onLanguageRow: () -> Unit,
    showAppLockSetting: Boolean = false,
    appLockEnabled: Boolean = false,
    appLockAvailable: Boolean = true,
    onAppLockChanged: (Boolean) -> Unit = {},
) {
    val c = Rasul.colors
    val rowPad = PaddingValues(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.tab_more))
        SectionLabel(stringResource(R.string.more_pcs))
        InsetGroup {
            pcs.forEach { pc ->
                RasulRow(
                    onClick = { onEdit(pc.id) }, hairlineStart = 54.dp, contentPadding = rowPad,
                    leading = { Icon(RasulIcons.Pc, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                    trailing = {
                        if (pc.id == activeId) Text(stringResource(R.string.pc_active), style = RasulType.sectionLabel, color = c.accent)
                    },
                ) {
                    RowTitle(pc.name)
                    RowSub(pc.baseUrl + (pc.profile?.let { " · $it" } ?: ""), maxLines = 1)
                }
            }
            RasulRow(
                onClick = onAdd, last = true, contentPadding = rowPad,
                leading = { Icon(RasulIcons.Plus, null, tint = c.accent, modifier = Modifier.size(24.dp)) },
            ) { RowTitle(stringResource(R.string.add_pc), c.accent) }
        }
        SectionLabel(stringResource(R.string.more_settings))
        InsetGroup {
            if (showAppLockSetting) {
                AppLockSettingRow(appLockEnabled, appLockAvailable, onAppLockChanged, last = false)
            }
            RasulRow(
                onClick = onLanguageRow, last = true, contentPadding = rowPad,
                leading = { Icon(RasulIcons.Globe, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(language.label(), style = RasulType.body, color = c.muted)
                        Icon(RasulIcons.Chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                },
            ) { RowTitle(stringResource(R.string.more_language)) }
        }
        Text(stringResource(R.string.unofficial_notice), style = RasulType.bodySmall, color = c.muted, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun AppLockSettingRow(
    enabled: Boolean,
    available: Boolean,
    onChanged: (Boolean) -> Unit,
    last: Boolean,
) {
    RasulRow(
        last = last,
        leading = { Icon(RasulIcons.Lock, null, tint = Rasul.colors.ink, modifier = Modifier.size(24.dp)) },
        trailing = {
            app.rasul.ui.components.RasulToggle(
                enabled,
                label = stringResource(R.string.lock_setting),
                onCheckedChange = onChanged.takeIf { available },
            )
        },
    ) {
        RowTitle(stringResource(R.string.lock_setting))
        RowSub(stringResource(R.string.lock_setting_summary))
        if (!available) RowSub(stringResource(R.string.lock_unavailable), color = Rasul.colors.danger)
    }
}

@Composable
private fun LockSettingPreview() {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_more))
        SectionLabel(stringResource(R.string.more_settings))
        InsetGroup { AppLockSettingRow(enabled = false, available = true, onChanged = {}, last = true) }
    }
}

/** Language sheet with System default, English, and العربية; the current choice is checked. */
@Composable
fun LanguageSheet(current: LanguageOption, onDismiss: () -> Unit, onPick: (LanguageOption) -> Unit) {
    val c = Rasul.colors
    RasulSheet(stringResource(R.string.more_language), onDismiss) {
        LanguageOption.entries.forEachIndexed { i, o ->
            RasulRow(
                onClick = { onPick(o) }, hairlineStart = 0.dp, last = i == LanguageOption.entries.lastIndex,
                contentPadding = PaddingValues(vertical = 14.dp),
                selected = o == current,
                trailing = {
                    if (o == current) Icon(RasulIcons.Check, null, tint = c.accent, modifier = Modifier.size(22.dp))
                    else Icon(RasulIcons.Check, null, tint = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.size(22.dp))
                },
            ) {
                Text(o.label(), style = RasulType.optionTitle, color = c.ink)
                if (o == LanguageOption.System) {
                    Text(stringResource(R.string.language_system_hint), style = RasulType.meta, color = c.muted)
                }
            }
        }
    }
}

private val samplePcs = listOf(
    Connection("a", "Home PC", "http://100.64.0.7:8642", "default"),
    Connection("b", "Laptop", "http://100.64.0.9:8642", "default"),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun MoreEn() = RasulTheme(false) { MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun MoreAr() = RasulTheme(false) { MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun LockSettingEn() = RasulTheme(false) { LockSettingPreview() }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun LockSettingAr() = RasulTheme(false) { LockSettingPreview() }

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun LanguageSheetEn() = RasulTheme(false) { LanguageSheet(LanguageOption.System, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun LanguageSheetAr() = RasulTheme(false) { LanguageSheet(LanguageOption.System, {}, {}) }
