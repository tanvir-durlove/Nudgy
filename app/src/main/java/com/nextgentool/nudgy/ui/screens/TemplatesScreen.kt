package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.model.TaskTemplate
import com.nextgentool.nudgy.domain.model.TemplateDraft
import com.nextgentool.nudgy.ui.components.NudgyDatePickerDialog
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TemplatesViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sprint 1-4 — templates screen (Feature 13, spec §6).
 *
 * Lists the 3 approved built-in templates plus the user's own.
 * "Use template" asks for ONE due date (today / tomorrow / a date)
 * and creates all its tasks together at 9:00 local on that day.
 * Own templates can be created / edited / deleted; built-ins are
 * read-only (the ViewModel refuses built-in edits as a backstop).
 * Deleting a template never deletes the tasks it created.
 *
 * Sprint 7 — rebuilt to the prototype (scr-templates): the TopAppBar
 * and the FAB are gone; the top is a back Row ("Templates") + the
 * prototype intro line, then the template list, then a ghost
 * "Create custom template" button that reveals an INLINE create card
 * (label + field + teal Save) in the list flow. Creating uses the
 * same editor form, validation and ViewModel call as the dialog did;
 * the dialog stays for EDITING only. Design-only: the ViewModel, the
 * 3 built-ins, create/update/delete, the due-date flow and the
 * snackbar messages are untouched. Disclosed deviations: the inline
 * card carries a "Cancel" TextButton (the static prototype card has
 * no dismiss, but a real card needs one); the ghost button toggles
 * the card, and hiding it discards what was typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    viewModel: TemplatesViewModel = hiltViewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingTemplate by remember { mutableStateOf<TaskTemplate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var editorTemplate by remember { mutableStateOf<TaskTemplate?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    // Sprint 7 — the inline create card (prototype): its state is
    // hoisted to the screen (not inside the LazyColumn item) so
    // scrolling the card out of view does not discard what was typed.
    var showCreateCard by remember { mutableStateOf(false) }
    var createState by remember { mutableStateOf(TemplateEditorState(null)) }

    // The Scaffold stays for the SnackbarHost only (no topBar, no FAB).
    Scaffold(
        modifier = Modifier.testTag("templates_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NudgyCanvas)
                .padding(padding)
                .padding(vertical = 12.dp)
        ) {
            // Sprint 7 — backbar (prototype): the same back Row
            // TasksScreen uses (back + titleLarge, no TopAppBar).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("templates_backbar")
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NudgyCharcoal
                    )
                }
                Text(
                    text = "Templates",
                    style = MaterialTheme.typography.titleLarge,
                    color = NudgyCharcoal
                )
            }
            Text(
                text = stringResource(R.string.templates_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("templates_list")
            ) {
                if (templates.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.templates_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted
                        )
                    }
                }
                items(templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        onUse = { pendingTemplate = template },
                        onEdit = {
                            editorTemplate = template
                            showEditor = true
                        },
                        onDelete = { viewModel.deleteTemplate(template) }
                    )
                }
                // Sprint 7 — the ghost "Create custom template"
                // button replaces the FAB (prototype .btn-ghost,
                // block). It toggles the inline card below; hiding
                // the card discards what was typed.
                item(key = "create_button") {
                    OutlinedButton(
                        onClick = {
                            if (showCreateCard) {
                                createState = TemplateEditorState(null)
                            }
                            showCreateCard = !showCreateCard
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_template_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.templates_create_button))
                    }
                }
                // Sprint 7 — the inline create card (prototype): the
                // dialog's CREATE-mode content, in the list flow —
                // name field + task rows + teal Save. Same validation
                // and the same ViewModel call as the dialog had.
                if (showCreateCard) {
                    item(key = "create_card") {
                        Surface(
                            shape = NudgyTokens.cardRadius,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = NudgyTokens.cardElevation,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_template_card")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = stringResource(R.string.templates_create_title),
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NudgyCharcoal
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TemplateEditorForm(
                                    state = createState,
                                    usePlaceholders = true,
                                    tagPrefix = "create"
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    enabled = createState.isValid,
                                    onClick = {
                                        viewModel.createTemplate(
                                            createState.name.trim(),
                                            createState.toDrafts()
                                        )
                                        createState = TemplateEditorState(null)
                                        showCreateCard = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colorResource(R.color.nudgy_teal),
                                        contentColor = colorResource(R.color.nudgy_cream)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create_template_save")
                                ) {
                                    Text(stringResource(R.string.templates_create_save))
                                }
                                TextButton(
                                    onClick = {
                                        createState = TemplateEditorState(null)
                                        showCreateCard = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.templates_cancel))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("Due date for ${template.name}") },
            text = {
                Column {
                    Text("All ${template.drafts.size} tasks will share this due date.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.createTasksFromTemplate(
                                template,
                                viewModel.dueEpochMillisFor(LocalDate.now())
                            )
                            pendingTemplate = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "${template.drafts.size} tasks added to Today"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Today") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.createTasksFromTemplate(
                                template,
                                viewModel.dueEpochMillisFor(LocalDate.now().plusDays(1))
                            )
                            pendingTemplate = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "${template.drafts.size} tasks added to Tomorrow"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tomorrow") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pick a date") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text("Cancel") }
            },
            modifier = Modifier.testTag("template_due_dialog")
        )
    }

    if (showDatePicker && pendingTemplate != null) {
        NudgyDatePickerDialog(
            initialDateMillis = System.currentTimeMillis(),
            onDateSelected = { millis ->
                val date = Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                val template = pendingTemplate
                if (template != null) {
                    viewModel.createTasksFromTemplate(
                        template,
                        viewModel.dueEpochMillisFor(date)
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "${template.drafts.size} tasks added"
                        )
                    }
                }
                showDatePicker = false
                pendingTemplate = null
            },
            onDismiss = { showDatePicker = false }
        )
    }

    // Sprint 7 — the dialog is EDIT-only now (creating moved to the
    // inline card); editorTemplate is never null while it is open.
    val editingTemplate = editorTemplate
    if (showEditor && editingTemplate != null) {
        TemplateEditorDialog(
            template = editingTemplate,
            onDismiss = { showEditor = false },
            onSave = { name, drafts ->
                viewModel.updateTemplate(
                    editingTemplate.copy(name = name, drafts = drafts)
                )
                showEditor = false
            }
        )
    }
}

