package com.nextgentool.nudgy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.ProjectEntity
import com.nextgentool.nudgy.data.local.entity.TagEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.model.SavedViewFilter
import com.nextgentool.nudgy.ui.components.QuickAddTask
import com.nextgentool.nudgy.ui.components.TaskBoard
import com.nextgentool.nudgy.ui.components.TaskDetailSheet
import com.nextgentool.nudgy.ui.components.TaskViewMode
import com.nextgentool.nudgy.ui.components.colorFromHex
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 1-1 — Tasks screen (Features 1–2), rewritten for the rebuild.
 *
 * Top: QuickAddTask (title + Enter creates, no due → "No date") with the
 * inline due/priority row for the just-created task. Below: search
 * (title / description / tag name), saved views, filter chips
 * (project / tag / priority / status / group), then the list — active
 * tasks by due date (no-date last), completed tasks under their own
 * heading. Group rows carry a badge and are read-only (no edit/delete
 * here — Sprint 5); their checkbox still toggles completion locally,
 * as in v1.
 *
 * Reached from Today ("All tasks") — it is a plain "tasks" route, NOT a
 * bottom-nav destination, so no tab is highlighted while it is open.
 * Content arrives behind skeleton rows while the first Flow emission
 * loads (no abrupt pop-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {},
    onNavigateToTemplates: () -> Unit = {},
    // Sprint 3-5 — break the open task down (feature 20): the detail
    // sheet's button hands the task id to the chat's startSplit.
    onSplitTask: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showSaveViewDialog by remember { mutableStateOf(false) }
    var saveViewName by remember { mutableStateOf("") }
    // Sprint 1-4 — List / Board toggle (Feature 5). Local UI state only.
    var viewMode by remember { mutableStateOf(TaskViewMode.LIST) }
    // Sprint 1-4 — file picker target: the task open in the detail sheet.
    var pendingAttachmentTaskId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val attachmentError by viewModel.attachmentError.collectAsState()
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val taskId = pendingAttachmentTaskId
        if (uri != null && taskId != null) viewModel.attachFile(taskId, uri)
        pendingAttachmentTaskId = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
    ) {
        // Top bar (back — Tasks is not a bottom destination).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = "My tasks",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${uiState.totalCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.width(16.dp))
            // Sprint 1-4 — templates (Feature 13).
            TextButton(
                onClick = onNavigateToTemplates,
                modifier = Modifier.testTag("templates_button")
            ) {
                Text("Templates", color = NudgyAccent)
            }
        }

        if (uiState.isLoading) {
            Column(
                modifier = Modifier.padding(NudgyTokens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NudgySkeletonRow()
                NudgySkeletonRow()
                NudgySkeletonRow()
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NudgyTokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Quick add.
            item(key = "quick_add") {
                val lastCreated = uiState.items
                    .firstOrNull { it.task.id == uiState.lastCreatedTaskId }
                QuickAddTask(
                    onCreate = { viewModel.createTask(it) },
                    isCreating = uiState.isCreating,
                    lastCreatedTask = lastCreated?.task,
                    onLastCreatedDue = { viewModel.setLastCreatedDue(it) },
                    onLastCreatedPriority = { viewModel.setLastCreatedPriority(it) },
                    onDismissLastCreated = { viewModel.clearLastCreated() }
                )
            }

            // Search (title / description / tag name — Tasks only, not global).
            item(key = "search") {
                OutlinedTextField(
                    value = uiState.filter.text,
                    onValueChange = { viewModel.setSearchText(it) },
                    placeholder = { Text("Search tasks", color = NudgyMuted) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = NudgyMuted
                        )
                    },
                    singleLine = true,
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Saved views.
            item(key = "saved_views") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(uiState.savedViews, key = { it.id }) { view ->
                            val active = uiState.activeViewId == view.id
                            FilterChip(
                                selected = active,
                                onClick = { viewModel.applySavedView(view) },
                                label = { Text(view.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colorResource(R.color.nudgy_teal),
                                    selectedLabelColor = colorResource(R.color.nudgy_cream),
                                    // Unselected: explicit theme-aware
                                    // colors — the M3 defaults put a light
                                    // onSurface label on a transparent
                                    // container, invisible over a light
                                    // background in dark mode (device
                                    // 2026-11-21: washed-out filter chips).
                                    containerColor = NudgyCard,
                                    labelColor = NudgyCharcoal
                                )
                            )
                        }
                    }
                    if (uiState.activeViewId != null) {
                        TextButton(onClick = {
                            uiState.activeViewId?.let { viewModel.deleteSavedView(it) }
                        }) {
                            Text("Delete", color = NudgyMuted)
                        }
                    }
                    TextButton(
                        onClick = { showSaveViewDialog = true },
                        enabled = !uiState.filter.isEmpty()
                    ) {
                        Text("Save view", color = NudgyAccent)
                    }
                }
            }

            // Filter chips.
            item(key = "filters") {
                FilterChipRow(
                    filter = uiState.filter,
                    tags = uiState.tags,
                    projects = uiState.projects,
                    onFilterChange = { viewModel.setFilter(it) }
                )
            }

            // Sprint 1-4 — List / Board toggle (Feature 5).
            item(key = "view_mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = viewMode == mode,
                            onClick = { viewMode = mode },
                            label = {
                                Text(if (mode == TaskViewMode.LIST) "List" else "Board")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorResource(R.color.nudgy_teal),
                                selectedLabelColor = colorResource(R.color.nudgy_cream),
                                // Unselected: explicit theme-aware colors
                                // (same white-on-white-in-dark-mode trap
                                // as the saved-view chips above).
                                containerColor = NudgyCard,
                                labelColor = NudgyCharcoal
                            ),
                            modifier = Modifier.testTag(
                                "view_mode_${mode.name.lowercase()}"
                            )
                        )
                    }
                }
            }

            // Task list.
            val activeItems = uiState.items.filter { !it.task.isCompleted }
            val doneItems = uiState.items.filter { it.task.isCompleted }
            if (uiState.items.isEmpty()) {
                item(key = "empty_state") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NudgyTokens.cardRadius,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = NudgyTokens.cardElevation
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (uiState.filter.isEmpty()) {
                                    "No tasks yet"
                                } else {
                                    "No tasks match this filter"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = NudgyCharcoal
                            )
                            Text(
                                text = if (uiState.filter.isEmpty()) {
                                    "Add your first task above."
                                } else {
                                    "Try a different filter or search."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NudgyMuted
                            )
                            if (!uiState.filter.isEmpty()) {
                                TextButton(onClick = { viewModel.clearFilter() }) {
                                    Text("Clear filter", color = NudgyAccent)
                                }
                            }
                        }
                    }
                }
            } else if (viewMode == TaskViewMode.BOARD) {
                // Sprint 1-4 — Board view (Feature 5): the SAME filtered
                // items as the List, grouped by status. Dragging a card
                // moves it; ticking in the List moves it to Done here.
                item(key = "board") {
                    TaskBoard(
                        items = uiState.items,
                        onMoveTask = { task, status, index, visibleItems ->
                            viewModel.moveTaskToPosition(task, status, index, visibleItems)
                        },
                        onToggle = { viewModel.toggleTask(it) },
                        onTaskClick = { viewModel.selectTask(it.id) }
                    )
                }
            } else {
                items(activeItems, key = { it.task.id }) { item ->
                    TaskRow(
                        item = item,
                        onToggle = { viewModel.toggleTask(item.task) },
                        onClick = { viewModel.selectTask(item.task.id) }
                    )
                }
                if (doneItems.isNotEmpty()) {
                    item(key = "completed_header") {
                        Text(
                            text = "Completed (${doneItems.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = NudgyMuted,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                    }
                    items(doneItems, key = { it.task.id }) { item ->
                        TaskRow(
                            item = item,
                            onToggle = { viewModel.toggleTask(item.task) },
                            onClick = { viewModel.selectTask(item.task.id) }
                        )
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(84.dp)) }
        }
    }

    // Detail sheet.
    uiState.selectedDetail?.let { detail ->
        // Sprint 1-4 — attachments of the open task (Feature 10).
        val attachments by viewModel.observeAttachments(detail.task.id)
            .collectAsState(initial = emptyList())
        TaskDetailSheet(
            detail = detail,
            allTags = uiState.tags,
            allProjects = uiState.projects,
            exactAlarmsAllowed = viewModel.exactAlarmsAllowed,
            onUpdateTask = { viewModel.updateSelectedTask(it) },
            onDeleteTask = { viewModel.deleteTask(it) },
            onSetTags = { taskId, tagIds -> viewModel.setTaskTags(taskId, tagIds) },
            onCreateTag = { name, color -> viewModel.createTag(name, color) },
            onDeleteTag = { viewModel.deleteTag(it) },
            onRenameTag = { tag, name -> viewModel.renameTag(tag, name) },
            onCreateProject = { name, color -> viewModel.createProject(name, color) },
            onDeleteProject = { viewModel.deleteProject(it) },
            onRenameProject = { project, name -> viewModel.renameProject(project, name) },
            onAddSubtask = { parentId, title -> viewModel.addSubtask(parentId, title) },
            onToggleSubtask = { viewModel.toggleSubtask(it) },
            onDeleteSubtask = { viewModel.deleteSubtask(it) },
            onDismiss = { viewModel.dismissDetail() },
            // Sprint 3-5 — the detail sheet's "Break down" button.
            onSplitTask = onSplitTask,
            attachments = attachments,
            attachmentError = attachmentError,
            onAddAttachment = {
                pendingAttachmentTaskId = detail.task.id
                pickFileLauncher.launch("*/*")
            },
            onOpenAttachment = { attachment ->
                // FileProvider: the file lives in app-private storage, so
                // it can only be opened through a content Uri with a
                // one-shot read grant.
                val file = File(attachment.localPath)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "com.nextgentool.nudgy.fileprovider",
                        file
                    )
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW
                    ).apply {
                        setDataAndType(uri, attachment.mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        viewModel.setAttachmentError(
                            "No app can open this file"
                        )
                    }
                }
            },
            onDeleteAttachment = { viewModel.removeAttachment(it) },
            onClearAttachmentError = { viewModel.clearAttachmentError() }
        )
    }

    // Save-view dialog.
    if (showSaveViewDialog) {
        AlertDialog(
            onDismissRequest = { showSaveViewDialog = false },
            title = { Text("Save this view") },
            text = {
                OutlinedTextField(
                    value = saveViewName,
                    onValueChange = { saveViewName = it },
                    placeholder = { Text("View name") },
                    singleLine = true,
                    shape = NudgyTokens.cardRadius,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (saveViewName.isNotBlank()) {
                            viewModel.saveCurrentView(saveViewName)
                            saveViewName = ""
                            showSaveViewDialog = false
                        }
                    },
                    enabled = saveViewName.isNotBlank()
                ) { Text("Save", color = NudgyAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveViewDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FilterChipRow(
    filter: SavedViewFilter,
    tags: List<TagEntity>,
    projects: List<ProjectEntity>,
    onFilterChange: (SavedViewFilter) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "chip_all") {
            FilterChip(
                selected = filter.isEmpty(),
                onClick = { onFilterChange(SavedViewFilter.empty()) },
                label = { Text("All") },
                colors = filterChipColors()
            )
        }
        projects.forEach { project ->
            item(key = "chip_project_${project.id}") {
                val selected = filter.projectId == project.id
                FilterChip(
                    selected = selected,
                    onClick = {
                        onFilterChange(
                            filter.copy(projectId = if (selected) null else project.id)
                        )
                    },
                    label = { Text(project.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(project.color))
                        )
                    },
                    colors = filterChipColors()
                )
            }
        }
        tags.forEach { tag ->
            item(key = "chip_tag_${tag.id}") {
                val selected = tag.id in filter.tagIds
                FilterChip(
                    selected = selected,
                    onClick = {
                        onFilterChange(
                            filter.copy(tagIds = if (selected) emptyList() else listOf(tag.id))
                        )
                    },
                    label = { Text(tag.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(tag.color))
                        )
                    },
                    colors = filterChipColors()
                )
            }
        }
        Priority.entries.forEach { priority ->
            item(key = "chip_priority_${priority.name}") {
                val selected = filter.priority == priority
                FilterChip(
                    selected = selected,
                    onClick = {
                        onFilterChange(
                            filter.copy(priority = if (selected) null else priority)
                        )
                    },
                    label = { Text(priority.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    colors = filterChipColors()
                )
            }
        }
        TaskStatus.entries.forEach { status ->
            item(key = "chip_status_${status.name}") {
                val selected = filter.status == status
                FilterChip(
                    selected = selected,
                    onClick = {
                        onFilterChange(
                            filter.copy(status = if (selected) null else status)
                        )
                    },
                    label = {
                        Text(
                            when (status) {
                                TaskStatus.TODO -> "To do"
                                TaskStatus.IN_PROGRESS -> "In progress"
                                TaskStatus.DONE -> "Done"
                            }
                        )
                    },
                    colors = filterChipColors()
                )
            }
        }
        item(key = "chip_group") {
            FilterChip(
                selected = filter.groupOnly,
                onClick = { onFilterChange(filter.copy(groupOnly = !filter.groupOnly)) },
                label = { Text("Group") },
                colors = filterChipColors()
            )
        }
    }
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = colorResource(R.color.nudgy_teal),
    selectedLabelColor = colorResource(R.color.nudgy_cream),
    // Unselected: explicit theme-aware colors — the M3 defaults put a
    // light onSurface label on a transparent container, which reads as
    // washed-out / invisible over a light background in dark mode
    // (device 2026-11-21: "Low / Medium / High / To do" unreadable).
    containerColor = NudgyCard,
    labelColor = NudgyCharcoal
)

/** Priority marker: a dot only — coral for High, teal for Medium, muted for Low. Never text. */
@Composable
private fun priorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

@Composable
private fun TaskRow(
    item: TaskListItem,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val task = item.task
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(priorityDotColor(task.priority))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (task.isCompleted) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    ),
                    color = if (task.isCompleted) {
                        NudgyMuted
                    } else {
                        NudgyCharcoal
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDueLabel(task.dueDateEpochMillis, task.isCompleted),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                    if (item.project != null) {
                        Text(
                            text = "· ${item.project.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                    if (item.subtaskTotal > 0) {
                        Text(
                            text = "· ${item.subtaskDone}/${item.subtaskTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
                if (item.tags.isNotEmpty() || task.groupId != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.tags.forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colorFromHex(tag.color).copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NudgyCharcoal
                                )
                            }
                        }
                        if (task.groupId != null) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        colorResource(R.color.nudgy_teal).copy(alpha = 0.12f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Group",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NudgyAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDueLabel(dueMillis: Long, isCompleted: Boolean): String {
    if (dueMillis == TaskRepository.NO_DUE_DATE) return "No date"
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val now = Calendar.getInstance()
    val due = Calendar.getInstance().apply { timeInMillis = dueMillis }
    val sameDay = now.get(Calendar.YEAR) == due.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == due.get(Calendar.DAY_OF_YEAR)
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrow.get(Calendar.YEAR) == due.get(Calendar.YEAR) &&
        tomorrow.get(Calendar.DAY_OF_YEAR) == due.get(Calendar.DAY_OF_YEAR)
    val overdue = !isCompleted && dueMillis < startOfTodayMillis()
    val prefix = if (overdue) "Overdue · " else ""
    return when {
        sameDay -> "${prefix}Today, ${timeFmt.format(dueMillis)}"
        isTomorrow -> "${prefix}Tomorrow, ${timeFmt.format(dueMillis)}"
        else -> "${prefix}${dateFmt.format(dueMillis)}, ${timeFmt.format(dueMillis)}"
    }
}

private fun startOfTodayMillis(): Long =
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
