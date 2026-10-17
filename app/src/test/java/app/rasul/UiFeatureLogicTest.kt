package app.rasul

import android.content.res.Configuration
import app.rasul.chat.ChatModelChoice
import app.rasul.chat.TokenUsageDisplay
import app.rasul.chat.modelChoices
import app.rasul.chat.tokenUsageDisplay
import app.rasul.data.CapabilitiesDto
import app.rasul.data.CapabilityEndpointDto
import app.rasul.data.HermesError
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunTokenUsage
import app.rasul.data.TokenUsageSummary
import app.rasul.data.sumRunTokenUsage
import app.rasul.settings.skillsToolsEntryVisible
import app.rasul.ui.FeatureErrorSurface
import app.rasul.ui.errorMessageResourceId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.rasul.data.RelativeTimeLabels
import app.rasul.data.formatRelativeTime
import app.rasul.settings.AppearanceOption
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.NewChatAction
import app.rasul.ui.SearchCategory
import app.rasul.ui.SearchEntry
import app.rasul.ui.SearchFilter
import app.rasul.ui.filterSearchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiFeatureLogicTest {
    private val now = 1_760_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun relativeTimeBoundariesUseEnglishLabels() {
        val labels = RelativeTimeLabels("now", { _, n -> "${n}m" }, { _, n -> "${n}h" }, "Yesterday") { millis, locale, zone ->
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(Date(millis))
        }
        assertEquals("now", relative(59_999L, Locale.ENGLISH, labels))
        assertEquals("1m", relative(60_000L, Locale.ENGLISH, labels))
        assertEquals("5m", relative(5 * 60_000L, Locale.ENGLISH, labels))
        assertEquals("2h", relative(2 * 3_600_000L, Locale.ENGLISH, labels))
        assertEquals("Yesterday", relative(25 * 3_600_000L, Locale.ENGLISH, labels))
        assertEquals("Sep 25, 2025", relative(14 * 86_400_000L, Locale.US, labels))
    }

    @Test
    fun relativeTimeUsesArabicWordsAndDigits() {
        val labels = RelativeTimeLabels("الآن", { _, n -> "$n د" }, { _, n -> "$n س" }, "أمس") { millis, locale, zone ->
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(Date(millis))
        }
        assertEquals("الآن", relative(30_000L, Locale.forLanguageTag("ar"), labels))
        assertEquals("١ د", relative(60_000L, Locale.forLanguageTag("ar"), labels))
        assertEquals("٥ د", relative(5 * 60_000L, Locale.forLanguageTag("ar"), labels))
        assertEquals("٢ س", relative(2 * 3_600_000L, Locale.forLanguageTag("ar"), labels))
        assertEquals("أمس", relative(25 * 3_600_000L, Locale.forLanguageTag("ar"), labels))
        assertEquals(
            DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag("ar")).apply { timeZone = utc }
                .format(Date(now - 14 * 86_400_000L)),
            relative(14 * 86_400_000L, Locale.forLanguageTag("ar"), labels),
        )
    }

    @Test
    fun searchFiltersEachChipAndIgnoresArabicDiacritics() {
        val entries = listOf(
            SearchEntry("c", SearchCategory.Chat, "Deploy logs", "مُلَخَّص النشر"),
            SearchEntry("b", SearchCategory.Bot, "Research"),
            SearchEntry("j", SearchCategory.Job, "Daily report"),
        )
        assertEquals(listOf("c", "b", "j"), filterSearchEntries(entries, "", SearchFilter.All).map { it.key })
        assertEquals(listOf("c"), filterSearchEntries(entries, "deploy", SearchFilter.Chats).map { it.key })
        assertEquals(listOf("b"), filterSearchEntries(entries, "research", SearchFilter.Bots).map { it.key })
        assertEquals(listOf("j"), filterSearchEntries(entries, "report", SearchFilter.Jobs).map { it.key })
        assertEquals(listOf("c"), filterSearchEntries(entries, "ملخص النشر", SearchFilter.All).map { it.key })
    }

    @Test
    fun appearanceOptionsMapToSystemLightAndDark() {
        assertTrue(AppearanceOption.System.isDark(true))
        assertFalse(AppearanceOption.System.isDark(false))
        assertFalse(AppearanceOption.Light.isDark(true))
        assertFalse(AppearanceOption.Light.isDark(false))
        assertTrue(AppearanceOption.Dark.isDark(true))
        assertTrue(AppearanceOption.Dark.isDark(false))
    }

    @Test
    fun newChatOpensOneBotAndOffersPickerForSeveral() {
        val first = ChatBotChoice("one", "Research")
        assertEquals(NewChatAction.NoBots, app.rasul.ui.newChatAction(emptyList()))
        assertEquals(NewChatAction.Open(first), app.rasul.ui.newChatAction(listOf(first)))
        val second = ChatBotChoice("two", "Deploys")
        assertEquals(NewChatAction.Choose(listOf(first, second)), app.rasul.ui.newChatAction(listOf(first, second)))
    }

    @Test
    fun skillsToolsEntryRequiresBothAdvertisedEndpoints() {
        val skills = mapOf("skills" to CapabilityEndpointDto("GET", "/v1/skills"))
        val toolsets = mapOf("toolsets" to CapabilityEndpointDto("GET", "/v1/toolsets"))
        val both = skills + toolsets
        assertFalse(skillsToolsEntryVisible(null))
        assertFalse(skillsToolsEntryVisible(CapabilitiesDto(endpoints = skills)))
        assertFalse(skillsToolsEntryVisible(CapabilitiesDto(endpoints = toolsets)))
        assertTrue(skillsToolsEntryVisible(CapabilitiesDto(endpoints = both)))
    }

    @Test
    fun modelOptionsBecomeProviderGroupedChoices() {
        val options = ModelOptionsDto(
            providers = listOf(ModelOptionsProviderDto("example-provider", "Example provider", listOf("example-model", "example-model-small"))),
            model = "example-model",
            provider = "example-provider",
        )
        assertEquals(
            listOf(
                ChatModelChoice("example-provider", "Example provider", "example-model"),
                ChatModelChoice("example-provider", "Example provider", "example-model-small"),
            ),
            modelChoices(options),
        )
    }

    @Test
    fun tokenUsageShowsNotReportedOrSumsReportedRuns() {
        assertEquals(
            TokenUsageDisplay.NotReported,
            tokenUsageDisplay(sumRunTokenUsage(listOf(null, RunTokenUsage()))),
        )
        assertEquals(
            TokenUsageDisplay.Reported(15, 8, 23),
            tokenUsageDisplay(
                sumRunTokenUsage(
                    listOf(
                        RunTokenUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                        RunTokenUsage(inputTokens = 5, outputTokens = 3, totalTokens = 8),
                    ),
                ),
            ),
        )
    }

    @Test
    fun featureServerErrorsMapToSpecificEnglishAndArabicMessages() {
        assertEquals(
            R.string.ux3b_skills_tools_capabilities_error,
            errorMessageResourceId(HermesError.Server(500), FeatureErrorSurface.SkillsCapabilities),
        )
        assertEquals(
            R.string.ux3b_skills_tools_not_supported,
            errorMessageResourceId(HermesError.Incompatible(listOf("skills", "toolsets")), FeatureErrorSurface.SkillsCapabilities),
        )
        assertEquals(
            R.string.ux3b_skills_load_error,
            errorMessageResourceId(HermesError.Server(500), FeatureErrorSurface.Skills),
        )
        assertEquals(
            R.string.ux3b_toolsets_load_error,
            errorMessageResourceId(HermesError.Server(500), FeatureErrorSurface.Toolsets),
        )
        assertEquals(
            R.string.ux3b_model_options_load_error,
            errorMessageResourceId(HermesError.Incompatible(listOf("model_options")), FeatureErrorSurface.ModelOptions),
        )
        val english = RuntimeEnvironment.getApplication()
        val arabicConfiguration = Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }
        val arabic = english.createConfigurationContext(arabicConfiguration)
        assertEquals("Could not read server capabilities.", english.getString(R.string.ux3b_skills_tools_capabilities_error))
        assertEquals("تعذّر قراءة إمكانات الخادم.", arabic.getString(R.string.ux3b_skills_tools_capabilities_error))
        assertEquals("This server does not provide skills and tools.", english.getString(R.string.ux3b_skills_tools_not_supported))
        assertEquals("هذا الخادم لا يوفّر المهارات والأدوات.", arabic.getString(R.string.ux3b_skills_tools_not_supported))
        assertEquals("Could not load skills. Try again.", english.getString(R.string.ux3b_skills_load_error))
        assertEquals("تعذّر تحميل المهارات. حاول مرة أخرى.", arabic.getString(R.string.ux3b_skills_load_error))
        assertEquals("Could not load toolsets. Try again.", english.getString(R.string.ux3b_toolsets_load_error))
        assertEquals("تعذّر تحميل مجموعات الأدوات. حاول مرة أخرى.", arabic.getString(R.string.ux3b_toolsets_load_error))
        assertEquals("Could not load model options. Try again.", english.getString(R.string.ux3b_model_options_load_error))
        assertEquals("تعذّر تحميل خيارات النماذج. حاول مرة أخرى.", arabic.getString(R.string.ux3b_model_options_load_error))
    }

    private fun relative(ageMillis: Long, locale: Locale, labels: RelativeTimeLabels): String =
        formatRelativeTime(now - ageMillis, now, locale, labels, utc)
}
