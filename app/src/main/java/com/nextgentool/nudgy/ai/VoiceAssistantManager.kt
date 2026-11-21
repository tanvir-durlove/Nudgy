package com.nextgentool.nudgy.ai

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Sprint 3-4 — implements [VoiceInput]: the chat's mic is a brain-dump
 * session (continuous listening), and the restart loop must be testable
 * without a real SpeechRecognizer. The recognizer error is reported as
 * a [VoiceInputError] KIND (not a message string): the session
 * restarts on silence (NO_MATCH) and ends on anything else.
 */
class VoiceAssistantManager(private val context: Context) : TextToSpeech.OnInitListener, ReplySoundPlayer, VoiceInput {
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TTS initialization failed: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }
            })
        }
    }

    /**
     * Start Speech Recognition (English-only by default; the caller
     * passes the language — "bn-BD" from the S3-7 toggle).
     *
     * One utterance per start: the recognizer stops after the result
     * (or the error). The caller restarts it for continuous listening
     * (the brain-dump session in ChatViewModel). Exactly one of
     * [onResult] / [onError] fires per start.
     */
    override fun startListening(
        languageCode: String,
        onResult: (String) -> Unit,
        onError: (VoiceInputError) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                startListening(languageCode, onResult, onError)
            }
            return
        }

        stopSpeaking()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "TTS stop: ${e.message}")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(VoiceInputError.OTHER)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    // Sprint 3-4 — the kind, not the message: the
                    // brain-dump loop restarts on silence and ends on
                    // anything else. The user-facing wording lives in
                    // the caller (ChatViewModel), in the dump's
                    // language — never an English string matched here.
                    val kind = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputError.NO_MATCH
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_SERVER -> VoiceInputError.NETWORK
                        else -> VoiceInputError.OTHER
                    }
                    Log.d(TAG, "Speech recognition error code: $error")
                    onError(kind)
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        _recognizedText.value = text
                        onResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Nudgy...")
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _isListening.value = false
            onError(VoiceInputError.OTHER)
        }
    }

    override fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                stopListening()
            }
            return
        }

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.d(TAG, "Stop listening error: ${e.message}")
        } finally {
            _isListening.value = false
        }
    }

    /**
     * Speak response in English (voice language is English-only).
     */
    fun speak(text: String) {
        if (!isTtsInitialized || tts == null) return

        val available = tts?.setLanguage(Locale.US)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.US
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nudgy_speech_${System.currentTimeMillis()}")
    }

    /**
     * Short reply sound for chat text replies (PR #35): the system default
     * notification sound, played once. It runs on the notification stream,
     * so silent / Do Not Disturb are respected, and a ~1-second ting can
     * never linger in the background the way TTS did. Never throws.
     */
    override fun playReplySound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Could not play chat reply sound", e)
        }
    }

    fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        _isSpeaking.value = false
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { destroy() }
            return
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.d(TAG, "SpeechRecognizer destroy error: ${e.message}")
        }
        speechRecognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.d(TAG, "TTS shutdown error: ${e.message}")
        }
        tts = null
    }

    companion object {
        private const val TAG = "VoiceAssistant"
    }
}
