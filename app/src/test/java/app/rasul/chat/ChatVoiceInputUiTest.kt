package app.rasul.chat

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import app.rasul.R
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ModelOptionsDto
import app.rasul.data.RunFrame
import app.rasul.data.RunStatus
import app.rasul.data.RunsApi
import app.rasul.data.RunsClient
import app.rasul.data.StartedRun
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatVoiceInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** One listener per session instance; a cancelled session can still emit callbacks. */
    private class FakeRecognizer : SpeechRecognizerGateway {
        class Session(val listener: SpeechRecognizerGateway.Listener) {
            var stops = 0
            var cancels = 0
            var cancelThread: Thread? = null
        }

        val sessions = mutableListOf<Session>()
        val current: Session? get() = sessions.lastOrNull()
        var languageTag: String? = null

        override fun start(languageTag: String, listener: SpeechRecognizerGateway.Listener) {
            this.languageTag = languageTag
            sessions += Session(listener)
        }

        override fun stop() {
            current?.let { it.stops++ }
        }

        override fun cancel() {
            current?.let { it.cancels++; it.cancelThread = Thread.currentThread() }
        }
    }

    @Test
    fun micIsHiddenWhenRecognitionIsUnavailable() {
        showChat(available = false)

        compose.onNodeWithTag("chat_voice_button").assertDoesNotExist()
    }

    @Test
    fun listeningShowsLiveWordsInTheStripAndKeepsTheDraftUntouched() {
        val recognizer = showChat(available = true)

        compose.onNodeWithTag("chat_voice_button")
            .assertExists()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Voice input")))
            .performClick()
        compose.runOnIdle { recognizer.current!!.listener.onPartial("spoken words") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_live_strip").assertExists()
        compose.onNodeWithText("spoken words").assertExists()
        compose.onNodeWithTag("chat_message_field").assertTextEquals("typed text")
        compose.runOnIdle { assertEquals(0, sentCount.value) }

        compose.runOnIdle { recognizer.current!!.listener.onFinal("spoken words") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_live_strip").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_field").assertTextEquals("typed text spoken words")
    }

    @Test(timeout = 60_000)
    fun listeningStateRendersWithoutWaitingForInfinitePulse() {
        compose.mainClock.autoAdvance = false
        val recognizer = showChat(available = true)

        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_button").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening…"))
        compose.onNodeWithTag("chat_voice_button").assertExists()
        compose.onNodeWithTag("chat_voice_live_strip").assertExists()
    }

    @Test
    fun pulseRendersOnlyWhileListening() {
        showChat(available = true)

        compose.onNodeWithTag("chat_voice_pulse", useUnmergedTree = true).assertDoesNotExist()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_pulse", useUnmergedTree = true).assertExists()
    }

    @Test
    fun stoppingDisablesTheMicUntilTheRecognizerReportsBack() {
        val recognizer = showChat(available = true)

        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_button")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Finishing…"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))

        compose.runOnIdle { recognizer.current!!.listener.onFinal("done") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_voice_button")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled).not())
        compose.onNodeWithTag("chat_message_field").assertTextEquals("typed text done")
    }

    @Test
    fun backgroundingTheAppReleasesRecognition() {
        val recognizer = showChat(available = true)

        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.runOnIdle { assertEquals(VoiceInputState.Listening, controller.state.value) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        assertEquals(1, recognizer.current!!.cancels)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun sendButtonCancelsDictationThroughTheRealWiringAndDropsLateResults() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_message_field").performTextInput("note")
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()
        compose.runOnIdle { recognizer.current!!.listener.onPartial("one") }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_voice_live_strip").assertExists()

        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, recognizer.current!!.cancels)
            assertEquals(VoiceInputState.Idle, realController!!.state.value)
        }
        compose.waitUntil(10_000) { screenRuns.started == listOf("note") }
        compose.onNodeWithTag("chat_voice_live_strip").assertDoesNotExist()

        compose.runOnIdle { recognizer.current!!.listener.onFinal("one") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun backgroundingThroughTheRealScreenReleasesTheSession() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(VoiceInputState.Listening, realController!!.state.value) }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        compose.runOnIdle {
            assertEquals(1, recognizer.current!!.cancels)
            assertEquals(VoiceInputState.Idle, realController!!.state.value)
        }
    }

    @Test
    fun permissionResultGrantStartsListeningAndDenialShowsError() {
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(recognizer, { "note" }, { 4..3 }, {})

        onVoicePermissionResult(granted = true, ready = false, controller = controller, languageTag = "en")
        assertEquals(VoiceInputState.Idle, controller.state.value)

        onVoicePermissionResult(granted = true, ready = true, controller = controller, languageTag = "en")
        assertEquals(VoiceInputState.Listening, controller.state.value)
        assertEquals("en", recognizer.languageTag)

        controller.cancel()
        onVoicePermissionResult(granted = false, ready = true, controller = controller, languageTag = "en")
        assertEquals(VoiceInputState.Error(VoiceInputError.Permission), controller.state.value)
    }

    @Test
    fun stopTimeoutRunsOnTheMainLooperAndCannotEndANewerSession() {
        val recognizer = FakeRecognizer()
        // The production default scope is Dispatchers.Main.immediate: the timeout must run there.
        val controller = VoiceInputController(recognizer, { "" }, { 0..-1 }, {})
        controller.start("en")
        controller.stop()

        controller.start("en")
        assertEquals(2, recognizer.sessions.size)
        assertEquals(VoiceInputState.Listening, controller.state.value)

        // The old session's cancelled timer must not touch the newer session.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(VoiceInputState.Listening, controller.state.value)
        assertEquals(0, recognizer.sessions[1].cancels)

        controller.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals(1, recognizer.sessions[1].cancels)
        assertEquals(Looper.getMainLooper().thread, recognizer.sessions[1].cancelThread)
    }

    @Test
    fun micTapAsksAndroidForTheMicPermissionAndAGrantStartsListening() {
        denyMicPermission()
        val recognizer = showRealChat()

        compose.onNodeWithTag("chat_voice_button").performClick()

        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(
            "android.content.pm.action.REQUEST_PERMISSIONS",
            request?.intent?.action,
        )
        val extras = request?.intent?.extras
        assertTrue(extras?.keySet()?.any { key ->
            extras.getStringArray(key)?.contains(Manifest.permission.RECORD_AUDIO) == true
        } == true)

        deliverPermissionResult(compose.activity, request!!.intent, PackageManager.PERMISSION_GRANTED)
        compose.waitForIdle()

        assertEquals(VoiceInputState.Listening, realController!!.state.value)
        assertEquals("en", recognizer.languageTag)
    }

    @Test
    fun deniedPermissionShowsTheSettingsDialogAndTheNextTapAsksAgain() {
        denyMicPermission()
        showRealChat()

        compose.onNodeWithTag("chat_voice_button").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        deliverPermissionResult(compose.activity, request!!.intent, PackageManager.PERMISSION_DENIED)
        compose.waitForIdle()

        // The Settings dialog uses the permission title; the same body text also shows inline.
        compose.onNodeWithText("Allow microphone access").assertExists()

        // A tap after a denial must ask Android again (a second REQUEST_PERMISSIONS intent),
        // not shortcut straight to the Settings dialog like the removed "asked" flag did.
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()

        val askedAgain = shadowOf(compose.activity).nextStartedActivityForResult?.intent?.action ==
            "android.content.pm.action.REQUEST_PERMISSIONS"
        assertTrue(askedAgain)
    }

    @Test
    fun aMicPermissionAskAndGrantAfterRecreationStartsListening() {
        denyMicPermission()
        val recognizer = showRealChat()

        compose.onNodeWithTag("chat_voice_button").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(request)
        val oldController = realController

        // Recreate destroys the activity; the content must be set again on the new instance.
        compose.activityRule.scenario.recreate()
        compose.activity.setContent { RealChatContent(recognizer, checkNotNull(realVm)) }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        // The pending request's answer reaches the NEW activity through the real dispatch path and
        // clears the restored mHasCurrentPermissionsRequest flag. Robolectric does not restore the
        // launcher's rememberSaveable key, so this grant cannot reach the new launcher — that
        // delivery is not claimed here. What is verified: a fresh ask + grant works after recreate.
        deliverPermissionResult(compose.activity, request!!.intent, PackageManager.PERMISSION_GRANTED)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        assertNotSame(oldController, realController)
        compose.onNodeWithTag("chat_voice_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        val relaunch = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(relaunch)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", relaunch!!.intent.action)
        deliverPermissionResult(compose.activity, relaunch.intent, PackageManager.PERMISSION_GRANTED)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        assertEquals(VoiceInputState.Listening, realController!!.state.value)
        assertEquals(1, recognizer.sessions.size)
    }

    @Test
    fun tappingTheMicWithThePermissionStartsListeningOnTheRealScreen() {
        grantMicPermission()
        val recognizer = showRealChat()

        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()

        assertEquals(VoiceInputState.Listening, realController!!.state.value)
        assertEquals(1, recognizer.sessions.size)
        assertEquals("en", recognizer.languageTag)
    }

    @Test
    fun voiceErrorIsAPoliteLiveRegionAndClearsWhenTheDraftChanges() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.runOnIdle { recognizer.current!!.listener.onError(VoiceInputError.Network) }
        compose.waitForIdle()

        compose.onNodeWithText("The speech service could not connect. Check your connection and try again.")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        compose.onNodeWithTag("chat_message_field").performTextInput("x")
        compose.waitForIdle()

        compose.onNodeWithText("The speech service could not connect. Check your connection and try again.")
            .assertDoesNotExist()
    }

    @Test
    fun sendClearsTheVoiceError() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_message_field").performTextInput("note")
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.runOnIdle { recognizer.current!!.listener.onError(VoiceInputError.Network) }
        compose.waitForIdle()
        compose.onNodeWithText("The speech service could not connect. Check your connection and try again.")
            .assertExists()

        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10_000) { screenRuns.started == listOf("note") }

        compose.onNodeWithText("The speech service could not connect. Check your connection and try again.")
            .assertDoesNotExist()
    }

    @Test
    fun typingWhileASendIsBeingPreparedShowsOnlyTheDraft() {
        showRealChat()
        compose.onNodeWithTag("chat_message_field").performTextInput("note")

        historyGate = CompletableDeferred()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10_000) { realVm!!.state.value.preparingSend }

        // The keystroke reaches the field but the model ignores it while the send is prepared.
        compose.onNodeWithTag("chat_message_field").performTextInput(" x")
        compose.waitForIdle()

        // The field must show exactly what a second Send would send: the draft, still "note".
        compose.runOnIdle { assertEquals("note", realVm!!.state.value.draft) }
        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("note")))

        historyGate!!.complete(Unit)
        compose.waitUntil(10_000) { screenRuns.started == listOf("note") }
        compose.runOnIdle { assertEquals("", realVm!!.state.value.draft) }
        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun aVoiceResultWhileASendIsBeingPreparedIsNotDroppedSilently() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(VoiceInputState.Listening, realController!!.state.value) }

        // A notification reply prepares a send without touching the draft or the dictation.
        historyGate = CompletableDeferred()
        compose.runOnIdle { realVm!!.replyFromNotification("queued note") }
        compose.waitUntil(10_000) { realVm!!.state.value.preparingSend }

        // The transcript cannot reach the draft now; it must be reported, not dropped silently.
        compose.runOnIdle { recognizer.current!!.listener.onFinal("spoken words") }
        compose.waitForIdle()

        compose.onNodeWithText("A message is being sent, so the spoken words were not added. Try again in a moment.")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle {
            assertEquals(VoiceInputState.Error(VoiceInputError.SendInFlight), realController!!.state.value)
            assertEquals("", realVm!!.state.value.draft)
        }
        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))

        historyGate!!.complete(Unit)
        compose.waitUntil(10_000) { screenRuns.started == listOf("queued note") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDictationDroppedWhileASendIsPreparedKeepsTheCaretForTheNextDictation() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_message_field").performTextInput("hello world")
        compose.onNodeWithTag("chat_message_field").performTextInputSelection(TextRange(5))
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()

        // A notification reply prepares a send; the model ignores draft writes until it finishes.
        historyGate = CompletableDeferred()
        compose.runOnIdle { realVm!!.replyFromNotification("queued note") }
        compose.waitUntil(10_000) { realVm!!.state.value.preparingSend }

        compose.runOnIdle { recognizer.current!!.listener.onFinal("big") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(VoiceInputState.Error(VoiceInputError.SendInFlight), realController!!.state.value)
            assertEquals("hello world", realVm!!.state.value.draft)
        }

        historyGate!!.complete(Unit)
        compose.waitUntil(10_000) { screenRuns.started == listOf("queued note") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("hello world", realVm!!.state.value.draft) }

        // The dropped words must not have moved the stored caret: the retry lands at the
        // caret the user left (5), not mid-word at 9 where the ignored insert would have ended.
        compose.onNodeWithTag("chat_voice_button").performClick()
        compose.waitForIdle()
        compose.runOnIdle { recognizer.current!!.listener.onFinal("big") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("hello big world")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(9)))
    }

    @Test
    fun theHintIsNeverDrawnOverTypedTextWhileASendIsPrepared() {
        showRealChat()

        // A notification reply starts a send that does not clear the draft (it stays empty).
        historyGate = CompletableDeferred()
        compose.runOnIdle { realVm!!.replyFromNotification("queued note") }
        compose.waitUntil(10_000) { realVm!!.state.value.preparingSend }

        compose.onNodeWithTag("chat_message_field").performTextInput("typed")
        compose.waitForIdle()

        // The ignored keystroke is reverted, so the empty-draft hint is not drawn over text.
        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Ask Chat").assertExists()

        historyGate!!.complete(Unit)
        compose.waitUntil(10_000) { screenRuns.started == listOf("queued note") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun insertedVoiceTextLeavesTheCaretAfterIt() {
        grantMicPermission()
        val recognizer = showRealChat()
        compose.onNodeWithTag("chat_message_field").performTextInput("hello world")
        compose.onNodeWithTag("chat_message_field").performTextInputSelection(TextRange(0))
        compose.onNodeWithTag("chat_voice_button").performClick()

        compose.runOnIdle { recognizer.current!!.listener.onFinal("new") }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_message_field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("new hello world")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
    }

    @Test
    fun voiceErrorsHaveSpecificEnglishAndArabicValues() {
        val context = RuntimeEnvironment.getApplication()
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        val arabic = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) },
        )
        val errors = listOf(
            Triple(VoiceInputError.NoMatch, "No speech was recognized. Try again.", "لم يتم التعرف على كلام. حاول مرة أخرى."),
            Triple(VoiceInputError.Network, "The speech service could not connect. Check your connection and try again.", "تعذر على خدمة الكلام الاتصال. تحقق من اتصالك وحاول مرة أخرى."),
            Triple(VoiceInputError.Busy, "The speech service is busy. Try again in a moment.", "خدمة الكلام مشغولة. حاول مرة أخرى بعد قليل."),
            Triple(VoiceInputError.Permission, "Microphone access is needed for voice input. Allow it in Settings to continue.", "يلزم الوصول إلى الميكروفون للإدخال الصوتي. اسمح به من الإعدادات للمتابعة."),
            Triple(VoiceInputError.Unavailable, "Speech recognition is unavailable on this device.", "التعرف على الكلام غير متاح على هذا الجهاز."),
            Triple(VoiceInputError.Language, "Speech recognition does not support this language.", "التعرف على الكلام لا يدعم هذه اللغة."),
            Triple(VoiceInputError.Microphone, "The microphone could not record audio. Try again.", "تعذّر على الميكروفون تسجيل الصوت. حاول مرة أخرى."),
            Triple(VoiceInputError.SendInFlight, "A message is being sent, so the spoken words were not added. Try again in a moment.", "جارٍ إرسال رسالة، لذا لم تُضَف الكلمات المنطوقة. حاول مرة أخرى بعد قليل."),
            Triple(VoiceInputError.Unknown, "Speech recognition stopped unexpectedly. Try again.", "توقف التعرف على الكلام بشكل غير متوقع. حاول مرة أخرى."),
        )
        val messageIds = errors.map { voiceErrorString(it.first) }
        assertEquals(messageIds.size, messageIds.toSet().size)
        errors.forEachIndexed { index, (error, englishValue, arabicValue) ->
            val messageId = messageIds[index]
            assertEquals(englishValue, english.getString(messageId))
            assertEquals(arabicValue, arabic.getString(messageId))
        }
    }

    @Test
    fun privacyTextIsTruthfulAboutTheSpeechService() {
        val context = RuntimeEnvironment.getApplication()
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        val arabic = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) },
        )
        assertEquals(
            "The app talks only to your own PC. There are no accounts, analytics, or tracking. Notification text stays on this device. Voice input uses your phone’s speech service. Depending on that service, your voice may be sent to its provider (for example Google) to turn it into text. The app does not record or store audio.",
            english.getString(R.string.privacy_body),
        )
        assertEquals(
            "يتواصل التطبيق مع حاسوبك فقط. لا توجد حسابات أو تحليلات أو تتبّع. يظل نص الإشعارات على هذا الجهاز. يستخدم الإدخال الصوتي خدمة الكلام في هاتفك. وبحسب تلك الخدمة، قد يُرسَل صوتك إلى مزوّدها (مثل Google) لتحويله إلى نص. لا يسجّل التطبيق الصوت ولا يخزّنه.",
            arabic.getString(R.string.privacy_body),
        )
    }

    private fun grantMicPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun denyMicPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Delivers a runtime-permission result through Activity.dispatchActivityResult, the same path
     * the system uses, so mHasCurrentPermissionsRequest clears before any later request.
     */
    private fun deliverPermissionResult(
        activity: ComponentActivity,
        requestIntent: android.content.Intent,
        grantResult: Int,
    ) {
        val result = android.content.Intent()
            .putExtra(
                "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES",
                arrayOf(Manifest.permission.RECORD_AUDIO),
            )
            .putExtra(
                "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS",
                intArrayOf(grantResult),
            )
        shadowOf(activity).receiveResult(requestIntent, android.app.Activity.RESULT_OK, result)
    }

    private lateinit var draft: androidx.compose.runtime.MutableState<String>
    private lateinit var fieldSelection: androidx.compose.runtime.MutableState<TextRange>
    private lateinit var sentCount: androidx.compose.runtime.MutableIntState
    private lateinit var controller: VoiceInputController
    private var realController: VoiceInputController? = null
    private var realVm: ChatViewModel? = null
    private val screenRuns = ScreenRuns()

    /** While set, the fake history load parks inside sendText — keeping `preparingSend` true. */
    private var historyGate: CompletableDeferred<Unit>? = null

    private class ScreenRuns : RunsApi {
        val started = mutableListOf<String>()

        override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> {
            started += (input as kotlinx.serialization.json.JsonPrimitive).content
            return HermesResult.Ok(StartedRun("run_1"))
        }

        override suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus> =
            HermesResult.Ok(RunStatus("running", null, null))

        override suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit> =
            HermesResult.Ok(Unit)

        override suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit> =
            HermesResult.Ok(Unit)

        override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?): HermesResult<Unit> =
            HermesResult.Ok(Unit)

        override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> =
            MutableSharedFlow()
    }

    /** The real ChatScreen with the real send/release wiring; only the recognizer is faked. */
    private fun showRealChat(): FakeRecognizer {
        val recognizer = FakeRecognizer()
        val vm = ChatViewModel(
            TargetLoader { HermesResult.Ok(HermesTarget("http://example.invalid/", null, "k")) },
            screenRuns,
            object : SessionsApi {
                override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> {
                    historyGate?.await()
                    return HermesResult.Ok(emptyList())
                }
            },
            object : RunMemory {
                override suspend fun sessionId() = "sess-1"
                override suspend fun activeRun(): String? = null
                override suspend fun lastEventId(): Long? = null
                override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) = Unit
            },
            OrderedWriter(CoroutineScope(Dispatchers.Unconfined)),
        ).also { realVm = it }
        // Set content on the activity itself (not compose.setContent): the composition then uses
        // the window recomposer, so rememberSaveable keys match after an activity recreate.
        compose.activity.setContent { RealChatContent(recognizer, vm) }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(10_000) { vm.state.value.load == ChatLoad.Ready }
        return recognizer
    }

    @androidx.compose.runtime.Composable
    private fun RealChatContent(recognizer: FakeRecognizer, vm: ChatViewModel) {
        RasulTheme(dark = false) {
            ChatScreen(
                vm = vm,
                title = "Chat",
                onBack = {},
                runs = RunsClient(),
                modelOptionsLoader = { HermesResult.Ok(ModelOptionsDto(providers = emptyList())) },
                voiceInputFactory = { _, chatVm, selection, onInsertCursor ->
                    VoiceInputController(
                        recognizer,
                        { chatVm.state.value.draft },
                        selection,
                        chatVm::onDraft,
                        onInsertCursor = onInsertCursor,
                    ).also { realController = it }
                },
            )
        }
    }

    private fun showChat(available: Boolean): FakeRecognizer {
        val recognizer = FakeRecognizer()
        draft = mutableStateOf("typed text")
        fieldSelection = mutableStateOf(TextRange(10))
        sentCount = mutableIntStateOf(0)
        controller = VoiceInputController(
            recognizer,
            { draft.value },
            { fieldSelection.value.let { it.min..it.max - 1 } },
            { draft.value = it },
        )
        val voiceAvailable = mutableStateOf(available)
        compose.setContent {
            val voiceState = controller.state.collectAsState().value
            val liveText = controller.liveText.collectAsState().value
            RasulTheme(dark = false) {
                ChatScreenContent(
                    state = ChatState(load = ChatLoad.Ready, sessionId = "sample-session", draft = draft.value),
                    title = "Sample chat",
                    onBack = {},
                    onDraft = { draft.value = it },
                    onSend = { controller.cancel(); sentCount.intValue++ },
                    onStop = {},
                    onReconnect = {},
                    onRefresh = {},
                    onAnswerApproval = { _, _ -> },
                    onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Ok(ModelOptionsDto(providers = emptyList())) },
                    selectedModel = null,
                    tokenUsage = null,
                    onSelectModel = {},
                    voiceAvailable = voiceAvailable.value,
                    voiceState = voiceState,
                    voiceLiveText = liveText,
                    onSelectionChange = { fieldSelection.value = it },
                    onVoiceToggle = { controller.toggle("en") },
                    onVoiceRelease = { controller.release() },
                )
            }
        }
        compose.waitForIdle()
        return recognizer
    }
}
