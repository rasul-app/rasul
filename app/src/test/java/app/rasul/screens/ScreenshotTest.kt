package app.rasul.screens

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.github.takahirom.roborazzi.captureRoboImage
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen preview in both app languages and both themes on the JVM.
 * Preview functions are private, so includePrivatePreviews(). Size/locale come from each @Preview
 * (390 dp wide = phone preview width; height is the preview's own).
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest(private val preview: ComposablePreview<AndroidPreviewInfo>, private val dark: Boolean) {
    // Sessions/Jobs dates are formatted in the JVM default zone; pin UTC so PNGs do not depend on the host.
    private var savedZone: TimeZone? = null

    @Before
    fun pinUtc() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        savedZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun capture() {
        val themed = object : ComposablePreview<AndroidPreviewInfo> by preview {
            @Composable
            override fun invoke() {
                CompositionLocalProvider(LocalInspectionMode provides true) { preview() }
            }

            override val previewInfo = preview.previewInfo.copy(
                uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                backgroundColor = if (dark) 0xFF111113 else 0xFFFFFFFF,
            )
        }
        themed.captureRoboImage(fileName(preview.methodName, dark))
    }

    companion object {
        // Order of screens in the file names (nn-).
        private val order = listOf("Bots", "BotsEmpty", "Edit", "Sessions", "Jobs", "ResumeDialog", "More", "LanguageSheet")

        fun fileName(method: String, dark: Boolean = false): String {
            val lang = if (method.endsWith("Ar")) "ar" else "en"
            val screen = method.removeSuffix("Ar").removeSuffix("En")
            val n = when {
                screen == "ChatIdle" -> 10
                screen == "VoiceListening" -> 56
                screen == "ChatThinking" -> 25
                screen == "ChatTool" -> 26
                screen == "ChatApproval" -> 27
                screen == "ChatLost" -> 28
                screen == "ChatHistoryError" -> 29
                screen == "ChatFork" -> 57
                screen == "Lock" -> 11
                screen == "LockSetting" -> 12
                screen == "ScanPermission" -> 13
                screen == "Scanning" -> 14
                screen == "CodeConfirm" -> 15
                screen == "BridgeBotsReady" -> 16
                screen == "BridgeBotsNotReady" -> 17
                screen == "BridgeBotsUnreachable" -> 18
                screen == "BridgePcUnreachable" -> 23
                screen == "BridgePcNoReadyBots" -> 24
                screen == "SearchFiltered" -> 30
                screen == "Settings" -> 31
                screen == "PlusMenu" -> 32
                screen == "SkillsTools" -> 44
                screen == "ModelPicker" -> 45
                screen == "TokenUsage" -> 46
                screen == "CreateBotEmpty" -> 19
                screen == "CreateBotInvalid" -> 20
                screen == "CreateBotCreating" -> 21
                screen == "CreateBotError" -> 22
                screen == "MoreBridge" -> 33
                screen == "PairingGuide" -> 34
                screen == "Transcript" -> 35
                screen == "SearchEmpty" -> 36
                screen == "SearchNoResults" -> 37
                screen == "SearchLoading" -> 38
                screen == "SearchError" -> 39
                screen == "BotPicker" -> 40
                screen == "BridgePicker" -> 41
                screen == "AppearanceSheet" -> 42
                screen == "Privacy" -> 43
                screen == "JobForm" -> 47
                screen == "JobDelete" -> 48
                screen == "SessionRename" -> 49
                screen == "RunHistory" -> 50
                screen == "Usage" -> 51
                screen == "SkillsDesktop" -> 52
                screen == "SkillToggleConfirm" -> 53
                screen == "SkillInstallConfirm" -> 54
                screen == "MoreAlerts" -> 55
                screen == "UpdateAvailable" -> 58
                screen == "UpdateDownloading" -> 59
                screen == "UpdateFailed" -> 60
                screen == "UpdateSettings" -> 61
                screen == "MoreUpdates" -> 62
                screen == "HermesUpdateAvailable" -> 70
                screen == "HermesUpdateQueued" -> 71
                screen == "HermesUpdateRunning" -> 72
                screen == "HermesUpdateDone" -> 73
                screen == "HermesUpdateFailed" -> 74
                screen == "HermesUpdateNowConfirm" -> 75
                screen == "PcCheckOk" -> 76
                screen == "PcCheckProblems" -> 77
                screen == "McpCatalog" -> 78
                screen == "HermesUpdateUnknown" -> 79
                screen == "ChatApprovalLong" -> 80
                screen == "ChannelsList" -> 100
                screen == "ChannelsRestart" -> 101
                screen == "ChannelsBusy" -> 102
                screen == "TelegramSetupWaiting" -> 103
                screen == "TelegramSetupReady" -> 104
                else -> order.indexOf(screen).let { if (it < 0) order.size else it } + 1
            }
            val suffix = if (dark) "-dark" else ""
            return "src/test/screenshots/%02d-%s-%s%s.png".format(n, screen.lowercase(), lang, suffix)
        }

        @JvmStatic
        @Parameters(name = "{0}, dark={1}")
        fun previews(): List<Array<Any>> =
            allPreviews().filterNot { it.methodName.startsWith("PlusMenu") || it.methodName.startsWith("VoiceListening") }.flatMap {
                listOf(arrayOf(it, false), arrayOf(it, true))
            }

        fun allPreviews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("app.rasul")
                .includePrivatePreviews()
                .getPreviews()
    }
}
