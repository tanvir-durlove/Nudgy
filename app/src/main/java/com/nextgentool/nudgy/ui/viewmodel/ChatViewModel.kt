package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.ai.ChatDraft
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.ChatGuardrails
import com.nextgentool.nudgy.ai.LlmFallbackHandler
import com.nextgentool.nudgy.ai.LlmFallbackResult
import com.nextgentool.nudgy.ai.PreFilterResult
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.VoiceAssistantManager
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
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
 * 3. Unhandled → the LLM fallback ([LlmFallbackHandler], S3-3): the
 *    cap chain (local cap → server counter → Gemini, central gate
 *    OFF by default) and the structured-output contract. A validated
 *    single task/event becomes a pending draft on the preview card —
 *    the same draft-then-confirm flow as the prefilter (only
 *    [confirmDraft] writes to the DB). Anything else is a plain-text
 *    reply. Offline / no-LLM-available / Gemini failure → the local
 *    fallback answer, and the local turn is refunded on failure.
 *    The thinking bubble shows the phase ("Thinking…" / "ভাবছি…")
 *    while the LLM call runs.
 *
 * Out of scope here, deliberately: the voice brain dump (continuous
 * listening) is S3-4 — [startVoice] is the v1 single utterance only.
 *
 * Sprint 3-2 (Q2 = (a)) — draft-then-confirm: the prefilter never
 * saves directly. A creation returns [PreFilterResult.PendingDraft]
 * and the draft waits in [_pendingDraft] until the user confirms,
 * edits, or discards it on the preview card. Only [confirmDraft]
 * writes to the DB, through [ClientIntentPreFilter.confirmDraft].
 * The draft lives in this ViewModel, so it survives rotation; a
 * process death loses it (the preview question stays in history as
 * plain text and the user can retype — the same accepted loss as
 * pendingClarification in the prefilter). Sending a NEW message
 * abandons the pending draft: a stale draft must never be
 * confirmable after the user moved on.
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
    private val replySoundPlayer: ReplySoundPlayer,
    // Sprint 3-3 — the LLM fallback (cap chain + central gate +
    // structured output). Constructor-injected; tests pass a handler
    // built on fakes.
    private val llmFallbackHandler: LlmFallbackHandler,
    // Sprint 3-3 — user memories for the Gemini system prompt (v1
    // passed them too). Read once per LLM call; a read failure means
    // no memories, never a failed send.
    private val memoryRepository: MemoryRepository
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

    /** S3-2 — the pending draft (Q2 = (a)). Set when the prefilter
     *  returns [PreFilterResult.PendingDraft]; cleared on Confirm,
     *  Discard, Edit-replace, or a new outgoing message. */
    private val _pendingDraft = MutableStateFlow<ChatDraft?>(null)
    val pendingDraft: StateFlow<ChatDraft?> = _pendingDraft.asStateFlow()

    /** The id of the assistant message the preview card is attached
     *  to. The card renders only on that message while a draft is
     *  pending; older preview messages stay as plain text. */
    private val _pendingDraftMessageId = MutableStateFlow<String?>(null)
    val pendingDraftMessageId: StateFlow<String?> = _pendingDraftMessageId.asStateFlow()

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
        // S3-2: a new message abandons any pending draft.
        _pendingDraft.value = null
        _pendingDraftMessageId.value = null
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
                        // The history BEFORE this message: the LLM must
                        // not see its own prompt twice (once as history,
                        // once as the current message). Read BEFORE the
                        // add — the Room flow updates asynchronously, so
                        // reading after would race.
                        val historyBefore = messages.value.takeLast(6)
                            .map { it.text to it.senderIsUser }
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
                            // S3-2: a creation parsed but NOT saved — the
                            // draft waits for Confirm / Edit / Discard.
                            is PreFilterResult.PendingDraft -> {
                                _pendingDraft.value = result.draft
                                val messageId = postAssistantMessage(
                                    text = result.previewText,
                                    actionType = ChatActionType.DRAFT_PREVIEW,
                                    quickSuggestions = result.quickSuggestions
                                )
                                _pendingDraftMessageId.value = messageId
                            }
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
                            // the local rules cannot parse) → the LLM
                            // fallback (S3-3): cap chain → Gemini →
                            // validated draft or plain-text reply.
                            is PreFilterResult.Unhandled ->
                                handleLlmFallback(trimmed, historyBefore)
                            // The prefilter itself failed: the old
                            // behaviour — a local fallback answer, no
                            // LLM call, no turn spent.
                            null -> postAssistantMessage(
                                llmFallbackHandler.localFallbackReply(trimmed)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // §4.7 — the chat never dies on a send: a failed send
                // still answers, instead of leaving a spinner forever.
                runCatching { postAssistantMessage("Something went wrong — please try again.") }
            } finally {
                _isThinking.value = false
                // Back to the default phase text: the LLM phase
                // ("Thinking…" / "ভাবছি…") applies to that send only.
                _thinkingText.value = "Nudgy is thinking..."
            }
        }
    }

    /**
     * Sprint 3-3 — the Unhandled branch: the LLM fallback. The thinking
     * bubble shows the phase while the cap chain / Gemini call runs.
     * A validated draft becomes the SAME pending draft the prefilter
     * produces (preview card → Confirm / Edit / Discard); a reply or a
     * capped answer is posted as a plain assistant message. The DB is
     * never touched here — only [confirmDraft] writes.
     */
    private suspend fun handleLlmFallback(
        prompt: String,
        historyBefore: List<Pair<String, Boolean>>
    ) {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        _thinkingText.value = if (isBengali) "ভাবছি…" else "Thinking…"
        val memories = runCatching {
            memoryRepository.getMemories(userId).map { it.content }
        }.getOrDefault(emptyList())
        when (val llm = llmFallbackHandler.handleUnhandled(userId, prompt, historyBefore, memories)) {
            is LlmFallbackResult.Reply -> postAssistantMessage(llm.text)
            is LlmFallbackResult.Capped -> postAssistantMessage(llm.text)
            is LlmFallbackResult.Draft -> {
                _pendingDraft.value = llm.draft
                val messageId = postAssistantMessage(
                    text = llm.previewText,
                    actionType = ChatActionType.DRAFT_PREVIEW
                )
                _pendingDraftMessageId.value = messageId
            }
        }
    }

    /** Focus proposal "Block Time" button (ChatScreen action card 2).
     *  Writes the focus event, then marks the proposal message done so
     *  the card flips to "Scheduled". Unchanged by S3-2: the proposal
     *  card IS the preview (it shows the slot) and this button IS the
     *  confirm — no second confirmation is needed for it. */
    fun onBlockFocusTime(startMillis: Long, endMillis: Long, title: String, messageId: String) {
        viewModelScope.launch {
            runCatching { calendarRepository.blockFocusTime(userId, startMillis, endMillis, title) }
            runCatching { chatRepository.markFocusActionCompleted(messageId) }
        }
    }

    /**
     * S3-2 — Confirm on the preview card: the ONLY path that saves a
     * draft. The draft is cleared FIRST (synchronously, so a double
     * tap can never save twice), then the prefilter writes and the
     * receipt message (with the created task/event card) is posted.
     * A confirm with no pending draft is a no-op.
     */
    fun confirmDraft() {
        val draft = _pendingDraft.value ?: return
        _pendingDraft.value = null
        _pendingDraftMessageId.value = null
        viewModelScope.launch {
            try {
                val result = runCatching { preFilter.confirmDraft(userId, draft) }.getOrNull()
                if (result is PreFilterResult.Handled) {
                    postAssistantMessage(
                        text = result.responseText,
                        actionType = result.actionType,
                        task = result.createdTask,
                        event = result.createdEvent,
                        focusStartMillis = result.focusStartMillis,
                        focusEndMillis = result.focusEndMillis,
                        focusDurationMinutes = result.focusDurationMinutes,
                        quickSuggestions = result.quickSuggestions
                    )
                } else {
                    postAssistantMessage("Something went wrong — please try again.")
                }
            } catch (e: Exception) {
                runCatching { postAssistantMessage("Something went wrong — please try again.") }
            }
        }
    }

    /**
     * S3-2 — Discard on the preview card: nothing is created, and a
     * short note says so. A discard with no pending draft is a no-op.
     */
    fun discardDraft() {
        val draft = _pendingDraft.value ?: return
        _pendingDraft.value = null
        _pendingDraftMessageId.value = null
        viewModelScope.launch {
            postAssistantMessage(
                if (draft.isBengali) "ঠিক আছে, কিছু তৈরি করিনি — ড্রাফটটা বাতিল করেছি।"
                else "No problem — I discarded the draft, nothing was created."
            )
        }
    }

    /**
     * S3-2 — the preview card's Edit saves changes back into the
     * pending draft (title / time). Nothing is written to the DB; the
     * card re-renders with the edited values and Confirm saves those.
     */
    fun editDraft(updated: ChatDraft) {
        if (_pendingDraft.value == null) return
        _pendingDraft.value = updated
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

    /** Stores the assistant reply and plays the reply sound (§4.4).
     *  Returns the message id so the caller can attach live state to
     *  it (S3-2: the preview card attaches to the draft message). */
    private suspend fun postAssistantMessage(
        text: String,
        actionType: ChatActionType = ChatActionType.NONE,
        task: TaskEntity? = null,
        event: CalendarEventEntity? = null,
        focusStartMillis: Long? = null,
        focusEndMillis: Long? = null,
        focusDurationMinutes: Int = 45,
        quickSuggestions: List<String> = emptyList()
    ): String {
        val messageId = UUID.randomUUID().toString()
        chatRepository.addMessage(
            userId,
            ChatMessage(
                id = messageId,
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
        return messageId
    }
}
