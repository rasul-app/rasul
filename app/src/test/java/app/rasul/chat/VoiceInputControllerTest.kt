package app.rasul.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputControllerTest {
    /**
     * Models the platform constraint: each start() runs on a fresh recognizer with its own
     * listener. A cancelled session can still emit callbacks; the controller must drop them.
     */
    private class FakeRecognizer : SpeechRecognizerGateway {
        class Session(val listener: SpeechRecognizerGateway.Listener) {
            var stops = 0
            var cancels = 0
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
            current?.let { it.cancels++ }
        }
    }

    /** A caret at position [at] is an empty closed range. */
    private fun caret(at: Int): IntRange = at..at - 1

    private fun controller(
        recognizer: FakeRecognizer,
        draft: () -> String,
        selection: () -> IntRange,
        onDraft: (String) -> Unit,
        scope: TestScope? = null,
        onInsertCursor: (Int) -> Unit = {},
    ): VoiceInputController = VoiceInputController(
        recognizer,
        draft,
        selection,
        onDraft,
        // Plain JUnit has no Android Main dispatcher; production defaults to Dispatchers.Main.
        scope ?: TestScope(),
        onInsertCursor = onInsertCursor,
    )

    @Test
    fun startUsesTheSelectedLanguage() {
        val recognizer = FakeRecognizer()
        val controller = controller(recognizer, { "typed text" }, { caret(10) }, {})

        controller.start("ar")

        assertEquals(1, recognizer.sessions.size)
        assertEquals("ar", recognizer.languageTag)
        assertEquals(VoiceInputState.Listening, controller.state.value)
        assertEquals("", controller.liveText.value)
    }

    @Test
    fun secondToggleStopsListeningAndWaitsForTheCallback() {
        val recognizer = FakeRecognizer()
        var draft = "typed text"
        val controller = controller(recognizer, { draft }, { caret(draft.length) }, { draft = it })

        controller.toggle("en")
        controller.toggle("en")

        assertEquals(1, recognizer.current!!.stops)
        assertEquals(VoiceInputState.Stopping, controller.state.value)

        recognizer.current!!.listener.onFinal("")

        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals(1, recognizer.current!!.cancels)
    }

    @Test
    fun partialWordsShowInTheLiveStripWithoutTouchingTheDraft() {
        val recognizer = FakeRecognizer()
        val drafts = mutableListOf<String>()
        var draft = "typed text"
        val controller = controller(recognizer, { draft }, { caret(draft.length) }, { draft = it; drafts.add(it) })
        controller.start("en")
        val listener = recognizer.current!!.listener

        listener.onPartial("first words")
        assertEquals("first words", controller.liveText.value)
        assertEquals("typed text", draft)
        listener.onPartial("final words")
        assertEquals("final words", controller.liveText.value)
        assertEquals("typed text", draft)
        listener.onFinal("final words")

        assertEquals(listOf("typed text final words"), drafts)
        assertEquals("", controller.liveText.value)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun finalTextInsertsAtTheCursorAfterInSessionEdits() {
        // The repeated-text case from the round-2 review: "note" + partial "one", the user types
        // " one more"; the caret ends at 13, so final "two" produces "note one more two" — the
        // user's text is never replaced.
        val recognizer = FakeRecognizer()
        var draft = "note"
        var selection = caret(4)
        val controller = controller(recognizer, { draft }, { selection }, { draft = it })
        controller.start("en")
        val listener = recognizer.current!!.listener

        listener.onPartial("one")
        draft = "note one more"
        selection = caret(13)
        listener.onFinal("two")

        assertEquals("note one more two", draft)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun editingTheDraftThenMovingTheCaretInsertsAtTheCaret() {
        // Start on "hello world", type "!", move the caret to the start, then final "new" arrives.
        val recognizer = FakeRecognizer()
        var draft = "hello world"
        var selection = caret(11)
        var insertCursor = -1
        val controller = controller(recognizer, { draft }, { selection }, { draft = it }, onInsertCursor = { insertCursor = it })
        controller.start("en")
        val listener = recognizer.current!!.listener

        draft = "hello world!"
        selection = caret(0)
        listener.onFinal("new")

        assertEquals("new hello world!", draft)
        assertEquals(3, insertCursor)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun deletingTheDraftDuringDictationStillInsertsTheFinalWords() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        val controller = controller(recognizer, { draft }, { caret(0) }, { draft = it })
        controller.start("en")
        val listener = recognizer.current!!.listener

        listener.onPartial("one")
        draft = ""
        listener.onFinal("two")

        assertEquals("two", draft)

        draft = "note"
        controller.start("en")
        recognizer.current!!.listener.onPartial("one")
        draft = "no"
        recognizer.current!!.listener.onFinal("two")

        assertEquals("two no", draft)
    }

    @Test
    fun finalWithoutEditsInsertsAtTheCaretAndReplacesTheSelection() {
        val recognizer = FakeRecognizer()
        var draft = "hi there"
        var selection = caret(8)
        val controller = controller(recognizer, { draft }, { selection }, { draft = it })
        controller.start("en")

        recognizer.current!!.listener.onFinal("friend")
        assertEquals("hi there friend", draft)

        // A selected range is replaced by the final words.
        draft = "note one"
        selection = 5..7
        controller.start("en")
        recognizer.current!!.listener.onFinal("two")

        assertEquals("note two", draft)
    }

    @Test
    fun typingWhileListeningIsNeverChangedByPartials() {
        val recognizer = FakeRecognizer()
        var draft = "typed text"
        var selection = caret(10)
        val controller = controller(recognizer, { draft }, { selection }, { draft = it })
        controller.start("en")
        val listener = recognizer.current!!.listener

        listener.onPartial("first words")
        assertEquals("first words", controller.liveText.value)
        assertEquals("typed text", draft)

        draft = "$draft!"
        selection = caret(11)
        listener.onPartial("more words")

        assertEquals("more words", controller.liveText.value)
        assertEquals("typed text!", draft)

        listener.onFinal("more words please")
        assertEquals("typed text! more words please", draft)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun lateFinalAfterStopInsertsOnceAtTheCursor() {
        val recognizer = FakeRecognizer()
        var draft = "hi"
        var selection = caret(2)
        var insertCursor = -1
        val controller = controller(recognizer, { draft }, { selection }, { draft = it }, onInsertCursor = { insertCursor = it })
        controller.start("en")
        val listener = recognizer.current!!.listener
        listener.onPartial("one")
        controller.stop()
        assertEquals(VoiceInputState.Stopping, controller.state.value)

        draft = "$draft?"
        selection = caret(3)

        listener.onFinal("one two")
        assertEquals("hi? one two", draft)
        assertEquals(11, insertCursor)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals(1, recognizer.current!!.cancels)
    }

    @Test
    fun restartWhileStoppingDropsTheOldSessionAndUsesAFreshRecognizer() {
        val recognizer = FakeRecognizer()
        var draft = ""
        val controller = controller(recognizer, { draft }, { caret(0) }, { draft = it })
        controller.start("en")
        val first = recognizer.current!!
        controller.stop()

        controller.start("en")

        assertEquals(1, first.cancels)
        assertEquals(2, recognizer.sessions.size)
        assertEquals(VoiceInputState.Listening, controller.state.value)

        first.listener.onFinal("ghost words")
        assertEquals("", draft)

        recognizer.current!!.listener.onFinal("new words")
        assertEquals("new words", draft)
    }

    @Test
    fun aMissingCallbackAfterStopTimesOutAndDestroysTheSession() {
        val testScope = TestScope()
        val recognizer = FakeRecognizer()
        var draft = ""
        val controller = controller(recognizer, { draft }, { caret(0) }, { draft = it }, testScope)
        controller.start("en")
        val first = recognizer.current!!
        controller.stop()
        testScope.runCurrent()

        assertEquals(VoiceInputState.Stopping, controller.state.value)
        assertEquals(0, first.cancels)

        testScope.advanceTimeBy(3_001)

        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals(1, first.cancels)

        controller.start("en")
        assertEquals(2, recognizer.sessions.size)
        first.listener.onFinal("ghost words")
        assertEquals("", draft)
    }

    @Test
    fun sendCancelsTheSessionAndDropsLateResults() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        val controller = controller(recognizer, { draft }, { caret(4) }, { draft = it })
        controller.start("en")
        val first = recognizer.current!!
        first.listener.onPartial("words")
        assertEquals("words", controller.liveText.value)
        assertEquals("note", draft)

        controller.cancel()

        assertEquals(1, first.cancels)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals("", controller.liveText.value)

        draft = ""
        first.listener.onFinal("late words")
        assertEquals("", draft)
    }

    @Test
    fun sendDuringDictationThenRestartNeverBringsSentWordsBack() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        val controller = controller(recognizer, { draft }, { caret(draft.length) }, { draft = it })
        controller.start("en")
        val first = recognizer.current!!
        first.listener.onPartial("one")

        controller.cancel()
        draft = ""

        controller.start("en")
        assertEquals(2, recognizer.sessions.size)
        first.listener.onFinal("one")
        assertEquals("", draft)

        recognizer.current!!.listener.onFinal("two")
        assertEquals("two", draft)
    }

    @Test
    fun releaseEndsTheSessionAndDestroysTheRecognizer() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        val controller = controller(recognizer, { draft }, { caret(4) }, { draft = it })
        controller.start("en")
        val first = recognizer.current!!
        first.listener.onPartial("words")
        assertEquals("note", draft)

        controller.release()

        assertEquals(1, first.cancels)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        first.listener.onFinal("late words")
        assertEquals("note", draft)

        controller.start("en")
        assertEquals(2, recognizer.sessions.size)
        assertEquals(VoiceInputState.Listening, controller.state.value)
    }

    @Test
    fun anIgnoredDraftWriteReportsAnErrorInsteadOfDroppingTheWords() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        // The writer ignores edits while a send is being prepared (like ChatViewModel.onDraft does).
        var acceptWrites = false
        val controller = controller(recognizer, { draft }, { caret(4) }, { if (acceptWrites) draft = it })
        controller.start("en")

        recognizer.current!!.listener.onFinal("spoken")

        assertEquals("note", draft)
        assertEquals(VoiceInputState.Error(VoiceInputError.SendInFlight), controller.state.value)
        assertEquals("", controller.liveText.value)
        assertEquals(1, recognizer.current!!.cancels)

        acceptWrites = true
        controller.start("en")
        recognizer.current!!.listener.onFinal("spoken")

        assertEquals("note spoken", draft)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun anIgnoredDraftWriteLeavesTheReportedCaretAlone() {
        val recognizer = FakeRecognizer()
        var draft = "note"
        // The writer ignores edits while a send is being prepared (like ChatViewModel.onDraft does).
        var acceptWrites = false
        val insertCursors = mutableListOf<Int>()
        val controller = controller(recognizer, { draft }, { caret(4) }, { if (acceptWrites) draft = it }, onInsertCursor = { insertCursors += it })
        controller.start("en")

        recognizer.current!!.listener.onFinal("spoken")

        assertEquals("note", draft)
        assertEquals(emptyList<Int>(), insertCursors)
        assertEquals(VoiceInputState.Error(VoiceInputError.SendInFlight), controller.state.value)

        acceptWrites = true
        controller.start("en")
        recognizer.current!!.listener.onFinal("spoken")

        assertEquals("note spoken", draft)
        assertEquals(listOf(11), insertCursors)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun recognitionErrorRetainsItsSpecificCause() {
        val recognizer = FakeRecognizer()
        val controller = controller(recognizer, { "" }, { caret(0) }, {})
        controller.start("en")

        val listener = recognizer.current!!.listener
        listener.onError(VoiceInputError.Network)

        assertEquals(VoiceInputState.Error(VoiceInputError.Network), controller.state.value)
        assertEquals(1, recognizer.current!!.cancels)
    }

    @Test
    fun permissionDenialIsAnExplicitState() {
        val controller = controller(FakeRecognizer(), { "" }, { caret(0) }, {})

        controller.permissionDenied()

        assertEquals(VoiceInputState.Error(VoiceInputError.Permission), controller.state.value)
    }
}
