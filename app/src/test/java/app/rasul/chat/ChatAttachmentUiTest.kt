package app.rasul.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.ModelOptionsDto
import app.rasul.data.PendingAttachment
import app.rasul.ui.theme.RasulTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class ChatAttachmentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparingChipShowsOnlyWhileALoadIsPending() {
        var pending by androidx.compose.runtime.mutableStateOf(1)
        compose.setContent {
            RasulTheme(false) {
                ChatScreenContent(
                    state = ChatState(load = ChatLoad.Ready, sessionId = "s", pendingAttachments = pending),
                    title = "Chat",
                    onBack = {},
                    onDraft = {},
                    onSend = {},
                    onStop = {},
                    onReconnect = {},
                    onRefresh = {},
                    onAnswerApproval = { _, _ -> },
                    onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                    selectedModel = null,
                    tokenUsage = null,
                    onSelectModel = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Preparing attachment…").assertExists()
        compose.runOnIdle { pending = 0 }
        compose.onNodeWithContentDescription("Preparing attachment…").assertDoesNotExist()
    }

    @Test fun attachmentChipRemoveTargetsAre48dpAndNamed() {
        compose.setContent {
            RasulTheme(false) {
                ChatScreenContent(
                    state = ChatState(
                        load = ChatLoad.Ready,
                        sessionId = "s",
                        attachments = listOf(
                            PendingAttachment.TextFile(1, "notes.md", "t"),
                            PendingAttachment.Photo(2, ByteArray(4), ByteArray(4)),
                        ),
                    ),
                    title = "Chat",
                    onBack = {},
                    onDraft = {},
                    onSend = {},
                    onStop = {},
                    onReconnect = {},
                    onRefresh = {},
                    onAnswerApproval = { _, _ -> },
                    onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                    selectedModel = null,
                    tokenUsage = null,
                    onSelectModel = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Remove attachment notes.md")
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Remove attachment Photo 1")
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
    }
}
