package com.nextgentool.nudgy.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Sprint 3-4 — the voice-input seam. The chat's mic is a brain-dump
 * session (continuous listening, transcript accumulation), not a
 * single utterance — and the session's restart loop must be testable
 * without a real SpeechRecognizer (Robolectric cannot drive one).
 *
 * [VoiceAssistantManager] is the production implementation; tests pass
 * a fake that records start/stop calls and delivers canned results and
 * errors on demand. The error is a KIND, not a message string: the
 * brain-dump loop restarts on [VoiceInputError.NO_MATCH] (silence) and
 * ends the session on anything else — matching on the manager's
 * English error strings would be fragile and untranslatable.
 */
enum class VoiceInputError {
    /** Silence / no speech detected — the recognizer timed out
     *  waiting. Recoverable: the dump session restarts listening. */
    NO_MATCH,
    /** The recognizer could not reach its service (offline, server
     *  error). Ends the dump session; whatever was heard so far is
     *  still processed. */
    NETWORK,
    /** Anything else (client error, unknown code). Ends the session. */
    OTHER
}

interface VoiceInput {
    val isListening: StateFlow<Boolean>

    /**
     * Starts one recognition utterance. [onResult] fires once with the
     * best transcript (never blank); [onError] fires once with the
     * error kind. Exactly one of the two fires per start.
     */
    fun startListening(
        languageCode: String = "en-US",
        onResult: (String) -> Unit = {},
        onError: (VoiceInputError) -> Unit = {}
    )

    fun stopListening()
}
