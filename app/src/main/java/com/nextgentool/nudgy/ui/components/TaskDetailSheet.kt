package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.local.entity.TaskAttachmentEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.formatMinutes
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskDetailState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Tag / project colours — muted, one per label. Coral is a dot only, never text. */
val TagColorPalette = listOf(
    "#0F766E", "#2563EB", "#7C3AED", "#DB2777",
    "#F97360", "#CA8A04", "#059669", "#475569"
)

fun colorFromHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Color(0xFF0F766E)
}

/** A tag or project, reduced to what the selector dialog needs. */
data class LabelItem(val id: String, val name: String, val colorHex: String)

/**
 * Sprint 1-1 — task detail sheet (Feature 2).
 *
 * Title / description edit here (saved with Done); due date + time,
 * priority, project, tags and subtasks apply immediately. A subtask is
 * one level only — the sheet of a subtask shows no subtask section.
 *
 * Group tasks (groupId != null) are READ-ONLY here: everything is shown
 * as plain text, no editing, no delete — that is Sprint 5's work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    detail: TaskDetailState,
    allTags: List<TagEntity>,
    allProjects: List<ProjectEntity>,
    exactAlarmsAllowed: Boolean = true,
    onUpdateTask: (TaskEntity) -> Unit,
    onDeleteTask: (TaskEntity) -> Unit,
    onSetTags: (taskId: String, tagIds: List<String>) -> Unit,
    onCreateTag: (String, String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRenameTag: (TagEntity, String) -> Unit,
    onCreateProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onRenameProject: (ProjectEntity, String) -> Unit,
    onAddSubtask: (String, String) -> Unit,
    onToggleSubtask: (TaskEntity) -> Unit,
    onDeleteSubtask: (String) -> Unit,
    onDismiss: () -> Unit,
    // Sprint 1-4 — attachments (Feature 10). Optional with no-op defaults
    // so existing callers keep compiling; the Tasks screen passes them.
    attachments: List<TaskAttachmentEntity> = emptyList(),
    attachmentError: String? = null,
    onAddAttachment: () -> Unit = {},
    onOpenAttachment: (TaskAttachmentEntity) -> Unit = {},
    onDeleteAttachment: (String) -> Unit = {},
    onClearAttachmentError: () -> Unit = {}
) {
    val task = detail.task
    val isGroup = task.groupId != null
    val isSubtask = task.parentTaskId != null
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var newSubtaskTitle by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .testTag("task_detail_sheet_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = if (isGroup) "Group task" else if (isSubtask) "Subtask" else "Task details",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(R.color.nudgy_ink)
                )
            }

            if (isGroup) {
                item {
                    Text(
                        text = "Group task — editing comes in Sprint 5.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.nudgy_muted)
                    )
                }
            }

            // Title + description (saved with Done).
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (!isGroup) title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    readOnly = isGroup,
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (!isGroup) description = it },
                    label = { Text("Notes") },
                    readOnly = isGroup,
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Due date / time.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Due",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorResource(R.color.nudgy_ink)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val dueLabel = if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) {
                            "No date"
                        } else {
                            SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
                                .format(task.dueDateEpochMillis)
                        }
                        TextButton(
                            onClick = { showDatePicker = true },
                            enabled = !isGroup
                        ) { Text(dueLabel, color = colorResource(R.color.nudgy_teal)) }
                        if (task.dueDateEpochMillis != TaskRepository.NO_DUE_DATE) {
                            TextButton(
                                onClick = { showTimePicker = true },
                                enabled = !isGroup
                            ) { Text("Change time", color = colorResource(R.color.nudgy_teal)) }
                            TextButton(
                                onClick = { onUpdateTask(task.copy(dueDateEpochMillis = TaskRepository.NO_DUE_DATE)) },
                                enabled = !isGroup
                            ) { Text("Clear", color = colorResource(R.color.nudgy_muted)) }
                        }
                    }
                }
            }

            // Reminder (S1-3a, Feature 6) + repeat task (Feature 3).
            item {
                ReminderSection(
                    task = task,
                    exactAlarmsAllowed = exactAlarmsAllowed,
                    enabled = !isGroup,
                    onUpdateTask = onUpdateTask
                )
            }
            item {
                RecurrenceSection(
                    task = task,
                    enabled = !isGroup,
                    onUpdateTask = onUpdateTask
                )
            }

            // Estimate + actual time (S1-5, Feature 18) — top-level
            // personal tasks only. Plan/Timeline blocks already read
            // estimatedMinutes (S1-2); this is the editor for it.
            // A subtask's estimate means nothing to Plan, and group
            // tasks are read-only until Sprint 5.
            if (!isGroup && !isSubtask) {
                item {
                    EstimateSection(
                        task = task,
                        onUpdateTask = onUpdateTask
                    )
                }
                item {
                    ActualTimeSection(
                        task = task,
                        onUpdateTask = onUpdateTask
                    )
                }
            }

            // Attachments (S1-4, Feature 10) — top-level personal tasks only.
            if (!isGroup && !isSubtask) {
                item {
                    AttachmentSection(
                        attachments = attachments,
                        error = attachmentError,
                        onAdd = onAddAttachment,
                        onOpen = onOpenAttachment,
                        onDelete = onDeleteAttachment,
                        onClearError = onClearAttachmentError
                    )
                }
            }

            // Priority.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Priority",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorResource(R.color.nudgy_ink)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.entries.forEach { priority ->
                            val selected = task.priority == priority
                            FilterChip(
                                selected = selected,
                                onClick = { if (!isGroup) onUpdateTask(task.copy(priority = priority)) },
                                label = {
                                    Text(priority.name.lowercase().replaceFirstChar { it.uppercase() })
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colorResource(R.color.nudgy_teal),
                                    selectedLabelColor = colorResource(R.color.nudgy_cream)
                                )
                            )
                        }
                    }
                }
            }

            // Project.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Project",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorResource(R.color.nudgy_ink)
                    )
                    val projectName = allProjects.firstOrNull { it.id == task.projectId }?.name
                        ?: "No project"
                    TextButton(
                        onClick = { showProjectDialog = true },
                        enabled = !isGroup
                    ) { Text(projectName, color = colorResource(R.color.nudgy_teal)) }
                }
            }

            // Tags.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorResource(R.color.nudgy_ink)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail.tags.isEmpty()) {
                            Text(
                                text = "No tags",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorResource(R.color.nudgy_muted)
                            )
                        } else {
                            detail.tags.forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(NudgyTokens.cardRadius)
                                        .background(colorFromHex(tag.color).copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(R.color.nudgy_ink)
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = { showTagDialog = true },
                            enabled = !isGroup
                        ) { Text("Edit tags", color = colorResource(R.color.nudgy_teal)) }
                    }
                }
            }

            // Subtasks (one level only — a subtask has no subtask section).
            if (!isSubtask) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Subtasks (${detail.subtasks.count { it.isCompleted }}/${detail.subtasks.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorResource(R.color.nudgy_ink)
                        )
                        detail.subtasks.forEach { subtask ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = subtask.isCompleted,
                                    onCheckedChange = { if (!isGroup) onToggleSubtask(subtask) },
                                    enabled = !isGroup,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colorResource(R.color.nudgy_teal)
                                    )
                                )
                                Text(
                                    text = subtask.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDecoration = if (subtask.isCompleted) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        }
                                    ),
                                    color = if (subtask.isCompleted) {
                                        colorResource(R.color.nudgy_muted)
                                    } else {
                                        colorResource(R.color.nudgy_ink)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isGroup) {
                                    IconButton(onClick = { onDeleteSubtask(subtask.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete subtask",
                                            tint = colorResource(R.color.nudgy_muted)
                                        )
                                    }
                                }
                            }
                        }
                        if (!isGroup) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newSubtaskTitle,
                                    onValueChange = { newSubtaskTitle = it },
                                    placeholder = { Text("Add subtask") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    shape = NudgyTokens.cardRadius,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (newSubtaskTitle.isNotBlank()) {
                                            onAddSubtask(task.id, newSubtaskTitle)
                                            newSubtaskTitle = ""
                                        }
                                    },
                                    enabled = newSubtaskTitle.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Add subtask",
                                        tint = colorResource(R.color.nudgy_teal)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Done + delete.
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            if (!isGroup && title.isNotBlank()) {
                                onUpdateTask(
                                    task.copy(
                                        title = title.trim(),
                                        description = description.trim().ifBlank { null }
                                    )
                                )
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.nudgy_teal),
                            contentColor = colorResource(R.color.nudgy_cream)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Done") }
                    if (!isGroup) {
                        Button(
                            onClick = {
                                onDeleteTask(task)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorResource(R.color.nudgy_coral),
                                contentColor = colorResource(R.color.nudgy_cream)
                            )
                        ) { Text("Delete") }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }

    if (showDatePicker) {
        NudgyDatePickerDialog(
            initialDateMillis = task.dueDateEpochMillis
                .takeIf { it != TaskRepository.NO_DUE_DATE },
            onDateSelected = { dateMillis ->
                showDatePicker = false
                onUpdateTask(task.copy(dueDateEpochMillis = combineDateAndTime(dateMillis, 9, 0)))
            },
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker) {
        val due = task.dueDateEpochMillis
        val cal = Calendar.getInstance().apply { timeInMillis = due }
        NudgyTimePickerDialog(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            onTimeSelected = { hour, minute ->
                showTimePicker = false
                val (year, month, day) = localDateParts(due)
                onUpdateTask(
                    task.copy(
                        dueDateEpochMillis = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    )
                )
            },
            onDismiss = { showTimePicker = false }
        )
    }
    if (showTagDialog) {
        LabelSelectorDialog(
            title = "Tags",
            labels = allTags.map { LabelItem(it.id, it.name, it.color) },
            selectedIds = detail.tags.map { it.id }.toSet(),
            multiSelect = true,
            onToggle = { tagId ->
                val current = detail.tags.map { it.id }.toSet()
                onSetTags(
                    task.id,
                    if (tagId in current) (current - tagId).toList() else (current + tagId).toList()
                )
            },
            onCreate = onCreateTag,
            onDelete = onDeleteTag,
            onRename = { id, name ->
                allTags.firstOrNull { it.id == id }?.let { onRenameTag(it, name) }
            },
            onDismiss = { showTagDialog = false }
        )
    }
    if (showProjectDialog) {
        LabelSelectorDialog(
            title = "Project",
            labels = allProjects.map { LabelItem(it.id, it.name, it.color) },
            selectedIds = task.projectId?.let { setOf(it) }.orEmpty(),
            multiSelect = false,
            onToggle = { projectId ->
                onUpdateTask(task.copy(projectId = if (task.projectId == projectId) null else projectId))
            },
            onCreate = onCreateProject,
            onDelete = onDeleteProject,
            onRename = { id, name ->
                allProjects.firstOrNull { it.id == id }?.let { onRenameProject(it, name) }
            },
            onDismiss = { showProjectDialog = false }
        )
    }
}

/**
 * Sprint 1-5 — estimate editor (Feature 18, spec §6).
 *
 * Quick-pick 15 / 30 / 60 minutes + a custom number of minutes, or
 * clear the estimate (Q8 — never mandatory). Applies immediately, the
 * same as Priority in this sheet. Plan's 8h budget and the Timeline's
 * blocks already read estimatedMinutes (S1-2); setting one here makes
 * the block longer there. A task with no estimate stays a point on
 * the timeline, never a block.
 */
