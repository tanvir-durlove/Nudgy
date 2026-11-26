package com.nextgentool.nudgy.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.ChatVoiceLanguage

/**
 * Sprint 3-1 — the single chat input bar, shared by the full
 * ChatScreen and the Quick Chat sheet, so both behave identically.
 *
 * Approved design (Sprint 7, prototype scr-chat): a cream mic
 * circle + a white pill field + a teal send circle. The draft
 * survives rotation (rememberSaveable — spec §10.15: rotating with
 * the keyboard open must not lose the draft).
 *
 * Voice is the v1 single utterance (continuous listening / brain dump
 * is S3-4). The send guard is here too: dead while a reply is pending,
 * so a second prompt can never start a second send run.
 *
 * Sprint 3-7 (Q6 = (a)) — the explicit EN/বাংলা voice toggle: a
 * small chip next to the mic shows the CURRENT language ("EN" /
 * "বাংলা"); tapping it flips [voiceLanguage] in the ViewModel (the
 * state lives there, so it survives rotation and is shared by the
 * sheet and the full chat). The mic then listens in that language.
 */
@Composable
fun ChatInputBar(
    isThinking: Boolean,
    isListening: Boolean,
    onSendMessage: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    modifier: Modifier = Modifier,
    voiceLanguage: ChatVoiceLanguage = ChatVoiceLanguage.ENGLISH,
    onToggleVoiceLanguage: () -> Unit = {}
) {
    val context = LocalContext.current
    var inputText by rememberSaveable { mutableStateOf("") }
    val teal = colorResource(R.color.nudgy_teal)
    val cream = colorResource(R.color.nudgy_cream)
    val creamDark = colorResource(R.color.nudgy_cream_dark)
    val ink = colorResource(R.color.nudgy_ink)
    val muted = colorResource(R.color.nudgy_muted)

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

    // Sprint 7 — the prototype chatbar (scr-chat): three separate
    // elements — a cream mic circle, a white pill field, a teal send
    // circle — instead of one pill holding everything. The EN/বাংলা
    // toggle (S3-7) stays, inside the field pill next to the text.
    // The bar is shared, so the Quick Chat sheet gets the same
    // restyle; every behaviour (send guard, draft survival,
    // permission flow) is untouched.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mic: cream circle (prototype micbtn), ink icon — teal
        // while listening, when it shows Stop instead.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(cream)
                .border(BorderStroke(1.dp, creamDark), CircleShape)
                .clickable { handleVoiceClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                contentDescription = "Voice input",
                tint = if (isListening) teal else ink,
                modifier = Modifier.size(22.dp)
            )
        }

        // Field: white pill holding the text field and the
        // voice-language toggle chip.
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(100.dp),
            color = Color.White,
            border = BorderStroke(1.dp, muted.copy(alpha = 0.35f)),
            shadowElevation = NudgyTokens.cardElevation
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
                            text = if (isListening) "Listening to voice..." else "Tell Nudgy what to do…",
                            color = muted,
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

                // Sprint 3-7 — the voice-language toggle: shows the
                // CURRENT language; tapping flips it (the mic then
                // listens in the other language). Never auto-detects.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(
                            if (voiceLanguage == ChatVoiceLanguage.BENGALI) {
                                teal.copy(alpha = 0.12f)
                            } else {
                                muted.copy(alpha = 0.10f)
                            }
                        )
                        .clickable { onToggleVoiceLanguage() }
                        .semantics {
                            contentDescription =
                                "Voice language: ${voiceLanguage.label}. Tap to switch language."
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = voiceLanguage.label,
                        color = if (voiceLanguage == ChatVoiceLanguage.BENGALI) teal else muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Send: teal circle with an up arrow (prototype sendbtn).
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(teal)
                .clickable {
                    // Dead while a reply is pending.
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
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
