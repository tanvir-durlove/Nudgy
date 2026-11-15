package com.example.ui.sheets

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.auth.AuthManager
import com.example.auth.NudgyUser
import com.example.data.local.entity.NudgyMemoryEntity
import com.example.ui.components.NudgyInsightCard
import com.example.ui.theme.NudgyAccent
import com.example.ui.theme.NudgyAvatarGold
import com.example.ui.theme.NudgyBorder
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPeach
import com.example.ui.theme.NudgyPrimary
import kotlinx.coroutines.CoroutineScope

private const val PRIVACY_POLICY_URL = "https://tanvir-durlove.github.io/nudgy/privacy-policy.html"
private const val FEEDBACK_EMAIL = "nextgentoolbd@gmail.com"

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
    onNavigateCalendar: () -> Unit = {},
    themeMode: String = "system",
    onThemeModeChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsPrefs = remember { context.getSharedPreferences("nudgy_settings", android.content.Context.MODE_PRIVATE) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showMemoriesDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var customApiKeyInput by remember { mutableStateOf(settingsPrefs.getString("custom_gemini_api_key", "") ?: "") }
    var newMemoryInput by remember { mutableStateOf("") }
    // C7: deletion progress — drives the Delete Forever button state
    // (progress label + disabled while the deletion runs).
    val isDeleting by authManager.isLoading.collectAsStateWithLifecycle()

    var morningBriefEnabled by remember { mutableStateOf(true) }
    var reminderSoundEnabled by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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

            if (user.isGuest) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFF3E0),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFFFB74D))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFE0B2)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "G",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        ),
                                        color = Color(0xFFE65100)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Guest Mode",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        ),
                                        color = NudgyCharcoal
                                    )
                                }
                            }

                            Switch(
                                checked = true,
                                onCheckedChange = { isChecked ->
                                    if (!isChecked) {
                                        val activity = context as? android.app.Activity
                                        if (activity != null) {
                                            authManager.signInWithGoogle(
                                                activity = activity,
                                                scope = scope,
                                                onSuccess = {
                                                    Toast.makeText(context, "Signed in with Google", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NudgyPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val activity = context as? android.app.Activity
                        Button(
                            onClick = {
                                if (activity != null) {
                                    authManager.signInWithGoogle(
                                        activity = activity,
                                        scope = scope,
                                        onSuccess = {
                                            Toast.makeText(context, "Signed in with Google", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { error ->
                                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_google_logo),
                                contentDescription = "Google Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sign in with Google to Sync",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            } else {
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
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Menu Items List
            NudgyMenuItem(

                icon = Icons.Outlined.Notifications,
                title = "Notifications & Reminders",
                onClick = { showNotificationsDialog = true }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.DarkMode,
                title = "Appearance",
                onClick = { showThemeDialog = true }
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
                icon = Icons.Outlined.Key,
                title = "Gemini API Key (BYOK)",
                onClick = { showApiKeyDialog = true }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.PrivacyTip,
                title = "Privacy Policy",
                onClick = {
                    onDismiss()
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    )
                }
            )
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.5f))

            NudgyMenuItem(
                icon = Icons.Outlined.Email,
                title = "Help & feedback",
                onClick = {
                    onDismiss()
                    val feedbackIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$FEEDBACK_EMAIL")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, "Nudgy feedback")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Hi Nudgy team,\n\nApp version: ${BuildConfig.VERSION_NAME}\n\n"
                        )
                    }
                    try {
                        context.startActivity(feedbackIntent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
                    }
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
                            color = NudgyAccent
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

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Appearance", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThemeModeChange(mode) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = NudgyPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = NudgyCharcoal)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showThemeDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Done", color = Color.White)
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
            // C7: keep the dialog up while deletion runs — dismissing it
            // on tap used to hide a failure entirely. It closes only on
            // success (onComplete) or an explicit Cancel.
            onDismissRequest = { if (!isDeleting) showDeleteConfirmDialog = false },
            title = { Text("Delete Account?") },
            text = { Text("This will permanently remove your account, tasks, and Nudgy memory. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        // No scope passed on purpose: the deletion runs in
                        // AuthManager's own scope, which survives sign-out.
                        authManager.deleteAccount(
                            onComplete = {
                                showDeleteConfirmDialog = false
                                onDismiss()
                            },
                            // C7: a failed deletion used to be completely
                            // silent (no onError passed, default is a
                            // no-op). Surface the message and keep the
                            // sheet open so the user can retry.
                            onError = { error ->
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text(if (isDeleting) "Deleting…" else "Delete Forever")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API Key (BYOK)", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Bring Your Own Key: Enter your personal Gemini API key from Google AI Studio. Stored privately on this device to protect developer quota.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                    OutlinedTextField(
                        value = customApiKeyInput,
                        onValueChange = { customApiKeyInput = it },
                        placeholder = { Text("AIzaSy...", color = NudgyMuted) },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = customApiKeyInput.trim()
                        if (trimmed.isNotBlank()) {
                            settingsPrefs.edit().putString("custom_gemini_api_key", trimmed).apply()
                            Toast.makeText(context, "Custom Gemini API Key saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            settingsPrefs.edit().remove("custom_gemini_api_key").apply()
                            Toast.makeText(context, "Using Nudgy's built-in AI", Toast.LENGTH_SHORT).show()
                        }
                        showApiKeyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        customApiKeyInput = ""
                        settingsPrefs.edit().remove("custom_gemini_api_key").apply()
                        Toast.makeText(context, "Using Nudgy's built-in AI", Toast.LENGTH_SHORT).show()
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Reset")
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