@Composable
private fun EstimateSection(
    task: TaskEntity,
    onUpdateTask: (TaskEntity) -> Unit
) {
    var showCustom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    val customMinutes = customText.toIntOrNull()
    val customValid = customMinutes != null && customMinutes in 1..MAX_MINUTES_PER_TASK
    val estimate = task.estimatedMinutes
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Estimate",
            style = MaterialTheme.typography.labelLarge,
            color = colorResource(R.color.nudgy_ink)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EstimateQuickPick.entries.forEach { pick ->
                val selected = estimate == pick.minutes
                FilterChip(
                    selected = selected,
                    onClick = {
                        showCustom = false
                        onUpdateTask(task.copy(estimatedMinutes = pick.minutes))
                    },
                    label = { Text(pick.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorResource(R.color.nudgy_teal),
                        selectedLabelColor = colorResource(R.color.nudgy_cream)
                    ),
                    modifier = Modifier.testTag("estimate_pick_${pick.minutes}")
                )
            }
            FilterChip(
                selected = estimate != null && estimate !in EstimateQuickPick.entries.map { it.minutes },
                onClick = { showCustom = true },
                label = { Text("Custom") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colorResource(R.color.nudgy_teal),
                    selectedLabelColor = colorResource(R.color.nudgy_cream)
                ),
                modifier = Modifier.testTag("estimate_pick_custom")
            )
            if (estimate != null) {
                TextButton(
                    onClick = {
                        showCustom = false
                        onUpdateTask(task.copy(estimatedMinutes = null))
                    },
                    modifier = Modifier.testTag("estimate_clear")
                ) { Text("Clear", color = colorResource(R.color.nudgy_muted)) }
            }
        }
        if (showCustom) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("estimate_custom_field")
                )
                Button(
                    onClick = {
                        if (customValid) {
                            onUpdateTask(task.copy(estimatedMinutes = customMinutes))
                            showCustom = false
                            customText = ""
                        }
                    },
                    enabled = customValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(R.color.nudgy_teal),
                        contentColor = colorResource(R.color.nudgy_cream)
                    ),
                    modifier = Modifier.testTag("estimate_custom_save")
                ) { Text("Save") }
            }
        } else if (estimate != null) {
            Text(
                text = "Estimated ${formatMinutes(estimate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
        }
    }
}

