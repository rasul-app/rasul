package app.rasul.settings

import androidx.core.os.LocaleListCompat

/** The user's language choice. System = follow the phone (empty app locale list). */
enum class LanguageOption(val tag: String) {
    System(""),
    English("en"),
    Arabic("ar"),
    ;

    fun toLocales(): LocaleListCompat = LocaleListCompat.forLanguageTags(tag)

    companion object {
        /** Maps the app's current locale tags (e.g. "ar-SA", "") back to an option; unknown tags read as System. */
        fun fromTags(tags: String): LanguageOption {
            val first = tags.substringBefore(',').substringBefore('-').trim().lowercase()
            return entries.firstOrNull { it != System && it.tag == first } ?: System
        }
    }
}
