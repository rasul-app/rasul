package app.rasul.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.data.ModelCapabilityDto
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunModelSelection
import app.rasul.data.TokenUsageSummary
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.text
import app.rasul.ui.components.Hairline
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

sealed interface ModelPickerUiState {
    data object Loading : ModelPickerUiState
    data class Error(val error: HermesError) : ModelPickerUiState
    data class Ready(val options: ModelOptionsDto) : ModelPickerUiState
}

@Composable
fun ModelPickerSheet(
    state: ModelPickerUiState,
    selectedModel: RunModelSelection?,
    onDismiss: () -> Unit,
    onChoose: (RunModelSelection?) -> Unit,
    onChooseEffort: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    RasulSheet(stringResource(R.string.ux3b_model_picker_title), onDismiss) {
        ModelPickerContent(state, selectedModel, onChoose, onChooseEffort, onRefresh)
    }
}

/** Localized label for a server reasoning rung; unknown/empty -> null (label shows the model alone). */
@Composable
internal fun effortText(effort: String?): String? = when (effort) {
    "low" -> stringResource(R.string.chat_effort_low)
    "medium" -> stringResource(R.string.chat_effort_medium)
    "high" -> stringResource(R.string.chat_effort_high)
    else -> null
}

@Composable
internal fun ModelPickerContent(
    state: ModelPickerUiState,
    selectedModel: RunModelSelection?,
    onChoose: (RunModelSelection?) -> Unit,
    onChooseEffort: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val isArabic = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onRefresh) {
            Text(stringResource(R.string.refresh), modifier = Modifier.padding(start = 6.dp))
        }
    }
    when (state) {
        ModelPickerUiState.Loading -> Box(
            Modifier.fillMaxWidth().heightIn(min = 150.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Rasul.colors.accent)
                Text(stringResource(R.string.ux3b_model_picker_loading), style = RasulType.body, color = Rasul.colors.muted)
            }
        }
        is ModelPickerUiState.Error -> Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.error.text(FeatureErrorSurface.ModelOptions), style = RasulType.body, color = Rasul.colors.dangerText)
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
        }
        is ModelPickerUiState.Ready -> {
            val options = state.options
            val choices = modelChoices(options)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item(key = "server-default") {
                    RasulRow(
                        selected = selectedModel == null,
                        onClick = { onChoose(null) },
                        hairlineStart = if (isArabic) 16.dp else 0.dp, hairlineEnd = if (isArabic) 0.dp else 16.dp,
                        contentPadding = PaddingValues(start = if (isArabic) 16.dp else 0.dp, end = if (isArabic) 0.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        RowTitle(stringResource(R.string.ux3b_model_server_default))
                        options.model?.takeIf { it.isNotBlank() }?.let {
                            RowSub(stringResource(R.string.ux3b_model_server_default_current, it), maxLines = 1)
                        }
                    }
                }
                if (choices.isEmpty()) {
                    item(key = "models-empty") {
                        Text(
                            stringResource(R.string.ux3b_model_picker_empty),
                            style = RasulType.body,
                            color = Rasul.colors.muted,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    }
                }
                options.providers.forEach { provider ->
                    val providerChoices = choices.filter { it.providerSlug == provider.slug }
                    if (providerChoices.isNotEmpty()) {
                        item(key = "provider:${provider.slug}") {
                            SectionLabel(provider.name, contentPadding = PaddingValues(start = 24.dp, end = 16.dp, top = 13.dp, bottom = 7.dp))
                        }
                        itemsIndexed(
                            providerChoices,
                            key = { index, choice -> "${provider.slug}:${choice.model}:$index" },
                        ) { index, choice ->
                            val selection = choice.toRunModelSelection()
                            val isSelected = selectedModel?.provider == selection.provider && selectedModel.model == selection.model
                            val supportsReasoning = provider.capabilities[choice.model]?.reasoning == true
                            val showEffort = isSelected && supportsReasoning
                            Column {
                                RasulRow(
                                    selected = isSelected,
                                    onClick = {
                                        // Carry the picked effort only across models that can reason.
                                        onChoose(selection.copy(effort = if (supportsReasoning) selectedModel?.effort else null))
                                    },
                                    last = index == providerChoices.lastIndex || showEffort,
                                    hairlineStart = if (isArabic) 16.dp else 0.dp, hairlineEnd = if (isArabic) 0.dp else 16.dp,
                                    contentPadding = PaddingValues(start = if (isArabic) 16.dp else 0.dp, end = if (isArabic) 0.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                                ) {
                                    RowTitle(choice.model)
                                }
                                if (showEffort) {
                                    EffortChips(
                                        effort = selectedModel?.effort,
                                        onChooseEffort = onChooseEffort,
                                        contentPadding = PaddingValues(
                                            start = if (isArabic) 40.dp else 24.dp,
                                            end = 16.dp,
                                            bottom = 12.dp,
                                        ),
                                    )
                                    if (index != providerChoices.lastIndex) {
                                        Hairline(
                                            Modifier.padding(
                                                start = if (isArabic) 16.dp else 0.dp,
                                                end = if (isArabic) 0.dp else 16.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reasoning rung row under the selected model; server sends no effort = "Auto" (null). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EffortChips(
    effort: String?,
    onChooseEffort: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val colors = Rasul.colors
    val options = listOf<String?>(
        null,
        "low",
        "medium",
        "high",
    )
    Column(modifier.fillMaxWidth().padding(contentPadding)) {
        Text(
            stringResource(R.string.chat_reasoning_label),
            style = RasulType.meta,
            color = colors.muted,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { value ->
                val label = effortText(value) ?: stringResource(R.string.chat_effort_auto)
                val chosen = effort == value
                Text(
                    label,
                    style = RasulType.chip.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (chosen) colors.onAccent else colors.ink,
                    maxLines = 1,
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .clip(RasulShapes.chip)
                        .background(if (chosen) colors.accent else colors.fill)
                        .selectable(selected = chosen, role = Role.RadioButton) { onChooseEffort(value) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
fun TokenUsageDialog(summary: TokenUsageSummary?, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        app.rasul.ui.components.DialogScrim()
        Column(
            Modifier.width(298.dp).heightIn(min = 241.dp).background(Rasul.colors.bg, app.rasul.ui.theme.RasulShapes.dialog)
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 29.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ux3b_token_usage_title),
                    style = RasulType.emptyTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, lineHeight = 30.sp),
                    color = Rasul.colors.ink,
                    modifier = Modifier.heightIn(min = 30.dp),
                )
                val textStyle = RasulType.body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                when (val usage = tokenUsageDisplay(summary)) {
                    TokenUsageDisplay.NotReported -> Text(stringResource(R.string.ux3b_token_usage_not_reported), style = textStyle, color = Rasul.colors.muted)
                    is TokenUsageDisplay.Reported -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(stringResource(R.string.ux3b_token_usage_input, usage.input), style = textStyle, color = Rasul.colors.muted)
                        Text(stringResource(R.string.ux3b_token_usage_output, usage.output), style = textStyle, color = Rasul.colors.muted)
                        Text(stringResource(R.string.ux3b_token_usage_total, usage.total), style = textStyle, color = Rasul.colors.muted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

private val sampleModelOptions = ModelOptionsDto(
    providers = listOf(
        ModelOptionsProviderDto(
            "example-provider", "Example provider", listOf("example-model", "example-model-small"),
            capabilities = mapOf("example-model" to ModelCapabilityDto(reasoning = true)),
        ),
    ),
    model = "example-model",
    provider = "example-provider",
)

@Preview(showBackground = true, widthDp = 390, heightDp = 620, name = "Model picker EN")
@Composable
private fun ModelPickerEn() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(16.dp)) {
        ScreenHeader(stringResource(R.string.ux3b_model_picker_title), contentPadding = PaddingValues(
            start = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 0.dp else 20.dp,
            end = 20.dp, top = 10.dp, bottom = 6.dp,
        ))
        ModelPickerContent(ModelPickerUiState.Ready(sampleModelOptions), RunModelSelection("example-provider", "example-model", "high"), {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 620, name = "Model picker AR", locale = "ar")
@Composable
private fun ModelPickerAr() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(16.dp)) {
        ScreenHeader(stringResource(R.string.ux3b_model_picker_title), contentPadding = PaddingValues(
            start = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 0.dp else 20.dp,
            end = 20.dp, top = 10.dp, bottom = 6.dp,
        ))
        ModelPickerContent(ModelPickerUiState.Ready(sampleModelOptions), RunModelSelection("example-provider", "example-model", "high"), {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 380, name = "Token usage EN")
@Composable
private fun TokenUsageEn() = RasulTheme(false) {
    TokenUsageDialog(TokenUsageSummary(completedRuns = 2, reportedRuns = 2, inputTokens = 120, outputTokens = 40, totalTokens = 160)) {}
}

@Preview(showBackground = true, widthDp = 390, heightDp = 380, name = "Token usage AR", locale = "ar")
@Composable
private fun TokenUsageAr() = RasulTheme(false) {
    TokenUsageDialog(TokenUsageSummary(completedRuns = 2, reportedRuns = 2, inputTokens = 120, outputTokens = 40, totalTokens = 160)) {}
}