/** Quick-pick estimates (Feature 18, spec §6): 15 / 30 / 60 minutes. */
private enum class EstimateQuickPick(val minutes: Int, val label: String) {
    MIN_15(15, "15 min"),
    MIN_30(30, "30 min"),
    MIN_60(60, "60 min")
}

/** A single task never takes more than a day of minutes (sanity cap). */
private const val MAX_MINUTES_PER_TASK = 1440

/**
 * Sprint 1-5 — actual-time editor (Feature 18, spec §6).
 *
 * Hand-written only — there is no timer in Sprint 1 (auto-tracking
 * comes with Sprint 4's Pomodoro, feature 24). The logged actual
 * surfaces on the finished task in the Weekly review. Applies
 * immediately, the same as the estimate above.
 */
@Composable
private fun ActualTimeSection(
    task: TaskEntity,
    onUpdateTask: (TaskEntity) -> Unit
) {
    var actualText by remember(task.id, task.actualMinutes) {
        mutableStateOf(task.actualMinutes?.toString() ?: "")
    }
    val actualMinutes = actualText.toIntOrNull()
    val actualValid = actualMinutes != null && actualMinutes in 1..MAX_MINUTES_PER_TASK
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Actual time",
            style = MaterialTheme.typography.labelLarge,
            color = colorResource(R.color.nudgy_ink)
        )
        Text(
            text = "How long it actually took — enter by hand.",
            style = MaterialTheme.typography.bodySmall,
            color = colorResource(R.color.nudgy_muted)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = actualText,
                onValueChange = { actualText = it.filter { c -> c.isDigit() } },
                placeholder = { Text("Minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                shape = NudgyTokens.cardRadius,
                modifier = Modifier
                    .weight(1f)
                    .testTag("actual_minutes_field")
            )
            Button(
                onClick = {
                    if (actualValid) {
                        onUpdateTask(task.copy(actualMinutes = actualMinutes))
                    }
                },
                enabled = actualValid && actualMinutes != task.actualMinutes,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.nudgy_teal),
                    contentColor = colorResource(R.color.nudgy_cream)
                ),
                modifier = Modifier.testTag("actual_minutes_save")
            ) { Text("Save") }
            if (task.actualMinutes != null) {
                TextButton(
                    onClick = { onUpdateTask(task.copy(actualMinutes = null)) },
                    modifier = Modifier.testTag("actual_minutes_clear")
                ) { Text("Clear", color = colorResource(R.color.nudgy_muted)) }
            }
        }
        if (task.actualMinutes != null) {
            Text(
                text = "Actually took ${formatMinutes(task.actualMinutes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
        }
    }
}

