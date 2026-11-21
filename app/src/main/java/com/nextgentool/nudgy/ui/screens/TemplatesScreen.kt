package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.model.TaskTemplate
import com.nextgentool.nudgy.domain.model.TemplateDraft
import com.nextgentool.nudgy.ui.components.NudgyDatePickerDialog
import com.nextgentool.nudgy.ui.theme.NudgyAccent
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

    Scaffold(
        modifier = Modifier.testTag("templates_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Templates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editorTemplate = null
                    showEditor = true
                },
                containerColor = colorResource(R.color.nudgy_teal),
                modifier = Modifier.testTag("new_template_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New template")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("templates_list")
        ) {
            if (templates.isEmpty()) {
                item {
                    Text(
                        text = "No templates yet. Tap + to create one.",
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

    if (showEditor) {
        TemplateEditorDialog(
            template = editorTemplate,
            onDismiss = { showEditor = false },
            onSave = { name, drafts ->
                if (editorTemplate == null) {
                    viewModel.createTemplate(name, drafts)
                } else {
                    viewModel.updateTemplate(
                        editorTemplate!!.copy(name = name, drafts = drafts)
                    )
                }
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

@Composable
private fun TemplateEditorDialog(
    template: TaskTemplate?,
    onDismiss: () -> Unit,
    onSave: (String, List<TemplateDraft>) -> Unit
) {
    var name by remember { mutableStateOf(template?.name ?: "") }
    val drafts = remember {
        mutableStateListOf<DraftEditorRow>().apply {
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
    }
    val isValid = name.isNotBlank() &&
        drafts.any { it.title.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (template == null) "New template" else "Edit template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                drafts.forEachIndexed { index, row ->
                    Column {
                        OutlinedTextField(
                            value = row.title,
                            onValueChange = { row.title = it },
                            label = { Text("Task ${index + 1}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
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
                        if (drafts.size > 1) {
                            TextButton(onClick = { drafts.removeAt(index) }) {
                                Text("Remove task")
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { drafts.add(DraftEditorRow("", "", Priority.MEDIUM)) }
                ) { Text("+ Add task") }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    onSave(
                        name.trim(),
                        drafts.filter { it.title.isNotBlank() }.map {
                            TemplateDraft(
                                title = it.title.trim(),
                                estimatedMinutes = it.estimate.toIntOrNull(),
                                priority = it.priority
                            )
                        }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
