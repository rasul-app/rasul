package app.rasul

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobFormContent
import app.rasul.jobs.JobList
import app.rasul.jobs.JobsApi
import app.rasul.jobs.JobsScreen
import app.rasul.jobs.JobsViewModel
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.LoadView
import app.rasul.ui.components.SegmentedSwitch
import app.rasul.sessions.SessionRenameContent
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class LargeFontControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun segmentedEnglishAndArabicLabelsFitAndKeepLargeTouchTargets() {
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    SegmentedSwitch(listOf("en" to "English", "ar" to "العربية"), "en", {})
                }
            }
        }

        listOf("English", "العربية").forEach { label ->
            val matcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(label)
            val option = compose.onNode(matcher).assertExists().fetchSemanticsNode()
            val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).assertExists().fetchSemanticsNode()
            assertTrue("$label target height=" + option.boundsInRoot.height, option.boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
            assertTrue("$label target width=" + option.boundsInRoot.width, option.boundsInRoot.width >= with(compose.density) { 48.dp.toPx() })
            assertTrue("$label text bounds=" + labelNode.boundsInRoot + ", option=" + option.boundsInRoot, (labelNode.boundsInRoot.left >= option.boundsInRoot.left && labelNode.boundsInRoot.top >= option.boundsInRoot.top && labelNode.boundsInRoot.right <= option.boundsInRoot.right && labelNode.boundsInRoot.bottom <= option.boundsInRoot.bottom))
        }
    }

    @Test fun bridgeUnreachableRetryFitsAndKeepsFortyEightDpTargetAtLargeFontEnglish() =
        assertRetryFitsAtLargeFont("en")

    @Test fun bridgeUnreachableRetryFitsAndKeepsFortyEightDpTargetAtLargeFontArabic() =
        assertRetryFitsAtLargeFont("ar")

    @Test fun pausedJobRowUsesOffResumeSwitchWithoutResumeButton() {
        RuntimeEnvironment.setQualifiers("+en-w390dp-h700dp")
        val fixture = JobsScreenFixture()
        try {
            compose.setContent { RasulTheme(false) { JobsScreen(fixture.jobs) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Backup check", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val label = context.getString(app.rasul.R.string.job_resume_named, "Backup check")
            assertEquals("Resume Backup check", label)
            val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
            val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            compose.onNode(switchRole and hasContentDescription(label)).assertExists().assertIsOff()
            compose.onNode(buttonRole and hasText(context.getString(app.rasul.R.string.job_resume))).assertDoesNotExist()
        } finally {
            fixture.close()
        }
    }

    @Test fun pausedJobSwitchOpensResumeConfirmation() {
        RuntimeEnvironment.setQualifiers("+en-w390dp-h700dp")
        val fixture = JobsScreenFixture()
        try {
            compose.setContent { RasulTheme(false) { JobsScreen(fixture.jobs) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Backup check", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val label = context.getString(app.rasul.R.string.job_resume_named, "Backup check")
            val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
            compose.onNode(switchRole and hasContentDescription(label)).assertExists().performClick()
            compose.onNodeWithText(context.getString(app.rasul.R.string.job_resume_confirm_title)).assertExists()
            compose.onNodeWithText(context.getString(app.rasul.R.string.job_resume_confirm_body, "Backup check")).assertExists()
            assertEquals(0, fixture.api.resumeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test fun scheduledJobActionsFitAt320dpEnglishFontScale1() = assertJobActionsFit(JobCase.SCHEDULED, 320, "en", 1f)
    @Test fun scheduledJobActionsFitAt320dpEnglishFontScale2() = assertJobActionsFit(JobCase.SCHEDULED, 320, "en", 2f)

    @Test fun scheduledJobActionsFitAt320dpArabicFontScale1() = assertJobActionsFit(JobCase.SCHEDULED, 320, "ar", 1f)
    @Test fun scheduledJobActionsFitAt320dpArabicFontScale2() = assertJobActionsFit(JobCase.SCHEDULED, 320, "ar", 2f)

    @Test fun scheduledJobActionsFitAt390dpEnglishFontScale1() = assertJobActionsFit(JobCase.SCHEDULED, 390, "en", 1f)
    @Test fun scheduledJobActionsFitAt390dpEnglishFontScale2() = assertJobActionsFit(JobCase.SCHEDULED, 390, "en", 2f)

    @Test fun scheduledJobActionsFitAt390dpArabicFontScale1() = assertJobActionsFit(JobCase.SCHEDULED, 390, "ar", 1f)
    @Test fun scheduledJobActionsFitAt390dpArabicFontScale2() = assertJobActionsFit(JobCase.SCHEDULED, 390, "ar", 2f)

    @Test fun pausedJobActionsFitAt320dpEnglishFontScale1() = assertJobActionsFit(JobCase.PAUSED, 320, "en", 1f)
    @Test fun pausedJobActionsFitAt320dpEnglishFontScale2() = assertJobActionsFit(JobCase.PAUSED, 320, "en", 2f)

    @Test fun pausedJobActionsFitAt320dpArabicFontScale1() = assertJobActionsFit(JobCase.PAUSED, 320, "ar", 1f)
    @Test fun pausedJobActionsFitAt320dpArabicFontScale2() = assertJobActionsFit(JobCase.PAUSED, 320, "ar", 2f)

    @Test fun pausedJobActionsFitAt390dpEnglishFontScale1() = assertJobActionsFit(JobCase.PAUSED, 390, "en", 1f)
    @Test fun pausedJobActionsFitAt390dpEnglishFontScale2() = assertJobActionsFit(JobCase.PAUSED, 390, "en", 2f)

    @Test fun pausedJobActionsFitAt390dpArabicFontScale1() = assertJobActionsFit(JobCase.PAUSED, 390, "ar", 1f)
    @Test fun pausedJobActionsFitAt390dpArabicFontScale2() = assertJobActionsFit(JobCase.PAUSED, 390, "ar", 2f)

    @Test fun completedJobActionsFitAt320dpEnglishFontScale1() = assertJobActionsFit(JobCase.COMPLETED, 320, "en", 1f)
    @Test fun completedJobActionsFitAt320dpEnglishFontScale2() = assertJobActionsFit(JobCase.COMPLETED, 320, "en", 2f)

    @Test fun completedJobActionsFitAt320dpArabicFontScale1() = assertJobActionsFit(JobCase.COMPLETED, 320, "ar", 1f)
    @Test fun completedJobActionsFitAt320dpArabicFontScale2() = assertJobActionsFit(JobCase.COMPLETED, 320, "ar", 2f)

    @Test fun completedJobActionsFitAt390dpEnglishFontScale1() = assertJobActionsFit(JobCase.COMPLETED, 390, "en", 1f)
    @Test fun completedJobActionsFitAt390dpEnglishFontScale2() = assertJobActionsFit(JobCase.COMPLETED, 390, "en", 2f)

    @Test fun completedJobActionsFitAt390dpArabicFontScale1() = assertJobActionsFit(JobCase.COMPLETED, 390, "ar", 1f)
    @Test fun completedJobActionsFitAt390dpArabicFontScale2() = assertJobActionsFit(JobCase.COMPLETED, 390, "ar", 2f)

    @Test fun failedJobActionsFitAt320dpEnglishFontScale1() = assertJobActionsFit(JobCase.FAILED, 320, "en", 1f)
    @Test fun failedJobActionsFitAt320dpEnglishFontScale2() = assertJobActionsFit(JobCase.FAILED, 320, "en", 2f)

    @Test fun failedJobActionsFitAt320dpArabicFontScale1() = assertJobActionsFit(JobCase.FAILED, 320, "ar", 1f)
    @Test fun failedJobActionsFitAt320dpArabicFontScale2() = assertJobActionsFit(JobCase.FAILED, 320, "ar", 2f)

    @Test fun failedJobActionsFitAt390dpEnglishFontScale1() = assertJobActionsFit(JobCase.FAILED, 390, "en", 1f)
    @Test fun failedJobActionsFitAt390dpEnglishFontScale2() = assertJobActionsFit(JobCase.FAILED, 390, "en", 2f)

    @Test fun failedJobActionsFitAt390dpArabicFontScale1() = assertJobActionsFit(JobCase.FAILED, 390, "ar", 1f)
    @Test fun failedJobActionsFitAt390dpArabicFontScale2() = assertJobActionsFit(JobCase.FAILED, 390, "ar", 2f)

    @Test fun jobFormCancelFitsAtLargeFontEnglish() = assertCancelFitsAtLargeFont("en", true)

    @Test fun jobFormCancelFitsAtLargeFontArabic() = assertCancelFitsAtLargeFont("ar", true)

    @Test fun sessionRenameCancelFitsAtLargeFontEnglish() = assertCancelFitsAtLargeFont("en", false)

    @Test fun sessionRenameCancelFitsAtLargeFontArabic() = assertCancelFitsAtLargeFont("ar", false)

    @Test fun scheduledAndPausedJobSwitchesAreDisabledWhileBusy() {
        RuntimeEnvironment.setQualifiers("+en-w390dp-h700dp")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            RasulTheme(false) {
                JobList(
                    listOf(
                        JobDto("scheduled", "Scheduled task", enabled = true, state = "scheduled"),
                        JobDto("paused", "Paused task", enabled = false, state = "paused"),
                    ),
                    busy = true,
                    onToggle = {},
                    onRunNow = {},
                )
            }
        }

        val role = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val scheduled = context.getString(app.rasul.R.string.job_pause_named, "Scheduled task")
        val paused = context.getString(app.rasul.R.string.job_resume_named, "Paused task")
        compose.onNode(role and hasContentDescription(scheduled)).assertExists().assertIsNotEnabled()
        compose.onNode(role and hasContentDescription(paused)).assertExists().assertIsNotEnabled()
    }

    private fun assertCancelFitsAtLargeFont(language: String, jobForm: Boolean) {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h700dp")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cancel = context.getString(app.rasul.R.string.cancel)
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    if (jobForm) {
                        JobFormContent(job = null, saving = false, serverError = null, onDismiss = {}, onSave = { _, _, _ -> })
                    } else {
                        SessionRenameContent(initialTitle = "Nightly summary", saving = false, serverError = null, onCancel = {}, onSave = {})
                    }
                }
            }
        }

        val button = compose.onNode(hasClickAction() and hasText(cancel)).assertExists().fetchSemanticsNode()
        val label = compose.onNodeWithText(cancel, useUnmergedTree = true).assertExists().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(cancel, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        assertEquals("$language cancel fontScale", 2f, layouts.single().layoutInput.density.fontScale, 0f)
        assertTrue("$language cancel width overflow=${layouts.single().didOverflowWidth}, size=${layouts.single().size}, button=${button.boundsInRoot}", !layouts.single().didOverflowWidth)
        assertTrue("$language cancel text bounds=${label.boundsInRoot}, button=${button.boundsInRoot}",
            label.boundsInRoot.left >= button.boundsInRoot.left && label.boundsInRoot.top >= button.boundsInRoot.top &&
                label.boundsInRoot.right <= button.boundsInRoot.right && label.boundsInRoot.bottom <= button.boundsInRoot.bottom)
    }

    private fun assertRetryFitsAtLargeFont(language: String) {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h700dp")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pcName = if (language == "ar") "حاسوب الاستوديو" else "Studio PC"
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.fillMaxSize()) {
                        LoadView(Load.BridgeUnreachable(pcName), {}, content = {})
                    }
                }
            }
        }

        val message = context.getString(app.rasul.R.string.bridge_pc_unreachable, pcName)
        val retryText = context.getString(app.rasul.R.string.retry)
        compose.onNodeWithText(message).assertExists()
        val retryButton = compose.onNode(hasClickAction() and hasText(retryText)).assertExists().fetchSemanticsNode()
        val retryLabel = compose.onNodeWithText(retryText, useUnmergedTree = true).assertExists().fetchSemanticsNode()
        assertTrue("Retry target height=" + retryButton.boundsInRoot.height, retryButton.boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Retry text bounds=" + retryLabel.boundsInRoot + ", button=" + retryButton.boundsInRoot, (retryLabel.boundsInRoot.left >= retryButton.boundsInRoot.left && retryLabel.boundsInRoot.top >= retryButton.boundsInRoot.top && retryLabel.boundsInRoot.right <= retryButton.boundsInRoot.right && retryLabel.boundsInRoot.bottom <= retryButton.boundsInRoot.bottom))
    }

    private enum class JobCase { SCHEDULED, PAUSED, COMPLETED, FAILED }

    private fun assertJobActionsFit(state: JobCase, widthDp: Int, language: String, fontScale: Float) {
        RuntimeEnvironment.setQualifiers("+%s-w%ddp-h700dp".format(language, widthDp))
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val job = when (state) {
            JobCase.SCHEDULED -> JobDto("job-scheduled", "Scheduled task", "Every day", enabled = true)
            JobCase.PAUSED -> JobDto("job-paused", "Paused task", "Every day", enabled = false)
            JobCase.COMPLETED -> JobDto("job-completed", "Completed task", "Every day", state = "completed")
            JobCase.FAILED -> JobDto("job-failed", "Failed task", "Every day", state = "error")
        }
        val actionLabels = mutableListOf(
            context.getString(app.rasul.R.string.ux3c_job_edit),
            context.getString(app.rasul.R.string.ux3c_job_delete),
        )
        if (state == JobCase.SCHEDULED || state == JobCase.PAUSED) {
            actionLabels += context.getString(app.rasul.R.string.job_run_now)
        }
        val refWidths = mutableMapOf<String, Float>()
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    val measurer = rememberTextMeasurer()
                    actionLabels.forEach { label -> refWidths[label] = measurer.measure(label, style = RasulType.buttonSmall).size.width.toFloat() }
                    Box(Modifier.width(widthDp.dp)) {
                        JobList(listOf(job), busy = false, onToggle = {}, onRunNow = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        val trailingBounds = when (state) {
            JobCase.SCHEDULED -> compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                    and hasContentDescription(context.getString(app.rasul.R.string.job_pause_named, "Scheduled task")),
            ).assertExists().fetchSemanticsNode().boundsInRoot
            JobCase.PAUSED -> compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                    and hasContentDescription(context.getString(app.rasul.R.string.job_resume_named, "Paused task")),
            ).assertExists().fetchSemanticsNode().boundsInRoot
            JobCase.COMPLETED -> edgeTextBounds(context.getString(app.rasul.R.string.job_completed), language)
            JobCase.FAILED -> edgeTextBounds(context.getString(app.rasul.R.string.job_failed), language)
        }
        val tag = "$state ${widthDp}dp $language fs=$fontScale"
        val line = compose.onNodeWithTag("job_actions_line").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag action line top=${line.top} not below trailing=$trailingBounds", line.top >= trailingBounds.bottom - 1f)
        if (language == "ar") {
            assertTrue("$tag action line=$line left!=16dp", abs(line.left - 16f) <= 1f)
            assertTrue("$tag action line=$line right!=${widthDp - 74}dp", abs(line.right - (widthDp - 74f)) <= 1f)
            assertTrue("$tag action line left=${line.left} not under trailing right=${trailingBounds.right}", line.left <= trailingBounds.right + 1f)
        } else {
            assertTrue("$tag action line=$line left!=74dp", abs(line.left - 74f) <= 1f)
            assertTrue("$tag action line=$line right!=${widthDp - 16}dp", abs(line.right - (widthDp - 16f)) <= 1f)
            assertTrue("$tag action line right=${line.right} not under trailing left=${trailingBounds.left}", line.right >= trailingBounds.left - 1f)
        }
        actionLabels.forEach { label ->
            val button = compose.onNode(hasClickAction() and hasText(label)).assertExists().fetchSemanticsNode()
            val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).assertExists().fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
            val layout = layouts.single()
            assertTrue("$tag '$label' didOverflowWidth size=${layout.size} button=${button.boundsInRoot}", !layout.didOverflowWidth)
            val refWidth = refWidths[label] ?: error("no ref layout for $label")
            assertTrue(
                "$tag '$label' text clipped: laid=${labelNode.boundsInRoot.width} < ref=$refWidth button=${button.boundsInRoot}",
                labelNode.boundsInRoot.width >= refWidth - 1f,
            )
            assertTrue(
                "$tag '$label' button narrow: ${button.boundsInRoot.width} < refPad=${refWidth + 27f}",
                button.boundsInRoot.width >= refWidth + 27f,
            )
            assertTrue("$tag '$label' hasVisualOverflow size=${layout.size} button=${button.boundsInRoot}", !layout.hasVisualOverflow)
            assertTrue(
                "$tag '$label' text=${labelNode.boundsInRoot} outside button=${button.boundsInRoot}",
                labelNode.boundsInRoot.left >= button.boundsInRoot.left && labelNode.boundsInRoot.top >= button.boundsInRoot.top &&
                    labelNode.boundsInRoot.right <= button.boundsInRoot.right && labelNode.boundsInRoot.bottom <= button.boundsInRoot.bottom,
            )
            assertTrue(
                "$tag '$label' button=${button.boundsInRoot} overlaps trailing=$trailingBounds",
                !button.boundsInRoot.overlaps(trailingBounds),
            )
        }
    }

    private fun edgeTextBounds(text: String, language: String): Rect {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no node with text $text", nodes.isNotEmpty())
        val node = if (language == "ar") nodes.minByOrNull { it.boundsInRoot.left } else nodes.maxByOrNull { it.boundsInRoot.left }
        return node!!.boundsInRoot
    }

}

