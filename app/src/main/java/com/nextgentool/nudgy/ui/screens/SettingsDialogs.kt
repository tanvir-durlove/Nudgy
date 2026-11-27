package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.NudgyMemoryEntity
import com.nextgentool.nudgy.settings.SettingsPreferences
import com.nextgentool.nudgy.ui.theme.NudgyBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPeach
import com.nextgentool.nudgy.ui.theme.NudgyPrimary

/**
 * Sprint 6-1 — the Settings dialogs (spec §4–§5, v1 texts verbatim).
 *
 * These are the YourNudgySheet dialogs (origin/main) moved into the
 * Settings tab: the BYOK dialog, the Memories dialog and the delete
 * confirm dialog. The sheet itself stays as-is (zero callers) — the
 * texts and behaviour are carried, not the sheet.
 *
 * HARD RULE (spec §2 Q11): the Memories empty state is the honest
 * v1 dialog text — NEVER the hardcoded fake example v1's "Nudgy
 * remembers" card showed with zero memories ("You prefer focus time
 * in the morning and studying at 7 PM."). That was a v1 bug.
 */

/** The Privacy Policy link (v1 YourNudgySheet PRIVACY_POLICY_URL). */
const val PRIVACY_POLICY_URL = "https://tanvir-durlove.github.io/nudgy/privacy-policy.html"

/** The Help & feedback address (PR #52's idea, built from scratch in S6-1). */
const val HELP_FEEDBACK_EMAIL = "nextgentoolbd@gmail.com"

/**
 * The Gemini API Key (BYOK) dialog (v1 YourNudgySheet).
 * Save with a blank field removes the key ("Using Nudgy's built-in
 * AI"); Reset removes it too.
 */
@Composable
fun ByokKeyDialog(
    initialKey: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialKey) }
    AlertDialog(
        modifier = Modifier.testTag("settings_byok_dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_byok_title), fontWeight = FontWeight.Bold, color = NudgyCharcoal)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_byok_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NudgyMuted
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("AIzaSy...", color = NudgyMuted) },
                    label = { Text(stringResource(R.string.settings_byok_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_byok_input"),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(input.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(stringResource(R.string.settings_byok_save), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.settings_byok_reset))
            }
        }
    )
}

/**
 * The Memories dialog (v1 "Nudgy Memories & Knowledge").
 * Add row + deletable list; the empty state is honest — no fake
 * example memory is ever shown.
 */
@Composable
fun MemoriesDialog(
    memories: List<NudgyMemoryEntity>,
    onAddMemory: (String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newMemoryInput by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.testTag("settings_memories_dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_memories_title), fontWeight = FontWeight.Bold, color = NudgyCharcoal)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_memories_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newMemoryInput,
                        onValueChange = { newMemoryInput = it },
                        placeholder = { Text(stringResource(R.string.settings_memories_hint), fontSize = 12.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_memory_input"),
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
                        Text(stringResource(R.string.settings_memories_add))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (memories.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_memories_empty_dialog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .testTag("settings_memories_empty")
                        )
                    } else {
                        memories.forEach { memory ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_memory_item"),
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
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(stringResource(R.string.settings_memories_done), color = Color.White)
            }
        }
    )
}

/**
 * Step 2-2 — the Profile Edit dialog (name only, no photo).
 *
 * The field prefills with the current name and caps at 256
 * characters (the Firebase displayName limit). Save is disabled
 * while the field is blank or a rename is already running; the
 * dialog stays open on failure (the ViewModel toasts the error)
 * and closes only on [SettingsViewModel.renameCompleted].
 */
@Composable
fun ProfileEditDialog(
    initialName: String,
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialName) }
    AlertDialog(
        modifier = Modifier.testTag("settings_profile_edit_dialog"),
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(stringResource(R.string.settings_profile_edit_title), fontWeight = FontWeight.Bold, color = NudgyCharcoal)
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(256) },
                label = { Text(stringResource(R.string.settings_profile_edit_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_edit_field"),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(input.trim()) },
                enabled = input.isNotBlank() && !isSaving,
                modifier = Modifier.testTag("profile_edit_save"),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
            ) {
                Text(stringResource(R.string.settings_byok_save), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.testTag("profile_edit_cancel")
            ) {
                Text(stringResource(R.string.settings_delete_cancel))
            }
        }
    )
}

/**
 * The delete-account confirm dialog (v1 YourNudgySheet, C7).
 * It stays up while the deletion runs — it closes only on success
 * ([SettingsViewModel.deleteCompleted]) or an explicit Cancel — so
 * a failure is never hidden by a dismissed dialog.
 */
@Composable
fun DeleteAccountDialog(
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag("settings_delete_dialog"),
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text(stringResource(R.string.settings_delete_title)) },
        text = {
            Text(stringResource(R.string.settings_delete_body))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text(
                    if (isDeleting) stringResource(R.string.settings_delete_deleting)
                    else stringResource(R.string.settings_delete_confirm)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting
            ) {
                Text(stringResource(R.string.settings_delete_cancel))
            }
        }
    )
}

/**
 * Sprint 6-2 — the Language dialog (spec §2 Q10=(a)).
 *
 * English / বাংলা. Selecting one writes the preference and the
 * NavHost restarts the Activity (AppLocale.applyAndRecreate), so
 * the whole app re-renders in the chosen language. Bengali
 * strings cover the Settings screen + the main screens
 * (Today/Tasks/navigation) this sprint; the rest follows later.
 */
@Composable
fun LanguageDialog(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPreferences.LANGUAGE_ENGLISH to stringResource(R.string.settings_language_english),
        SettingsPreferences.LANGUAGE_BENGALI to stringResource(R.string.settings_language_bengali)
    )
    AlertDialog(
        modifier = Modifier.testTag("settings_language_dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_section_language), fontWeight = FontWeight.Bold, color = NudgyCharcoal)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { (code, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentLanguage == code,
                                onClick = { onSelect(code) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp)
                            .testTag("settings_language_$code")
                    ) {
                        RadioButton(
                            selected = currentLanguage == code,
                            onClick = { onSelect(code) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyCharcoal,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_memories_done))
            }
        }
    )
}
