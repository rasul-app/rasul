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

/** Bubbles group tighter inside one sender's run; a sender switch or a day break gets the wider gap. */
internal fun sameSenderGroup(a: ChatMessage?, b: ChatMessage): Boolean =
    a != null && senderGroup(a) == senderGroup(b)

internal fun senderGroup(m: ChatMessage): Int = when (m.kind) {
    MessageKind.User, MessageKind.UserSteer -> 0
    MessageKind.Assistant -> 1
    else -> 2
}

/** A centered day/time line sits above the first message of a new day (and above the first stamped message). */
internal fun needsDaySeparator(prev: ChatMessage?, current: ChatMessage, zone: java.time.ZoneId): Boolean {
    val cur = current.atMillis ?: return false
    val before = prev?.atMillis ?: return true
    return java.time.Instant.ofEpochMilli(before).atZone(zone).toLocalDate() !=
        java.time.Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
}

fun tokenUsageDisplay(summary: TokenUsageSummary?): TokenUsageDisplay =
    if (summary?.isReported == true) {
        TokenUsageDisplay.Reported(summary.inputTokens, summary.outputTokens, summary.totalTokens)
    } else {
        TokenUsageDisplay.NotReported
    }