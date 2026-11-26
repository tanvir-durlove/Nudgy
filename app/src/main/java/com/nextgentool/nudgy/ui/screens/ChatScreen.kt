package com.nextgentool.nudgy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ai.ChatDraft
import com.nextgentool.nudgy.ai.DraftKind
import com.nextgentool.nudgy.ui.components.NudgyDatePickerDialog
import com.nextgentool.nudgy.ui.components.NudgyTimePickerDialog
import com.nextgentool.nudgy.ui.components.localDateParts
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import com.nextgentool.nudgy.ads.ChatCapState
import com.nextgentool.nudgy.auth.NudgyUser
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.ui.components.ChatInputBar
import com.nextgentool.nudgy.ui.viewmodel.ChatVoiceLanguage
import java.text.SimpleDateFormat
import java.util.Locale

enum class ChatActionType {
    NONE,
    TASK_PREVIEW,
    EVENT_CREATED,
    FOCUS_TIME_PROPOSAL,
    DAILY_PLAN,
    /** S3-2 — the message carries a pending draft (preview question);
     *  the card renders only while the ViewModel still holds it. */
    DRAFT_PREVIEW
}

data class ChatMessage(
    val id: String,
    val senderIsUser: Boolean,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val actionType: ChatActionType = ChatActionType.NONE,
    val associatedTask: TaskEntity? = null,
    val associatedEvent: CalendarEventEntity? = null,
    val focusStartMillis: Long? = null,
    val focusEndMillis: Long? = null,
    val focusDurationMinutes: Int = 45,
    val focusActionCompleted: Boolean = false,
    val quickSuggestions: List<String> = emptyList(),
    // Sprint 7 — the WHO label on the user's bubble: voice messages
    // show "YOU · VOICE", typed ones "YOU". Display-only, defaulted
    // false: the voice path does not stamp it yet (that is a
    // ViewModel change, out of this design-only task's scope), so
    // every user bubble shows "YOU" today.
    val isVoice: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    user: NudgyUser,
    messages: List<ChatMessage>,
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    // Thinking indicator: phase text for the thinking bubble, set by
    // ChatViewModel ("ভাবছি…" / "শিডিউল করছি…" / "টাস্ক তৈরি করছি…",
    // or the English equivalents).
    thinkingText: String = "Nudgy is thinking...",
    onSendMessage: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    // Sprint 3-7 (Q6 = (a)) — the explicit EN/বাংলা voice toggle,
    // passed through to the shared input bar. Defaulted: the toggle
    // simply shows EN and flips to বাংলা when tapped.
    voiceLanguage: ChatVoiceLanguage = ChatVoiceLanguage.ENGLISH,
    onToggleVoiceLanguage: () -> Unit = {},
    onTaskClick: (TaskEntity) -> Unit = {},
    onBlockFocusTime: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
    // Sprint 3-2 — draft-then-confirm (Q2 = (a)): the pending draft and
    // the id of the message its preview card is attached to, plus the
    // card's actions. All defaulted: the card simply never renders
    // without a pending draft.
    pendingDraft: ChatDraft? = null,
    pendingDraftMessageId: String? = null,
    onConfirmDraft: () -> Unit = {},
    onDiscardDraft: () -> Unit = {},
    onDraftEdited: (ChatDraft) -> Unit = {},
    // Sprint 3-5 — subtask split (feature 20): the split preview card
    // lists the subtasks with a per-item remove (the brain-dump card's
    // Q9 pattern, on the single pending draft). No Edit: a split has
    // no time to edit and the parent title is not editable here.
    onRemoveSplitSubtask: (Int) -> Unit = {},
    // Sprint 3-4 — brain dump (feature 19, Q9 = (a)): the combined
    // preview card — all split drafts in one card, one Save-all,
    // per-item remove. All defaulted: the card never renders without
    // pending drafts. isBrainDumpActive keeps the mic on Stop between
    // utterances (the recognizer's isListening flickers false while
    // the session restarts it); brainDumpTranscript is the "heard so
    // far" line in the listening banner.
    isBrainDumpActive: Boolean = false,
    brainDumpTranscript: String = "",
    pendingBrainDumpDrafts: List<ChatDraft> = emptyList(),
    pendingBrainDumpMessageId: String? = null,
    onConfirmBrainDump: () -> Unit = {},
    onDiscardBrainDump: () -> Unit = {},
    onRemoveBrainDumpDraft: (Int) -> Unit = {},
    // Sprint 3-6 — auto-schedule (feature 21, Q4 = (a)): the day-plan
    // combined preview card — one FOCUS_EVENT draft per proposed
    // slot, one Save-all, per-item remove. Same pattern as the
    // brain-dump card; renders only on its own message while the
    // ViewModel still holds the drafts.
    pendingPlanDrafts: List<ChatDraft> = emptyList(),
    pendingPlanMessageId: String? = null,
    onConfirmPlan: () -> Unit = {},
    onDiscardPlan: () -> Unit = {},
    onRemovePlanDraft: (Int) -> Unit = {},
    // Sprint 3-6 — priority sort (feature 22, Q5 = (a)): the Apply
    // card on the DAILY_PLAN reply. Renders only on the message the
    // pending order is attached to; Apply stamps the order into My
    // Day, "Not now" dismisses it without applying.
    pendingPriorityMessageId: String? = null,
    onApplyPriorityOrder: () -> Unit = {},
    onDismissPriorityOrder: () -> Unit = {},
    // Ads: rewarded entry — non-null only when the free daily chat cap is
    // hit. The button grants +1 AI turn only after a completed ad view.
    chatCapState: ChatCapState? = null,
    rewardedAdReady: Boolean = false,
    rewardedLoadError: String? = null,
    onWatchRewardedAd: () -> Unit = {},
    onRetryRewardedAd: () -> Unit = {},
    // Sprint 7 — backbar (prototype scr-chat): Chat is a bottom-nav
    // destination, so Back goes to the Today tab, not popBackStack.
    // Defaulted: the smoke tests render without a NavController.
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val draftDateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val draftDateTimeFormat = remember { SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()) }

    // S3-2 — draft edit dialog state. The dialog edits a COPY of the
    // pending draft; only its Save button pushes back through
    // onDraftEdited. Cancel / dismiss leaves the draft untouched.
    var editingDraft by remember { mutableStateOf<ChatDraft?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editStartMillis by remember { mutableLongStateOf(0L) }
    var showEditDatePicker by remember { mutableStateOf(false) }
    var showEditTimePicker by remember { mutableStateOf(false) }
    // Sprint 3-1 — approved design tokens (brand_colors.xml, single
    // source of truth). The v1 palette (coral-dark primary, peach /
    // lavender / butter tints) and the mascot are gone from this
    // screen: teal is the primary, cream the only tint, coral only a
    // small status dot (never text — see brand_colors.xml).
    val teal = colorResource(R.color.nudgy_teal)
    val cream = colorResource(R.color.nudgy_cream)
    val ink = colorResource(R.color.nudgy_ink)
    val muted = colorResource(R.color.nudgy_muted)
    // Sprint 7 — the prototype confirm-card button row: soft Edit
    // (teal-soft fill, teal-dark text) and ghost Discard (white fill,
    // ink text, track border).
    val tealSoft = colorResource(R.color.nudgy_teal_soft)
    val tealDark = colorResource(R.color.nudgy_teal_dark)
    val track = colorResource(R.color.nudgy_track)
    // Sprint 7 — the prototype caps bubbles at 82% of the screen
    // width (both sides); wrap-content below that cap.
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp

    val quickPrompts = listOf(
        "Plan my day",
        "Show today's tasks",
        "Find free time",
        "Block 45m focus",
        "What do you remember?",
        "আজকের সব কাজ দেখাও"
    )

    // Bottom-anchored list: reverseLayout lays items out from the
    // bottom, so the newest message sits directly above the input bar
    // and short content sits at the bottom too — no void below the
    // last message, with the keyboard open or closed. Pinning to the
    // bottom is scrollToItem(0): offset 0 in a reversed list, which
    // does NOT depend on viewport size — unlike the old
    // scroll-to-lastIndex, whose bottom-pinned offset changed while
    // the IME was still resizing, so the scroll ended stale and the
    // keyboard gap remained (PR #48). Instant, not animated: an
    // animation target computed mid-resize goes stale the same way.
    // WindowInsets.isImeVisible is snapshot-state-backed, so reading
    // it here re-runs this effect when the keyboard opens or closes.
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(messages.size, isThinking, isListening, isImeVisible) {
        listState.scrollToItem(0)
    }

    // isSpeaking is intentionally unused: the chat never speaks
    // (§4.4 — the reply is a short notification sound, never TTS), so
    // there is no TALKING mood and no mascot anywhere on this screen.
    // The parameter stays so the call sites (NavHost, smoke tests)
    // keep compiling unchanged.

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.nudgy_background))
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Sprint 7 — backbar (prototype scr-chat; the same back
            // Row Templates uses: back + titleLarge, no TopAppBar).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("chat_backbar")
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ink
                    )
                }
                Text(
                    text = "Chat",
                    style = MaterialTheme.typography.titleLarge,
                    color = ink
                )
            }
            // Sprint 7 — intro (prototype-verbatim). Fixed under the
            // backbar like the Templates intro, so it is always
            // visible; the quick-prompt pills stay in the thread's
            // hero item below.
            Text(
                text = "Say or type what you need — in English or বাংলা. Chat works offline on this device; AI help is used only for hard requests, and nothing is saved until you confirm it.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = muted,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "trailing_spacer") {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (isThinking) {
                    item(key = "thinking_indicator") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.25f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Thinking dots: three dots fading in
                                    // sequence, Gemini-style, next to the
                                    // phase text (ভাবছি… / শিডিউল করছি… /
                                    // টাস্ক তৈরি করছি…).
                                    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dots")
                                    repeat(3) { index ->
                                        val dotAlpha by infiniteTransition.animateFloat(
                                            initialValue = 0.25f,
                                            targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(durationMillis = 600, delayMillis = index * 200),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "thinking_dot_$index"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .alpha(dotAlpha)
                                                .background(teal, CircleShape)
                                        )
                                        if (index < 2) Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = thinkingText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            fontSize = 13.sp
                                        ),
                                        color = muted
                                    )
                                }
                            }
                        }
                    }
                }

                // Chat Message Stream
                items(messages.reversed(), key = { it.id }) { message ->
                    if (message.senderIsUser) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp).copy(bottomEnd = androidx.compose.foundation.shape.CornerSize(6.dp)),
                                color = teal,
                                modifier = Modifier.widthIn(max = maxBubbleWidth)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                    // Sprint 7 — WHO label (prototype
                                    // scr-chat): "YOU · VOICE" on voice
                                    // messages, "YOU" on typed ones.
                                    Text(
                                        text = if (message.isVoice) "YOU · VOICE" else "YOU",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFBFE8E1)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = Color.White,
                                            fontSize = 14.5.sp
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp).copy(bottomStart = androidx.compose.foundation.shape.CornerSize(6.dp)),
                                        color = Color.White,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.25f)),
                                        modifier = Modifier.widthIn(max = maxBubbleWidth)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                            // Sprint 7 — WHO label
                                            // (prototype scr-chat).
                                            Text(
                                                text = "NUDGY",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = muted
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = message.text,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    color = ink,
                                                    fontSize = 14.5.sp
                                                )
                                            )
                                        }
                                    }

                                    // Action Card 1: Task Preview
                                    if (message.actionType == ChatActionType.TASK_PREVIEW && message.associatedTask != null) {
                                        val task = message.associatedTask
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = cream,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, teal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.TaskAlt,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = task.title,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = ink
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Priority: ${task.priority.name.lowercase().replaceFirstChar { it.uppercase() }} • Due: ${timeFormat.format(task.dueDateEpochMillis)}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = muted
                                                    )
                                                }

                                                Button(
                                                    onClick = { onTaskClick(task) },
                                                    shape = RoundedCornerShape(100.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                    modifier = Modifier.padding(start = 8.dp)
                                                ) {
                                                    Text("View Task", fontSize = 12.sp, color = Color.White)
                                                }
                                            }
                                        }
                                    }

                                    // Action Card 1b: Event Preview Card
                                    if (message.actionType == ChatActionType.EVENT_CREATED && message.associatedEvent != null) {
                                        val event = message.associatedEvent
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = cream,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, teal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Schedule,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = event.title,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = ink
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${timeFormat.format(event.startEpochMillis)} – ${timeFormat.format(event.endEpochMillis)} • ${event.locationOrLink ?: "Google Meet"}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = muted
                                                    )
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(100.dp),
                                                    color = Color.White
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Scheduled", color = teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // S3-2 — Action Card 1c: Draft Preview.
                                    // The pre-save approval (Q2 = (a)): this
                                    // card renders ONLY on the message the
                                    // pending draft is attached to, and only
                                    // while the ViewModel still holds the
                                    // draft. Save confirms and saves, Edit changes
                                    // the draft, Discard drops it — nothing
                                    // is in the DB yet. The TASK_PREVIEW /
                                    // EVENT_CREATED cards above stay as the
                                    // POST-save receipt.
                                    if (message.actionType == ChatActionType.DRAFT_PREVIEW &&
                                        message.id == pendingDraftMessageId && pendingDraft != null
                                    ) {
                                        val draft = pendingDraft
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().testTag("draft_preview_card"),
                                            shape = RoundedCornerShape(18.dp),
                                            color = Color.White,
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, teal)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (draft.kind == DraftKind.SCHEDULE || draft.kind == DraftKind.FOCUS_EVENT) Icons.Default.Schedule else Icons.Outlined.TaskAlt,
                                                        contentDescription = null,
                                                        tint = teal,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = draft.title,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp
                                                        ),
                                                        color = ink
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                // Sprint 3-5 — the split preview: the parent
                                                // title is the header above; the card lists
                                                // the subtasks with a per-item remove, then
                                                // Save / Discard. No time line (a split
                                                // has no time) and no Edit button.
                                                if (draft.kind == DraftKind.SUBTASK_SPLIT) {
                                                    Text(
                                                        text = if (draft.isBengali) {
                                                            "${draft.subtaskTitles.size}টা ছোট ভাগে ভাগ করব:"
                                                        } else {
                                                            "Smaller steps:"
                                                        },
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = muted
                                                    )
                                                    draft.subtaskTitles.forEachIndexed { index, sub ->
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.TaskAlt,
                                                                contentDescription = null,
                                                                tint = teal,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = sub,
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                                                color = ink,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            IconButton(
                                                                onClick = { onRemoveSplitSubtask(index) },
                                                                modifier = Modifier.testTag("split_subtask_remove_$index").size(28.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Close,
                                                                    contentDescription = "Remove $sub",
                                                                    tint = muted,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(10.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Button(
                                                            onClick = onConfirmDraft,
                                                            shape = RoundedCornerShape(100.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                            modifier = Modifier.testTag("draft_confirm_button")
                                                        ) {
                                                            Text("Save", fontSize = 13.sp, color = Color.White)
                                                        }
                                                        Button(
                                                            onClick = onDiscardDraft,
                                                            shape = RoundedCornerShape(100.dp),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = Color.White,
                                                                contentColor = ink
                                                            ),
                                                            border = androidx.compose.foundation.BorderStroke(1.dp, track),
                                                            modifier = Modifier.testTag("draft_discard_button")
                                                        ) {
                                                            Text("Discard", fontSize = 13.sp, color = ink)
                                                        }
                                                    }
                                                } else {
                                                Text(
                                                    text = when (draft.kind) {
                                                        DraftKind.TASK -> "Due: ${draftDateTimeFormat.format(draft.startEpochMillis)} • Priority: ${draft.priority.name.lowercase().replaceFirstChar { it.uppercase() }}"
                                                        DraftKind.REMINDER -> "Reminder: ${draftDateTimeFormat.format(draft.startEpochMillis)}"
                                                        DraftKind.SCHEDULE -> "${draftDateTimeFormat.format(draft.startEpochMillis)} – ${timeFormat.format(draft.endEpochMillis ?: draft.startEpochMillis)} • ${draft.scheduleLocation ?: "Google Meet"}"
                                                        DraftKind.FOCUS_EVENT -> "${draftDateTimeFormat.format(draft.startEpochMillis)} – ${timeFormat.format(draft.endEpochMillis ?: draft.startEpochMillis)} • ${draft.focusDurationMinutes} min"
                                                        // Unreachable: a split renders in the
                                                        // branch above, never this subtitle.
                                                        DraftKind.SUBTASK_SPLIT -> ""
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                    color = muted
                                                )
                                                if (draft.conflictingTitle != null) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (draft.isBengali) "⚠ ওই সময়ে \"${draft.conflictingTitle}\" আছে" else "⚠ Overlaps with \"${draft.conflictingTitle}\"",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = Color(0xFFB45309)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = onConfirmDraft,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                        modifier = Modifier.testTag("draft_confirm_button")
                                                    ) {
                                                        Text("Save", fontSize = 13.sp, color = Color.White)
                                                    }
                                                    Button(
                                                        onClick = {
                                                            editingDraft = draft
                                                            editTitle = draft.title
                                                            editStartMillis = draft.startEpochMillis
                                                        },
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = tealSoft,
                                                            contentColor = tealDark
                                                        ),
                                                        modifier = Modifier.testTag("draft_edit_button")
                                                    ) {
                                                        Text("Edit", fontSize = 13.sp, color = tealDark)
                                                    }
                                                    Button(
                                                        onClick = onDiscardDraft,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = Color.White,
                                                            contentColor = ink
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, track),
                                                        modifier = Modifier.testTag("draft_discard_button")
                                                    ) {
                                                        Text("Discard", fontSize = 13.sp, color = ink)
                                                    }
                                                }
                                                }
                                            }
                                        }
                                    }

                                    // S3-4 — Action Card 1d: Brain Dump
                                    // combined preview (Q9 = (a)). One card
                                    // for ALL split drafts: each row shows
                                    // title + time with its own remove
                                    // button; Save-all saves every
                                    // remaining draft, Discard drops them
                                    // all. Renders only on the message the
                                    // dump is attached to, and only while
                                    // the ViewModel still holds the drafts.
                                    if (message.actionType == ChatActionType.DRAFT_PREVIEW &&
                                        message.id == pendingBrainDumpMessageId && pendingBrainDumpDrafts.isNotEmpty()
                                    ) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().testTag("brain_dump_preview_card"),
                                            shape = RoundedCornerShape(18.dp),
                                            color = Color.White,
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, teal)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Text(
                                                    text = "Brain dump — ${pendingBrainDumpDrafts.size} ${if (pendingBrainDumpDrafts.size == 1) "task" else "tasks"}",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp
                                                    ),
                                                    color = ink
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                pendingBrainDumpDrafts.forEachIndexed { index, item ->
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (item.kind == DraftKind.SCHEDULE || item.kind == DraftKind.FOCUS_EVENT) Icons.Default.Schedule else Icons.Outlined.TaskAlt,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = item.title,
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                                                color = ink
                                                            )
                                                            Text(
                                                                text = draftDateTimeFormat.format(item.startEpochMillis),
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                                                color = muted
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = { onRemoveBrainDumpDraft(index) },
                                                            modifier = Modifier.testTag("brain_dump_remove_$index").size(28.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Close,
                                                                contentDescription = "Remove ${item.title}",
                                                                tint = muted,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = onConfirmBrainDump,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                        modifier = Modifier.testTag("brain_dump_confirm_button")
                                                    ) {
                                                        Text("Save all", fontSize = 13.sp, color = Color.White)
                                                    }
                                                    Button(
                                                        onClick = onDiscardBrainDump,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = Color.White,
                                                            contentColor = ink
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, track),
                                                        modifier = Modifier.testTag("brain_dump_discard_button")
                                                    ) {
                                                        Text("Discard", fontSize = 13.sp, color = ink)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // S3-6 — Action Card 1e: Day Plan
                                    // combined preview (feature 21, Q4 =
                                    // (a)). One card for ALL proposed
                                    // focus blocks: each row shows task
                                    // + slot range with its own remove
                                    // button; Save-all schedules every
                                    // remaining block, Discard drops them
                                    // all. Same pattern as the brain-dump
                                    // card (1d); renders only on the
                                    // message the plan is attached to,
                                    // and only while the ViewModel still
                                    // holds the drafts.
                                    if (message.actionType == ChatActionType.DRAFT_PREVIEW &&
                                        message.id == pendingPlanMessageId && pendingPlanDrafts.isNotEmpty()
                                    ) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().testTag("day_plan_preview_card"),
                                            shape = RoundedCornerShape(18.dp),
                                            color = Color.White,
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, teal)
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Text(
                                                    text = "Day plan — ${pendingPlanDrafts.size} ${if (pendingPlanDrafts.size == 1) "focus block" else "focus blocks"}",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp
                                                    ),
                                                    color = ink
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                pendingPlanDrafts.forEachIndexed { index, item ->
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Schedule,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = item.title,
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                                                color = ink
                                                            )
                                                            val planEnd = item.endEpochMillis
                                                                ?: (item.startEpochMillis + item.focusDurationMinutes * 60 * 1000L)
                                                            Text(
                                                                text = "${timeFormat.format(item.startEpochMillis)} – ${timeFormat.format(planEnd)}",
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                                                color = muted
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = { onRemovePlanDraft(index) },
                                                            modifier = Modifier.testTag("day_plan_remove_$index").size(28.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Close,
                                                                contentDescription = "Remove ${item.title}",
                                                                tint = muted,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = onConfirmPlan,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                        modifier = Modifier.testTag("day_plan_confirm_button")
                                                    ) {
                                                        Text("Save all", fontSize = 13.sp, color = Color.White)
                                                    }
                                                    Button(
                                                        onClick = onDiscardPlan,
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = Color.White,
                                                            contentColor = ink
                                                        ),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, track),
                                                        modifier = Modifier.testTag("day_plan_discard_button")
                                                    ) {
                                                        Text("Discard", fontSize = 13.sp, color = ink)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Action Card 2: Focus Time Proposal
                                    if (message.actionType == ChatActionType.FOCUS_TIME_PROPOSAL && message.focusStartMillis != null && message.focusEndMillis != null) {
                                        var focusScheduled by remember { mutableStateOf(message.focusActionCompleted) }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = Color.White,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, teal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Schedule,
                                                            contentDescription = null,
                                                            tint = teal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "${message.focusDurationMinutes} Min Focus Session",
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = ink
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${timeFormat.format(message.focusStartMillis)} – ${timeFormat.format(message.focusEndMillis)}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = muted
                                                    )
                                                }

                                                if (focusScheduled) {
                                                    Surface(
                                                        shape = RoundedCornerShape(100.dp),
                                                        color = cream
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = teal,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = "Scheduled",
                                                                color = teal,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Button(
                                                        onClick = {
                                                            focusScheduled = true
                                                            onBlockFocusTime(message.focusStartMillis, message.focusEndMillis, "Focus Time", message.id)
                                                        },
                                                        shape = RoundedCornerShape(100.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    ) {
                                                        Text("Block Time", fontSize = 12.sp, color = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // S3-6 — Action Card 3: Priority
                                    // Apply (feature 22, Q5 = (a)). The
                                    // DAILY_PLAN reply lists the day in
                                    // priority order; this card applies
                                    // that order to My Day (plannedStart
                                    // stamping, the Plan pick path).
                                    // Renders only on the message the
                                    // pending order is attached to —
                                    // once applied or dismissed, the
                                    // ViewModel clears the pending id and
                                    // the card disappears, leaving the
                                    // numbered reply in history.
                                    if (message.actionType == ChatActionType.DAILY_PLAN &&
                                        message.id == pendingPriorityMessageId
                                    ) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().testTag("priority_apply_card"),
                                            shape = RoundedCornerShape(16.dp),
                                            color = Color.White,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, teal.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Apply this order to My Day?",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    ),
                                                    color = ink,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Button(
                                                    onClick = onApplyPriorityOrder,
                                                    shape = RoundedCornerShape(100.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = teal),
                                                    modifier = Modifier.testTag("priority_apply_button").padding(start = 8.dp)
                                                ) {
                                                    Text("Apply", fontSize = 12.sp, color = Color.White)
                                                }
                                                TextButton(
                                                    onClick = onDismissPriorityOrder,
                                                    modifier = Modifier.testTag("priority_dismiss_button")
                                                ) {
                                                    Text("Not now", fontSize = 12.sp, color = muted)
                                                }
                                            }
                                        }
                                    }

                                    // Contextual Quick Suggestions chips under Nudgy message
                                    if (message.quickSuggestions.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            message.quickSuggestions.forEach { suggestion ->
                                                Surface(
                                                    shape = RoundedCornerShape(100.dp),
                                                    color = Color.White,
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.25f)),
                                                    // C1: disabled while thinking (see
                                                    // quick-prompt pills above).
                                                    modifier = Modifier.clickable(enabled = !isThinking) { onSendMessage(suggestion) }
                                                ) {
                                                    Text(
                                                        text = suggestion,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium
                                                        ),
                                                        color = ink,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Voice Listening Indicator Banner. During a brain dump
                // (S3-4) the banner stays up between utterances — the
                // recognizer's isListening flickers false while the
                // session restarts it — and shows the transcript heard
                // so far, so the user sees what was caught.
                if (isListening || isBrainDumpActive) {
                    item(key = "listening_banner") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = cream
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = teal,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isBrainDumpActive) "Brain dump — keep talking, tap Stop when you're done" else "Listening... Speak now",
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                        color = ink
                                    )
                                    if (isBrainDumpActive && brainDumpTranscript.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = brainDumpTranscript,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                            color = muted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Hero — Sprint 7: the intro paragraph moved out to
                // the fixed header above (prototype scr-chat); only
                // the quick-prompt pills stay here, at the top of the
                // thread. The pills are behaviour (S3), not
                // decoration: tapping one sends that command.
                item(key = "hero") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp)
                    ) {
                        // Quick Prompt Pills
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickPrompts.forEach { prompt ->
                                Surface(
                                    shape = RoundedCornerShape(100.dp),
                                    color = Color.Transparent,
                                    border = androidx.compose.foundation.BorderStroke(1.2.dp, muted.copy(alpha = 0.35f)),
                                    // C1: disabled while a reply is pending —
                                    // a second prompt must not start a second
                                    // send run (duplicate tasks / double-spent
                                    // turns). The guard in ChatViewModel
                                    // .sendMessage is the backstop; this is
                                    // the visible one.
                                    modifier = Modifier.clickable(enabled = !isThinking) { onSendMessage(prompt) }
                                ) {
                                    Text(
                                        text = prompt,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        ),
                                        color = ink,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Ads: rewarded entry — shown ONLY when the free daily chat cap
            // is hit. The reward (+1 AI turn) is granted only after a
            // completed ad view; a closed or skipped ad grants nothing.
            // Local (preFilter) commands still work while capped.
            if (chatCapState?.isCapped == true) {
                // A failed rewarded load leaves the button tappable as a
                // retry — the user must never be stranded on "Loading ad…".
                val showRewardedRetry = !rewardedAdReady && rewardedLoadError != null
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, muted.copy(alpha = 0.25f)),
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Want 1 more answer?",
                            color = ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (chatCapState.canEarnMoreRewarded) {
                                "Watch a short ad to unlock 1 more AI answer. " +
                                    "(${chatCapState.rewardedTurnsGrantedToday} of " +
                                    "${chatCapState.maxRewardedTurnsPerDay} bonus answers used today)"
                            } else {
                                "That's all ${chatCapState.maxRewardedTurnsPerDay} bonus answers for today — " +
                                    "fresh free answers tomorrow."
                            },
                            color = muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = if (showRewardedRetry) onRetryRewardedAd else onWatchRewardedAd,
                            enabled = chatCapState.canEarnMoreRewarded &&
                                (rewardedAdReady || rewardedLoadError != null),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = teal,
                                contentColor = Color.White,
                                disabledContainerColor = muted,
                                disabledContentColor = Color.White
                            ),
                            shape = RoundedCornerShape(100.dp),
                            // S3-4 — the smoke test taps the offer by
                            // tag (clicking merged Button text is
                            // fragile across Compose versions).
                            modifier = Modifier.testTag("rewarded_watch_button")
                        ) {
                            Text(
                                text = when {
                                    rewardedAdReady || !chatCapState.canEarnMoreRewarded -> "Watch ad for +1 answer"
                                    showRewardedRetry -> "Ad didn't load — tap to retry"
                                    else -> "Loading ad…"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Input Bar — the shared ChatInputBar (the same bar the
            // Quick Chat sheet uses). The floating push-to-talk button
            // is gone (S3-1 restyle): the mic in the bar is the one
            // voice entry point. S3-4: the mic shows Stop while a
            // brain-dump session is open, even between utterances.
            ChatInputBar(
                isThinking = isThinking,
                isListening = isListening || isBrainDumpActive,
                onSendMessage = onSendMessage,
                onStartVoice = onStartVoice,
                onStopVoice = onStopVoice,
                voiceLanguage = voiceLanguage,
                onToggleVoiceLanguage = onToggleVoiceLanguage
            )
            // Sprint 7 — footer note (prototype-verbatim): the
            // assistant never saves directly.
            Text(
                text = "The assistant never saves directly — every task appears as a confirmation card first.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
        }

        // S3-2 — draft edit dialog: title + date + time. The picker
        // millis are UTC midnight (DatePicker contract): the initial
        // value is built in UTC from the draft's local date, and the
        // picked value comes back through Instant + system ZoneId —
        // never a raw local Calendar read of UTC millis (that shifts
        // the day in negative-offset timezones).
        if (editingDraft != null) {
            val base = editingDraft!!
            AlertDialog(
                onDismissRequest = { editingDraft = null },
                title = { Text("Edit draft") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("draft_edit_title")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showEditDatePicker = true },
                                modifier = Modifier.testTag("draft_edit_date_button")
                            ) {
                                Text(draftDateFormat.format(editStartMillis), color = teal)
                            }
                            TextButton(
                                onClick = { showEditTimePicker = true },
                                modifier = Modifier.testTag("draft_edit_time_button")
                            ) {
                                Text(timeFormat.format(editStartMillis), color = teal)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = base.withTitle(editTitle.trim().ifBlank { base.title })
                            .withStartTime(editStartMillis)
                        onDraftEdited(updated)
                        editingDraft = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingDraft = null }) { Text("Cancel") }
                }
            )
        }
        if (showEditDatePicker && editingDraft != null) {
            val (year, month, day) = localDateParts(editStartMillis)
            val utcMidnight = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            NudgyDatePickerDialog(
                initialDateMillis = utcMidnight,
                onDateSelected = { dateMillis ->
                    showEditDatePicker = false
                    val picked = Instant.ofEpochMilli(dateMillis)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    val cal = Calendar.getInstance().apply { timeInMillis = editStartMillis }
                    editStartMillis = Calendar.getInstance().apply {
                        set(Calendar.YEAR, picked.year)
                        set(Calendar.MONTH, picked.monthValue - 1)
                        set(Calendar.DAY_OF_MONTH, picked.dayOfMonth)
                        set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                        set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                },
                onDismiss = { showEditDatePicker = false }
            )
        }
        if (showEditTimePicker && editingDraft != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = editStartMillis }
            NudgyTimePickerDialog(
                initialHour = cal.get(Calendar.HOUR_OF_DAY),
                initialMinute = cal.get(Calendar.MINUTE),
                onTimeSelected = { hour, minute ->
                    showEditTimePicker = false
                    val (year, month, day) = localDateParts(editStartMillis)
                    editStartMillis = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                },
                onDismiss = { showEditTimePicker = false }
            )
        }
    }
}