@Composable
private fun TemplateCard(
    template: TaskTemplate,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("template_card_${template.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NudgyCharcoal
                    )
                    Text(
                        text = "${template.drafts.size} tasks · " +
                            formatTotalMinutes(template.totalEstimatedMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted
                    )
                }
                if (template.isBuiltIn) {
                    Text(
                        text = "Built-in",
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyAccent
                    )
                } else {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit template")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete template")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            template.drafts.forEach { draft ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "• ${draft.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyCharcoal,
                        modifier = Modifier.weight(1f)
                    )
                    draft.estimatedMinutes?.let {
                        Text(
                            text = formatTotalMinutes(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onUse,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("use_template_${template.id}")
            ) { Text("Use template") }
        }
    }
}

private fun formatTotalMinutes(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) {
        if (rest > 0) "${hours}h ${rest}m" else "${hours}h"
    } else {
        "${rest}m"
    }
}

/** One editable draft row inside the template editor dialog. */
private class DraftEditorRow(
    title: String,
    estimate: String,
    priority: Priority
) {
    var title by mutableStateOf(title)
    var estimate by mutableStateOf(estimate)
    var priority by mutableStateOf(priority)
}

/**
 * Sprint 7 — the editor state shared by the edit dialog and the
 * inline create card: the name + one editable row per draft. The
 * validation is exactly the dialog's old rule (a non-blank name and
 * at least one non-blank draft); [toDrafts] drops blank rows and
 * trims titles, as the dialog's Save did.
 */
