package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ads.ChatCapState
import com.example.auth.NudgyUser
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.TaskEntity
import com.example.ui.components.MascotMood
import com.example.ui.components.NudgyMascot
import com.example.ui.theme.NudgyAccent
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyButter
import com.example.ui.theme.NudgyCardBorder
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyLavender
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import com.example.ui.theme.NudgySage
import java.text.SimpleDateFormat
import java.util.Locale

enum class ChatActionType {
    NONE,
    TASK_PREVIEW,
    EVENT_CREATED,
    FOCUS_TIME_PROPOSAL,
    DAILY_PLAN
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
    // MainActivity.handleUserChat ("ভাবছি…" / "শিডিউল করছি…" /
    // "টাস্ক তৈরি করছি…", or the English equivalents).
    thinkingText: String = "Nudgy is thinking...",
    onSendMessage: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onTaskClick: (TaskEntity) -> Unit = {},
    onBlockFocusTime: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
    // Ads: rewarded entry — non-null only when the free daily chat cap is
    // hit. The button grants +1 AI turn only after a completed ad view.
    chatCapState: ChatCapState? = null,
    rewardedAdReady: Boolean = false,
    rewardedLoadError: String? = null,
    onWatchRewardedAd: () -> Unit = {},
    onRetryRewardedAd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onStartVoice()
        }
    }

    fun handleVoiceClick() {
        if (isListening) {
            onStopVoice()
            return
        }
        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            onStartVoice()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val quickPrompts = listOf(
        "Plan my day",
        "Show today's tasks",
        "Find free time",
        "Block 45m focus",
        "What do you remember?",
        "আজকের সব কাজ দেখাও"
    )

    LaunchedEffect(messages.size, isThinking) {
        val count = messages.size + if (isThinking) 1 else 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    val currentMascotMood = when {
        isSpeaking -> MascotMood.TALKING
        isListening -> MascotMood.LISTENING
        isThinking -> MascotMood.THINKING
        else -> MascotMood.IDLE
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Mascot & Greeting
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NudgyMascot(
                            size = 100.dp,
                            mood = currentMascotMood
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "YOUR PRODUCTIVITY PARTNER",
                            style = MaterialTheme.typography.labelSmall,
                            color = NudgyAccent
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "What can I help you\nget done?",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp
                            ),
                            textAlign = TextAlign.Center,
                            color = NudgyCharcoal
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "I can manage tasks, schedule focus time, and keep your day moving in English or বাংলা.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = NudgyMuted,
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
                                    border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFE2DCD1)),
                                    // C1: disabled while a reply is pending —
                                    // a second prompt must not start a second
                                    // handleUserChat run (duplicate tasks /
                                    // double-spent turns). The guard in
                                    // MainActivity.handleUserChat is the
                                    // backstop; this is the visible one.
                                    modifier = Modifier.clickable(enabled = !isThinking) { onSendMessage(prompt) }
                                ) {
                                    Text(
                                        text = prompt,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        ),
                                        color = NudgyCharcoal,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Voice Listening Indicator Banner
                if (isListening) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = NudgyButter
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = NudgyPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "🎙️ Listening... Speak now",
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                                    color = NudgyCharcoal
                                )
                            }
                        }
                    }
                }

                // Chat Message Stream
                items(messages, key = { it.id }) { message ->
                    if (message.senderIsUser) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp).copy(bottomEnd = androidx.compose.foundation.shape.CornerSize(4.dp)),
                                color = NudgyPrimary
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
                                NudgyMascot(
                                    size = 32.dp,
                                    mood = if (isSpeaking) MascotMood.TALKING else MascotMood.IDLE,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp).copy(bottomStart = androidx.compose.foundation.shape.CornerSize(4.dp)),
                                        color = NudgyCard,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
                                    ) {
                                        Text(
                                            text = message.text,
                                            style = MaterialTheme.typography.bodyLarge.copy(color = NudgyCharcoal),
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
                                            color = NudgyPeach,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyPrimary.copy(alpha = 0.3f))
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
                                                            tint = NudgyPrimary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = task.title,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = NudgyCharcoal
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Priority: ${task.priority.name.lowercase().replaceFirstChar { it.uppercase() }} • Due: ${timeFormat.format(task.dueDateEpochMillis)}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = NudgyMuted
                                                    )
                                                }

                                                Button(
                                                    onClick = { onTaskClick(task) },
                                                    shape = RoundedCornerShape(100.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
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
                                            color = NudgyPeach,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyPrimary.copy(alpha = 0.3f))
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
                                                            tint = NudgyPrimary,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = event.title,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = NudgyCharcoal
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${timeFormat.format(event.startEpochMillis)} – ${timeFormat.format(event.endEpochMillis)} • ${event.locationOrLink ?: "Google Meet"}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = NudgyMuted
                                                    )
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(100.dp),
                                                    color = Color(0xFFE8F5E9)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = Color(0xFF2E7D32),
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Scheduled", color = Color(0xFF2E7D32), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                                            color = NudgyLavender,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7A68E6).copy(alpha = 0.3f))
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
                                                            tint = Color(0xFF5B45E0),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "${message.focusDurationMinutes} Min Focus Session",
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 15.sp
                                                            ),
                                                            color = NudgyCharcoal
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${timeFormat.format(message.focusStartMillis)} – ${timeFormat.format(message.focusEndMillis)}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                        color = NudgyMuted
                                                    )
                                                }

                                                if (focusScheduled) {
                                                    Surface(
                                                        shape = RoundedCornerShape(100.dp),
                                                        color = Color(0xFFE8F5E9)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = Color(0xFF2E7D32),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = "Scheduled",
                                                                color = Color(0xFF2E7D32),
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
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B45E0)),
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
                                                    color = NudgyCard,
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder),
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
                                                        color = NudgyCharcoal,
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

                if (isThinking) {
                    item(key = "thinking_indicator") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NudgyMascot(size = 28.dp, mood = MascotMood.THINKING)
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = NudgyCard,
                                border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
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
                                                .background(NudgyMuted, CircleShape)
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
                                        color = NudgyMuted
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
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
                    color = NudgyCard,
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, NudgyCardBorder),
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
                            color = NudgyCharcoal,
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
                            color = NudgyMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = if (showRewardedRetry) onRetryRewardedAd else onWatchRewardedAd,
                            enabled = chatCapState.canEarnMoreRewarded &&
                                (rewardedAdReady || rewardedLoadError != null),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NudgyAccent,
                                contentColor = Color.White,
                                disabledContainerColor = NudgyMuted,
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

            // Input Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(100.dp),
                color = NudgyCard,
                border = androidx.compose.foundation.BorderStroke(1.2.dp, NudgyCardBorder),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = if (isListening) "Listening to voice..." else "Ask Nudgy anything...",
                                color = NudgyMuted,
                                fontSize = 14.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    IconButton(onClick = { handleVoiceClick() }) {
                        Icon(
                            imageVector = if (isListening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                            contentDescription = "Voice input",
                            tint = if (isListening) NudgyPrimary else NudgyMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(NudgyPrimary)
                            .clickable {
                                // C1: dead while a reply is pending.
                                if (!isThinking && inputText.isNotBlank()) {
                                    val textToSend = inputText.trim()
                                    inputText = ""
                                    onSendMessage(textToSend)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isThinking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Floating push-to-talk waveform button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 74.dp)
                .size(54.dp)
                .clip(CircleShape)
                .background(if (isListening) NudgyPrimary else MaterialTheme.colorScheme.inverseSurface)
                .clickable { handleVoiceClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = "Push to talk",
                tint = if (isListening) Color.White else MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
