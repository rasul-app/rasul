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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.SkillDto
import app.rasul.data.ToolsetDto
import app.rasul.settings.skillsToolsEntryVisible
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.text
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private sealed interface ListState<out T> {
    data object Empty : ListState<Nothing>
    data class Ready<T>(val values: List<T>) : ListState<T>
    data class Error(val error: HermesError) : ListState<Nothing>
}

private sealed interface SkillsToolsState {
    data object Loading : SkillsToolsState
    data class Error(val error: HermesError) : SkillsToolsState
    data class Content(
        val skills: ListState<SkillDto>,
        val toolsets: ListState<ToolsetDto>,
    ) : SkillsToolsState
}

private fun <T> HermesResult<List<T>>.toListState(): ListState<T> = when (this) {
    is HermesResult.Err -> ListState.Error(error)
    is HermesResult.Ok -> value.takeIf { it.isNotEmpty() }?.let { ListState.Ready(it) } ?: ListState.Empty
}

@Composable
fun SkillsToolsScreen(target: ActiveTarget.Ready, client: HermesClient, onBack: () -> Unit) {
    val requestTarget = target.target
    var retryVersion by remember(requestTarget) { mutableIntStateOf(0) }
    var state by remember(requestTarget) { mutableStateOf<SkillsToolsState>(SkillsToolsState.Loading) }

    LaunchedEffect(requestTarget, retryVersion) {
        state = SkillsToolsState.Loading
        when (val capabilities = client.capabilities(requestTarget)) {
            is HermesResult.Err -> state = SkillsToolsState.Error(capabilities.error)
            is HermesResult.Ok -> {
                if (!skillsToolsEntryVisible(capabilities.value)) {
                    state = SkillsToolsState.Error(HermesError.Incompatible(listOf("skills", "toolsets")))
                } else {
                    state = coroutineScope {
                        val skills = async { client.skills(requestTarget) }
                        val toolsets = async { client.toolsets(requestTarget) }
                        SkillsToolsState.Content(skills.await().toListState(), toolsets.await().toListState())
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(RasulIcons.Back, contentDescription = stringResource(R.string.back), tint = Rasul.colors.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ux3b_skills_tools_title), style = RasulType.rowTitle, color = Rasul.colors.ink)
                Text(target.name, style = RasulType.meta, color = Rasul.colors.muted, maxLines = 1)
            }
            TextButton(onClick = { retryVersion += 1 }) { Text(stringResource(R.string.refresh)) }
        }
        when (val shown = state) {
            SkillsToolsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Rasul.colors.accent)
                    Text(stringResource(R.string.ux3b_skills_tools_loading), style = RasulType.body, color = Rasul.colors.muted)
                }
            }
            is SkillsToolsState.Error -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(shown.error.text(FeatureErrorSurface.SkillsCapabilities), style = RasulType.body, color = Rasul.colors.dangerText)
                TextButton(onClick = { retryVersion += 1 }) { Text(stringResource(R.string.retry)) }
            }
            is SkillsToolsState.Content -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            ) {
                SectionLabel(stringResource(R.string.ux3b_skills_tools_skills), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                InsetGroup {
                    when (val skills = shown.skills) {
                        ListState.Empty -> Text(stringResource(R.string.ux3b_skills_tools_skills_empty), style = RasulType.body, color = Rasul.colors.muted, modifier = Modifier.padding(16.dp))
                        is ListState.Error -> FeatureError(skills.error, FeatureErrorSurface.Skills) { retryVersion += 1 }
                        is ListState.Ready -> skills.values.forEachIndexed { index, skill ->
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (index == 0) 12.dp else 11.dp, bottom = 12.dp)) {
                                Text(skill.name, style = RasulType.rowTitle, color = Rasul.colors.ink)
                                skill.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = RasulType.body.copy(lineHeight = 18.sp), color = Rasul.colors.muted, modifier = Modifier.heightIn(min = 18.dp)) }
                                skill.category?.takeIf { it.isNotBlank() }?.let { Text(it, style = RasulType.meta, color = Rasul.colors.accent, modifier = Modifier.heightIn(min = 18.dp)) }
                            }
                            if (index != skills.values.lastIndex) app.rasul.ui.components.Hairline(Modifier.padding(start = 16.dp, end = 16.dp))
                        }
                    }
                }
                SectionLabel(stringResource(R.string.ux3b_skills_tools_toolsets), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 3.dp))
                InsetGroup {
                    when (val toolsets = shown.toolsets) {
                        ListState.Empty -> Text(stringResource(R.string.ux3b_skills_tools_toolsets_empty), style = RasulType.body, color = Rasul.colors.muted, modifier = Modifier.padding(16.dp))
                        is ListState.Error -> FeatureError(toolsets.error, FeatureErrorSurface.Toolsets) { retryVersion += 1 }
                        is ListState.Ready -> toolsets.values.forEachIndexed { index, toolset ->
                            ToolsetRow(toolset, last = index == toolsets.values.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureError(error: HermesError, surface: FeatureErrorSurface, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.text(surface), style = RasulType.body, color = Rasul.colors.dangerText)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
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

private val skillsToolsPreview = SkillsToolsState.Content(
    skills = ListState.Ready(
        listOf(
            SkillDto("Example skill", "Summarizes a folder of notes.", "Research"),
            SkillDto("Example review", "Checks a change list for issues.", "Development"),
        ),
    ),
    toolsets = ListState.Ready(
        listOf(
            ToolsetDto("example-tools", "Example tools", "Browse local files and run commands.", true, true, listOf("example_action_one", "example_action_two")),
            ToolsetDto("example-calendar", "Example calendar", "Calendar access is not configured.", false, false, emptyList()),
        ),
    ),
)

@Composable
private fun SkillsToolsPreviewContent() {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.ux3b_skills_tools_title), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionLabel(stringResource(R.string.ux3b_skills_tools_skills), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
            InsetGroup {
                val skills = (skillsToolsPreview as SkillsToolsState.Content).skills as ListState.Ready
                skills.values.forEachIndexed { index, skill ->
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (index == 0) 12.dp else 11.dp, bottom = 12.dp)) {
                        Text(skill.name, style = RasulType.rowTitle, color = Rasul.colors.ink)
                        Text(skill.description.orEmpty(), style = RasulType.body.copy(lineHeight = 18.sp), color = Rasul.colors.muted, modifier = Modifier.heightIn(min = 18.dp))
                        Text(skill.category.orEmpty(), style = RasulType.meta, color = Rasul.colors.accent, modifier = Modifier.heightIn(min = 18.dp))
                    }
                    if (index != skills.values.lastIndex) app.rasul.ui.components.Hairline(Modifier.padding(start = 16.dp, end = 16.dp))
                }
            }
            SectionLabel(stringResource(R.string.ux3b_skills_tools_toolsets), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 3.dp))
            InsetGroup {
                val toolsets = (skillsToolsPreview as SkillsToolsState.Content).toolsets as ListState.Ready
                toolsets.values.forEachIndexed { index, toolset -> ToolsetRow(toolset, last = index == toolsets.values.lastIndex, initiallyExpanded = index == 0) }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760, name = "Skills and tools EN")
@Composable
private fun SkillsToolsEn() = RasulTheme(false) { SkillsToolsPreviewContent() }

@Preview(showBackground = true, widthDp = 390, heightDp = 760, locale = "ar", name = "Skills and tools AR")
@Composable
private fun SkillsToolsAr() = RasulTheme(false) { SkillsToolsPreviewContent() }