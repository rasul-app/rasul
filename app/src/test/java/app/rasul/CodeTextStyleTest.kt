package app.rasul

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.chat.codeTextStyle
import app.rasul.ui.theme.RasulMonoFontFamily
import app.rasul.ui.theme.rasulFontFamily
import app.rasul.ui.theme.rasulTypography
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class CodeTextStyleTest {
    @Test
    fun codeTextIsForcedLeftToRightAndKeepsItsStyle() {
        val mono = TextStyle(fontFamily = RasulMonoFontFamily, fontSize = 13.sp)
        val styled = codeTextStyle(mono)
        assertEquals(TextDirection.Ltr, styled.textDirection)
        assertEquals(RasulMonoFontFamily, styled.fontFamily)
        assertEquals(mono.fontSize, styled.fontSize)
    }

    @Test
    fun monoFamilyIsTheSystemMonospace() {
        assertEquals(FontFamily.Monospace, RasulMonoFontFamily)
    }

    @Test
    fun appFontFamilyIsTheSystemDefaultForEveryLanguage() {
        val arabic = rasulFontFamily("ar")
        val english = rasulFontFamily("en")
        val other = rasulFontFamily("fr")

        assertEquals(FontFamily.Default, english)
        assertEquals(FontFamily.Default, arabic)
        assertEquals(FontFamily.Default, other)
        assertEquals(FontFamily.Default, rasulTypography(english).bodyLarge.fontFamily)
        assertEquals(FontFamily.Default, rasulTypography(arabic).bodyLarge.fontFamily)
    }

    @Test
    fun plainStyleGainsOnlyTheDirection() {
        assertEquals(TextDirection.Ltr, codeTextStyle(TextStyle()).textDirection)
    }

    @Test fun keptReplyHasSpecificEnglishAndArabicMessages() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        fun text(language: String): String {
            val config = android.content.res.Configuration(app.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            return app.createConfigurationContext(config).getString(R.string.chat_err_reply_kept)
        }
        assertEquals("Couldn't send yet. Your reply is in the chat box.", text("en"))
        assertEquals("تعذّر الإرسال الآن. ردّك في مربع الكتابة.", text("ar"))
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
