package app.rasul.chat

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import app.rasul.R
import app.rasul.data.HermesResult
import app.rasul.data.ModelOptionsDto
import app.rasul.data.RunModelSelection
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatModelPickerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var modelSelectionState: MutableState<RunModelSelection?>
    private lateinit var layoutDirectionState: MutableState<LayoutDirection>
    private lateinit var localeState: MutableState<Locale>
    private lateinit var backLabelState: MutableState<String>

    @Test
    fun modelLabelShowsSelectedModelOrServerDefault() {
        val backLabel = showChat(modelName = "Example model", layoutDirection = LayoutDirection.Ltr)
        compose.onNodeWithText("Example model").assertExists()
        compose.onNodeWithTag("chat_model_label")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
        compose.onNodeWithTag(RasulIcons.Back.name, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(backLabel)))

        compose.runOnIdle { modelSelectionState.value = null }
        compose.waitForIdle()
        compose.onNodeWithText("Server default").assertExists()

        compose.runOnIdle {
            modelSelectionState.value = RunModelSelection("example", "Example model")
            layoutDirectionState.value = LayoutDirection.Rtl
            localeState.value = Locale("ar")
        }
        compose.waitForIdle()
        val arabicBackLabel = backLabelState.value
        assertTrue("back label did not switch to Arabic", arabicBackLabel != backLabel)
        compose.onNodeWithTag(RasulIcons.Back.name, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(arabicBackLabel)))
    }

    @Test
    fun modelLabelTapOpensTheExistingPicker() {
        showChat()
        compose.onNodeWithTag("chat_model_label").assertHasClickAction().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Choose a model").assertExists()
    }

    @Test
    fun modelLabelShowsPickedEffort() {
        showChat(modelName = "Example model")
        compose.runOnIdle {
            modelSelectionState.value = RunModelSelection("example", "Example model", effort = "high")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(" High", useUnmergedTree = true).assertExists()
    }

    @Test
    fun modelLabelIsDisabledWhenModelSelectionIsUnavailable() {
        showChat(canChooseModel = false)
        compose.onNodeWithText("Example model").assertExists()
        compose.onNodeWithTag("chat_model_label")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Model selection unavailable"))
        compose.onNodeWithTag("chat_model_label").performTouchInput { click() }
        compose.onNodeWithText("Choose a model").assertDoesNotExist()
    }

    @Test
    fun longModelNameEllipsizesAtLargeFontWithoutMovingInput() {
        showChat(modelName = "Example model", fontScale = 2f)
        compose.onNodeWithTag("chat_model_label").assertExists()
        val shortLabel = compose.onNodeWithTag("chat_model_label").fetchSemanticsNode().boundsInRoot
        val shortInput = compose.onNodeWithTag("chat_message_field").fetchSemanticsNode().boundsInRoot

        val longName = "An example model name that should be clipped before it reaches the send button"
        compose.runOnIdle { modelSelectionState.value = RunModelSelection("example", longName) }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("chat_model_label").fetchSemanticsNode()
        val nameNode = compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true).fetchSemanticsNode()
        val longInput = compose.onNodeWithTag("chat_message_field").fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val textLayout = layouts.single()

        assertTrue("label height=${label.boundsInRoot.height}", label.boundsInRoot.height >= 48f * compose.density.density)
        assertTrue("label width=${label.boundsInRoot.width}", label.boundsInRoot.width >= 48f * compose.density.density)
        assertTrue(
            "name did not overflow its viewport; label=${label.boundsInRoot}; name=${nameNode.boundsInRoot}; " +
                "layout=${textLayout.size}; constraints=${textLayout.layoutInput.constraints}",
            textLayout.didOverflowWidth,
        )
        assertEquals(TextOverflow.Ellipsis, textLayout.layoutInput.overflow)
        assertEquals(1, textLayout.layoutInput.maxLines)
        assertEquals(2f, textLayout.layoutInput.density.fontScale, 0f)
        assertEquals(longName, textLayout.layoutInput.text.text)
        val density = compose.density.density
        // Control row sits inside the composer: 12dp outer padding each side, 8dp row padding,
        // 48dp send button at the end — so the label may not cross that space.
        val maxWidth = (390f - 24f - 16f - 48f) * density
        assertTrue("label=${label.boundsInRoot}; maxWidth=$maxWidth", label.boundsInRoot.width <= maxWidth + 0.01f)
        assertEquals("input left changed", shortInput.left, longInput.left, 0.01f)
        assertEquals("input top changed", shortInput.top, longInput.top, 0.01f)
        assertEquals("input width changed", shortInput.width, longInput.width, 0.01f)
        assertEquals("input height changed", shortInput.height, longInput.height, 0.01f)
        assertTrue("short label=$shortLabel", shortLabel.width <= maxWidth + 0.01f)
    }

    @Test
    fun headerStaysAboveTheFirstMessageAtFontScale2AndPillIs48dp() {
        showChat(fontScale = 2f, messages = listOf(ChatMessage(1, MessageKind.User, "First message")))
        val density = compose.density.density
        val header = compose.onNodeWithTag("chat_header").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("chat_header_name").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("First message").fetchSemanticsNode().boundsInRoot
        assertTrue("pill height=${pill.height}", pill.height >= 48f * density - 0.01f)
        assertTrue("header bottom=${header.bottom} first top=${first.top}", first.top >= header.bottom - 0.01f)
    }

    @Test
    fun historyErrorStateDoesNotPadTheHeaderTwice() {
        showChat(
            messages = listOf(ChatMessage(1, MessageKind.User, "First message")),
            historyFailure = ChatFailure.Api(app.rasul.data.HermesError.Server(503)),
        )
        val retry = compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("First message").fetchSemanticsNode().boundsInRoot
        assertTrue("gap=${first.top - retry.bottom}", first.top - retry.bottom < 40f * compose.density.density)
    }

    @Test
    fun daySeparatorFollowsTheAppLanguage() {
        showChat(
            layoutDirection = LayoutDirection.Rtl,
            messages = listOf(ChatMessage(1, MessageKind.User, "x", atMillis = 1_792_097_160_000L)),
        )
        val english = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        english.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
    }

    @Test
    fun busyRunIsAnnouncedOnTheHeaderPill() {
        showChat(active = true)
        compose.onNodeWithTag("chat_header_name")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working..."))
    }

    @Test
    fun menuSubtitleShowsTheLevelToo() {
        showChat(modelName = "Example model")
        compose.runOnIdle { modelSelectionState.value = RunModelSelection("example", "Example model", effort = "high") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Example model High").assertExists()
    }

    private fun showChat(
        modelName: String? = "Example model",
        canChooseModel: Boolean = true,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        messages: List<ChatMessage> = emptyList(),
        historyFailure: ChatFailure? = null,
        active: Boolean = false,
    ): String {
        layoutDirectionState = mutableStateOf(layoutDirection)
        localeState = mutableStateOf(if (layoutDirection == LayoutDirection.Rtl) Locale("ar") else Locale.ENGLISH)
        backLabelState = mutableStateOf("")
        modelSelectionState = mutableStateOf(modelName?.let { RunModelSelection("example", it) })
        val canChooseState = mutableStateOf(canChooseModel)
        val fontScaleState = mutableStateOf(fontScale)
        compose.setContent {
            val baseContext = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val locale = localeState.value
            val currentLayoutDirection = layoutDirectionState.value
            val localizedConfiguration = remember(baseContext, baseConfiguration, locale) {
                Configuration(baseConfiguration).apply { setLocale(locale) }
            }
            val localizedContext = remember(baseContext, localizedConfiguration) {
                baseContext.createConfigurationContext(localizedConfiguration)
            }
            backLabelState.value = localizedContext.getString(R.string.back)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
                LocalLayoutDirection provides currentLayoutDirection,
            ) {
                val selection = modelSelectionState.value
                val canChoose = canChooseState.value
                val currentFontScale = fontScaleState.value
                val state = ChatState(
                    load = ChatLoad.Ready,
                    sessionId = "sample-session",
                    run = if (canChoose && !active) null else ActiveRun("sample-run", 0L, StatusLabel.Thinking),
                    messages = messages,
                    historyFailure = historyFailure,
                )
                RasulTheme(dark = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, currentFontScale)) {
                        ChatScreenContent(
                            state = state,
                            title = "Sample chat",
                            onBack = {},
                            onDraft = {},
                            onSend = {},
                            onStop = {},
                            onReconnect = {},
                            onRefresh = {},
                            onAnswerApproval = { _, _ -> },
                            onRetryHistory = {},
                            modelOptionsLoader = { HermesResult.Ok(ModelOptionsDto(providers = emptyList())) },
                            selectedModel = selection,
                            tokenUsage = null,
                            onSelectModel = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return backLabelState.value
    }
}
