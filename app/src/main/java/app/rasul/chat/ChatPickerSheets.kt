package app.rasul.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunModelSelection
import app.rasul.data.TokenUsageSummary
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.text
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.theme.Rasul
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
    onRefresh: () -> Unit,
) {
    RasulSheet(stringResource(R.string.ux3b_model_picker_title), onDismiss) {
        ModelPickerContent(state, selectedModel, onChoose, onRefresh)
    }
}

@Composable
internal fun ModelPickerContent(
    state: ModelPickerUiState,
    selectedModel: RunModelSelection?,
    onChoose: (RunModelSelection?) -> Unit,
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
                            RasulRow(
                                selected = selectedModel == selection,
                                onClick = { onChoose(selection) },
                                last = index == providerChoices.lastIndex,
                                hairlineStart = if (isArabic) 16.dp else 0.dp, hairlineEnd = if (isArabic) 0.dp else 16.dp,
                                contentPadding = PaddingValues(start = if (isArabic) 16.dp else 0.dp, end = if (isArabic) 0.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                RowTitle(choice.model)
                            }
                        }
                    }
                }
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
        ModelOptionsProviderDto("example-provider", "Example provider", listOf("example-model", "example-model-small")),
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
        ModelPickerContent(ModelPickerUiState.Ready(sampleModelOptions), null, {}, {})
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
        ModelPickerContent(ModelPickerUiState.Ready(sampleModelOptions), null, {}, {})
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