private class TemplateEditorState(template: TaskTemplate?) {
    var name by mutableStateOf(template?.name ?: "")
    val drafts = mutableStateListOf<DraftEditorRow>().apply {
        (template?.drafts ?: listOf(TemplateDraft(title = ""))).forEach { draft ->
            add(
                DraftEditorRow(
                    title = draft.title,
                    estimate = draft.estimatedMinutes?.toString() ?: "",
                    priority = draft.priority
                )
            )
        }
    }

    val isValid: Boolean get() =
        name.isNotBlank() && drafts.any { it.title.isNotBlank() }

    fun toDrafts(): List<TemplateDraft> =
        drafts.filter { it.title.isNotBlank() }.map {
            TemplateDraft(
                title = it.title.trim(),
                estimatedMinutes = it.estimate.toIntOrNull(),
                priority = it.priority
            )
        }
}

@Composable
private fun rememberTemplateEditorState(template: TaskTemplate?) =
    remember { TemplateEditorState(template) }

/**
 * Sprint 7 — the name + draft rows shared by the edit dialog and the
 * inline create card (the dialog's old content, moved out verbatim
 * apart from the label/placeholder switch). Dialog mode uses floating
 * labels ("Template name", "Task 1"); the inline card uses the
 * prototype's placeholders. [tagPrefix] scopes the testTags
 * ("create" / "edit") so the two forms never collide when both are
 * composed at once (the dialog can open over an open create card).
 */
@Composable
private fun TemplateEditorForm(
    state: TemplateEditorState,
    usePlaceholders: Boolean,
    tagPrefix: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            label = if (!usePlaceholders) {
                { Text("Template name") }
            } else {
                null
            },
            placeholder = if (usePlaceholders) {
                { Text(stringResource(R.string.templates_create_name_hint)) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${tagPrefix}_template_name")
        )
        state.drafts.forEachIndexed { index, row ->
            Column {
                OutlinedTextField(
                    value = row.title,
                    onValueChange = { row.title = it },
                    label = if (!usePlaceholders) {
                        { Text("Task ${index + 1}") }
                    } else {
                        null
                    },
                    placeholder = if (usePlaceholders) {
                        {
                            Text(
                                if (index == 0) {
                                    stringResource(R.string.templates_create_task_hint)
                                } else {
                                    "Task ${index + 1}"
                                }
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${tagPrefix}_template_task_$index")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = row.estimate,
                        onValueChange = { row.estimate = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Priority.entries.forEach { priority ->
                        TextButton(
                            onClick = { row.priority = priority },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = when (priority) {
                                    Priority.HIGH -> "High"
                                    Priority.MEDIUM -> "Med"
                                    Priority.LOW -> "Low"
                                },
                                color = if (row.priority == priority) {
                                    NudgyAccent
                                } else {
                                    NudgyMuted
                                }
                            )
                        }
                    }
                }
                if (state.drafts.size > 1) {
                    TextButton(onClick = { state.drafts.removeAt(index) }) {
                        Text("Remove task")
                    }
                }
            }
        }
        TextButton(
            onClick = { state.drafts.add(DraftEditorRow("", "", Priority.MEDIUM)) }
        ) { Text("+ Add task") }
    }
}

@Composable
private fun TemplateEditorDialog(
    template: TaskTemplate,
    onDismiss: () -> Unit,
    onSave: (String, List<TemplateDraft>) -> Unit
) {
    // Sprint 7 — edit-only: creating moved to the inline card, so the
    // template is never null here and the title is always "Edit
    // template". The form and the validation are shared with the card.
    val state = rememberTemplateEditorState(template)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit template") },
        text = {
            TemplateEditorForm(
                state = state,
                usePlaceholders = false,
                tagPrefix = "edit"
            )
        },
        confirmButton = {
            Button(
                enabled = state.isValid,
                onClick = { onSave(state.name.trim(), state.toDrafts()) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
