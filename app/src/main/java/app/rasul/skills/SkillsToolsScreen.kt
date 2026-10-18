package app.rasul.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.bridge.CatalogSkillDto
import app.rasul.bridge.DesktopError
import app.rasul.bridge.McpCatalogEntryDto
import app.rasul.bridge.McpServerDto
import app.rasul.data.ToolsetDto
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.text
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RasulToggle
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.SmallOutlineButton
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

private data class PendingToggle(val name: String, val enabled: Boolean)

/** Skills, toolsets and — over Rasul Bridge only — switches, the official catalog and MCP servers. */
@Composable
fun SkillsToolsScreen(vm: SkillsToolsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    // Dialogs keep the user's pick locally; nothing is sent before the confirm tap.
    var toggleAsk by remember { mutableStateOf<PendingToggle?>(null) }
    var installAsk by remember { mutableStateOf<CatalogSkillDto?>(null) }
    var mcpInstallAsk by remember { mutableStateOf<McpCatalogEntryDto?>(null) }

    SkillsToolsContent(
        state, vm.targetName, onBack,
        onRefresh = vm::load,
        onToggleAsk = { name, enabled -> toggleAsk = PendingToggle(name, enabled) },
        onOpenCatalog = vm::openCatalog,
        onCloseCatalog = vm::closeCatalog,
        onInstallAsk = { installAsk = it },
        onCheckAgain = vm::checkAgain,
        onRetrySection = vm::load,
        onToggleMcp = vm::toggleMcp,
        onTestMcp = vm::testMcp,
        onOpenMcpCatalog = vm::openMcpCatalog,
        onCloseMcpCatalog = vm::closeMcpCatalog,
        onInstallMcpAsk = { mcpInstallAsk = it },
        onDismissMcpTest = vm::dismissMcpTest,
    )

    val content = state as? SkillsToolsState.Content
    mcpInstallAsk?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.mcp_install_title, entry.name),
            body = stringResource(R.string.mcp_install_body),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.skill_install),
            onCancel = { mcpInstallAsk = null },
            onConfirm = { vm.installMcp(entry); mcpInstallAsk = null },
        )
    }
    toggleAsk?.let { ask ->
        ConfirmDialog(
            title = stringResource(if (ask.enabled) R.string.skill_turn_on_title else R.string.skill_turn_off_title, ask.name),
            body = stringResource(if (ask.enabled) R.string.skill_turn_on_body else R.string.skill_turn_off_body),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(if (ask.enabled) R.string.skill_turn_on else R.string.skill_turn_off),
            onCancel = { toggleAsk = null },
            onConfirm = { vm.toggle(ask.name, ask.enabled); toggleAsk = null },
        )
    }
    installAsk?.let { skill ->
        ConfirmDialog(
            title = stringResource(R.string.skill_install_title, skill.name),
            body = stringResource(R.string.skill_install_body),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.skill_install),
            onCancel = { installAsk = null },
            onConfirm = { vm.install(skill); installAsk = null },
        )
    }
    content?.actionError?.let { e ->
        NoticeDialog(stringResource(e.message), stringResource(R.string.ok), vm::dismissActionError)
    }
}

