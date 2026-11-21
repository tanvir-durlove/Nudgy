package com.nextgentool.nudgy.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.components.ChatInputBar
import com.nextgentool.nudgy.ui.screens.ChatMessage
import com.nextgentool.nudgy.ui.viewmodel.ChatVoiceLanguage

/**
 * Sprint 3-1 — Quick Chat sheet (spec Q8(a)): the centre + button
 * opens this compact sheet — input + the last few messages — over
 * whatever tab the user is on. The first message sent here, or the
 * expand button, moves to the full chat; the sheet and the full chat
 * share the same ChatViewModel, so history survives leaving and
 * re-entering the sheet.
 *
 * The sheet never saves anything itself: it only forwards to the
 * shared send pipeline (guardrails → prefilter → local fallback).
 * Action cards (task / event / focus) live in the full chat only —
 * here the recent messages are plain text bubbles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickChatSheet(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    isListening: Boolean,
    onSendMessage: (String) -> Unit,
    onExpandToFullChat: () -> Unit,
    onDismiss: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    // Sprint 3-7 (Q6 = (a)) — the explicit EN/বাংলা voice toggle,
    // passed through to the shared input bar (same ViewModel state
    // as the full chat, so the toggle never disagrees).
    voiceLanguage: ChatVoiceLanguage = ChatVoiceLanguage.ENGLISH,
    onToggleVoiceLanguage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val teal = colorResource(R.color.nudgy_teal)
    val ink = colorResource(R.color.nudgy_ink)
    val muted = colorResource(R.color.nudgy_muted)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colorResource(R.color.nudgy_background),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Chat",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ink
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onExpandToFullChat) {
                    Icon(
                        imageVector = Icons.Filled.OpenInFull,
                        contentDescription = "Open full chat",
                        tint = teal
                    )
                }
            }

            // The last few messages only — a compact glance, not the
            // full history. Plain text bubbles; the newest message is
            // at the bottom, directly above the input bar.
            val recent = messages.takeLast(RECENT_MESSAGE_COUNT)
            if (recent.isEmpty()) {
                Text(
                    text = "What can I help you get done?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = muted
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { message ->
                        if (message.senderIsUser) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp).copy(bottomEnd = CornerSize(4.dp)),
                                    color = teal
                                ) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp).copy(bottomStart = CornerSize(4.dp)),
                                    color = Color.White,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.25f))
                                ) {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = ink),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ChatInputBar(
                isThinking = isThinking,
                isListening = isListening,
                onSendMessage = { text ->
                    onSendMessage(text)
                    onExpandToFullChat()
                },
                onStartVoice = onStartVoice,
                onStopVoice = onStopVoice,
                voiceLanguage = voiceLanguage,
                onToggleVoiceLanguage = onToggleVoiceLanguage
            )
        }
    }
}

/** How many of the newest messages the compact sheet shows. */
internal const val RECENT_MESSAGE_COUNT = 3
