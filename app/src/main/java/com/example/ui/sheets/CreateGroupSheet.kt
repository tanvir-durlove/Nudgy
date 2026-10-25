package com.example.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.GroupEntity
import com.example.data.repository.GroupResult
import com.example.ui.theme.NudgyCard
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary
import kotlinx.coroutines.launch

/**
 * Create-group sheet. The join code is generated on the server side
 * (GroupRepository) and shown on the group detail screen after creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupSheet(
    onDismiss: () -> Unit,
    onCreateGroup: suspend (name: String) -> GroupResult<GroupEntity>,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 28.dp, topEnd = 28.dp
        ),
        dragHandle = null
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Create a group",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "A group lets the creator post tasks and schedules that land " +
                    "in every member's own lists. Members join with a code.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; errorMessage = null },
                label = { Text("Group name") },
                placeholder = { Text("e.g. Class 12-A") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyMuted.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = androidx.compose.ui.graphics.Color(0xFFB3261E)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (name.isBlank() || isBusy) return@Button
                    isBusy = true
                    scope.launch {
                        when (val result = onCreateGroup(name)) {
                            is GroupResult.Ok -> onDismiss()
                            is GroupResult.Err -> {
                                errorMessage = result.message
                                isBusy = false
                            }
                        }
                    }
                },
                enabled = name.isNotBlank() && !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isBusy) "Creating…" else "Create group",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