@Composable
internal fun SkillsToolsContent(
    state: SkillsToolsState,
    targetName: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAsk: (String, Boolean) -> Unit,
    onOpenCatalog: () -> Unit,
    onCloseCatalog: () -> Unit,
    onInstallAsk: (CatalogSkillDto) -> Unit,
    onCheckAgain: (String) -> Unit,
    onRetrySection: () -> Unit,
    onToggleMcp: (String, Boolean) -> Unit = { _, _ -> },
    onTestMcp: (String) -> Unit = {},
    onOpenMcpCatalog: () -> Unit = {},
    onCloseMcpCatalog: () -> Unit = {},
    onInstallMcpAsk: (McpCatalogEntryDto) -> Unit = {},
    onDismissMcpTest: () -> Unit = {},
) {
    val c = Rasul.colors
    val actionRunning = (state as? SkillsToolsState.Content)
        ?.let { it.busySkill != null || it.installing != null || it.mcpBusy } == true
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(RasulIcons.Back, contentDescription = stringResource(R.string.back), tint = c.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ux3b_skills_tools_title), style = RasulType.rowTitle, color = c.ink)
                Text(targetName, style = RasulType.meta, color = c.muted, maxLines = 1)
            }
            TextButton(onClick = onRefresh, enabled = !actionRunning) { Text(stringResource(R.string.refresh)) }
        }
        when (val shown = state) {
            SkillsToolsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(stringResource(R.string.ux3b_skills_tools_loading), style = RasulType.body, color = c.muted)
                }
            }
            is SkillsToolsState.Error -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(shown.error.text(FeatureErrorSurface.SkillsCapabilities), style = RasulType.body, color = c.dangerText)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
            }
            is SkillsToolsState.Content -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            ) {
                SectionLabel(stringResource(R.string.ux3b_skills_tools_skills), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                InsetGroup {
                    SkillsSection(shown, onToggleAsk, onOpenCatalog, onRetrySection)
                }
                shown.mcp?.let { mcp ->
                    SectionLabel(stringResource(R.string.mcp_servers), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                    InsetGroup {
                        McpSection(mcp, shown, onRetrySection, !actionRunning, onToggleMcp, onTestMcp, onOpenMcpCatalog)
                    }
                    if (shown.mcpStale) {
                        Text(
                            stringResource(R.string.mcp_list_stale), style = RasulType.bodySmall, color = c.muted,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                        )
                    }
                }
                SectionLabel(stringResource(R.string.ux3b_skills_tools_toolsets), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 3.dp))
                InsetGroup {
                    when (val toolsets = shown.toolsets) {
                        ListState.Empty -> Text(stringResource(R.string.ux3b_skills_tools_toolsets_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(16.dp))
                        is ListState.Error -> FeatureError(toolsets.error, FeatureErrorSurface.Toolsets, onRetrySection, retryEnabled = !actionRunning)
                        is ListState.Ready -> toolsets.values.forEachIndexed { index, toolset ->
                            ToolsetRow(toolset, last = index == toolsets.values.lastIndex)
                        }
                    }
                }
            }
        }
    }
    (state as? SkillsToolsState.Content)?.let { content ->
        val catalog = content.catalog
        if (catalog != CatalogState.Closed) {
            CatalogSheet(content, catalog, onCloseCatalog, onInstallAsk, onCheckAgain, onOpenCatalog)
        }
        if (content.mcpCatalog != McpCatalogState.Closed) {
            RasulSheet(stringResource(R.string.mcp_catalog_title), onCloseMcpCatalog) {
                McpCatalogBody(content.mcpCatalog, content.installingMcp, onInstallMcpAsk, onOpenMcpCatalog)
            }
        }
        (content.mcpTest as? McpTestState.Result)?.let { test ->
            NoticeDialog(mcpTestText(test), stringResource(R.string.ok), onDismissMcpTest)
        }
    }
}

@Composable
private fun mcpTestText(test: McpTestState.Result): String {
    val result = test.result
    return if (result.ok) {
        pluralStringResource(R.plurals.mcp_test_ok, result.tools.size, test.name, result.tools.size)
    } else {
        stringResource(R.string.mcp_test_failed, test.name, result.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mcp_test_no_reason))
    }
}

