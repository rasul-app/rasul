package app.rasul.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import app.rasul.chat.ChatFailure
import app.rasul.data.HermesError
import app.rasul.data.PendingAttachment
import app.rasul.share.PendingShare
import app.rasul.share.RecentChats
import app.rasul.share.SendToContent
import app.rasul.share.SendToState
import app.rasul.sessions.SessionDto
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.theme.RasulTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class SendToScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()

    private val ar = language == "ar"
    private val bots = listOf(
        ChatBotChoice("bridge:b1:research", if (ar) "الباحث" else "Research", "b1", "research", if (ar) "حاسوب الدراسة" else "Study PC"),
        ChatBotChoice("bridge:b1:writer", if (ar) "الكاتب" else "Writer", "b1", "writer", if (ar) "حاسوب الدراسة" else "Study PC"),
        ChatBotChoice("c1", if (ar) "بوت المنزل" else "Home bot"),
    )
    private val chats = listOf(
        SessionDto("s1", if (ar) "ملخص المقال" else "Article summary", preview = if (ar) "هل يمكنك تلخيص الرابط؟" else "Can you summarise the link?"),
        SessionDto("s2", if (ar) "خطة الأسبوع" else "Plan for the week", preview = if (ar) "رتب لي المهام" else "Order my tasks"),
        SessionDto("s3", null, preview = null),
    )
    private val text = if (ar) "مقال مفيد\nhttps://example.invalid/article" else "Useful article\nhttps://example.invalid/article"
    private val photo = PendingAttachment.Photo(1, ByteArray(1), ByteArray(1))

    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp")
        val share = when (kind) {
            "nothing" -> PendingShare(1, "", dropped = 1)
            "unsupported" -> PendingShare(1, "", failure = ChatFailure.FileTypeUnsupported)
            "bots" -> PendingShare(1, text, items = listOf(photo, photo.copy(key = 2), photo.copy(key = 3), photo.copy(key = 4)), dropped = 2)
            else -> PendingShare(1, text)
        }
        val state = when (kind) {
            "chats" -> SendToState(bots[0], RecentChats.Ready(chats))
            "chatsfail" -> SendToState(bots[0], RecentChats.Failed(HermesError.BridgeTimeout))
            else -> SendToState()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    SendToContent(share, bots, false, state, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val number = mapOf("bots" to 110, "chats" to 111, "nothing" to 112, "chatsfail" to 113, "unsupported" to 114).getValue(kind)
        compose.onRoot().captureRoboImage("src/test/screenshots/$number-sendto$kind-$language${if (dark) "-dark" else ""}.png")
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark -> listOf("bots", "chats", "nothing", "chatsfail", "unsupported").map { arrayOf(language, dark, it) } }
        }
    }
}
