package app.rasul.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidSpeechRecognizerGateway(private val context: Context) : SpeechRecognizerGateway {
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    /** The recognizer serving the current dictation; a fresh one per [start], dropped on [cancel]. */
    private var recognizer: SpeechRecognizer? = null

    override fun start(languageTag: String, listener: SpeechRecognizerGateway.Listener) {
        // One recognizer per dictation: a previous instance can never deliver callbacks to this listener.
        cancel()
        if (!available) {
            listener.onError(VoiceInputError.Unavailable)
            return
        }
        val speechRecognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (speechRecognizer == null) {
            listener.onError(VoiceInputError.Unavailable)
            return
        }
        recognizer = speechRecognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                listener.onError(error.toVoiceInputError())
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(VoiceInputError.NoMatch) else listener.onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            speechRecognizer.startListening(intent)
        } catch (_: SecurityException) {
            failStart(speechRecognizer, listener, VoiceInputError.Permission)
        } catch (_: IllegalStateException) {
            failStart(speechRecognizer, listener, VoiceInputError.Busy)
        } catch (_: RuntimeException) {
            failStart(speechRecognizer, listener, VoiceInputError.Unknown)
        }
    }

    override fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: RuntimeException) {
            Unit
        }
    }

    override fun cancel() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.cancel()
        } catch (_: RuntimeException) {
            Unit
        }
        try {
            current.destroy()
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun failStart(speechRecognizer: SpeechRecognizer, listener: SpeechRecognizerGateway.Listener, error: VoiceInputError) {
        if (recognizer === speechRecognizer) recognizer = null
        runCatching { speechRecognizer.destroy() }
        listener.onError(error)
    }

    private fun Int.toVoiceInputError(): VoiceInputError = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputError.NoMatch
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceInputError.Network
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceInputError.Busy
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.Permission
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceInputError.Language
        SpeechRecognizer.ERROR_AUDIO -> VoiceInputError.Microphone
        else -> VoiceInputError.Unknown
    }
}
