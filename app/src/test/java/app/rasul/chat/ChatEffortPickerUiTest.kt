package app.rasul.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.rasul.data.ModelCapabilityDto
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunModelSelection
import app.rasul.ui.theme.RasulTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatEffortPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private val options = ModelOptionsDto(
        providers = listOf(
            ModelOptionsProviderDto(
                "prov", "Provider", listOf("reasoner", "plain", "unknown"),
                capabilities = mapOf(
                    "reasoner" to ModelCapabilityDto(reasoning = true),
                    "plain" to ModelCapabilityDto(reasoning = false),
                ),
            ),
        ),
    )

    private fun show(
        selection: MutableState<RunModelSelection?>,
        open: MutableState<Boolean> = mutableStateOf(true),
        fontScale: Float = 1f,
        widthDp: Int? = null,
    ) {
        compose.setContent {
            RasulTheme(dark = false) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    Box(if (widthDp != null) Modifier.width(widthDp.dp) else Modifier.fillMaxWidth()) {
                        if (open.value) {
                            ModelPickerContent(
                                ModelPickerUiState.Ready(options),
                                selection.value,
                                onChoose = { selection.value = it },
                                onChooseEffort = { e -> selection.value = selection.value?.copy(effort = e) },
                                onRefresh = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun pickingHighKeepsTheChipsAndTheLevelAfterReopen() {
        val selection = mutableStateOf<RunModelSelection?>(RunModelSelection("prov", "reasoner"))
        val open = mutableStateOf(true)
        show(selection, open)
        compose.onNodeWithText("Auto").assertIsSelected()
        compose.onNodeWithText("High").performClick()
        compose.waitForIdle()
        assertEquals("high", selection.value?.effort)
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Auto").assertIsNotSelected()

        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        compose.runOnIdle { open.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("High").assertIsSelected()

        // Tapping the already chosen model row keeps the level.
        compose.onNodeWithText("reasoner").performClick()
        compose.waitForIdle()
        assertEquals(RunModelSelection("prov", "reasoner", "high"), selection.value)
    }

    @Test
    fun chipsShowOnlyWhenTheSelectedModelReportsReasoningTrue() {
        val selection = mutableStateOf<RunModelSelection?>(RunModelSelection("prov", "reasoner"))
        show(selection)
        compose.onNodeWithText("Medium").assertExists()
        compose.runOnIdle { selection.value = RunModelSelection("prov", "plain") }
        compose.waitForIdle()
        compose.onNodeWithText("Medium").assertDoesNotExist()
        compose.runOnIdle { selection.value = RunModelSelection("prov", "unknown") }
        compose.waitForIdle()
        compose.onNodeWithText("Medium").assertDoesNotExist()
    }

    @Test
    fun capabilitiesJsonWithExtraKeysDecodes() {
        // Shape per Hermes inventory.py (_apply_capabilities): extra flags beside fast/reasoning.
        val json = """{"providers":[{"slug":"prov","name":"Provider","models":["m"],"total_models":1,
            "capabilities":{"m":{"fast":true,"reasoning":true,"ultrafast":false,"can_disable_reasoning":true}}}],
            "model":"m","provider":"prov","extra":1}"""
        val decoded = lenientJson.decodeFromString<ModelOptionsDto>(json)
        assertEquals(ModelCapabilityDto(fast = true, reasoning = true), decoded.providers.single().capabilities["m"])
    }

    @Test
    fun chipsWrapInsteadOfClippingAtFontScale2() {
        // Robolectric text metrics are not real, so force a narrow column: the four chips (each at least 48 dp wide)
        // cannot fit one row, and every chip must still lie inside it.
        val selection = mutableStateOf<RunModelSelection?>(RunModelSelection("prov", "reasoner", "high"))
        show(selection, fontScale = 2f, widthDp = 200)
        val width = 200f * compose.density.density
        val chips = listOf("Auto", "Low", "Medium", "High").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        chips.forEach {
            assertTrue("chip clipped: $it", it.left >= 0f && it.right <= width + 0.01f)
            assertTrue("chip too short: $it", it.height >= 44f * compose.density.density)
        }
        assertTrue("chips did not wrap: $chips", chips.map { it.top }.distinct().size > 1)
    }
}
