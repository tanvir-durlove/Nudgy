package com.nextgentool.nudgy.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Read-only detail for a group task, shown to members (non-creators).
 * Members can only toggle their own local completion — never edit or delete
 * the shared item. Creators get EditTaskSheet instead (see GroupsScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTaskDetailSheet(
    task: TaskEntity,
    groupName: String,
    onDismiss: () -> Unit,
    onToggleComplete: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormatter = remember {
        SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(NudgyPrimary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    ),
                    color = NudgyAccent
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dateFormatter.format(task.dueDateEpochMillis),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            // Sprint 5-5 (feature 36): the assignee's name — blank
            // reads "Member".
            if (task.assigneeUid != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Assigned to ${task.assigneeName?.ifBlank { "Member" } ?: "Member"}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = NudgyMuted
                )
            }
            if (!task.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = task.description!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyCharcoal
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.priority.name.lowercase().replaceFirstChar { it.uppercase() } + " priority",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = NudgyMuted
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = task.category.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = NudgyMuted
                )
                if (task.reminderSet) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Reminder set",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onToggleComplete(task); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (task.isCompleted) "Mark as not done" else "Mark as done",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = NudgyMuted)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
