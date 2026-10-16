package app.rasul

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import app.rasul.chat.codeTextStyle
import app.rasul.ui.theme.RasulType
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class CodeTextStyleTest {
    @Test
    fun codeTextIsForcedLeftToRightAndKeepsItsStyle() {
        val mono = RasulType.body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        val styled = codeTextStyle(mono)
        assertEquals(TextDirection.Ltr, styled.textDirection)
        assertEquals(FontFamily.Monospace, styled.fontFamily)
        assertEquals(mono.fontSize, styled.fontSize)
    }

    @Test
    fun plainStyleGainsOnlyTheDirection() {
        assertEquals(TextDirection.Ltr, codeTextStyle(TextStyle()).textDirection)
    }

    @Test fun unsavedAnswerHasSpecificEnglishAndArabicMessages() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        fun text(language: String): String {
            val config = android.content.res.Configuration(app.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            return app.createConfigurationContext(config).getString(R.string.chat_err_answer_unsaved)
        }
        assertEquals("Your PC hasn't saved the last answer yet. Try again in a moment.", text("en"))
        assertEquals("لم يحفظ حاسوبك الإجابة الأخيرة بعد. حاول مرة أخرى بعد قليل.", text("ar"))
    }

    @Test fun sessionReadFailureHasSpecificEnglishAndArabicMessages() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        fun text(language: String): String {
            val config = android.content.res.Configuration(app.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            return app.createConfigurationContext(config).getString(R.string.chat_err_session_unavailable)
        }
        assertEquals("The saved conversation could not be read. Retry to load it before sending.", text("en"))
        assertEquals("تعذرت قراءة المحادثة المحفوظة. أعد المحاولة لتحميلها قبل الإرسال.", text("ar"))
    }
}