private class JobsScreenFixture {
    private val snapshots = MutableStateFlow<Preferences>(emptyPreferences())
    private val dataStore = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = snapshots

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(snapshots.value)
            snapshots.value = updated
            return updated
        }
    }
    private val store = ConnectionStore(dataStore, object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    })
    private val pcScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val viewModelStore = ViewModelStore()
    val api = JobsScreenApi(listOf(JobDto("backup-check", "Backup check", enabled = false, state = "paused")))
    val jobs: JobsViewModel

    init {
        runBlocking { store.save(Connection("sample", "Studio PC", "http://192.0.2.1"), "sample-key") }
        jobs = JobsViewModel(store, ActivePcHolder(store, pcScope), api)
        viewModelStore.put(JobsViewModel::class.java.name, jobs)
    }

    fun close() {
        viewModelStore.clear()
        pcScope.cancel()
    }
}

private class JobsScreenApi(private val jobs: List<JobDto>) : JobsApi {
    var resumeCalls = 0

    override suspend fun create(target: HermesTarget, name: String, schedule: String, prompt: String): HermesResult<JobDto> = error("unused")
    override suspend fun update(target: HermesTarget, id: String, name: String?, schedule: String?, prompt: String?): HermesResult<JobDto> = error("unused")
    override suspend fun delete(target: HermesTarget, id: String): HermesResult<Unit> = error("unused")
    override suspend fun list(target: HermesTarget): HermesResult<List<JobDto>> = HermesResult.Ok(jobs)
    override suspend fun pause(target: HermesTarget, id: String): HermesResult<JobDto> = error("unused")
    override suspend fun resume(target: HermesTarget, id: String): HermesResult<JobDto> {
        resumeCalls++
        return HermesResult.Ok(jobs.first())
    }
    override suspend fun runNow(target: HermesTarget, id: String): HermesResult<JobDto> = error("unused")
}
