package app.rasul.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceInputError {
    NoMatch,
    Network,
    Busy,
    Permission,
    Unavailable,
    Language,
    Microphone,
    /** The transcript could not be added to the draft (e.g. a send was being prepared). */
    SendInFlight,
    Unknown,
}

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object Listening : VoiceInputState
    /** stop() was sent; the recognizer's final/error callback (or a timeout) ends this state. */
    data object Stopping : VoiceInputState
    data class Error(val cause: VoiceInputError) : VoiceInputState
}

interface SpeechRecognizerGateway {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(error: VoiceInputError)
    }

    /** Begins a dictation; each call runs on a fresh recognizer so old callbacks cannot reach it. */
    fun start(languageTag: String, listener: Listener)
    /** Graceful end: asks the recognizer to finish; its final/error callback is still expected. */
    fun stop()
    /** Aborts the current dictation and frees its recognizer; late callbacks are dropped. */
    fun cancel()
}

/**
 * Drives one voice dictation session at a time. While listening, partial words go only to
 * [liveText] for the strip above the composer; the draft is never touched. On the final result the
 * transcript is inserted once into the draft (see [insertTranscript]), then the session's recognizer
 * is cancelled and destroyed. Results arriving after a generation change are dropped.
 */
class VoiceInputController(
    private val recognizer: SpeechRecognizerGateway,
    private val currentDraft: () -> String,
    private val currentSelection: () -> IntRange,
    private val onDraft: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val stopTimeoutMs: Long = 3_000,
    private val onInsertCursor: (Int) -> Unit = {},
) {
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /** The words heard so far in the active session, for the live strip; never part of the draft. */
    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private var generation = 0L
    private var stoppedGeneration: Long? = null
    private var closed = false
    private var stopTimeoutJob: Job? = null

    fun toggle(languageTag: String) {
        when (_state.value) {
            VoiceInputState.Listening -> stop()
            VoiceInputState.Stopping -> Unit
            else -> start(languageTag)
        }
    }

    fun start(languageTag: String) {
        if (closed) return
        if (_state.value == VoiceInputState.Listening || _state.value == VoiceInputState.Stopping) {
            abortSession()
            _state.value = VoiceInputState.Idle
        }
        val session = ++generation
        stoppedGeneration = null
        _liveText.value = ""
        _state.value = VoiceInputState.Listening
        recognizer.start(languageTag, object : SpeechRecognizerGateway.Listener {
            override fun onPartial(text: String) {
                if (session == generation && _state.value == VoiceInputState.Listening && text.isNotBlank()) {
                    _liveText.value = text.trim()
                }
            }

            override fun onFinal(text: String) {
                if (session != generation) return
                val finishing = _state.value == VoiceInputState.Stopping && stoppedGeneration == session
                if (_state.value != VoiceInputState.Listening && !finishing) return
                if (text.isBlank()) {
                    finishSession()
                    _state.value = if (finishing) VoiceInputState.Idle else VoiceInputState.Error(VoiceInputError.NoMatch)
                } else {
                    val inserted = insertTranscript(text)
                    finishSession()
                    // A dropped write is reported, not silent: the words never reached the draft.
                    _state.value = if (inserted) VoiceInputState.Idle else VoiceInputState.Error(VoiceInputError.SendInFlight)
                }
            }

            override fun onError(error: VoiceInputError) {
                if (session != generation) return
                when {
                    _state.value == VoiceInputState.Listening -> {
                        finishSession()
                        _state.value = VoiceInputState.Error(error)
                    }
                    _state.value == VoiceInputState.Stopping && stoppedGeneration == session -> {
                        finishSession()
                        _state.value = VoiceInputState.Idle
                    }
                }
            }
        })
    }

    fun stop() {
        if (_state.value != VoiceInputState.Listening) return
        val session = generation
        stoppedGeneration = session
        _state.value = VoiceInputState.Stopping
        stopTimeoutJob?.cancel()
        stopTimeoutJob = scope.launch {
            delay(stopTimeoutMs)
            if (stoppedGeneration == session && _state.value == VoiceInputState.Stopping) {
                finishSession()
                _state.value = VoiceInputState.Idle
            }
        }
        recognizer.stop()
    }

    /** Stop and drop every pending result; used when the draft is being sent or discarded. */
    fun cancel() {
        if (_state.value != VoiceInputState.Listening && _state.value != VoiceInputState.Stopping) return
        abortSession()
        _state.value = VoiceInputState.Idle
    }

    /** The app went to the background: end the session and free the recognizer. */
    fun release() {
        if (closed) return
        generation++
        finishSession()
        if (_state.value == VoiceInputState.Listening || _state.value == VoiceInputState.Stopping) {
            _state.value = VoiceInputState.Idle
        }
    }

    fun permissionDenied() {
        stoppedGeneration = null
        generation++
        _state.value = VoiceInputState.Error(VoiceInputError.Permission)
    }

    fun clearError() {
        if (_state.value is VoiceInputState.Error) _state.value = VoiceInputState.Idle
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        finishSession()
        _state.value = VoiceInputState.Idle
    }

    /** Ends the active session: drops callbacks, frees the recognizer, clears the strip. */
    private fun abortSession() {
        generation++
        finishSession()
    }

    private fun finishSession() {
        stoppedGeneration = null
        _liveText.value = ""
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        recognizer.cancel()
    }

    /**
     * Inserts the final transcript once, at the caret/selection the field reports right now (an
     * empty range is a caret at `first`; a non-empty range is replaced), then reports the index
     * just past the inserted words via [onInsertCursor] so the field can put its caret there. A
     * space is added on either side when the neighbouring character is not whitespace; user text is
     * never removed.
     */
    private fun insertTranscript(transcript: String): Boolean {
        val spoken = transcript.trim()
        if (spoken.isEmpty()) return true
        val draft = currentDraft()
        val selection = currentSelection()
        val start = selection.first.coerceIn(0, draft.length)
        val end = (if (selection.isEmpty()) selection.first else selection.last + 1).coerceIn(start, draft.length)
        val prefix = draft.substring(0, start)
        val suffix = draft.substring(end)
        // A space joins a word on the right, and any non-space, non-opening character on the left
        // (so "note." gains a space but "note(" and "two?" do not).
        val lead = prefix.lastOrNull()?.let { !it.isWhitespace() && it !in "([{\"'«" } == true
        val trail = suffix.firstOrNull()?.isLetterOrDigit() == true
        val withTranscript = prefix + (if (lead) " " else "") + spoken + (if (trail) " " else "") + suffix
        onDraft(withTranscript)
        // The draft writer may ignore the edit (e.g. while a send is being prepared); the caller
        // reports that instead of dropping the words silently. The caret moves only when the
        // words landed, so an ignored write cannot leave it pointing at text that was never added.
        val inserted = currentDraft() == withTranscript
        if (inserted) onInsertCursor(start + (if (lead) 1 else 0) + spoken.length)
        return inserted
    }
}
