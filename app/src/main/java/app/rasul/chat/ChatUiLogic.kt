package app.rasul.chat

import app.rasul.data.ModelOptionsDto
import app.rasul.data.RunModelSelection
import app.rasul.data.TokenUsageSummary

data class ChatModelChoice(
    val providerSlug: String,
    val providerName: String,
    val model: String,
)

fun ChatModelChoice.toRunModelSelection(): RunModelSelection = RunModelSelection(providerSlug, model)

fun modelChoices(options: ModelOptionsDto): List<ChatModelChoice> =
    options.providers.flatMap { provider ->
        provider.models.map { model -> ChatModelChoice(provider.slug, provider.name, model) }
    }

sealed interface TokenUsageDisplay {
    data object NotReported : TokenUsageDisplay
    data class Reported(val input: Long, val output: Long, val total: Long) : TokenUsageDisplay
}

fun tokenUsageDisplay(summary: TokenUsageSummary?): TokenUsageDisplay =
    if (summary?.isReported == true) {
        TokenUsageDisplay.Reported(summary.inputTokens, summary.outputTokens, summary.totalTokens)
    } else {
        TokenUsageDisplay.NotReported
    }