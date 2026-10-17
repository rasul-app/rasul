package app.rasul

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.rasul.data.HermesError
import app.rasul.jobs.jobValidationText
import app.rasul.sessions.sessionValidationText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchErrorMessagesTest {
    private fun contexts(): Pair<android.content.Context, android.content.Context> {
        val english = ApplicationProvider.getApplicationContext<android.content.Context>()
        val arabicConfiguration = Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }
        return english to english.createConfigurationContext(arabicConfiguration)
    }

    @Test
    fun jobCreateAndPatchValidationMessagesAreLocalizedInEnglishAndArabic() {
        val (english, arabic) = contexts()
        val messages = listOf(
            Triple("Name is required", "Enter a job name.", "أدخل اسمًا للمهمة."),
            Triple("Name must be ≤ 200 characters", "Keep the job name to 200 characters or fewer.", "يجب ألا يتجاوز اسم المهمة ٢٠٠ حرف."),
            Triple("Schedule is required", "Enter a schedule.", "أدخل جدولًا زمنيًا."),
            Triple("Cron job has nothing to run: the prompt is blank and no script or skill(s) are set. Provide a prompt, a script, or at least one skill.", "Enter a prompt.", "أدخل تعليمات للمهمة."),
            Triple("Prompt must be ≤ 5000 characters", "Keep the prompt to 5,000 characters or fewer.", "يجب ألا تتجاوز التعليمات ٥٬٠٠٠ حرف."),
            Triple("Repeat must be a positive integer", "Enter a positive whole-number repeat count.", "أدخل عدد تكرارات صحيحًا موجبًا."),
            Triple("No valid fields to update", "Make at least one change before saving.", "أجرِ تغييرًا واحدًا على الأقل قبل الحفظ."),
            Triple(
                "Invalid schedule 'whenever'. Use:\n" +
                    "  - Interval: '30m', 'every 30m', 'every 2h' (recurring)\n" +
                    "  - One-shot delay: 'in 30m', 'in 2h' (fires once)\n" +
                    "  - Weekly/daily: 'every monday 9am', 'weekdays at 9am' (recurring)\n" +
                    "  - Cron: '0 9 * * *' (cron expression)\n" +
                    "  - Timestamp: '2026-02-03T14:00:00' (one-shot at time)",
                "That schedule is not in a supported format. Use an interval, a one-time delay, a weekday and time, a cron expression, or an ISO timestamp.",
                "صيغة الجدول غير مدعومة. استخدم مدة أو تأخيرًا لمرة واحدة أو يومًا ووقتًا أو تعبير cron أو طابعًا زمنيًا بصيغة ISO.",
            ),
            Triple(
                "Invalid duration: '7weeks'. Use format like '30m', '2h', '1d', or a bare unit like 'hour' (defaults to 1).",
                "That interval is not valid. Use a duration such as 30m, 2h, or 1d.",
                "صيغة المدة غير صالحة. استخدم مدة مثل 30m أو 2h أو 1d.",
            ),
            Triple(
                "Invalid duration 'later' after 'in '. Use e.g. 'in 30m', 'in 2h'.",
                "That one-time delay is not valid. Use a delay such as in 30m or in 2h.",
                "صيغة التأخير لمرة واحدة غير صالحة. استخدم تأخيرًا مثل in 30m أو in 2h.",
            ),
            Triple(
                "Invalid timestamp '2026-02-30T10:00:00': invalid date",
                "That date and time is not valid. Enter an ISO timestamp such as 2026-02-03T14:00:00.",
                "التاريخ والوقت غير صالحين. أدخل طابعًا زمنيًا بصيغة ISO صالحًا، مثل 2026-02-03T14:00:00.",
            ),
            Triple(
                "Invalid schedule 'every monday 9am': parser detail",
                "That weekday and time schedule could not be parsed. Check the weekday and time.",
                "تعذر تفسير جدول اليوم والوقت. تحقق من اليوم والوقت المدخلين.",
            ),
            Triple(
                "Invalid cron expression '0 99 * * *': parser detail",
                "That cron expression is not valid. Check its fields, for example 0 9 * * *.",
                "تعبير cron غير صالح. تحقق من حقوله، مثل 0 9 * * *.",
            ),
            Triple(
                "Weekday/time schedules like 'every monday 9am' require the 'croniter' package. Install with: pip install croniter",
                "This server cannot parse weekday and time schedules. Use an interval or timestamp, or ask the server administrator to install croniter.",
                "لا يستطيع الخادم تفسير جداول الأيام والأوقات. استخدم مدة أو طابعًا زمنيًا، أو اطلب من مسؤول الخادم تثبيت croniter.",
            ),
            Triple(
                "Cron expressions require 'croniter' package. Install with: pip install croniter",
                "This server cannot parse cron expressions. Use an interval or timestamp, or ask the server administrator to install croniter.",
                "لا يستطيع الخادم تفسير تعبيرات cron. استخدم مدة أو طابعًا زمنيًا، أو اطلب من مسؤول الخادم تثبيت croniter.",
            ),
            Triple(
                "Requested one-shot time 2026-01-01T10:00:00+00:00 is more than 120s in the past and cannot be scheduled.",
                "That run time is too far in the past. Choose a future time or use a delay.",
                "وقت التشغيل المحدد مضى خارج مهلة الجدولة. اختر وقتًا لاحقًا أو استخدم تأخيرًا.",
            ),
        )

        messages.forEach { (detail, expectedEnglish, expectedArabic) ->
            val error = HermesError.Server(400, detail)
            assertEquals(expectedEnglish, jobValidationText(error, english))
            assertEquals(expectedArabic, jobValidationText(error, arabic))
        }
    }

    @Test
    fun invalidSessionTitleCodeIsLocalizedInEnglishAndArabic() {
        val (english, arabic) = contexts()
        val error = HermesError.Server(400, "Title too long (201 chars, max 200)", "invalid_title")

        assertEquals("This chat title can’t be used. Choose a different one.", sessionValidationText(error, english))
        assertEquals("لا يمكن استخدام عنوان هذه المحادثة. اختر عنوانًا آخر.", sessionValidationText(error, arabic))
    }

    @Test
    fun unrecognizedValidationDetailUsesLocalizedFallbackAndKeepsRawDetail() {
        val (english, arabic) = contexts()
        val jobError = HermesError.Server(400, "unsafe pattern")
        val sessionError = HermesError.Server(400, "unexpected detail")

        assertEquals("The server rejected this job. Details: unsafe pattern", jobValidationText(jobError, english))
        assertEquals("رفض الخادم حفظ المهمة. التفاصيل: unsafe pattern", jobValidationText(jobError, arabic))
        assertEquals("The server rejected this chat title. Details: unexpected detail", sessionValidationText(sessionError, english))
        assertEquals("رفض الخادم عنوان المحادثة. التفاصيل: unexpected detail", sessionValidationText(sessionError, arabic))
    }
    @Test
    fun searchHttpMessagesNameCategoryAndStatusInEnglishAndArabic() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val arabicConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }
        val arabic = context.createConfigurationContext(arabicConfiguration)

        assertEquals("Chats could not be loaded (HTTP 503).", context.getString(R.string.search_http_error_chats, 503))
        assertEquals("Jobs could not be loaded (HTTP 503).", context.getString(R.string.search_http_error_jobs, 503))
        assertEquals("تعذّر تحميل المحادثات (HTTP ٥٠٣).", arabic.getString(R.string.search_http_error_chats, 503))
        assertEquals("تعذّر تحميل المهام (HTTP ٥٠٣).", arabic.getString(R.string.search_http_error_jobs, 503))
        assertEquals("Select a PC in Sessions to search chats.", context.getString(R.string.search_no_connection_chats))
        assertEquals("اختر حاسوبًا من تبويب المحادثات للبحث فيها.", arabic.getString(R.string.search_no_connection_chats))
    }
}
