package app.rasul.ui

import java.text.Normalizer
import java.util.Locale

enum class SearchFilter {
    All,
    Chats,
    Bots,
    Jobs,
}

enum class SearchCategory {
    Chat,
    Bot,
    Job,
}

data class SearchEntry(
    val key: String,
    val category: SearchCategory,
    val title: String,
    val preview: String? = null,
)

data class ChatBotChoice(
    val id: String,
    val name: String,
    val bridgeId: String? = null,
    val profileId: String? = null,
    val pcName: String? = null,
)

sealed interface NewChatAction {
    data object Loading : NewChatAction
    data class Unavailable(val error: app.rasul.data.HermesError?) : NewChatAction
    data object NoBots : NewChatAction
    data class Open(val bot: ChatBotChoice) : NewChatAction
    data class Choose(val bots: List<ChatBotChoice>) : NewChatAction
}

fun newChatAction(
    bots: List<ChatBotChoice>,
    bridgesLoading: Boolean = false,
    unavailable: Boolean = false,
    error: app.rasul.data.HermesError? = null,
): NewChatAction = when {
    bridgesLoading -> NewChatAction.Loading
    unavailable -> NewChatAction.Unavailable(error)
    else -> when (bots.size) {
        0 -> NewChatAction.NoBots
        1 -> NewChatAction.Open(bots.single())
        else -> NewChatAction.Choose(bots)
    }
}

fun normalizeSearchText(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
    return buildString(decomposed.length) {
        decomposed.forEach { char ->
            val type = Character.getType(char)
            if (type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt() && char != '\u0640'
            ) append(char)
        }
    }.lowercase(Locale.ROOT)
}

fun filterSearchEntries(entries: List<SearchEntry>, query: String, filter: SearchFilter): List<SearchEntry> {
    val normalizedQuery = normalizeSearchText(query).trim()
    return entries.filter { entry ->
        val categoryMatches = when (filter) {
            SearchFilter.All -> true
            SearchFilter.Chats -> entry.category == SearchCategory.Chat
            SearchFilter.Bots -> entry.category == SearchCategory.Bot
            SearchFilter.Jobs -> entry.category == SearchCategory.Job
        }
        val queryMatches = normalizedQuery.isEmpty() ||
            normalizeSearchText(entry.title).contains(normalizedQuery) ||
            normalizeSearchText(entry.preview.orEmpty()).contains(normalizedQuery)
        categoryMatches && queryMatches
    }
}
