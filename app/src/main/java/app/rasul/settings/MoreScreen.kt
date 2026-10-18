package app.rasul.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.CapabilitiesDto
import app.rasul.data.Connection
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.Load
import app.rasul.bridge.BridgeBotsState
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

data class PairedBridgeRow(val id: String, val name: String, val botCount: Int?)

internal fun pairedBridgeRows(state: BridgeBotsState): List<PairedBridgeRow> = state.groups.map { group ->
    PairedBridgeRow(group.id, group.name, group.profiles.size.takeIf { !group.loading && group.error == null })
}

fun skillsToolsEntryVisible(capabilities: CapabilitiesDto?): Boolean =
    capabilities?.advertisesEndpoint("skills") == true && capabilities.advertisesEndpoint("toolsets")

/** The approval-alerts toggle exists only once a bridge bot exists: without one there is nothing to watch. */
internal fun approvalAlertsRowVisible(bots: BridgeBotsState): Boolean = bots.groups.any { it.profiles.isNotEmpty() }

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
    bridgeState: BridgeBotsState = BridgeBotsState(),
    onBridgeOpen: (String) -> Unit = {},
    appearance: AppearanceOption = AppearanceOption.System,
    hapticsEnabled: Boolean = true,
    versionName: String = "",
    onAppearanceChanged: (AppearanceOption) -> Unit = {},
    onHapticsChanged: (Boolean) -> Unit = {},
    approvalAlertsEnabled: Boolean = false,
    onApprovalAlertsChanged: (Boolean) -> Unit = {},
    onHelp: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onFeedback: () -> Unit = {},
    client: HermesClient? = null,
    onSkillsTools: () -> Unit = {},
    onUsage: () -> Unit = {},
) {
    var current by remember { mutableStateOf(LanguageOption.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var appearanceSheet by rememberSaveable { mutableStateOf(false) }
    var showSkillsTools by remember(pcState.target) { mutableStateOf(false) }
    val targetForCapabilities = (pcState.target as? ActiveTarget.Ready)?.target
    LaunchedEffect(client, targetForCapabilities) {
        showSkillsTools = false
        val api = client ?: return@LaunchedEffect
        val requestTarget = targetForCapabilities ?: return@LaunchedEffect
        val result = api.capabilities(requestTarget)
        showSkillsTools = (result as? HermesResult.Ok)?.value?.let(::skillsToolsEntryVisible) == true
    }
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
            appearance = appearance,
            hapticsEnabled = hapticsEnabled,
            versionName = versionName,
            onAppearanceRow = { appearanceSheet = true },
            onHapticsChanged = onHapticsChanged,
            showApprovalAlerts = approvalAlertsRowVisible(bridgeState),
            approvalAlerts = approvalAlertsEnabled,
            onApprovalAlertsChanged = onApprovalAlertsChanged,
            onHelp = onHelp,
            onPrivacy = onPrivacy,
            onFeedback = onFeedback,
            bridges = pairedBridgeRows(bridgeState),
            onBridgeOpen = onBridgeOpen,
            skillsToolsTargetName = (pcState.target as? ActiveTarget.Ready)?.name.takeIf { showSkillsTools },
            onSkillsTools = onSkillsTools,
            usageTargetName = (pcState.target as? ActiveTarget.Ready)?.name,
            usageNeedsBridge = (pcState.target as? ActiveTarget.Ready)?.target?.bridge == null,
            onUsage = onUsage,
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
    if (appearanceSheet) {
        AppearanceSheet(appearance, onDismiss = { appearanceSheet = false }) { option ->
            appearanceSheet = false
            onAppearanceChanged(option)
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
    bridges: List<PairedBridgeRow> = emptyList(),
    onBridgeOpen: (String) -> Unit = {},
    showAppLockSetting: Boolean = false,
    appLockEnabled: Boolean = false,
    appLockAvailable: Boolean = true,
    onAppLockChanged: (Boolean) -> Unit = {},
    appearance: AppearanceOption = AppearanceOption.System,
    hapticsEnabled: Boolean = true,
    versionName: String = "",
    onAppearanceRow: () -> Unit = {},
    onHapticsChanged: (Boolean) -> Unit = {},
    showApprovalAlerts: Boolean = false,
    approvalAlerts: Boolean = false,
    onApprovalAlertsChanged: (Boolean) -> Unit = {},
    onHelp: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onFeedback: () -> Unit = {},
    skillsToolsTargetName: String? = null,
    onSkillsTools: () -> Unit = {},
    usageTargetName: String? = null,
    usageNeedsBridge: Boolean = false,
    onUsage: () -> Unit = {},
) {
    val c = Rasul.colors
    val rowPad = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.tab_more), modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
        SectionLabel(stringResource(R.string.more_pcs))
        InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
            bridges.forEach { bridge ->
                RasulRow(
                    onClick = { onBridgeOpen(bridge.id) }, hairlineStart = 16.dp, hairlineEnd = 54.dp, gap = 12.dp, textSpacing = 4.dp,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    leading = { Icon(RasulIcons.Pc, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                    trailing = { Text(stringResource(R.string.bridge_status_paired), style = RasulType.sectionLabel, color = c.accent) },
                ) {
                    RowTitle(bridge.name)
                    bridge.botCount?.let { RowSub(pluralStringResource(R.plurals.bridge_bot_count, it, it), maxLines = 1) }
                }
            }
            pcs.forEach { pc ->
                RasulRow(
                    onClick = { onEdit(pc.id) }, hairlineStart = 16.dp, hairlineEnd = 54.dp, gap = 12.dp, textSpacing = 4.dp,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
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
                onClick = onAdd, last = true, minHeight = 32.dp, gap = 6.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                leading = { Icon(RasulIcons.Plus, null, tint = c.accent, modifier = Modifier.size(20.dp)) },
            ) { RowTitle(stringResource(R.string.add_pc), c.accent) }
        }
        if (skillsToolsTargetName != null) {
            SectionLabel(stringResource(R.string.ux3b_skills_tools_section))
            InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
                RasulRow(
                    onClick = onSkillsTools, last = true, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                    leading = { Icon(RasulIcons.Chat, null, tint = c.accent, modifier = Modifier.size(24.dp)) },
                    trailing = { Icon(RasulIcons.Chev, null, tint = c.muted, modifier = Modifier.size(18.dp)) },
                ) {
                    RowTitle(stringResource(R.string.ux3b_skills_tools_title))
                    RowSub(stringResource(R.string.ux3b_skills_tools_target, skillsToolsTargetName), maxLines = 1)
                }
            }
        }
        if (usageTargetName != null) {
            SectionLabel(stringResource(R.string.desktop_section))
            InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
                if (usageNeedsBridge) {
                    Text(
                        stringResource(R.string.usage_needs_bridge), style = RasulType.bodySmall, color = c.muted,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    RasulRow(
                        onClick = onUsage, last = true, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                        leading = { Icon(RasulIcons.Clock, null, tint = c.accent, modifier = Modifier.size(24.dp)) },
                        trailing = { Icon(RasulIcons.Chev, null, tint = c.muted, modifier = Modifier.size(18.dp)) },
                    ) {
                        RowTitle(stringResource(R.string.usage_title))
                        RowSub(stringResource(R.string.usage_row_sub, usageTargetName), maxLines = 1)
                    }
                }
            }
        }
        SectionLabel(stringResource(R.string.more_settings))
        InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
            if (showAppLockSetting) {
                AppLockSettingRow(appLockEnabled, appLockAvailable, onAppLockChanged, last = false)
            }
            if (showApprovalAlerts) {
                ApprovalAlertsRow(approvalAlerts, onApprovalAlertsChanged)
            }
            RasulRow(
                onClick = onAppearanceRow, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Info, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(appearance.label(), style = RasulType.bodySmall, color = c.muted)
                        Icon(RasulIcons.Chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                },
            ) { RowTitle(stringResource(R.string.settings_appearance)) }
            RasulRow(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 6.dp),
                gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Info, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                trailing = {
                    Box(Modifier.width(104.dp), contentAlignment = AbsoluteAlignment.CenterLeft) {
                    app.rasul.ui.components.RasulToggle(
                        hapticsEnabled,
                        label = stringResource(R.string.settings_haptics),
                        onCheckedChange = onHapticsChanged,
                    )
                    }
                },
            ) { RowTitle(stringResource(R.string.settings_haptics)) }
            RasulRow(
                onClick = onHelp, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Info, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
            ) { RowTitle(stringResource(R.string.settings_help)) }
            RasulRow(
                onClick = onPrivacy, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Lock, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
            ) { RowTitle(stringResource(R.string.settings_privacy)) }
            RasulRow(
                onClick = onFeedback, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Chat, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
            ) { RowTitle(stringResource(R.string.settings_feedback)) }
            RasulRow(
                contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Info, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                trailing = { Text(versionName, style = RasulType.bodySmall, color = c.muted) },
            ) { RowTitle(stringResource(R.string.settings_version)) }
            RasulRow(
                onClick = onLanguageRow, last = true, contentPadding = rowPad, gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
                leading = { Icon(RasulIcons.Globe, null, tint = c.ink, modifier = Modifier.size(24.dp)) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(language.label(), style = RasulType.bodySmall, color = c.muted)
                        Icon(RasulIcons.Chev, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                },
            ) { RowTitle(stringResource(R.string.more_language)) }
        }
        Text(stringResource(R.string.unofficial_notice), style = RasulType.bodySmall, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

internal fun AppearanceOption.labelResource(): Int = when (this) {
    AppearanceOption.System -> R.string.appearance_system
    AppearanceOption.Light -> R.string.appearance_light
    AppearanceOption.Dark -> R.string.appearance_dark
}

@Composable
internal fun AppearanceOption.label(): String = stringResource(labelResource())

@Composable
fun AppearanceSheet(current: AppearanceOption, onDismiss: () -> Unit, onPick: (AppearanceOption) -> Unit) {
    val c = Rasul.colors
    RasulSheet(stringResource(R.string.settings_appearance), onDismiss) {
        AppearanceOption.entries.forEachIndexed { index, option ->
            RasulRow(
                onClick = { onPick(option) }, hairlineStart = 0.dp,
                last = index == AppearanceOption.entries.lastIndex,
                contentPadding = PaddingValues(vertical = 14.dp),
                selected = option == current,
            ) {
                Text(option.label(), style = RasulType.optionTitle, color = c.ink)
            }
        }
    }
}

@Composable
private fun AppLockSettingRow(
    enabled: Boolean,
    available: Boolean,
    onChanged: (Boolean) -> Unit,
    last: Boolean,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    textSpacing: Dp = 4.dp,
    leading: (@Composable () -> Unit)? = { Icon(RasulIcons.Lock, null, tint = Rasul.colors.ink, modifier = Modifier.size(24.dp)) },
) {
    RasulRow(
        last = last, gap = 10.dp, textSpacing = textSpacing, hairlineStart = 16.dp, hairlineEnd = 54.dp,
        contentPadding = contentPadding,
        leading = leading,
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

/**
 * Opt-in approval alerts while Rasul is closed. Enabling asks for POST_NOTIFICATIONS on API 33+;
 * a denial keeps the toggle off and shows where to allow it. Below-33 needs no permission.
 */
@Composable
private fun ApprovalAlertsRow(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onChanged(true) else denied = true }
    RasulRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 6.dp),
        gap = 10.dp, hairlineStart = 16.dp, hairlineEnd = 54.dp,
        leading = { Icon(RasulIcons.Bot, null, tint = Rasul.colors.ink, modifier = Modifier.size(24.dp)) },
        trailing = {
            app.rasul.ui.components.RasulToggle(
                enabled,
                label = stringResource(R.string.approval_alerts),
            ) { checked ->
                when {
                    !checked -> onChanged(false)
                    Build.VERSION.SDK_INT < 33 -> onChanged(true)
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED -> onChanged(true)
                    else -> askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        },
    ) {
        // Plain Text on purpose: the single-line ellipsis path of RowTitle reports visual overflow for short Arabic titles.
        Text(stringResource(R.string.approval_alerts), style = RasulType.rowTitle, color = Rasul.colors.ink, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.approval_alerts_summary), style = RasulType.rowSub, color = Rasul.colors.muted, modifier = Modifier.fillMaxWidth())
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.approval_alerts)) },
            text = { Text(stringResource(R.string.approval_alerts_denied)) },
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
}

@Composable
private fun LockSettingPreview() {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_more), modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
        SectionLabel(stringResource(R.string.more_settings), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp))
        InsetGroup(contentPadding = PaddingValues(vertical = 16.dp)) {
            AppLockSettingRow(enabled = false, available = true, onChanged = {}, last = true,
                contentPadding = PaddingValues(start = 16.dp, end = 31.dp), textSpacing = 3.dp, leading = null)
        }
    }
}

