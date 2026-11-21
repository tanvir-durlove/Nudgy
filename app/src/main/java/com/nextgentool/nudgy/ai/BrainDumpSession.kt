package com.nextgentool.nudgy.ai

/**
 * Sprint 3-4 — the brain-dump session state machine. Pure Kotlin, no
 * Android: the SpeechRecognizer itself lives in [VoiceAssistantManager]
 * (behind the [VoiceInput] seam), this class only owns WHAT the
 * session heard and WHEN it ends.
 *
 * Lifecycle (driven by ChatViewModel):
 * - [start] clears the transcript and opens the session.
 * - [onUtterance] appends one recognised utterance. Utterances are
 *   joined with ". " so the splitter sees a sentence boundary even
 *   when the speaker did not pause with punctuation ("buy milk" +
 *   "call mom at 3pm" → "buy milk. call mom at 3pm").
 * - [noteSilentRestart] counts silence restarts (ERROR_NO_MATCH /
 *   speech timeout: the user paused). It returns true while the
 *   session should keep listening; after [MAX_SILENT_RESTARTS] it
 *   returns false — the user has walked away, so the session ends
 *   and whatever was heard is processed instead of listening forever.
 * - [stop] ends the session and returns the accumulated transcript
 *   (blank when nothing was heard).
 *
 * No-ops when inactive: an utterance or error arriving after the
 * session ended (a late recognizer callback) is dropped, never
 * appended to the next session.
 */
class BrainDumpSession {

    companion object {
        /** Silence restarts before the session gives up on its own. */
        const val MAX_SILENT_RESTARTS = 5
    }

    var isActive: Boolean = false
        private set

    /** The accumulated transcript, utterances joined with ". ". */
    var transcript: String = ""
        private set

    var utteranceCount: Int = 0
        private set

    private var silentRestarts: Int = 0

    fun start() {
        isActive = true
        transcript = ""
        utteranceCount = 0
        silentRestarts = 0
    }

    /** Appends one utterance. Dropped when the session is not active. */
    fun onUtterance(text: String) {
        if (!isActive) return
        val cleaned = text.trim().trimEnd('.', '!', '?', '।')
        if (cleaned.isBlank()) return
        transcript = if (transcript.isBlank()) cleaned else "$transcript. $cleaned"
        utteranceCount++
        // A heard utterance resets the silence count: the user is
        // still there, a later pause gets a fresh allowance.
        silentRestarts = 0
    }

    /**
     * Records one silence restart. Returns true while the session
     * should restart listening, false when the silence allowance is
     * spent and the session must end. No-op (false) when inactive.
     */
    fun noteSilentRestart(): Boolean {
        if (!isActive) return false
        silentRestarts++
        return silentRestarts <= MAX_SILENT_RESTARTS
    }

    /** Ends the session and returns the accumulated transcript. */
    fun stop(): String {
        isActive = false
        return transcript
    }
}