@Composable
internal fun McpCatalogBody(
    catalog: McpCatalogState,
    installing: String?,
    onInstallAsk: (McpCatalogEntryDto) -> Unit,
    onRetry: () -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        when (catalog) {
            McpCatalogState.Closed -> {}
            is McpCatalogState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(catalog.error.message), style = RasulType.body, color = c.dangerText)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
            }
            McpCatalogState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), color = c.accent)
                Text(stringResource(R.string.mcp_catalog_loading), style = RasulType.body, color = c.muted, modifier = Modifier.padding(start = 12.dp))
            }
            is McpCatalogState.Open -> {
                if (catalog.entries.isEmpty()) {
                    Text(stringResource(R.string.mcp_catalog_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
                }
                catalog.entries.forEachIndexed { index, entry ->
                    RasulRow(
                        last = index == catalog.entries.lastIndex, hairlineStart = 0.dp, hairlineEnd = 0.dp, textSpacing = 3.dp,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        trailing = {
                            when {
                                entry.installed -> StatusChip(stringResource(R.string.skill_installed), ChipKind.Done)
                                installing == entry.id -> Text(stringResource(R.string.skill_installing), style = RasulType.bodySmall, color = c.accent)
                                entry.installable -> SmallOutlineButton(
                                    stringResource(R.string.skill_install), { onInstallAsk(entry) }, enabled = installing == null,
                                )
                            }
                        },
                    ) {
                        RowTitle(entry.name)
                        entry.description?.takeIf { it.isNotBlank() }?.let { RowSub(it) }
                        if (!entry.installed && !entry.installable) {
                            Text(stringResource(R.string.mcp_needs_setup), style = RasulType.meta, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsSection(
    shown: SkillsToolsState.Content,
    onToggleAsk: (String, Boolean) -> Unit,
    onOpenCatalog: () -> Unit,
    onRetrySection: () -> Unit,
) {
    val c = Rasul.colors
    shown.skillsNotice?.let {
        Text(
            stringResource(it.message), style = RasulType.bodySmall, color = c.muted,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        )
    }
    when (val skills = shown.skills) {
        ListState.Empty -> Text(stringResource(R.string.ux3b_skills_tools_skills_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(16.dp))
        is ListState.Error -> FeatureError(skills.error, FeatureErrorSurface.Skills, onRetrySection, retryEnabled = shown.busySkill == null && shown.installing == null)
        is ListState.Ready -> skills.values.forEachIndexed { index, skill ->
            SkillRowItem(skill, shown.busySkill == null && shown.installing == null, onToggleAsk)
            if (index != skills.values.lastIndex || !shown.direct) {
                app.rasul.ui.components.Hairline(Modifier.padding(start = 16.dp, end = 16.dp))
            }
        }
    }
    if (shown.direct) {
        Text(
            stringResource(R.string.skills_needs_bridge), style = RasulType.bodySmall, color = c.muted,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    } else {
        RasulRow(
            onClick = onOpenCatalog, last = true, minHeight = 48.dp, gap = 6.dp, hairlineStart = 0.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            leading = { Icon(RasulIcons.Plus, null, tint = c.accent, modifier = Modifier.size(20.dp)) },
        ) { RowTitle(stringResource(R.string.skill_add), c.accent) }
    }
}

@Composable
private fun SkillRowItem(skill: SkillRow, switchesEnabled: Boolean, onToggleAsk: (String, Boolean) -> Unit) {
    val c = Rasul.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(skill.name, style = RasulType.rowTitle, color = c.ink)
            skill.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = RasulType.body.copy(lineHeight = 18.sp), color = c.muted, modifier = Modifier.heightIn(min = 18.dp))
            }
            skill.category?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = RasulType.meta, color = c.accent, modifier = Modifier.heightIn(min = 18.dp))
            }
            if (skill.essential) {
                Text(stringResource(R.string.skill_essential), style = RasulType.meta, color = c.muted)
            }
        }
        skill.enabled?.let { enabled ->
            RasulToggle(
                enabled,
                label = skill.name,
                enabled = switchesEnabled && !skill.essential,
            ) { wanted -> onToggleAsk(skill.name, wanted) }
        }
    }
}

@Composable
private fun McpSection(
    mcp: ListState<McpServerDto>,
    shown: SkillsToolsState.Content,
    onRetry: () -> Unit,
    actionsEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val c = Rasul.colors
    when (mcp) {
        ListState.Empty -> Text(stringResource(R.string.mcp_servers_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(16.dp))
        is ListState.Error -> FeatureError(mcp.error, FeatureErrorSurface.Skills, onRetry, actionsEnabled)
        is ListState.Ready -> mcp.values.forEachIndexed { index, server ->
            // Plugin-provided servers are managed by their plugin: the bridge refuses a switch or test for them.
            val manageable = server.plugin.isNullOrBlank() && server.enabled != null
            RasulRow(
                last = false, hairlineStart = 16.dp, hairlineEnd = 16.dp, textSpacing = 4.dp,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                trailing = if (manageable) {
                    {
                        RasulToggle(
                            server.enabled == true, label = server.name, enabled = actionsEnabled,
                        ) { wanted -> onToggle(server.name, wanted) }
                    }
                } else null,
                bottom = if (manageable) {
                    {
                        val testing = (shown.mcpTest as? McpTestState.Testing)?.name == server.name
                        if (testing) {
                            Text(stringResource(R.string.mcp_testing), style = RasulType.bodySmall, color = c.accent)
                        } else {
                            SmallOutlineButton(stringResource(R.string.mcp_test), { onTest(server.name) }, enabled = actionsEnabled)
                        }
                    }
                } else null,
            ) {
                RowTitle(server.name)
                listOfNotNull(server.transport?.takeIf { it.isNotBlank() }, server.source?.takeIf { it.isNotBlank() })
                    .joinToString(" · ").takeIf { it.isNotBlank() }?.let { RowSub(it, maxLines = 1) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        text = stringResource(if (server.enabled == true) R.string.ux3b_skills_tools_enabled else R.string.ux3b_skills_tools_disabled),
                        kind = if (server.enabled == true) ChipKind.Done else ChipKind.Neutral,
                    )
                    server.plugin?.takeIf { it.isNotBlank() }?.let {
                        StatusChip(text = stringResource(R.string.mcp_plugin) + ": " + it, kind = ChipKind.Neutral)
                    }
                }
            }
        }
    }
    if (mcp !is ListState.Error) {
        RasulRow(
            onClick = onOpenCatalog, last = true, minHeight = 48.dp, gap = 6.dp, hairlineStart = 0.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            leading = { Icon(RasulIcons.Plus, null, tint = c.accent, modifier = Modifier.size(20.dp)) },
        ) { RowTitle(stringResource(R.string.mcp_add), c.accent) }
    }
}

@Composable
private fun CatalogSheet(
    content: SkillsToolsState.Content,
    catalog: CatalogState,
    onDismiss: () -> Unit,
    onInstallAsk: (CatalogSkillDto) -> Unit,
    onCheckAgain: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val c = Rasul.colors
    RasulSheet(stringResource(R.string.skill_catalog_title), onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            when (catalog) {
                CatalogState.Closed -> {}
                is CatalogState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(catalog.error.message), style = RasulType.body, color = c.dangerText)
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
                }
                CatalogState.Loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = c.accent)
                    Text(stringResource(R.string.skill_catalog_loading), style = RasulType.body, color = c.muted, modifier = Modifier.padding(start = 12.dp))
                }
                is CatalogState.Open -> {
                    if (catalog.skills.isEmpty()) {
                        Text(stringResource(R.string.skill_catalog_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    catalog.skills.forEachIndexed { index, skill ->
                        RasulRow(
                            last = index == catalog.skills.lastIndex, hairlineStart = 0.dp, hairlineEnd = 0.dp, textSpacing = 3.dp,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            trailing = { CatalogAction(skill, content, onInstallAsk, onCheckAgain) },
                        ) {
                            RowTitle(skill.name)
                            skill.description?.takeIf { it.isNotBlank() }?.let { RowSub(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogAction(
    skill: CatalogSkillDto,
    content: SkillsToolsState.Content,
    onInstallAsk: (CatalogSkillDto) -> Unit,
    onCheckAgain: (String) -> Unit,
) {
    val c = Rasul.colors
    when {
        skill.installed -> StatusChip(stringResource(R.string.skill_installed), ChipKind.Done)
        content.installing == skill.identifier -> Text(stringResource(R.string.skill_installing), style = RasulType.bodySmall, color = c.accent)
        content.checkAgain == skill.identifier -> SmallOutlineButton(stringResource(R.string.skill_check_again), { onCheckAgain(skill.identifier) })
        else -> SmallOutlineButton(
            stringResource(R.string.skill_install), { onInstallAsk(skill) },
            enabled = content.installing == null && content.busySkill == null,
        )
    }
}

@Composable
private fun FeatureError(error: SectionError, surface: FeatureErrorSurface, onRetry: () -> Unit, retryEnabled: Boolean) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.text(surface), style = RasulType.body, color = Rasul.colors.dangerText)
        TextButton(onClick = onRetry, enabled = retryEnabled) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun SectionError.text(surface: FeatureErrorSurface): String = when (this) {
    is SectionError.Host -> error.text(surface)
    is SectionError.Desktop -> stringResource(error.message)
}

@Composable
private fun ToolsetRow(toolset: ToolsetDto, last: Boolean, initiallyExpanded: Boolean = false) {
    var expanded by remember(toolset.name) { mutableStateOf(initiallyExpanded) }
    Column {
        RasulRow(
            onClick = { expanded = !expanded },
            last = last && !expanded,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (last) 12.dp else 18.dp),
            textSpacing = 1.dp, hairlineStart = 16.dp, hairlineEnd = 16.dp,
            trailing = {
                Icon(
                    if (expanded) RasulIcons.ChevDown else RasulIcons.Chev,
                    contentDescription = null,
                    tint = Rasul.colors.muted,
                    modifier = Modifier.size(18.dp),
                )
            },
        ) {
            RowTitle(toolset.label?.takeIf { it.isNotBlank() } ?: toolset.name)
            toolset.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = RasulType.body.copy(lineHeight = 18.sp), color = Rasul.colors.muted, modifier = Modifier.heightIn(min = 18.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = stringResource(if (toolset.enabled == true) R.string.ux3b_skills_tools_enabled else R.string.ux3b_skills_tools_disabled),
                    kind = if (toolset.enabled == true) ChipKind.Done else ChipKind.Neutral,
                )
                StatusChip(
                    text = stringResource(if (toolset.configured == true) R.string.ux3b_skills_tools_configured else R.string.ux3b_skills_tools_not_configured),
                    kind = if (toolset.configured == true) ChipKind.Done else ChipKind.Neutral,
                )
            }
        }
        if (expanded) {
            if (toolset.tools.isEmpty()) {
                Text(
                    stringResource(R.string.ux3b_skills_tools_no_tools),
                    style = RasulType.bodySmall,
                    color = Rasul.colors.muted,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 17.dp, bottom = 7.dp)) {
                    toolset.tools.forEach { tool ->
                        Text(tool, style = RasulType.bodySmall, color = Rasul.colors.ink, modifier = Modifier.padding(vertical = 1.dp).heightIn(min = 18.dp))
                    }
                }
            }
        }
    }
}

private val previewSkills = listOf(
    SkillRow("Example skill", "Summarizes a folder of notes.", "Research", enabled = true, essential = false),
    SkillRow("System core", "Required by the bot itself.", "Core", enabled = true, essential = true),
    SkillRow("Example review", "Checks a change list for issues.", "Development", enabled = false, essential = false),
)

private val previewToolsets = listOf(
    ToolsetDto("example-tools", "Example tools", "Browse local files and run commands.", true, true, listOf("example_action_one", "example_action_two")),
    ToolsetDto("example-calendar", "Example calendar", "Calendar access is not configured.", false, false, emptyList()),
)

private val previewMcp = listOf(
    McpServerDto("local files", "stdio", enabled = true, source = "config", plugin = null),
    McpServerDto("web lookup", "http", enabled = false, source = "config", plugin = "search-plugin"),
)

private val previewCatalog = CatalogState.Open(
    skills = listOf(
        CatalogSkillDto("official/notes", "Notes helper", "Reads and summarizes note folders.", installed = true),
        CatalogSkillDto("official/dev/review", "Code review", "Reviews staged changes.", installed = false),
        CatalogSkillDto("official/web/digest", "Daily digest", "Builds a morning summary.", installed = false),
    ),
    readAt = 0L,
)

private val previewState = SkillsToolsState.Content(
    direct = false,
    skills = ListState.Ready(previewSkills),
    toolsets = ListState.Ready(previewToolsets),
    mcp = ListState.Ready(previewMcp),
)

private val directPreviewState = SkillsToolsState.Content(
    direct = true,
    skills = ListState.Ready(previewSkills.map { it.copy(enabled = null) }),
    toolsets = ListState.Ready(previewToolsets),
    mcp = null,
)

@Composable
private fun SkillsToolsPreviewContent(state: SkillsToolsState = previewState) {
    SkillsToolsContent(state, "My PC", {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "SkillsTools")
@Composable
private fun SkillsToolsEn() = RasulTheme(false) { SkillsToolsPreviewContent(directPreviewState) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "SkillsTools")
@Composable
private fun SkillsToolsAr() = RasulTheme(false) { SkillsToolsPreviewContent(directPreviewState) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "SkillsDesktop")
@Composable
private fun SkillsDesktopEn() = RasulTheme(false) { SkillsToolsPreviewContent() }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "SkillsDesktop")
@Composable
private fun SkillsDesktopAr() = RasulTheme(false) { SkillsToolsPreviewContent() }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "SkillToggleConfirm")
@Composable
private fun SkillToggleConfirmEn() = RasulTheme(false) {
    SkillsToolsPreviewContent()
    ConfirmDialog(
        title = stringResource(R.string.skill_turn_on_title, "Example review"),
        body = stringResource(R.string.skill_turn_on_body),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.skill_turn_on),
        onCancel = {}, onConfirm = {},
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "SkillToggleConfirm")
@Composable
private fun SkillToggleConfirmAr() = RasulTheme(false) {
    SkillsToolsPreviewContent()
    ConfirmDialog(
        title = stringResource(R.string.skill_turn_on_title, "مراجعة المثال"),
        body = stringResource(R.string.skill_turn_on_body),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.skill_turn_on),
        onCancel = {}, onConfirm = {},
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "SkillInstallConfirm")
@Composable
private fun SkillInstallConfirmEn() = RasulTheme(false) {
    SkillsToolsPreviewContent()
    ConfirmDialog(
        title = stringResource(R.string.skill_install_title, "Code review"),
        body = stringResource(R.string.skill_install_body),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.skill_install),
        onCancel = {}, onConfirm = {},
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "SkillInstallConfirm")
@Composable
private fun SkillInstallConfirmAr() = RasulTheme(false) {
    SkillsToolsPreviewContent()
    ConfirmDialog(
        title = stringResource(R.string.skill_install_title, "مراجعة التعليمات البرمجية"),
        body = stringResource(R.string.skill_install_body),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.skill_install),
        onCancel = {}, onConfirm = {},
    )
}

private val previewMcpCatalog = McpCatalogState.Open(
    listOf(
        McpCatalogEntryDto("notes", "Notes", "Reads and searches local note folders.", installed = false, installable = true),
        McpCatalogEntryDto("mail", "Mail", "Reads and drafts email.", installed = false, installable = false),
        McpCatalogEntryDto("files", "Files", "Browses a chosen folder.", installed = true, installable = false),
    ),
)

@Composable
private fun McpCatalogPreviewBody() = Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(20.dp)) {
    Text(stringResource(R.string.mcp_catalog_title), style = RasulType.sheetTitle, color = Rasul.colors.ink, modifier = Modifier.padding(bottom = 6.dp))
    McpCatalogBody(previewMcpCatalog, installing = null, onInstallAsk = {}, onRetry = {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 480, name = "McpCatalog")
@Composable
private fun McpCatalogEn() = RasulTheme(false) { McpCatalogPreviewBody() }

@Preview(showBackground = true, widthDp = 390, heightDp = 480, locale = "ar", name = "McpCatalog")
@Composable
private fun McpCatalogAr() = RasulTheme(false) { McpCatalogPreviewBody() }