/** Language sheet with System default, English, and العربية; the current choice is checked. */
@Composable
fun LanguageSheet(current: LanguageOption, onDismiss: () -> Unit, onPick: (LanguageOption) -> Unit) {
    val c = Rasul.colors
    RasulSheet(stringResource(R.string.more_language), onDismiss) {
        LanguageOption.entries.forEachIndexed { i, o ->
            RasulRow(
                onClick = { onPick(o) }, hairlineStart = 0.dp, minHeight = 50.dp, last = i == LanguageOption.entries.lastIndex,
                contentPadding = PaddingValues(vertical = 14.dp), textSpacing = 2.dp, hairlineEnd = 0.dp,
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
    Connection("a", "My PC", "http://192.0.2.7:8642", "default"),
    Connection("b", "Laptop", "http://192.0.2.9:8642", "default"),
)
private val sampleBridgeRows = listOf(PairedBridgeRow("bridge-sample", "Studio PC", 3))

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun MoreEn() = RasulTheme(false) { MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 800, locale = "ar")
@Composable
private fun MoreAr() = RasulTheme(false) { MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun SettingsEn() = RasulTheme(false) {
    MoreContent(
        samplePcs, "a", LanguageOption.System, {}, {}, {},
        showAppLockSetting = true, appearance = AppearanceOption.System,
        hapticsEnabled = true, versionName = PREVIEW_VERSION,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, locale = "ar")
@Composable
private fun SettingsAr() = RasulTheme(false) {
    MoreContent(
        samplePcs, "a", LanguageOption.System, {}, {}, {},
        showAppLockSetting = true, appearance = AppearanceOption.System,
        hapticsEnabled = true, versionName = PREVIEW_VERSION,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun MoreBridgeEn() = RasulTheme(false) {
    MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}, bridges = sampleBridgeRows, usageTargetName = "My PC")
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, locale = "ar")
@Composable
private fun MoreBridgeAr() = RasulTheme(false) {
    MoreContent(samplePcs, "a", LanguageOption.System, {}, {}, {}, bridges = sampleBridgeRows, usageTargetName = "My PC")
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun MoreAlertsEn() = RasulTheme(false) {
    MoreContent(
        samplePcs, "a", LanguageOption.System, {}, {}, {},
        bridges = sampleBridgeRows, showApprovalAlerts = true, approvalAlerts = true,
        showAppLockSetting = true, appearance = AppearanceOption.System,
        hapticsEnabled = true, versionName = PREVIEW_VERSION,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, locale = "ar")
@Composable
private fun MoreAlertsAr() = RasulTheme(false) {
    MoreContent(
        samplePcs, "a", LanguageOption.System, {}, {}, {},
        bridges = sampleBridgeRows, showApprovalAlerts = true, approvalAlerts = true,
        showAppLockSetting = true, appearance = AppearanceOption.System,
        hapticsEnabled = true, versionName = PREVIEW_VERSION,
    )
}

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

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun AppearanceSheetEn() = RasulTheme(false) { AppearanceSheet(AppearanceOption.System, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun AppearanceSheetAr() = RasulTheme(false) { AppearanceSheet(AppearanceOption.System, {}, {}) }

/** Fixed sample so a version bump does not change the preview screenshots. */
private const val PREVIEW_VERSION = "0.2.1"
