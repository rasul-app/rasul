package app.rasul.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionList
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Chat list goldens, numbered 120: a pinned chat first, and a draft line under a chat that has one. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class ChatListScreenshotTest(private val language: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private var savedZone: TimeZone? = null

    @Before fun pinUtc() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreZone() {
        savedZone?.let { TimeZone.setDefault(it) }
    }

    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h700dp")
        val ar = language == "ar"
        val sessions = listOf(
            SessionDto("1", if (ar) "ملخص النشر الليلي" else "Nightly deploy summary", preview = if (ar) "التقرير جاهز للمراجعة." else "The report is ready to review.", lastActive = JsonPrimitive(1_759_999_700)),
            SessionDto("2", if (ar) "إصلاح اختبار الدخول" else "Fix flaky login test", preview = if (ar) "وجدت مشكلة التوقيت." else "I found the timing issue.", lastActive = JsonPrimitive(1_759_992_800)),
            SessionDto("3", if (ar) "خطة الرحلة" else "Trip plan", preview = if (ar) "سأجهز الجدول." else "I will prepare the schedule.", lastActive = JsonPrimitive(1_759_900_000)),
        )
        val draft = if (ar) "هل يمكنك أيضاً مراجعة سجل الأخطاء؟" else "Could you also check the error log?"
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
                        SessionList(
                            sessions, "a", 1_760_000_000_000L, onOpen = {},
                            pinned = listOf("3"), draftLines = mapOf("2" to draft),
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/120-chatlist-pinned-draft-$language${if (dark) "-dark" else ""}.png")
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { l -> listOf(false, true).map { arrayOf(l, it) } }
    }
}
