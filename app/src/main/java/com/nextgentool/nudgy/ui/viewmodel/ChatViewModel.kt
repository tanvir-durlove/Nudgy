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
import com.nextgentool.nudgy.ai.DayPlanCommand
import com.nextgentool.nudgy.ai.DayPlanCommandParser
import com.nextgentool.nudgy.ai.DraftKind
import com.nextgentool.nudgy.ai.LlmConfigProvider
import com.nextgentool.nudgy.ai.LlmFallbackHandler
import com.nextgentool.nudgy.ai.LlmFallbackResult
import com.nextgentool.nudgy.ai.PreFilterResult
import com.nextgentool.nudgy.ai.PrefilterCommand
import com.nextgentool.nudgy.ai.ReplySoundPlayer
import com.nextgentool.nudgy.ai.SplitCommand
import com.nextgentool.nudgy.ai.SplitCommandParser
import com.nextgentool.nudgy.ai.SplitParent
import com.nextgentool.nudgy.ai.VoiceInput
import com.nextgentool.nudgy.ai.VoiceInputError
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.ChatRepository
import com.nextgentool.nudgy.data.repository.MemoryRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.DayPlanner
import com.nextgentool.nudgy.domain.usecase.PlanDayUseCase
import com.nextgentool.nudgy.ui.screens.ChatActionType
import com.nextgentool.nudgy.ui.screens.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
 * Sprint 3-6 (features 21 + 22) — plan the day, locally. A
 * day-plan command ("plan my day" / "দিনটা সাজাও", or "what should
 * I do first" / "দিনটা সাজিয়ে দাও") is detected LOCALLY by
 * [DayPlanCommandParser] before the prefilter runs — the
 * prefilter's old "Plan my day" summary (branch 8) is deleted in
 * S3-6: the phrase now really plans the day. Auto-schedule
 * proposes one focus slot per unplaced task (existing
 * findSuggestedFocusSlot + checkConflict; all-day events never
 * block; already-placed tasks are busy intervals, never moved)
 * as FOCUS_EVENT drafts on one combined preview card — only
 * [confirmPlan] writes, through [ClientIntentPreFilter.confirmDraft]
 * → blockFocusTime per draft. Priority answers with a NUMBERED
 * reply (overdue → due time → HIGH → conflict last, the scoring
 * visible on every line) + an Apply button: [applyPriorityOrder]
 * stamps the order through the Plan pick path (sequential
 * plannedStart via [PlanDayUseCase.assignPriorityStartTimes] +
 * updateTask, the same stamping PlanViewModel.startMyDay does) —
 * My Day sorts by plannedStart, so its order follows the reply.
 * Apply is not a creation, so it does not go through confirmDraft;
 * List/Board due-first sort is untouched (S1 locked).
 *
 * Sprint 3-7 (feature 23, Q6 = (a)) — Bengali voice + Bengali
 * natural language. The input bar carries an explicit EN/বাংলা
 * toggle ([voiceLanguage], [toggleVoiceLanguage]) — the user picks,
 * the app never auto-detects; the toggle's code ("en-US" / "bn-BD")
 * is what [startVoice] hears in, and it decides the reply language
 * for voice-driven flows. The bank grew three intents (SPLIT_TASK /
 * PLAN_DAY / PRIORITY_SORT, 241 entries, 6516 total) so the local
 * matcher parses the Bengali commands offline; the prefilter
 * returns them as [PreFilterResult.Command] and this ViewModel
 * routes each to its existing handler — a command phrasing the
 * parsers cannot read asks which task, never a guess. A command
 * piece inside a brain dump counts as skipped, never a task. The
 * priority Apply receipt answers in the command's language
 * ([_pendingPriorityIsBengali] rides with the pending order).
 *
 * Sound (§4.4): every assistant reply plays the short notification
 * sound ([ReplySoundPlayer]). [speak] is NEVER called from the chat
 * path — [isSpeaking] is a constant false, so the mascot never shows
 * its TALKING mood in chat. TTS stays dormant.
 */
/**
 * Sprint 3-7 (Q6 = (a)) — the explicit EN/বাংলা voice toggle. The
 * user picks the language; the app never auto-detects. [code] is the
 * SpeechRecognizer locale ("en-US" / "bn-BD"); [label] is what the
 * toggle chip in the input bar shows.
 */
enum class ChatVoiceLanguage(val code: String, val label: String) {
    ENGLISH("en-US", "EN"),
    BENGALI("bn-BD", "বাংলা")
}

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
    private val rewardedAdGateway: RewardedAdGateway,
    // Sprint 3-6 — Apply stamps the priority order through the
    // Plan pick path ([PlanDayUseCase.assignPriorityStartTimes]);
    // Hilt provides it (it has an @Inject constructor), tests pass
    // a plain instance.
    private val planDayUseCase: PlanDayUseCase,
    // Sprint 5 fix round 4 — the live-user seam (device test on
    // ci-803: tab ViewModels froze the uid captured at construction).
    // Nullable with a default on purpose (TodayViewModel precedent):
    // Hilt always injects the real seam in production (AuthModule
    // provides it); call sites that don't pass one keep the previous
    // frozen-uid behaviour.
    private val groupAuth: GroupAuth? = null
) : ViewModel() {

    /**
     * Sprint 5 fix round 4 — the LIVE user (GroupsViewModel pattern,
     * PR #98). The fallback is the TaskRepository-derived user (blank
     * display name); the auth flow overrides it the moment it emits a
     * signed-in user. The equality guard cannot skip a real Google
     * sign-in: the fallback has a blank email while a real sign-in
     * always carries a non-blank one.
     */
    private val fallbackUser: NudgyUser = run {
        val uid = taskRepository.currentUserId()
        NudgyUser(
            uid = uid,
            displayName = "",
            email = "",
            isGuest = uid == AuthManager.GUEST_USER_ID
        )
    }
    private val userFlow = MutableStateFlow(fallbackUser)

    /** Live uid — read at call time so a sign-in flip is followed. */
    private val currentUserId: String get() = userFlow.value.uid

    /** The screen's `user` param. ChatScreen does not render it today;
     *  the value is kept honest anyway: the fallback carries no
     *  display name, and a signed-in user carries their real Google
     *  name — no name is ever invented here. It is a StateFlow now
     *  (Sprint 5 fix round 4) so the screen follows a sign-in flip
     *  instead of the uid frozen at construction. */
    val user: StateFlow<NudgyUser> = userFlow.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> =
        userFlow.flatMapLatest { user ->
            chatRepository.observeMessages(user.uid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Sprint 3-7 (Q6 = (a)) — the explicit EN/বাংলা voice toggle.
     * The user picks the language; the app never auto-detects. The
     * toggle lives in the ViewModel (not the bar) so it survives
     * rotation and is shared by the sheet and the full chat (same
     * ViewModel), and so [startVoice] always hears in the picked
     * language. Default English — Bengali is one tap away.
     */
    private val _voiceLanguage = MutableStateFlow(ChatVoiceLanguage.ENGLISH)
    val voiceLanguage: StateFlow<ChatVoiceLanguage> = _voiceLanguage.asStateFlow()

    /** Flips the voice toggle EN <-> বাংলা. */
    fun toggleVoiceLanguage() {
        _voiceLanguage.value = when (_voiceLanguage.value) {
            ChatVoiceLanguage.ENGLISH -> ChatVoiceLanguage.BENGALI
            ChatVoiceLanguage.BENGALI -> ChatVoiceLanguage.ENGLISH
        }
    }

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

    /** Sprint 3-6 — the pending day-plan drafts (feature 21, Q4 =
     *  (a)): one FOCUS_EVENT draft per proposed slot, one combined
     *  preview card, one Confirm-all (the brain-dump Q9 pattern).
     *  Mutually exclusive with [_pendingDraft] and
     *  [_pendingBrainDumpDrafts]: a new message clears all three. */
    private val _pendingPlanDrafts = MutableStateFlow<List<ChatDraft>>(emptyList())
    val pendingPlanDrafts: StateFlow<List<ChatDraft>> = _pendingPlanDrafts.asStateFlow()

    /** The id of the assistant message the day-plan preview card is
     *  attached to (same pattern as [_pendingBrainDumpMessageId]). */
    private val _pendingPlanMessageId = MutableStateFlow<String?>(null)
    val pendingPlanMessageId: StateFlow<String?> = _pendingPlanMessageId.asStateFlow()

    /** Sprint 3-6 — the pending priority order (feature 22, Q5 =
     *  (a)): task ids in priority order, waiting for the Apply
     *  button on the DAILY_PLAN reply. Null = no order pending. */
    private val _pendingPriorityOrder = MutableStateFlow<List<String>?>(null)
    val pendingPriorityOrder: StateFlow<List<String>?> = _pendingPriorityOrder.asStateFlow()

    /** Sprint 3-7 — whether the pending priority order came from a
     *  Bengali command, so [applyPriorityOrder]'s receipt answers
     *  in Bengali. Set with the order, cleared with it. */
    private val _pendingPriorityIsBengali = MutableStateFlow(false)

    /** The id of the assistant message the priority Apply card is
     *  attached to. */
    private val _pendingPriorityMessageId = MutableStateFlow<String?>(null)
    val pendingPriorityMessageId: StateFlow<String?> = _pendingPriorityMessageId.asStateFlow()

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
        // Sprint 5 fix round 4 — follow the LIVE user (same bug as
        // TodayViewModel on ci-803). On a flip, the new user's
        // welcome message is seeded (no-op when history exists) and
        // the messages flow re-subscribes via flatMapLatest.
        groupAuth?.let { auth ->
            viewModelScope.launch {
                auth.userState.collect { authUser ->
                    val newUser =
                        if (authUser == null || authUser.isGuest) fallbackUser else authUser
                    if (newUser == userFlow.value) return@collect
                    userFlow.value = newUser
                    runCatching {
                        chatRepository.checkAndSeedWelcomeMessage(newUser.uid, "")
                    }
                }
            }
        }
        // Seed the welcome message once (no-op when history exists).
        // displayName is "" until the Sprint 6 auth flow knows the
        // user's name — ChatRepository greets neutrally ("Hi! …") with
        // no name, never a hardcoded one.
        viewModelScope.launch {
            runCatching { chatRepository.checkAndSeedWelcomeMessage(currentUserId, "") }
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
        // S3-6: ... and any pending day-plan card / priority order.
        _pendingPlanDrafts.value = emptyList()
        _pendingPlanMessageId.value = null
        _pendingPriorityOrder.value = null
        _pendingPriorityMessageId.value = null
        _pendingPriorityIsBengali.value = false
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
                            // Sprint 3-6 — a day-plan command is detected
                            // locally and never reaches the prefilter (its
                            // old "Plan my day" summary, branch 8, is
                            // deleted in S3-6 — the phrase now really
                            // plans the day). A pending clarification
                            // wins over the day-plan check, like the
                            // split check above.
                            val dayPlanCommand =
                                if (preFilter.hasPendingClarification()) null
                                else DayPlanCommandParser.parse(trimmed)
                            when (dayPlanCommand) {
                                DayPlanCommand.AUTO_SCHEDULE ->
                                    handleAutoScheduleCommand(trimmed)
                                DayPlanCommand.PRIORITY ->
                                    handlePriorityCommand(trimmed)
                                null -> handlePrefilterMessage(trimmed, historyBefore)
                            }
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
            val todayTasks = taskRepository.getAllTasksOnce(currentUserId)
                .filter { it.dueDateEpochMillis in today..endOfDayMillis(today) }
            val events = calendarRepository
                .observeEventsForDay(currentUserId, today).first()
            preFilter.tryHandle(currentUserId, trimmed, todayTasks, events)
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
            // Sprint 3-7 — a recognised Sprint 3 command: route it to
            // the same handler the typed-message parsers reach. A
            // split the parsers cannot read (a bank-only phrasing)
            // asks which task — never a guess, never a creation.
            is PreFilterResult.Command -> when (result.command) {
                PrefilterCommand.SPLIT_TASK -> {
                    val split = SplitCommandParser.parse(result.prompt)
                        ?: SplitCommand(null)
                    handleSplitCommand(split, result.prompt, historyBefore)
                }
                PrefilterCommand.PLAN_DAY ->
                    handleAutoScheduleCommand(result.prompt)
                PrefilterCommand.PRIORITY_SORT ->
                    handlePriorityCommand(result.prompt)
            }
            // The prefilter itself failed: the old behaviour — a local
            // fallback answer, no LLM call, no turn spent.
            null -> postAssistantMessage(
                llmFallbackHandler.localFallbackReply(trimmed)
            )
        }
    }

    /**
     * Sprint 3-5 — title lookup for a split command. An exact
     * contains-match wins; a Bengali command usually names the task
     * with its definite suffix ("পার্টির আয়োজনটা" — the arrangement)
     * while the stored title has none ("পার্টির আয়োজন"), so a miss
     * retries with a trailing টা/টি stripped. A stored title that
     * keeps its suffix still matches on the exact pass first.
     */
    private fun matchSplitTitle(tasks: List<TaskEntity>, title: String): List<TaskEntity> {
        val exact = tasks.filter { it.title.contains(title, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        val stripped = title.removeSuffix("টা").removeSuffix("টি")
        if (stripped == title) return emptyList()
        return tasks.filter { it.title.contains(stripped, ignoreCase = true) }
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
        val allTasks = runCatching { taskRepository.getAllTasksOnce(currentUserId) }
            .getOrDefault(emptyList())
        val titled = matchSplitTitle(allTasks, title)
        val topLevel = titled.filter {
            it.parentTaskId == null && !it.isCompleted
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
                val subtaskMatch = titled.any { it.parentTaskId != null }
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
            memoryRepository.getMemories(currentUserId).map { it.content }
        }.getOrDefault(emptyList())
        when (val llm = llmFallbackHandler.handleSplit(
            userId = currentUserId,
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
                        // Sprint 3-7 — no parent title to read the
                        // language from, so the voice toggle (the
                        // user's explicit chat-language choice) decides.
                        if (_voiceLanguage.value == ChatVoiceLanguage.BENGALI) {
                            "ওই টাস্কটা আর পেলাম না।"
                        } else {
                            "I couldn't find that task anymore."
                        }
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
            memoryRepository.getMemories(currentUserId).map { it.content }
        }.getOrDefault(emptyList())
        return llmFallbackHandler.handleUnhandled(currentUserId, prompt, historyBefore, memories)
    }

    /** Focus proposal "Block Time" button (ChatScreen action card 2).
     *  Writes the focus event, then marks the proposal message done so
     *  the card flips to "Scheduled". Unchanged by S3-2: the proposal
     *  card IS the preview (it shows the slot) and this button IS the
     *  confirm — no second confirmation is needed for it. */
    fun onBlockFocusTime(startMillis: Long, endMillis: Long, title: String, messageId: String) {
        viewModelScope.launch {
            runCatching { calendarRepository.blockFocusTime(currentUserId, startMillis, endMillis, title) }
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
                val result = runCatching { preFilter.confirmDraft(currentUserId, draft) }.getOrNull()
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
                taskRepository.getAllTasksOnce(currentUserId)
                    .filter { it.dueDateEpochMillis in today..endOfDayMillis(today) }
            }.getOrDefault(emptyList())
            val events = runCatching {
                calendarRepository.observeEventsForDay(currentUserId, today).first()
            }.getOrDefault(emptyList())

            val drafts = mutableListOf<ChatDraft>()
            val unresolved = mutableListOf<String>()
            var unparsed = 0
            var skipped = 0
            var llmCapped = false
            for (piece in pieces) {
                val result = runCatching {
                    preFilter.tryHandle(currentUserId, piece, todayTasks, events)
                }.getOrNull()
                when (result) {
                    is PreFilterResult.PendingDraft -> drafts += result.draft
                    // Sprint 3-7 — a command piece inside a dump
                    // ("দিনটা সাজাও" spoken mid-dump): it is not a
                    // task, and executing a day-plan mid-dump would
                    // surprise the user — count it as skipped, like
                    // any other non-creation Handled piece.
                    is PreFilterResult.Command -> skipped++
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
                val result = runCatching { preFilter.confirmDraft(currentUserId, draft) }.getOrNull()
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

    // ------------------------------------------------------------------
    // Sprint 3-6 — auto-schedule (feature 21, Q4 = (a)) + priority
    // sort (feature 22, Q5 = (a)). Both are LOCAL and deterministic
    // (no LLM, no cap): they share the day-planning computation in
    // [DayPlanner] over today's unplaced work.
    //
    // Auto-schedule: one-shot "plan my day" → a FOCUS_EVENT draft
    // per proposed slot (the existing focus path) → one combined
    // preview card → Confirm-all saves each draft through
    // [ClientIntentPreFilter.confirmDraft] (→ blockFocusTime), never
    // a direct DB write. Already-placed tasks never move: they are
    // busy intervals for the slot search, never re-proposed.
    // All-day events never block (findSuggestedFocusSlot filters
    // them, S2-1b) — and neither does this code: only timed events
    // and placed tasks enter the busy timeline.
    //
    // Priority: one-shot "what should I do first" → a NUMBERED
    // reply in priority order + an Apply button. Apply stamps the
    // order through the Plan pick path
    // ([PlanDayUseCase.assignPriorityStartTimes] + updateTask, the
    // same stamping [PlanViewModel.startMyDay] does) — My Day sorts
    // by plannedStart, so the My-Day order follows the reply. This
    // is NOT a creation, so it does not go through confirmDraft;
    // List/Board due-first sort is untouched (S1 locked).
    // ------------------------------------------------------------------

    /**
     * Sprint 3-6 — the auto-schedule command. Nothing is written
     * here — the proposals wait as pending drafts until Confirm-all.
     */
    private suspend fun handleAutoScheduleCommand(prompt: String) {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val now = System.currentTimeMillis()
        val todayStart = startOfDayMillis(now)
        val todayEnd = endOfDayMillis(now)
        val allTasks = runCatching { taskRepository.getAllTasksOnce(currentUserId) }
            .getOrDefault(emptyList())
        val unplaced = DayPlanner.unplacedTasks(allTasks, todayStart, todayEnd)
        if (unplaced.isEmpty()) {
            postAssistantMessage(
                if (isBengali) "আজ সাজানোর মতো কোনো কাজ নেই — সব কাজেরই সময় ঠিক করা আছে, নয়তো আজকের কোনো কাজ নেই।"
                else "Nothing to schedule — every task already has its time, or there's nothing due today."
            )
            return
        }
        // Busy timeline for the slot search: already-placed tasks
        // (their planned start + estimate, 30 min default). Timed
        // calendar events are read by findSuggestedFocusSlot itself;
        // all-day events are labels, never blocks (S2-1b).
        val busy = allTasks
            .filter { !it.isCompleted && it.plannedStartEpochMillis != null &&
                it.plannedStartEpochMillis in todayStart until todayEnd }
            .map { task ->
                val start = task.plannedStartEpochMillis!!
                start to (start + (task.estimatedMinutes ?: 30) * 60 * 1000L)
            }.toMutableList()
        val drafts = mutableListOf<ChatDraft>()
        val unscheduled = mutableListOf<Pair<TaskEntity, String?>>()
        for (task in DayPlanner.scheduleOrder(unplaced)) {
            val slot = runCatching {
                calendarRepository.findSuggestedFocusSlot(currentUserId, now, busy)
            }.getOrNull()
            if (slot == null || slot.endEpochMillis > todayEnd) {
                // No free slot today (the fallback can land past
                // midnight when the day is full — that is not a
                // usable today-slot).
                unscheduled += task to null
                continue
            }
            val conflict = runCatching {
                calendarRepository.checkConflict(currentUserId, slot.startEpochMillis, slot.endEpochMillis)
            }.getOrNull()
            if (conflict?.hasConflict == true) {
                // The suggestion came from the same busy timeline,
                // so a conflict here means the calendar moved under
                // us — try the conflict's own alternative slot when
                // it is free of the busy intervals too.
                val alt = conflict.suggestedAlternativeSlot
                val altFree = alt != null && alt.endEpochMillis <= todayEnd &&
                    busy.none { it.first < alt.endEpochMillis && it.second > alt.startEpochMillis } &&
                    runCatching {
                        calendarRepository.checkConflict(currentUserId, alt.startEpochMillis, alt.endEpochMillis)
                    }.getOrNull()?.hasConflict == false
                if (altFree && alt != null) {
                    drafts += focusDraft(task, alt.startEpochMillis, alt.endEpochMillis, alt.durationMinutes, isBengali)
                    busy += alt.startEpochMillis to alt.endEpochMillis
                } else {
                    unscheduled += task to conflict.conflictingEvent?.title
                }
            } else {
                drafts += focusDraft(task, slot.startEpochMillis, slot.endEpochMillis, slot.durationMinutes, isBengali)
                busy += slot.startEpochMillis to slot.endEpochMillis
            }
        }
        if (drafts.isEmpty()) {
            postAssistantMessage(
                if (isBengali) "আজ আর ফাঁকা সময় নেই — ক্যালেন্ডার ভরা। কোনো কাজের জন্য স্লট খুঁজে পাইনি।"
                else "No free slot today — your calendar is full. I couldn't find a slot for any of the tasks."
            )
            return
        }
        _pendingDraft.value = null
        _pendingDraftMessageId.value = null
        _pendingBrainDumpDrafts.value = emptyList()
        _pendingBrainDumpMessageId.value = null
        _pendingPlanDrafts.value = drafts
        val messageId = postAssistantMessage(
            text = buildDayPlanPreviewText(drafts, unscheduled, isBengali),
            actionType = ChatActionType.DRAFT_PREVIEW,
            quickSuggestions = listOf("Show today's tasks", "Show schedule")
        )
        _pendingPlanMessageId.value = messageId
    }

    private fun focusDraft(
        task: TaskEntity,
        startMillis: Long,
        endMillis: Long,
        durationMinutes: Int,
        isBengali: Boolean
    ): ChatDraft = ChatDraft(
        kind = DraftKind.FOCUS_EVENT,
        title = task.title,
        startEpochMillis = startMillis,
        endEpochMillis = endMillis,
        isBengali = isBengali,
        focusDurationMinutes = durationMinutes
    )

    /** The day-plan preview's message text: the proposed slots, then
     *  one line per task that could not be placed. The card itself
     *  renders the drafts with remove buttons; the text is the
     *  record in history. */
    private fun buildDayPlanPreviewText(
        drafts: List<ChatDraft>,
        unscheduled: List<Pair<TaskEntity, String?>>,
        isBengali: Boolean
    ): String {
        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val lines = StringBuilder()
        if (isBengali) {
            lines.append("দিনের প্ল্যান — ${drafts.size}টা ফোকাস ব্লক:\n")
        } else {
            lines.append("Here's your day plan — ${drafts.size} ${if (drafts.size == 1) "focus block" else "focus blocks"}:\n")
        }
        drafts.forEachIndexed { index, draft ->
            val end = draft.endEpochMillis ?: (draft.startEpochMillis + draft.focusDurationMinutes * 60 * 1000L)
            lines.append(
                "${index + 1}. ${draft.title} — " +
                    "${timeFormat.format(java.util.Date(draft.startEpochMillis))} – " +
                    "${timeFormat.format(java.util.Date(end))}\n"
            )
        }
        for ((task, conflictingTitle) in unscheduled) {
            lines.append(
                if (isBengali) {
                    if (conflictingTitle != null) "• \"${task.title}\" বসাতে পারিনি — ওই সময়ে \"$conflictingTitle\" আছে।\n"
                    else "• \"${task.title}\"-এর জন্য আজ ফাঁকা সময় নেই।\n"
                } else {
                    if (conflictingTitle != null) "• Couldn't place \"${task.title}\" — it clashes with '$conflictingTitle'.\n"
                    else "• No free slot today for \"${task.title}\".\n"
                }
            )
        }
        lines.append(
            if (isBengali) "সব ঠিক থাকলে Confirm করো — না হলে যেটা লাগবে না সেটা সরিয়ে দাও।"
            else "Confirm all if this looks right — or remove any block first."
        )
        return lines.toString().trimEnd()
    }

    /**
     * Sprint 3-6 — Confirm-all on the day-plan preview card: the
     * ONLY path that saves plan drafts. The list is cleared FIRST
     * (synchronously, so a double tap can never save twice), then
     * each draft is confirmed in order through
     * [ClientIntentPreFilter.confirmDraft] (→ blockFocusTime). ONE
     * summary receipt is posted. A confirm with no pending plan is
     * a no-op.
     */
    fun confirmPlan() {
        val drafts = _pendingPlanDrafts.value
        if (drafts.isEmpty()) return
        _pendingPlanDrafts.value = emptyList()
        _pendingPlanMessageId.value = null
        viewModelScope.launch {
            var created = 0
            var failed = 0
            val createdLines = mutableListOf<String>()
            val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            for (draft in drafts) {
                val result = runCatching { preFilter.confirmDraft(currentUserId, draft) }.getOrNull()
                if (result is PreFilterResult.Handled) {
                    created++
                    val end = draft.endEpochMillis
                        ?: (draft.startEpochMillis + draft.focusDurationMinutes * 60 * 1000L)
                    createdLines += "${draft.title} (${timeFormat.format(java.util.Date(draft.startEpochMillis))} – ${timeFormat.format(java.util.Date(end))})"
                } else {
                    failed++
                }
            }
            val isBengali = drafts.first().isBengali
            val summary = StringBuilder()
            if (created > 0) {
                summary.append(
                    if (isBengali) "হয়ে গেছে — ${created}টা ফোকাস ব্লক বসিয়েছি:\n"
                    else "Done — scheduled $created ${if (created == 1) "focus block" else "focus blocks"}:\n"
                )
                createdLines.forEach { summary.append("• $it\n") }
            }
            if (failed > 0) {
                summary.append(
                    if (isBengali) "${failed}টা বসানো যায়নি — আবার চেষ্টা করো।"
                    else "$failed couldn't be scheduled — please try again."
                )
            }
            postAssistantMessage(summary.toString().trimEnd())
        }
    }

    /**
     * Sprint 3-6 — Discard on the day-plan preview card: nothing
     * is created. A discard with no pending plan is a no-op.
     */
    fun discardPlan() {
        val drafts = _pendingPlanDrafts.value
        if (drafts.isEmpty()) return
        _pendingPlanDrafts.value = emptyList()
        _pendingPlanMessageId.value = null
        viewModelScope.launch {
            postAssistantMessage(
                if (drafts.first().isBengali) "ঠিক আছে, কিছু বসাইনি — প্ল্যানটা বাতিল করেছি।"
                else "No problem — I didn't schedule anything, the plan is discarded."
            )
        }
    }

    /**
     * Sprint 3-6 — per-item remove on the day-plan preview card:
     * drops one block before Confirm. Removing the last item clears
     * the card and posts the same note as Discard — nothing was
     * created.
     */
    fun removePlanDraft(index: Int) {
        val drafts = _pendingPlanDrafts.value
        if (index !in drafts.indices) return
        val remaining = drafts.toMutableList().also { it.removeAt(index) }
        _pendingPlanDrafts.value = remaining
        if (remaining.isEmpty()) {
            _pendingPlanMessageId.value = null
            viewModelScope.launch {
                postAssistantMessage(
                    if (drafts.first().isBengali) "ঠিক আছে, কিছু বসাইনি — প্ল্যানটা বাতিল করেছি।"
                    else "No problem — I didn't schedule anything, the plan is discarded."
                )
            }
        }
    }

    /**
     * Sprint 3-6 — the priority command. Nothing is written here —
     * the order waits as pending state until Apply. A conflict is
     * checked per task only for a real due time today that is still
     * ahead (an overdue task's window is in the past; a timeless
     * task has no window) — the check is what demotes a task below
     * a free one in [DayPlanner.priorityOrder].
     */
    private suspend fun handlePriorityCommand(prompt: String) {
        val isBengali = prompt.any { it in '\u0980'..'\u09FF' }
        val now = System.currentTimeMillis()
        val todayStart = startOfDayMillis(now)
        val todayEnd = endOfDayMillis(now)
        val allTasks = runCatching { taskRepository.getAllTasksOnce(currentUserId) }
            .getOrDefault(emptyList())
        val unplaced = DayPlanner.unplacedTasks(allTasks, todayStart, todayEnd)
        if (unplaced.isEmpty()) {
            postAssistantMessage(
                if (isBengali) "আজ সাজানোর মতো কোনো কাজ নেই — সব কাজেরই সময় ঠিক করা আছে, নয়তো আজকের কোনো কাজ নেই।"
                else "Nothing to order — every task already has its time, or there's nothing due today."
            )
            return
        }
        val conflicts = mutableMapOf<String, String?>()
        for (task in unplaced) {
            val due = task.dueDateEpochMillis
            if (DayPlanner.hasRealTime(task) && due in now until todayEnd) {
                val duration = (task.estimatedMinutes ?: 30) * 60 * 1000L
                val conflict = runCatching {
                    calendarRepository.checkConflict(currentUserId, due, due + duration)
                }.getOrNull()
                if (conflict?.hasConflict == true) {
                    conflicts[task.id] = conflict.conflictingEvent?.title
                }
            }
        }
        val ordered = DayPlanner.priorityOrder(unplaced, now) { conflicts.containsKey(it.id) }
        _pendingPriorityOrder.value = ordered.map { it.id }
        // Sprint 3-7 — the Apply receipt answers in the command's
        // language; the flag rides with the order.
        _pendingPriorityIsBengali.value = isBengali
        val messageId = postAssistantMessage(
            text = buildPriorityReplyText(ordered, conflicts, now, isBengali),
            actionType = ChatActionType.DAILY_PLAN,
            quickSuggestions = listOf("Plan my day", "Show today's tasks")
        )
        _pendingPriorityMessageId.value = messageId
    }

    /** The priority reply's text: the numbered order, with the
     *  scoring visible on every line (overdue / due time / HIGH /
     *  conflict) so the user can see WHY this is the order. */
    private fun buildPriorityReplyText(
        ordered: List<TaskEntity>,
        conflicts: Map<String, String?>,
        now: Long,
        isBengali: Boolean
    ): String {
        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val lines = StringBuilder()
        lines.append(
            if (isBengali) "আজ এভাবে করলে ভালো হবে:\n"
            else "Here's the order I'd tackle today:\n"
        )
        ordered.forEachIndexed { index, task ->
            val reason = buildString {
                if (DayPlanner.isOverdue(task, now)) {
                    append(
                        if (isBengali) "সময় পেরিয়ে গেছে — ${timeFormat.format(java.util.Date(task.dueDateEpochMillis))} ছিল"
                        else "overdue — due ${timeFormat.format(java.util.Date(task.dueDateEpochMillis))}"
                    )
                } else if (DayPlanner.hasRealTime(task)) {
                    append(
                        if (isBengali) timeFormat.format(java.util.Date(task.dueDateEpochMillis))
                        else "due ${timeFormat.format(java.util.Date(task.dueDateEpochMillis))}"
                    )
                } else {
                    append(if (isBengali) "যেকোনো সময়" else "anytime")
                }
                if (task.priority == com.nextgentool.nudgy.data.model.Priority.HIGH) {
                    append(if (isBengali) " · বেশি জরুরি" else " · HIGH priority")
                }
                val conflictingTitle = conflicts[task.id]
                if (conflictingTitle != null) {
                    append(
                        if (isBengali) " · \"$conflictingTitle\"-এর সঙ্গে ধাক্কা খায়"
                        else " · conflicts with '$conflictingTitle'"
                    )
                }
            }
            lines.append("${index + 1}. ${task.title} — $reason\n")
        }
        lines.append(
            if (isBengali) "Apply করলে My Day এই ক্রমে সাজবে।"
            else "Tap Apply to make this your My Day order."
        )
        return lines.toString().trimEnd()
    }

    /**
     * Sprint 3-6 — Apply on the priority reply: stamps the pending
     * order through the Plan pick path — plannedDate = today +
     * sequential plannedStart in priority order (via
     * [PlanDayUseCase.assignPriorityStartTimes], the same stamping
     * [PlanViewModel.startMyDay] does). My Day sorts by
     * plannedStart, so the My-Day order follows the reply. Due,
     * status and boardOrder are untouched; already-placed tasks
     * keep their stamps. An Apply with no pending order is a no-op.
     */
    fun applyPriorityOrder() {
        val orderIds = _pendingPriorityOrder.value ?: return
        val isBengali = _pendingPriorityIsBengali.value
        _pendingPriorityOrder.value = null
        _pendingPriorityIsBengali.value = false
        _pendingPriorityMessageId.value = null
        viewModelScope.launch {
            val allTasks = runCatching { taskRepository.getAllTasksOnce(currentUserId) }
                .getOrDefault(emptyList())
            val byId = allTasks.associateBy { it.id }
            val ordered = orderIds.mapNotNull { byId[it] }
                .filter { !it.isCompleted && it.parentTaskId == null }
            if (ordered.isEmpty()) {
                postAssistantMessage(
                    if (isBengali) "লাগানোর মতো কিছু নেই — টাস্কগুলো আর নেই।"
                    else "Nothing to apply — those tasks are gone."
                )
                return@launch
            }
            val now = System.currentTimeMillis()
            val starts = planDayUseCase.assignPriorityStartTimes(
                priorityOrdered = ordered,
                startOfToday = startOfDayMillis(now),
                now = now
            )
            val todayString = todayDateString()
            for (task in ordered) {
                runCatching {
                    taskRepository.updateTask(
                        task.copy(
                            plannedDate = todayString,
                            plannedStartEpochMillis = starts[task.id]
                        )
                    )
                }
            }
            val lines = StringBuilder(
                if (isBengali) "হয়ে গেছে — My Day এখন এই ক্রমে সাজবে:\n"
                else "Done — your My Day now follows this order:\n"
            )
            ordered.forEachIndexed { index, task -> lines.append("${index + 1}. ${task.title}\n") }
            postAssistantMessage(lines.toString().trimEnd())
        }
    }

    /** Sprint 3-6 — dismiss the priority Apply card without
     *  applying. The reply stays in history as plain text. */
    fun dismissPriorityOrder() {
        if (_pendingPriorityOrder.value == null) return
        _pendingPriorityOrder.value = null
        _pendingPriorityIsBengali.value = false
        _pendingPriorityMessageId.value = null
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
            currentUserId,
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
            currentUserId,
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
