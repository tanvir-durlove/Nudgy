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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val quickSuggestions: List<String> = emptyList()
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
    // Ads: rewarded entry — non-null only when the free daily chat cap is
    // hit. The button grants +1 AI turn only after a completed ad view.
    chatCapState: ChatCapState? = null,
    rewardedAdReady: Boolean = false,
    rewardedLoadError: String? = null,
    onWatchRewardedAd: () -> Unit = {},
    onRetryRewardedAd: () -> Unit = {},
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
                                shape = RoundedCornerShape(18.dp).copy(bottomEnd = androidx.compose.foundation.shape.CornerSize(4.dp)),
                                color = teal
                            ) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
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
                                        shape = RoundedCornerShape(18.dp).copy(bottomStart = androidx.compose.foundation.shape.CornerSize(4.dp)),
                                        color = Color.White,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.25f))
                                    ) {
                                        Text(
                                            text = message.text,
                                            style = MaterialTheme.typography.bodyLarge.copy(color = ink),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
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
                                    // draft. Confirm saves, Edit changes
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
                                            shape = RoundedCornerShape(16.dp),
                                            color = cream,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, teal.copy(alpha = 0.3f))
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
                                                Text(
                                                    text = when (draft.kind) {
                                                        DraftKind.TASK -> "Due: ${draftDateTimeFormat.format(draft.startEpochMillis)} • Priority: ${draft.priority.name.lowercase().replaceFirstChar { it.uppercase() }}"
                                                        DraftKind.REMINDER -> "Reminder: ${draftDateTimeFormat.format(draft.startEpochMillis)}"
                                                        DraftKind.SCHEDULE -> "${draftDateTimeFormat.format(draft.startEpochMillis)} – ${timeFormat.format(draft.endEpochMillis ?: draft.startEpochMillis)} • ${draft.scheduleLocation ?: "Google Meet"}"
                                                        DraftKind.FOCUS_EVENT -> "${draftDateTimeFormat.format(draft.startEpochMillis)} – ${timeFormat.format(draft.endEpochMillis ?: draft.startEpochMillis)} • ${draft.focusDurationMinutes} min"
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
                                                        Text("Confirm", fontSize = 13.sp, color = Color.White)
                                                    }
                                                    TextButton(
                                                        onClick = {
                                                            editingDraft = draft
                                                            editTitle = draft.title
                                                            editStartMillis = draft.startEpochMillis
                                                        },
                                                        modifier = Modifier.testTag("draft_edit_button")
                                                    ) {
                                                        Text("Edit", fontSize = 13.sp, color = teal)
                                                    }
                                                    TextButton(
                                                        onClick = onDiscardDraft,
                                                        modifier = Modifier.testTag("draft_discard_button")
                                                    ) {
                                                        Text("Discard", fontSize = 13.sp, color = muted)
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

                // Voice Listening Indicator Banner
                if (isListening) {
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
                                Text(
                                    text = "Listening... Speak now",
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                    color = ink
                                )
                            }
                        }
                    }
                }

                // Hero & Greeting — text only, no mascot (S3-1: the
                // rebuild chat has no mascot anywhere).
                item(key = "hero") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "YOUR PRODUCTIVITY PARTNER",
                            style = MaterialTheme.typography.labelSmall,
                            color = teal
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "What can I help you\nget done?",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp
                            ),
                            textAlign = TextAlign.Center,
                            color = ink
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "I can manage tasks, schedule focus time, and keep your day moving in English or বাংলা.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = muted,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

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
                            shape = RoundedCornerShape(100.dp)
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
            // voice entry point.
            ChatInputBar(
                isThinking = isThinking,
                isListening = isListening,
                onSendMessage = onSendMessage,
                onStartVoice = onStartVoice,
                onStopVoice = onStopVoice,
                modifier = Modifier.padding(bottom = 12.dp)
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
