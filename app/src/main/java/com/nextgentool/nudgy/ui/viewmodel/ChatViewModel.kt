package com.nextgentool.nudgy.ui.viewmodel

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.ads.ChatCapState
import com.nextgentool.nudgy.ads.ChatTurnCap
import com.nextgentool.nudgy.ads.RewardedAdGateway
import com.nextgentool.nudgy.ai.BrainDumpSession
import com.nextgentool.nudgy.ai.BrainDumpSplitter
import com.nextgentool.nudgy.ai.ChatDraft
import com.nextgentool.nudgy.ai.ClientIntentPreFilter
import com.nextgentool.nudgy.ai.ChatGuardrails
import com.nextgentool.nudgy.ai.DraftKind
import com.nextgentool.nudgy.ai.LlmConfigProvider
import com.nextgentool.nudgy.ai.LlmFallbackHandler
import com.nextgentool.nudgy.ai.LlmFallbackResult
import com.nextgentool.nudgy.ai.PreFilterResult
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.SplitCommand
import com.nextgentool.nudgy.ai.SplitCommandParser
import com.nextgentool.nudgy.ai.SplitParent
import com.nextgentool.nudgy.ai.VoiceInput
import com.nextgentool.nudgy.ai.VoiceInputError
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Sprint 3-4 — the voice brain dump (feature 19, Q3 = (a), Q9 = (a)):
 * the mic is a TOGGLE that opens a continuous listening session. The
 * recognizer stops after every utterance, so the session RESTARTS it
 * until the user taps Stop (or silence gives up after
 * [BrainDumpSession.MAX_SILENT_RESTARTS] restarts). Utterances
 * accumulate in the [BrainDumpSession] transcript; on Stop the
 * transcript is split by [BrainDumpSplitter] (sentence/line boundaries
 * + time-token conjunctions) and each piece goes through the prefilter
 * on its own. Pieces the local path cannot parse go to the LLM
 * fallback — only while it is available (Q1 = (a): BYOK or the central
 * gate open; central is OFF by default, so usually that means the
 * piece lands in the clarifying question instead). The result is ONE
 * combined preview card: all split drafts listed, one Confirm-all,
 * per-item remove before confirming. Discard creates nothing. Zero
 * drafts → the chat asks a clarifying question, creates nothing
 * silently. NOTHING is written to the DB before Confirm — only
 * [confirmBrainDump] writes, through [ClientIntentPreFilter.confirmDraft]
 * per draft, exactly like [confirmDraft]. Offline: local split + local
 * parse, no cap consumed. Real speech recognition is device-only; the
 * session state machine and the split/preview logic are tested here
 * with a fake [VoiceInput], the recognizer itself is not.
 *
 * Sprint 3-4 — the rewarded entry (spec §10.14): [chatCapState] is the
 * live [ChatTurnCap] snapshot (Remote Config limits via
 * [LlmConfigProvider]); the Chat route feeds it to the screen, so the
 * "+1 answer" offer appears when the 5 free turns are spent.
 * [watchRewardedAd] shows the preloaded ad through the
 * [RewardedAdGateway] and grants the +1 turn ONLY inside the reward
 * callback. Known gap (see RewardedAdGateway): NudgyAds.initialize is
 * never called in the rebuild yet, so no ad is preloaded until the
 * ads-init track lands — the offer, the grant path and the retry path
 * are wired and work the moment it does.
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
 * Sprint 3-5 (feature 20) — break a big task into subtasks. A split
 * command ("break this down" / "এই টাস্কটা ভেঙে দাও", optionally with
 * a title) is detected LOCALLY by [SplitCommandParser] before the
 * prefilter runs — the prefilter has no split branch, and the target
 * task must be resolved before the LLM is called (the split needs a
 * parent to attach to). Target resolution is local too: an exact
 * title match among the user's top-level tasks (one match → split;
 * none → "couldn't find"; several → list them and ask); a bare
 * "break this down" with no title asks which task — never a guess.
 * The split itself runs through [LlmFallbackHandler.handleSplit]
 * (the S3-3 cap chain + central gate, unchanged): a validated
 * `split_task` becomes a SUBTASK_SPLIT draft on the SAME preview
 * card as any other draft — only [confirmDraft] writes, through
 * [ClientIntentPreFilter.confirmDraft] → createSubtask per title.
 * A subtask can never be split (one level only): the title match
 * excludes subtasks, [startSplit] refuses a subtask id, and
 * confirmDraft re-checks — three backstops. LLM unavailable →
 * the handler's plain message + the manual path (detail sheet →
 * "Add subtask"), no turn spent. The detail sheet's "Break down"
 * button calls [startSplit] with the open task's id and navigates
 * here; the split starts at once, no typing needed.
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
    // Sprint 3-4 — the voice seam: the ViewModel depends on
    // [VoiceInput], not the concrete manager, so the brain-dump
    // restart loop is testable with a fake recognizer.
    private val voiceInput: VoiceInput,
    private val replySoundPlayer: ReplySoundPlayer,
    // Sprint 3-3 — the LLM fallback (cap chain + central gate +
    // structured output). Constructor-injected; tests pass a handler
    // built on fakes.
    private val llmFallbackHandler: LlmFallbackHandler,
    // Sprint 3-3 — user memories for the Gemini system prompt (v1
    // passed them too). Read once per LLM call; a read failure means
    // no memories, never a failed send.
    private val memoryRepository: MemoryRepository,
    // Sprint 3-4 — the rewarded entry: the cap snapshot needs the
    // application context (ChatTurnCap's SharedPreferences) and the
    // live Remote Config limits; the ad itself goes through the
    // [RewardedAdGateway] seam (fake in tests, NudgyAds in prod).
    @ApplicationContext private val context: Context,
    private val llmConfigProvider: LlmConfigProvider,
    private val rewardedAdGateway: RewardedAdGateway
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
     *  mascot never shows TALKING. (The voice manager's isSpeaking is
     *  deliberately NOT exposed: it would only ever become true if
     *  speak() were called, and nothing on the chat path may call it.) */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** Thinking phase text for the screen's thinking bubble. S3-0 keeps
     *  the single default; S3-3 sets phases ("ভাবছি…", …) per step. */
    private val _thinkingText = MutableStateFlow("Nudgy is thinking...")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    val isListening: StateFlow<Boolean> = voiceInput.isListening

    /** Sprint 3-4 — true while a brain-dump session is open. The mic
     *  button shows Stop while this is true even between utterances
     *  (the recognizer's [isListening] flickers false while the
     *  session restarts it). */
    private val _isBrainDumpActive = MutableStateFlow(false)
    val isBrainDumpActive: StateFlow<Boolean> = _isBrainDumpActive.asStateFlow()

    /** Sprint 3-4 — the transcript heard so far in the open session
     *  (the listening banner shows it, so the user sees what was
     *  heard). Empty outside a session. */
    private val _brainDumpTranscript = MutableStateFlow("")
    val brainDumpTranscript: StateFlow<String> = _brainDumpTranscript.asStateFlow()

    /** Sprint 3-4 — the pending brain-dump drafts (Q9 = (a)): one
     *  combined preview card, Confirm-all saves every item,
     *  per-item remove drops one. Mutually exclusive with
     *  [_pendingDraft]: a new message clears both. */
    private val _pendingBrainDumpDrafts = MutableStateFlow<List<ChatDraft>>(emptyList())
    val pendingBrainDumpDrafts: StateFlow<List<ChatDraft>> = _pendingBrainDumpDrafts.asStateFlow()

    /** The id of the assistant message the combined preview card is
     *  attached to (same pattern as [_pendingDraftMessageId]). */
    private val _pendingBrainDumpMessageId = MutableStateFlow<String?>(null)
    val pendingBrainDumpMessageId: StateFlow<String?> = _pendingBrainDumpMessageId.asStateFlow()

    /** Sprint 3-4 — the live cap snapshot for the rewarded entry
     *  (spec §10.14). Null until the first [refreshCapState]; the
     *  screen hides the offer while null. Refreshed after every send
     *  (an LLM turn may have been spent) and after a rewarded grant. */
    private val _chatCapState = MutableStateFlow<ChatCapState?>(null)
    val chatCapState: StateFlow<ChatCapState?> = _chatCapState.asStateFlow()

    /** Sprint 3-4 — the rewarded ad state, delegated to the gateway
     *  (NudgyAds in prod). The screen enables the "+1 answer" button
     *  while [rewardedAdReady], and offers a retry while
     *  [rewardedLoadError] is set. */
    val rewardedAdReady: StateFlow<Boolean> = rewardedAdGateway.isReady
    val rewardedLoadError: StateFlow<String?> = rewardedAdGateway.loadError

    /** The brain-dump session state machine (pure, no Android). One
     *  instance per ViewModel; [BrainDumpSession.start] resets it. */
    private val brainDumpSession = BrainDumpSession()

    /** The language the open dump session listens in ("en-US" today;
     *  the S3-7 toggle passes "bn-BD"). Decides the dump's reply
     *  language. Cleared when the session ends. */
    private var brainDumpLanguageCode: String = "en-US"

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
        // Sprint 3-4 — the rewarded entry starts from a fresh snapshot.
        refreshCapState()
    }

    /** Sprint 3-4 — re-reads the cap counters (Remote Config limits).
     *  Cheap (SharedPreferences); called after every send and after a
     *  rewarded grant, so the offer never shows stale counts. */
    fun refreshCapState() {
        val config = runCatching { llmConfigProvider.current() }.getOrNull() ?: return
        _chatCapState.value = ChatTurnCap.snapshot(
            context, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
        )
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
        // S3-4: ... and any pending brain-dump card.
        _pendingBrainDumpDrafts.value = emptyList()
        _pendingBrainDumpMessageId.value = null
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
                        // Sprint 3-5 — a split command is detected
                        // locally and never reaches the prefilter (it
                        // has no split branch): the target task is
                        // resolved first, then the LLM splits it. A
                        // pending clarification wins over the split
                        // check — the user's words may be its answer.
                        val splitCommand =
                            if (preFilter.hasPendingClarification()) null
                            else SplitCommandParser.parse(trimmed)
                        if (splitCommand != null) {
                            handleSplitCommand(splitCommand, trimmed, historyBefore)
                        } else {
                            handlePrefilterMessage(trimmed, historyBefore)
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
                // S3-4 — an LLM turn may have been spent: the cap
                // snapshot (and the rewarded offer) must not go stale.
                refreshCapState()
            }
        }
    }

    /**
     * The prefilter half of a send (extracted from [sendMessage] for
     * the Sprint 3-5 split branch — behaviour unchanged): the local
     * parse first; a creation waits as a pending draft, a handled
     * command answers at once, Unhandled goes to the LLM fallback.
     */
    private suspend fun handlePrefilterMessage(
        trimmed: String,
        historyBefore: List<Pair<String, Boolean>>
    ) {
        val result = runCatching {
            val today = startOfDayMillis(System.currentTimeMillis())
            val todayTasks = taskRepository.getAllTasksOnce(userId)
                .filter { it.dueDateEpochMillis in today..endOfDayMillis(today) }
            val events = calendarRepository
                .observeEventsForDay(userId, today).first()
            preFilter.tryHandle(userId, trimmed, todayTasks, events)
        }.getOrNull()
        when (result) {
            // S3-2: a creation parsed but NOT saved — the draft waits
            // for Confirm / Edit / Discard.
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
            // Unhandled (small talk, questions, anything the local
            // rules cannot parse) → the LLM fallback (S3-3): cap chain
            // → Gemini → validated draft or plain-text reply.
            is PreFilterResult.Unhandled ->
                handleLlmFallback(trimmed, historyBefore)
            // The prefilter itself failed: the old behaviour — a local
            // fallback answer, no LLM call, no turn spent.
            null -> postAssistantMessage(
                llmFallbackHandler.localFallbackReply(trimmed)
            )
        }
    }

    /**
     * Sprint 3-5 — a typed split command. The target is resolved
     * LOCALLY (no LLM call, no turn) before anything is split:
     * - a named title: exactly one top-level match → split it; none →
     *   "couldn't find"; several → list them and ask which one.
     * - no title ("break this down"): ask which task — never guess.
     * - a title that only matches subtasks → the one-level refusal
     *   (a subtask has no subtasks of its own).
     * Nothing is created here — [runSplitFor] only ever produces a
     * preview draft, and only Confirm saves it.
     */
    private suspend fun handleSplitCommand(
        split: SplitCommand,
        prompt: String,
        historyBefore: List<Pair<String, Boolean>>
    ) {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val title = split.targetTitle
        if (title == null) {
            postAssistantMessage(
                if (isBengali) "কোন টাস্কটা ভেঙে দেব? টাইটেলসহ বলো — যেমন \"পার্টির আয়োজনটা ভেঙে দাও\"।"
                else "Which task should I break down? Say it with the title — e.g. \"break down Plan party\"."
            )
            return
        }
        val allTasks = runCatching { taskRepository.getAllTasksOnce(userId) }
            .getOrDefault(emptyList())
        val topLevel = allTasks.filter {
            it.parentTaskId == null && !it.isCompleted &&
                it.title.contains(title, ignoreCase = true)
        }
        when {
            topLevel.size == 1 -> runSplitFor(topLevel.single(), prompt, historyBefore)
            topLevel.size > 1 -> {
                val listed = topLevel.take(5).joinToString("\n") { "• ${it.title}" }
                postAssistantMessage(
                    if (isBengali) {
                        "\"${title}\" নামে ${topLevel.size}টা টাস্ক পেয়েছি:\n$listed\nকোনটা ভেঙে দেব?"
                    } else {
                        "I found ${topLevel.size} tasks matching \"$title\":\n$listed\nWhich one should I break down?"
                    }
                )
            }
            else -> {
                val subtaskMatch = allTasks.any {
                    it.parentTaskId != null && it.title.contains(title, ignoreCase = true)
                }
                postAssistantMessage(
                    when {
                        subtaskMatch -> if (isBengali) {
                            "\"${title}\" নিজেই একটা subtask — subtask-কে আর ভাগ করা যায় না।"
                        } else {
                            "\"$title\" is already a subtask — subtasks can't be split further."
                        }
                        else -> if (isBengali) {
                            "\"${title}\" নামে কোনো টাস্ক পেলাম না — টাইটেলটা ঠিক করে আবার বলো।"
                        } else {
                            "I couldn't find a task called \"$title\" — check the title and try again."
                        }
                    }
                )
            }
        }
    }

    /**
     * Sprint 3-5 — the LLM split for a KNOWN parent (from a typed
     * command or the detail sheet). The thinking phase shows while the
     * cap chain / Gemini call runs; a validated split becomes the SAME
     * pending draft any other creation produces (preview card →
     * Confirm / Discard / per-item remove); a reply or a capped
     * answer is posted as a plain assistant message. The DB is never
     * touched here — only [confirmDraft] writes.
     */
    private suspend fun runSplitFor(
        parent: TaskEntity,
        prompt: String,
        historyBefore: List<Pair<String, Boolean>>
    ) {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
            || parent.title.any { it in '\u0980'..'\u09FF' }
        _thinkingText.value = if (isBengali) "ভাবছি…" else "Thinking…"
        val memories = runCatching {
            memoryRepository.getMemories(userId).map { it.content }
        }.getOrDefault(emptyList())
        when (val llm = llmFallbackHandler.handleSplit(
            userId = userId,
            parent = SplitParent(parent.id, parent.title, parent.dueDateEpochMillis),
            prompt = prompt,
            recentHistory = historyBefore,
            userMemories = memories
        )) {
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

    /**
     * Sprint 3-5 — the detail sheet's "Break down" button. The split
     * starts at once for the open task (no typing needed) and the
     * caller navigates here, where the preview card appears. A second
     * tap while a split is running is a no-op (same C1 backstop as a
     * send while thinking). A missing task or a subtask id answers
     * with a plain message — no LLM call, no turn.
     */
    fun startSplit(taskId: String) {
        if (_isThinking.value) return
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val parent = runCatching { taskRepository.getTaskById(taskId) }.getOrNull()
                when {
                    parent == null -> postAssistantMessage(
                        "I couldn't find that task anymore."
                    )
                    parent.parentTaskId != null -> postAssistantMessage(
                        if (parent.title.any { it in '\u0980'..'\u09FF' }) {
                            "এটা নিজেই একটা subtask — subtask-কে আর ভাগ করা যায় না।"
                        } else {
                            "That's already a subtask — subtasks can't be split further."
                        }
                    )
                    else -> {
                        val historyBefore = messages.value.takeLast(6)
                            .map { it.text to it.senderIsUser }
                        runSplitFor(parent, "Break down \"${parent.title}\"", historyBefore)
                    }
                }
            } catch (e: Exception) {
                runCatching { postAssistantMessage("Something went wrong — please try again.") }
            } finally {
                _isThinking.value = false
                _thinkingText.value = "Nudgy is thinking..."
                refreshCapState()
            }
        }
    }

    /**
     * Sprint 3-5 — per-item remove on the split preview card: drops
     * one subtask before Confirm (the brain-dump card's Q9 pattern,
     * on the single pending draft). Removing the last item clears the
     * card and posts the same note as Discard — nothing was created.
     * A remove with no split draft pending is a no-op.
     */
    fun removeSplitSubtask(index: Int) {
        val draft = _pendingDraft.value ?: return
        if (draft.kind != DraftKind.SUBTASK_SPLIT) return
        if (index !in draft.subtaskTitles.indices) return
        val remaining = draft.subtaskTitles.toMutableList().also { it.removeAt(index) }
        if (remaining.isEmpty()) {
            _pendingDraft.value = null
            _pendingDraftMessageId.value = null
            viewModelScope.launch {
                postAssistantMessage(
                    if (draft.isBengali) "ঠিক আছে, কিছু তৈরি করিনি — ড্রাফটটা বাতিল করেছি।"
                    else "No problem — I discarded the draft, nothing was created."
                )
            }
        } else {
            _pendingDraft.value = draft.copy(subtaskTitles = remaining)
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
        when (val llm = runLlmFallback(prompt, historyBefore)) {
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

    /**
     * Sprint 3-4 — the LLM fallback WITHOUT posting: the brain dump
     * runs it per unparsed piece and collects the Drafts into ONE
     * combined card instead of posting one message per piece. The
     * thinking phase text is set here (S3-3 behaviour, shared).
     */
    private suspend fun runLlmFallback(
        prompt: String,
        historyBefore: List<Pair<String, Boolean>>
    ): LlmFallbackResult {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        _thinkingText.value = if (isBengali) "ভাবছি…" else "Thinking…"
        val memories = runCatching {
            memoryRepository.getMemories(userId).map { it.content }
        }.getOrDefault(emptyList())
        return llmFallbackHandler.handleUnhandled(userId, prompt, historyBefore, memories)
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
     * Sprint 3-4 — the mic is a TOGGLE that opens a brain-dump
     * session (feature 19): the user talks continuously, the
     * recognizer stops after every utterance, and the session
     * RESTARTS it until the user taps Stop ([stopVoice]) or silence
     * gives up. [languageCode] is the listening language ("en-US"
     * today; the S3-7 toggle passes "bn-BD"). A second tap while a
     * session is open is a no-op — the mic button calls [stopVoice]
     * to end it.
     */
    fun startVoice(languageCode: String = "en-US") {
        if (_isBrainDumpActive.value) return
        brainDumpSession.start()
        brainDumpLanguageCode = languageCode
        _isBrainDumpActive.value = true
        _brainDumpTranscript.value = ""
        listenOnce(languageCode)
    }

    /** One recognizer start inside the open dump session. The
     *  callbacks below restart it after every utterance (the loop)
     *  or end the session on error. */
    private fun listenOnce(languageCode: String) {
        voiceInput.startListening(
            languageCode = languageCode,
            onResult = ::onBrainDumpUtterance,
            onError = ::onBrainDumpError
        )
    }

    /** One utterance heard: append it, show it live, and restart the
     *  recognizer while the session is still open. A late callback
     *  after the session ended is dropped by the session itself. */
    private fun onBrainDumpUtterance(text: String) {
        if (!_isBrainDumpActive.value) return
        brainDumpSession.onUtterance(text)
        _brainDumpTranscript.value = brainDumpSession.transcript
        if (_isBrainDumpActive.value) listenOnce(brainDumpLanguageCode)
    }

    /** A recognizer error inside the dump session. Silence (NO_MATCH)
     *  restarts listening up to the session's allowance; anything
     *  else ends the session — whatever was heard so far is still
     *  processed, and an empty transcript posts the error as the
     *  reply instead of a silent nothing. */
    private fun onBrainDumpError(error: VoiceInputError) {
        if (!_isBrainDumpActive.value) return
        when (error) {
            VoiceInputError.NO_MATCH -> {
                if (brainDumpSession.noteSilentRestart()) {
                    listenOnce(brainDumpLanguageCode)
                } else {
                    finishBrainDump()
                }
            }
            VoiceInputError.NETWORK, VoiceInputError.OTHER -> finishBrainDump(error)
        }
    }

    /**
     * Ends the dump session (the mic's Stop tap) and processes the
     * transcript. Outside a session this only stops a stray
     * recognizer (defensive; the mic always starts a session now).
     */
    fun stopVoice() {
        if (!_isBrainDumpActive.value) {
            voiceInput.stopListening()
            return
        }
        finishBrainDump()
    }

    /** Stops the recognizer, closes the session, and — when anything
     *  was heard — splits and previews the transcript. An empty
     *  transcript posts a short note instead ([error] decides its
     *  wording: the recognizer's failure, or simply nothing heard). */
    private fun finishBrainDump(error: VoiceInputError? = null) {
        if (!_isBrainDumpActive.value) return
        voiceInput.stopListening()
        val transcript = brainDumpSession.stop()
        val isBengali = brainDumpLanguageCode == "bn-BD" ||
            transcript.any { it in '\u0980'..'\u09FF' }
        _isBrainDumpActive.value = false
        _brainDumpTranscript.value = ""
        brainDumpLanguageCode = "en-US"
        if (transcript.isBlank()) {
            viewModelScope.launch {
                postAssistantMessage(
                    when (error) {
                        VoiceInputError.NETWORK ->
                            if (isBengali) "ভয়েস শুনতে সমস্যা হয়েছে — নেটওয়ার্কটা দেখে আবার চেষ্টা করো।"
                            else "I had trouble hearing you — check the network and try again."
                        else ->
                            if (isBengali) "কিছু শুনতে পাইনি — আবার বলো।"
                            else "I didn't catch anything — try again."
                    }
                )
            }
            return
        }
        viewModelScope.launch { processBrainDump(transcript, isBengali) }
    }

    /**
     * Sprint 3-4 — the dump pipeline (Q3 = (a), Q9 = (a)). The
     * transcript is stored as the user's message first (so the dump
     * stays in history), then split, then each piece goes through
     * the prefilter on its own:
     * - PendingDraft → collected into the combined preview.
     * - Handled → not a creation (a query, a proposal, or a
     *   non-creation command such as "clear completed tasks" — which
     *   the prefilter executes at once, as in a typed message; the
     *   Confirm gate covers CREATIONS, not commands). Counted as
     *   skipped, not a draft.
     * - a stored pending clarification (bare hour, schedule with no
     *   time) → the piece is recorded as unresolved with the
     *   prefilter's own question text, and the clarification is
     *   cleared so the NEXT piece is not eaten as its answer.
     * - Unhandled → the LLM fallback while it is available; a Draft
     *   is collected, a Reply is unparsed, and a Capped result stops
     *   further LLM attempts for this dump (the day's turns are
     *   spent — the rest stay unparsed instead of burning turns).
     *
     * Outcome: drafts → ONE combined preview card (DRAFT_PREVIEW);
     * zero drafts → ONE clarifying question (the first unresolved
     * question when there is one, else a rephrase ask). Nothing is
     * written to the DB here — only [confirmBrainDump] writes.
     */
    private suspend fun processBrainDump(transcript: String, isBengali: Boolean) {
        _isThinking.value = true
        try {
            // Guardrails FIRST, like a typed send: a refused dump
            // stores nothing and creates nothing.
            val guard = ChatGuardrails.check(transcript)
            if (guard is ChatGuardrails.GuardResult.Refused) {
                postAssistantMessage(guard.replyText)
                return
            }
            val historyBefore = messages.value.takeLast(6)
                .map { it.text to it.senderIsUser }
            addMessage(senderIsUser = true, text = transcript)

            val pieces = BrainDumpSplitter.split(transcript)
            if (pieces.isEmpty()) {
                postAssistantMessage(
                    if (isBengali) "কিছু শুনতে পাইনি — আবার বলো।"
                    else "I didn't catch anything — try again."
                )
                return
            }
            val today = startOfDayMillis(System.currentTimeMillis())
            val todayTasks = runCatching {
                taskRepository.getAllTasksOnce(userId)
                    .filter { it.dueDateEpochMillis in today..endOfDayMillis(today) }
            }.getOrDefault(emptyList())
            val events = runCatching {
                calendarRepository.observeEventsForDay(userId, today).first()
            }.getOrDefault(emptyList())

            val drafts = mutableListOf<ChatDraft>()
            val unresolved = mutableListOf<String>()
            var unparsed = 0
            var skipped = 0
            var llmCapped = false
            for (piece in pieces) {
                val result = runCatching {
                    preFilter.tryHandle(userId, piece, todayTasks, events)
                }.getOrNull()
                when (result) {
                    is PreFilterResult.PendingDraft -> drafts += result.draft
                    is PreFilterResult.Handled -> {
                        // A question was asked: the prefilter stored a
                        // pending clarification. Record its text and
                        // clear it — the next piece must be parsed on
                        // its own, not eaten as this answer.
                        if (preFilter.hasPendingClarification()) {
                            unresolved += result.responseText
                            preFilter.clearPendingClarification()
                        } else {
                            skipped++
                        }
                    }
                    is PreFilterResult.Unhandled -> {
                        if (llmCapped) {
                            unparsed++
                        } else {
                            when (val llm = runLlmFallback(piece, historyBefore)) {
                                is LlmFallbackResult.Draft -> drafts += llm.draft
                                is LlmFallbackResult.Capped -> {
                                    llmCapped = true
                                    unparsed++
                                }
                                is LlmFallbackResult.Reply -> unparsed++
                            }
                        }
                    }
                    null -> unparsed++
                }
            }

            if (drafts.isEmpty()) {
                // Zero drafts: ask ONE clarifying question, create
                // nothing silently (§6 feature 19).
                postAssistantMessage(
                    when {
                        unresolved.isNotEmpty() -> unresolved.first()
                        unparsed > 0 ->
                            if (isBengali) "এগুলো আলাদা আলাদা টাস্কে ভাগ করতে পারিনি — প্রতিটা কাজ আলাদা করে, সময় থাকলে সময়সহ বলো।"
                            else "I couldn't split that into tasks — try saying each task on its own, with a time if it has one."
                        else ->
                            if (isBengali) "এগুলো টাস্ক মনে হলো না — একটা কাজ বানাতে বলো।"
                            else "That didn't sound like tasks — try asking me to create a task."
                    }
                )
                return
            }

            // One combined preview card (Q9 = (a)): the drafts, the
            // count, and what could not be placed — Confirm-all saves
            // exactly the listed drafts.
            _pendingDraft.value = null
            _pendingDraftMessageId.value = null
            _pendingBrainDumpDrafts.value = drafts
            val previewText = buildBrainDumpPreviewText(drafts, unresolved, unparsed, skipped, isBengali)
            val messageId = postAssistantMessage(
                text = previewText,
                actionType = ChatActionType.DRAFT_PREVIEW
            )
            _pendingBrainDumpMessageId.value = messageId
        } catch (e: Exception) {
            runCatching { postAssistantMessage("Something went wrong — please try again.") }
        } finally {
            _isThinking.value = false
            _thinkingText.value = "Nudgy is thinking..."
            refreshCapState()
        }
    }

    /** The combined preview's message text: the draft list (title +
     *  time), then one line per thing that could not be placed. The
     *  card itself renders the drafts with remove buttons; the text
     *  is the record in history. */
    private fun buildBrainDumpPreviewText(
        drafts: List<ChatDraft>,
        unresolved: List<String>,
        unparsed: Int,
        skipped: Int,
        isBengali: Boolean
    ): String {
        val dateFormat = java.text.SimpleDateFormat("EEE, MMM d • h:mm a", java.util.Locale.getDefault())
        val lines = StringBuilder()
        if (isBengali) {
            lines.append("শুনেছি — ${drafts.size}টা কাজ পেয়েছি:\n")
        } else {
            lines.append("Here's what I heard — ${drafts.size} ${if (drafts.size == 1) "task" else "tasks"}:\n")
        }
        drafts.forEachIndexed { index, draft ->
            lines.append("${index + 1}. ${draft.title} — ${dateFormat.format(java.util.Date(draft.startEpochMillis))}\n")
        }
        if (unresolved.isNotEmpty()) {
            lines.append(
                if (isBengali) "• ${unresolved.size}টা এখনো ঝুলে আছে — সময়টা নিশ্চিত করলে বানাবো।\n"
                else "• ${unresolved.size} ${if (unresolved.size == 1) "item" else "items"} still need${if (unresolved.size == 1) "s" else ""} a time — tell me and I'll add ${if (unresolved.size == 1) "it" else "them"}.\n"
            )
        }
        if (unparsed > 0) {
            lines.append(
                if (isBengali) "• ${unparsed}টা বুঝতে পারিনি — আলাদা করে বললে বানাবো।\n"
                else "• Couldn't place $unparsed ${if (unparsed == 1) "item" else "items"} — say ${if (unparsed == 1) "it" else "them"} again on ${if (unparsed == 1) "its" else "their"} own.\n"
            )
        }
        if (skipped > 0) {
            lines.append(
                if (isBengali) "• ${skipped}টা টাস্ক নয় — বাদ দিয়েছি।\n"
                else "• Skipped $skipped ${if (skipped == 1) "item" else "items"} that ${if (skipped == 1) "wasn't" else "weren't"} a task.\n"
            )
        }
        lines.append(
            if (isBengali) "সব ঠিক থাকলে Confirm করো — না হলে যেটা ভুল সেটা সরিয়ে দাও।"
            else "Confirm all if this looks right — or remove any item first."
        )
        return lines.toString().trimEnd()
    }

    /**
     * Sprint 3-4 — Confirm-all on the combined preview card: the ONLY
     * path that saves brain-dump drafts. The list is cleared FIRST
     * (synchronously, so a double tap can never save twice), then
     * each draft is confirmed in order through
     * [ClientIntentPreFilter.confirmDraft]. ONE summary receipt is
     * posted (a receipt per draft would spam the chat). A confirm
     * with no pending dump is a no-op.
     */
    fun confirmBrainDump() {
        val drafts = _pendingBrainDumpDrafts.value
        if (drafts.isEmpty()) return
        _pendingBrainDumpDrafts.value = emptyList()
        _pendingBrainDumpMessageId.value = null
        viewModelScope.launch {
            var created = 0
            var failed = 0
            val createdTitles = mutableListOf<String>()
            for (draft in drafts) {
                val result = runCatching { preFilter.confirmDraft(userId, draft) }.getOrNull()
                if (result is PreFilterResult.Handled) {
                    created++
                    createdTitles += draft.title
                } else {
                    failed++
                }
            }
            val isBengali = drafts.first().isBengali
            val summary = StringBuilder()
            if (created > 0) {
                // Neutral wording on purpose: a dump can mix tasks and
                // events, so neither "tasks" nor "events" is right.
                summary.append(
                    if (isBengali) "হয়ে গেছে — ${created}টা তৈরি করেছি:\n"
                    else "Done — created $created:\n"
                )
                createdTitles.forEach { summary.append("• $it\n") }
            }
            if (failed > 0) {
                summary.append(
                    if (isBengali) "${failed}টা তৈরি হয়নি — আবার চেষ্টা করো।"
                    else "$failed couldn't be saved — please try again."
                )
            }
            postAssistantMessage(summary.toString().trimEnd())
            refreshCapState()
        }
    }

    /**
     * Sprint 3-4 — Discard on the combined preview card: nothing is
     * created. A discard with no pending dump is a no-op.
     */
    fun discardBrainDump() {
        val drafts = _pendingBrainDumpDrafts.value
        if (drafts.isEmpty()) return
        _pendingBrainDumpDrafts.value = emptyList()
        _pendingBrainDumpMessageId.value = null
        viewModelScope.launch {
            postAssistantMessage(
                if (drafts.first().isBengali) "ঠিক আছে, কিছু তৈরি করিনি — ড্রাফটগুলো বাতিল করেছি।"
                else "No problem — I discarded the drafts, nothing was created."
            )
        }
    }

    /**
     * Sprint 3-4 — per-item remove on the combined preview card
     * (Q9 = (a)): drops one draft before Confirm. Removing the last
     * item clears the card and posts the same note as Discard —
     * nothing was created.
     */
    fun removeBrainDumpDraft(index: Int) {
        val drafts = _pendingBrainDumpDrafts.value
        if (index !in drafts.indices) return
        val remaining = drafts.toMutableList().also { it.removeAt(index) }
        _pendingBrainDumpDrafts.value = remaining
        if (remaining.isEmpty()) {
            _pendingBrainDumpMessageId.value = null
            viewModelScope.launch {
                postAssistantMessage(
                    if (drafts.first().isBengali) "ঠিক আছে, কিছু তৈরি করিনি — ড্রাফটগুলো বাতিল করেছি।"
                    else "No problem — I discarded the drafts, nothing was created."
                )
            }
        }
    }

    /**
     * Sprint 3-4 — the rewarded entry: shows the preloaded rewarded
     * ad. The +1 turn is granted ONLY inside the reward callback
     * (a closed or skipped ad grants nothing); the cap snapshot is
     * refreshed right after, so the offer disappears at once. The
     * [activity] is used for this call only and never stored. A
     * false return (no ad ready) triggers a reload inside the
     * gateway — the button state follows the gateway flows.
     */
    fun watchRewardedAd(activity: Activity) {
        rewardedAdGateway.show(activity) {
            val config = runCatching { llmConfigProvider.current() }.getOrNull() ?: return@show
            ChatTurnCap.grantRewardedTurn(
                context, config.freeTurnsPerDay, config.maxRewardedTurnsPerDay
            )
            refreshCapState()
        }
    }

    /** Sprint 3-4 — the rewarded entry's retry: reloads the rewarded
     *  ad with the last requested unit id (see
     *  [NudgyAds.reloadRewarded]). */
    fun retryRewardedAd() {
        rewardedAdGateway.retryLoad()
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
