package com.example.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auth.NudgyUser
import com.example.data.local.entity.CalendarEventEntity
import com.example.data.local.entity.GroupEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.model.Priority
import com.example.data.model.TaskCategory
import com.example.data.repository.GroupResult
import com.example.ui.components.NudgyCheckbox
import com.example.ui.sheets.CreateGroupSheet
import com.example.ui.sheets.CreateTaskSheet
import com.example.ui.sheets.EditTaskSheet
import com.example.ui.sheets.GroupScheduleSheet
import com.example.ui.sheets.GroupTaskDetailSheet
import com.example.ui.sheets.JoinGroupSheet
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Groups tab (Group v1).
 *
 * List view: the user's groups with join codes. Detail view: the group's items
 * (tasks + schedules) with origin context. Rules enforced here:
 * - Guests see a sign-in prompt, never create/join controls.
 * - Only the creator sees Add task / Add schedule and the schedule delete icon.
 * - Tapping a group task opens EditTaskSheet for the creator (cloud write via
 *   GroupRepository) and the read-only GroupTaskDetailSheet for members
 *   (local completion toggle only).
 * - Schedule edit is not in v1: the creator deletes and re-creates a schedule
 *   to change it (see GroupScheduleSheet note).
 */
@Composable
fun GroupsScreen(
    user: NudgyUser,
    groups: List<GroupEntity>,
    allTasks: List<TaskEntity>,
    allEvents: List<CalendarEventEntity>,
    onCreateGroup: suspend (name: String) -> GroupResult<GroupEntity>,
    onJoinGroup: suspend (code: String) -> GroupResult<GroupEntity>,
    onCreateGroupTask: (
        groupId: String, title: String, description: String?, dueEpochMillis: Long,
        priority: Priority, category: TaskCategory, reminderSet: Boolean
    ) -> Unit,
    onCreateGroupSchedule: (
        groupId: String, title: String, startEpochMillis: Long, endEpochMillis: Long,
        locationOrLink: String?, eventType: String
    ) -> Unit,
    onUpdateGroupTask: (groupId: String, task: TaskEntity) -> Unit,
    onDeleteGroupItem: (groupId: String, itemId: String, isTask: Boolean) -> Unit,
    onToggleTask: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val taskDateFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }
    val eventTimeFormatter = remember {
        SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    }

    var selectedGroup by remember { mutableStateOf<GroupEntity?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showJoinGroup by remember { mutableStateOf(false) }
    var showCreateTask by remember { mutableStateOf(false) }
    var showCreateSchedule by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskEntity?>(null) }
    var detailTask by remember { mutableStateOf<TaskEntity?>(null) }
    var schedulePendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }

    fun copyCode(code: String) {
        clipboardManager.setText(AnnotatedString(code))
        Toast.makeText(context, "Code copied: $code", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        if (user.isGuest) {
            Text(
                text = "Groups",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = NudgyCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Sign in to use groups",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Groups need a signed-in account. Sign in from your " +
                            "profile to create a group or join one with a code.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = NudgyMuted
                    )
                }
            }
            return@Column
        }

        val group = selectedGroup
        if (group == null) {
            // ------------------------------------------------ list view
            Text(
                text = "Groups",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Class groups, study circles, family lists — one creator posts, " +
                    "everyone sees it.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { showCreateGroup = true },
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Create group",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        ),
                        color = Color.White
                    )
                }
                OutlinedButton(
                    onClick = { showJoinGroup = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Join with code",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        ),
                        color = NudgyPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            if (groups.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = NudgyCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = NudgyMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No groups yet",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyCharcoal
                        )
                        Text(
                            text = "Create one and share the code, or join with a code " +
                                "from your class CR.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = NudgyMuted
                        )
                    }
                }
            } else {
                groups.sortedBy { it.name.lowercase() }.forEach { item ->
                    val itemTaskCount = allTasks.count {
                        it.groupId == item.groupId && !it.isCompleted
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = NudgyCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable { selectedGroup = item }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = NudgyCharcoal
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (item.creatorUid == user.uid) {
                                        "Created by you · $itemTaskCount open tasks"
                                    } else {
                                        "$itemTaskCount open tasks"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NudgyMuted
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Code: ${item.code}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                        ),
                                        color = NudgyPrimary
                                    )
                                    IconButton(
                                        onClick = { copyCode(item.code) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy join code",
                                            tint = NudgyPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ------------------------------------------------ detail view
            val isCreator = group.creatorUid == user.uid
            val groupTasks = allTasks.filter { it.groupId == group.groupId }
                .sortedBy { it.dueDateEpochMillis }
            val groupEvents = allEvents.filter { it.groupId == group.groupId }
                .sortedBy { it.startEpochMillis }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedGroup = null }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to groups",
                        tint = NudgyCharcoal,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Code: ${group.code}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    ),
                    color = NudgyPrimary
                )
                IconButton(
                    onClick = { copyCode(group.code) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy join code",
                        tint = NudgyPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (isCreator) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(NudgyPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "You are the creator",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                            ),
                            color = NudgyPrimary
                        )
                    }
                }
            }

            if (isCreator) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showCreateTask = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Add task",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            ),
                            color = Color.White
                        )
                    }
                    OutlinedButton(
                        onClick = { showCreateSchedule = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Add schedule",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            ),
                            color = NudgyPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Tasks · ${groupTasks.size}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (groupTasks.isEmpty()) {
                Text(
                    text = "No tasks in this group yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            } else {
                groupTasks.forEach { task ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable {
                                if (isCreator) editTask = task else detailTask = task
                            }
                    ) {
                        NudgyCheckbox(
                            checked = task.isCompleted,
                            onCheckedChange = { onToggleTask(task) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                ),
                                color = if (task.isCompleted) NudgyMuted else NudgyCharcoal
                            )
                            Text(
                                text = taskDateFormatter.format(task.dueDateEpochMillis),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = NudgyMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Schedules · ${groupEvents.size}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (groupEvents.isEmpty()) {
                Text(
                    text = "No schedules in this group yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            } else {
                groupEvents.forEach { event ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = event.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                ),
                                color = NudgyCharcoal
                            )
                            Text(
                                text = eventTimeFormatter.format(event.startEpochMillis) +
                                    (event.locationOrLink?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = NudgyMuted
                            )
                        }
                        if (isCreator) {
                            IconButton(
                                onClick = { schedulePendingDelete = event },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete schedule",
                                    tint = NudgyMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ------------------------------------------------ sheets & dialogs
    if (showCreateGroup) {
        CreateGroupSheet(
            onDismiss = { showCreateGroup = false },
            onCreateGroup = onCreateGroup
        )
    }
    if (showJoinGroup) {
        JoinGroupSheet(
            onDismiss = { showJoinGroup = false },
            onJoinGroup = onJoinGroup
        )
    }
    val activeGroup = selectedGroup
    if (showCreateTask && activeGroup != null) {
        CreateTaskSheet(
            onDismiss = { showCreateTask = false },
            onSaveTask = { title, description, due, priority, category, reminderSet ->
                onCreateGroupTask(
                    activeGroup.groupId, title, description, due,
                    priority, category, reminderSet
                )
                showCreateTask = false
            }
        )
    }
    if (showCreateSchedule && activeGroup != null) {
        GroupScheduleSheet(
            onDismiss = { showCreateSchedule = false },
            onSaveSchedule = { title, start, end, location, type ->
                onCreateGroupSchedule(activeGroup.groupId, title, start, end, location, type)
                showCreateSchedule = false
            }
        )
    }
    editTask?.let { task ->
        val groupId = task.groupId
        if (groupId != null) {
            EditTaskSheet(
                task = task,
                onDismiss = { editTask = null },
                onUpdateTask = { updated ->
                    onUpdateGroupTask(groupId, updated)
                    editTask = null
                },
                onDeleteTask = { taskId ->
                    onDeleteGroupItem(groupId, taskId, true)
                    editTask = null
                },
                onToggleComplete = { onToggleTask(it) }
            )
        }
    }
    detailTask?.let { task ->
        GroupTaskDetailSheet(
            task = task,
            groupName = selectedGroup?.name ?: "Group",
            onDismiss = { detailTask = null },
            onToggleComplete = { onToggleTask(it) }
        )
    }
    schedulePendingDelete?.let { event ->
        val groupId = event.groupId
        AlertDialog(
            onDismissRequest = { schedulePendingDelete = null },
            title = { Text("Delete this schedule?") },
            text = {
                Text(
                    "\"${event.title}\" will be removed from every member's " +
                        "schedule list. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (groupId != null) {
                        onDeleteGroupItem(groupId, event.id, false)
                    }
                    schedulePendingDelete = null
                }) {
                    Text("Delete", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { schedulePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