/**
 * Sprint 1-4 — file attachments (Feature 10, spec §6).
 *
 * Files are LOCAL-ONLY (locked decision 6): copied into app-private
 * storage, Room holds only the path. Limits (spec-fixed): 25 MB per
 * file, 10 files per task — a rejected add shows the reason here,
 * never a silent drop. Tapping a row opens the file in another app
 * (via FileProvider); deleting removes the row AND the file.
 */
@Composable
private fun AttachmentSection(
    attachments: List<TaskAttachmentEntity>,
    error: String?,
    onAdd: () -> Unit,
    onOpen: (TaskAttachmentEntity) -> Unit,
    onDelete: (String) -> Unit,
    onClearError: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Attachments",
                style = MaterialTheme.typography.labelLarge,
                color = colorResource(R.color.nudgy_ink),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = colorResource(R.color.nudgy_teal)
                )
                Text("Add file", color = colorResource(R.color.nudgy_teal))
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_coral),
                modifier = Modifier.clickable { onClearError() }
            )
        }
        if (attachments.isEmpty()) {
            Text(
                text = "No files attached yet",
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.nudgy_muted)
            )
        }
        attachments.forEach { attachment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = colorResource(R.color.nudgy_muted)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.nudgy_ink),
                        maxLines = 1
                    )
                    Text(
                        text = formatFileSize(attachment.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(R.color.nudgy_muted)
                    )
                }
                IconButton(onClick = { onOpen(attachment) }) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = "Open ${attachment.fileName}",
                        tint = colorResource(R.color.nudgy_teal)
                    )
                }
                IconButton(onClick = { onDelete(attachment.id) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete ${attachment.fileName}",
                        tint = colorResource(R.color.nudgy_muted)
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * One dialog for tags (multi-select) and projects (single-select): pick,
 * create (name + colour), rename (tap the name) and delete. The same
 * dialog is the management UI — there is no separate Tags screen.
 */
@Composable
fun LabelSelectorDialog(
    title: String,
    labels: List<LabelItem>,
    selectedIds: Set<String>,
    multiSelect: Boolean,
    onToggle: (String) -> Unit,
    onCreate: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TagColorPalette.first()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    items(labels, key = { it.id }) { label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(label.id) }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = label.id in selectedIds,
                                onCheckedChange = { onToggle(label.id) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colorResource(R.color.nudgy_teal)
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(colorFromHex(label.colorHex))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (editingId == label.id) {
                                OutlinedTextField(
                                    value = editingName,
                                    onValueChange = { editingName = it },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            if (editingName.isNotBlank()) {
                                                onRename(label.id, editingName.trim())
                                            }
                                            editingId = null
                                        }) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Save name"
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Text(
                                    text = label.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorResource(R.color.nudgy_ink),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            editingId = label.id
                                            editingName = label.name
                                        }
                                )
                            }
                            IconButton(onClick = { onDelete(label.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete ${label.name}",
                                    tint = colorResource(R.color.nudgy_muted)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New name") },
                    singleLine = true,
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagColorPalette.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(hex))
                                .clickable { newColor = hex }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (newColor == hex) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreate(newName.trim(), newColor)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Add", color = colorResource(R.color.nudgy_teal)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
