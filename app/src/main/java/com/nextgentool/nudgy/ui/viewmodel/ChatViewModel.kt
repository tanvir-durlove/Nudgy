package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.ChatGuardrails
import com.nextgentool.nudgy.ai.PreFilterResult
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.ui.screens.ChatActionType
import com.nextgentool.nudgy.ui.screens.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Sprint 3-0 — the Chat destination owns its state here; the screen
 * only renders it (same pattern as CalendarViewModel).
 *
 * Send pipeline (§8, S3-0 half):
 * 1. Guardrails FIRST ([ChatGuardrails.check]) — before the user's
 *    message is stored, before the prefilter, before anything is
 *    created. A refused message creates nothing and stores nothing
 *    (not even the user's own message — it must never reach the
 *    cloud), and costs no turn.
 * 2. Prefilter ([ClientIntentPreFilter.tryHandle]) — the single local
 *    instance, owned by this ViewModel (survives rotation). Its
 *    pending-clarification state is in-memory (@Volatile): a process
 *    death loses it, the question stays in history, and the user can
 *    retype — documented in the spec, accepted.
 * 3. Unhandled → a LOCAL fallback answer. No LLM in S3-0: GeminiClient
 *    stays dormant, its wiring + cap chain + central gate is S3-3's
 *    job. The fallback never crashes and never spends a turn.
 *
 * Out of scope here, deliberately: confirm-before-save is S3-2, so the
 * prefilter's current immediate-save behaviour stays as-is in S3-0
 * (createFromParsed saves directly); the Quick Chat sheet + approved
 * restyle is S3-1; voice brain dump (continuous listening) is S3-4 —
 * [startVoice] is the v1 single utterance only.
 *
 * Sound (§4.4): every assistant reply plays the short notification
 * sound ([ReplySoundPlayer]). [speak] is NEVER called from the chat
 * path — [isSpeaking] is a constant false, so the mascot never shows
 * its TALKING mood in chat. TTS stays dormant.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val preFilter: ClientIntentPreFilter,
    private val taskRepository: TaskRepository,
    private val calendarRepository: CalendarRepository,
    private val voiceAssistantManager: VoiceAssistantManager,
    private val replySoundPlayer: ReplySoundPlayer
) : ViewModel() {

    private val userId: String = taskRepository.currentUserId()

    /** The screen's `user` param. ChatScreen does not render it today;
     *  the value is kept honest anyway: no display name is known before
     *  the Sprint 6 auth flow, so no name is invented here. */
    val user: NudgyUser = NudgyUser(
        uid = userId,
        displayName = "",
        email = "",
        isGuest = userId == AuthManager.GUEST_USER_ID
    )

    val messages: StateFlow<List<ChatMessage>> =
        chatRepository.observeMessages(userId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** §4.4 — constant false in S3-0. The chat never speaks, so the
     *  mascot never shows TALKING. (VoiceAssistantManager.isSpeaking is
     *  deliberately NOT exposed: it would only ever become true if
     *  speak() were called, and nothing on the chat path may call it.) */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** Thinking phase text for the screen's thinking bubble. S3-0 keeps
     *  the single default; S3-3 sets phases ("ভাবছি…", …) per step. */
    private val _thinkingText = MutableStateFlow("Nudgy is thinking...")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    val isListening: StateFlow<Boolean> = voiceAssistantManager.isListening

    init {
        // Seed the welcome message once (no-op when history exists).
        // displayName is "" until the Sprint 6 auth flow knows the
        // user's name — ChatRepository greets neutrally ("Hi! …") with
        // no name, never a hardcoded one.
        viewModelScope.launch {
            runCatching { chatRepository.checkAndSeedWelcomeMessage(userId, "") }
        }
    }

    /**
     * The one send entry point (screen send button, quick-prompt pills,
     * suggestion chips, voice results — all funnel here).
     *
     * C1 backstop: blank input and a send while a reply is pending are
     * ignored — the screen disables its send button the same way, this
     * is the guard underneath it. A second prompt must never start a
     * second handleUserChat run (duplicate tasks / double-spent turns).
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isThinking.value) return
        viewModelScope.launch {
            _isThinking.value = true
            try {
                when (val guard = ChatGuardrails.check(trimmed)) {
                    is ChatGuardrails.GuardResult.Refused -> {
                        // Refused: nothing is created, nothing is stored
                        // — the user's own message stays out of history.
                        postAssistantMessage(guard.replyText)
                    }
                    ChatGuardrails.GuardResult.Allowed -> {
                        addMessage(senderIsUser = true, text = trimmed)
                        val result = runCatching {
                            val today = startOfDayMillis(System.currentTimeMillis())
                            val todayTasks = taskRepository.getAllTasksOnce(userId)
                                .filter { it.dueDateEpochMillis in today..endOfDayMillis(today) }
                            val events = calendarRepository
                                .observeEventsForDay(userId, today).first()
                            preFilter.tryHandle(userId, trimmed, todayTasks, events)
                        }.getOrNull()
                        when (result) {
                            is PreFilterResult.Handled -> postAssistantMessage(
                                text = result.responseText,
                                actionType = result.actionType,
                                task = result.createdTask,
                                event = result.createdEvent,
                                focusStartMillis = result.focusStartMillis,
                                focusEndMillis = result.focusEndMillis,
                                focusDurationMinutes = result.focusDurationMinutes,
                                quickSuggestions = result.quickSuggestions
                            )
                            // Unhandled (small talk, questions, anything
                            // the local rules cannot parse) — and any
                            // prefilter failure: a local fallback answer.
                            // No LLM call, no crash, no turn spent.
                            else -> postAssistantMessage(localFallbackReply(trimmed))
                        }
                    }
                }
            } catch (e: Exception) {
                // §4.7 — the chat never dies on a send: a failed send
                // still answers, instead of leaving a spinner forever.
                runCatching { postAssistantMessage("Something went wrong — please try again.") }
            } finally {
                _isThinking.value = false
            }
        }
    }

    /** Focus proposal "Block Time" button (ChatScreen action card 2).
     *  Writes the focus event, then marks the proposal message done so
     *  the card flips to "Scheduled". */
    fun onBlockFocusTime(startMillis: Long, endMillis: Long, title: String, messageId: String) {
        viewModelScope.launch {
            runCatching { calendarRepository.blockFocusTime(userId, startMillis, endMillis, title) }
            runCatching { chatRepository.markFocusActionCompleted(messageId) }
        }
    }

    /**
     * Single-utterance voice input (v1 behaviour): one utterance, then
     * the recognised text goes through [sendMessage] like typed text.
     * Continuous listening / brain-dump restart loop is S3-4.
     */
    fun startVoice() {
        voiceAssistantManager.startListening(
            languageCode = "en-US",
            onResult = { sendMessage(it) },
            onError = { error ->
                viewModelScope.launch { postAssistantMessage(error) }
            }
        )
    }

    fun stopVoice() {
        voiceAssistantManager.stopListening()
    }

    private suspend fun addMessage(senderIsUser: Boolean, text: String) {
        chatRepository.addMessage(
            userId,
            ChatMessage(
                id = UUID.randomUUID().toString(),
                senderIsUser = senderIsUser,
                text = text
            )
        )
    }

    /** Stores the assistant reply and plays the reply sound (§4.4). */
    private suspend fun postAssistantMessage(
        text: String,
        actionType: ChatActionType = ChatActionType.NONE,
        task: TaskEntity? = null,
        event: CalendarEventEntity? = null,
        focusStartMillis: Long? = null,
        focusEndMillis: Long? = null,
        focusDurationMinutes: Int = 45,
        quickSuggestions: List<String> = emptyList()
    ) {
        chatRepository.addMessage(
            userId,
            ChatMessage(
                id = UUID.randomUUID().toString(),
                senderIsUser = false,
                text = text,
                actionType = actionType,
                associatedTask = task,
                associatedEvent = event,
                focusStartMillis = focusStartMillis,
                focusEndMillis = focusEndMillis,
                focusDurationMinutes = focusDurationMinutes,
                quickSuggestions = quickSuggestions
            )
        )
        replySoundPlayer.playReplySound()
    }

    /** Local fallback for Unhandled input — in the input's language,
     *  pointing at what the chat can actually do. Never a guess, never
     *  a creation, never an LLM call. */
    private fun localFallbackReply(prompt: String): String =
        if (prompt.any { it in '\u0980'..'\u09FF' }) {
            "এটা ঠিক বুঝতে পারিনি — আমাকে একটা কাজ বানাতে বলো, কিছু শিডিউল করতে বলো, বা দিনের প্ল্যান চাও।"
        } else {
            "I didn't quite catch that — try asking me to create a task, schedule something, or plan your day."
        }
}
