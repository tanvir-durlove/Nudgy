package com.example.ui.sheets

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildApkActivity
import com.example.auth.AuthManager
import com.example.auth.NudgyUser
import com.example.data.local.entity.NudgyMemoryEntity
import com.example.ui.components.NudgyInsightCard
import com.example.ui.theme.NudgyAvatarGold
import com.example.ui.theme.NudgyBorder
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourNudgySheet(
    user: NudgyUser,
    authManager: AuthManager,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    memories: List<NudgyMemoryEntity> = emptyList(),
    onAddMemory: (String) -> Unit = {},
    onDeleteMemory: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onNavigateCalendar: () -> Unit = {}
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showPreferencesDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showMemoriesDialog by remember { mutableStateOf(false) }
    var newMemoryInput by remember { mutableStateOf("") }

    var selectedPersona by remember { mutableStateOf("Gentle & Encouraging") }
    var morningBriefEnabled by remember { mutableStateOf(true) }
    var reminderSoundEnabled by remember { mutableStateOf(true) }
    var voiceRepliesEnabled by remember { mutableStateOf(true) }
    var selectedLanguage by remember { mutableStateOf("English (US)") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Nudgy",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NudgyCharcoal
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = NudgyCharcoal
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Profile Card (Matches Figma Screenshot 1: pale yellow avatar circle + peach card)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = NudgyPeach
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(NudgyAvatarGold),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = user.initials,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = NudgyCharcoal
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            ),
                            color = NudgyCharcoal
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Menu Items List
            NudgyMenuItem(
                icon = Icons.Outlined.Person,
                title = "Profile & preferences",
                onClick = { showPreferencesDialog = true }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.Notifications,
                title = "Notifications & Reminders",
                onClick = { showNotificationsDialog = true }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.GraphicEq,
                title = "Voice & language",
                onClick = { showVoiceDialog = true }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.CalendarMonth,
                title = "Calendar connections",
                onClick = {
                    onDismiss()
                    onNavigateCalendar()
                }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Default.PlayArrow,
                title = "Build APK in GitHub Actions",
                onClick = {
                    onDismiss()
                    context.startActivity(Intent(context, BuildApkActivity::class.java))
                }
            )

            Spacer(modifier = Modifier.height(22.dp))

            // "Nudgy remembers" Highlight Card
            NudgyInsightCard(
                title = "Nudgy remembers",
                subtitle = memories.firstOrNull()?.content ?: "You prefer focus time in the morning and studying at 7 PM.",
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${memories.size} facts",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = NudgyPrimary
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "View memories",
                            tint = NudgyPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                onClick = { showMemoriesDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Account Actions (Sign Out & Delete Account)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        authManager.signOut(scope) {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = NudgyCharcoal
                    )
                ) {
                    Text("Sign out")
                }

                TextButton(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text("Delete account")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPreferencesDialog) {
        AlertDialog(
            onDismissRequest = { showPreferencesDialog = false },
            title = { Text("Profile & Persona", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CHOOSE NUDGY'S PERSONA", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    listOf("Gentle & Encouraging", "Direct & Focused", "Sassy & Playful").forEach { persona ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPersona = persona }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedPersona == persona,
                                onClick = { selectedPersona = persona },
                                colors = RadioButtonDefaults.colors(selectedColor = NudgyPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(persona, style = MaterialTheme.typography.bodyMedium, color = NudgyCharcoal)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPreferencesDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Save", color = Color.White)
                }
            }
        )
    }

    if (showNotificationsDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationsDialog = false },
            title = { Text("Notification Preferences", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Morning Brief Alert", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Receive schedule summary at 8:00 AM", fontSize = 12.sp, color = NudgyMuted)
                        }
                        Switch(
                            checked = morningBriefEnabled,
                            onCheckedChange = { morningBriefEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = NudgyPrimary)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sound & Haptics", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Play sound when a reminder triggers", fontSize = 12.sp, color = NudgyMuted)
                        }
                        Switch(
                            checked = reminderSoundEnabled,
                            onCheckedChange = { reminderSoundEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = NudgyPrimary)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showNotificationsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Done", color = Color.White)
                }
            }
        )
    }

    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Voice & Speech", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Spoken Audio Replies", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Nudgy speaks responses aloud", fontSize = 12.sp, color = NudgyMuted)
                        }
                        Switch(
                            checked = voiceRepliesEnabled,
                            onCheckedChange = { voiceRepliesEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = NudgyPrimary)
                        )
                    }

                    Text("SPEECH LANGUAGE", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    listOf("English (US)", "Bengali (Bangladesh)").forEach { lang ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLanguage = lang }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedLanguage == lang,
                                onClick = { selectedLanguage = lang },
                                colors = RadioButtonDefaults.colors(selectedColor = NudgyPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(lang, style = MaterialTheme.typography.bodyMedium, color = NudgyCharcoal)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showVoiceDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Save", color = Color.White)
                }
            }
        )
    }

    if (showMemoriesDialog) {
        AlertDialog(
            onDismissRequest = { showMemoriesDialog = false },
            title = {
                Text("Nudgy Memories & Knowledge", fontWeight = FontWeight.Bold, color = NudgyCharcoal)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Things Nudgy has learned about your routine, preferences, and focus habits:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted
                    )

                    // Add new memory input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newMemoryInput,
                            onValueChange = { newMemoryInput = it },
                            placeholder = { Text("Teach Nudgy a preference...", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (newMemoryInput.isNotBlank()) {
                                    onAddMemory(newMemoryInput.trim())
                                    newMemoryInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                            enabled = newMemoryInput.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }

                    // List of memories
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (memories.isEmpty()) {
                            Text(
                                text = "No memories stored yet. Add one above or tell Nudgy in chat: 'Remember that...'",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            memories.forEach { memory ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = NudgyPeach.copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, NudgyBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = memory.content,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NudgyCharcoal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { onDeleteMemory(memory.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete memory",
                                                tint = Color(0xFFD32F2F),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showMemoriesDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Done", color = Color.White)
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Account?") },
            text = { Text("This will permanently remove your account, tasks, and Nudgy memory. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        authManager.deleteAccount(scope, onComplete = onDismiss)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NudgyMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NudgyCharcoal,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = NudgyCharcoal
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NudgyMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}
