package com.example.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
 * Join-by-code sheet. Codes are 6 characters; input is uppercased as typed.
 * Re-joining an already-joined group is a no-op success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupSheet(
    onDismiss: () -> Unit,
    onJoinGroup: suspend (code: String) -> GroupResult<GroupEntity>,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

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
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Join a group",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter the 6-character code shared by the group creator.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }; errorMessage = null },
                label = { Text("Join code") },
                placeholder = { Text("e.g. K7Q2XA") },
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
                    color = Color(0xFFB3261E)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (code.isBlank() || isBusy) return@Button
                    isBusy = true
                    scope.launch {
                        when (val result = onJoinGroup(code)) {
                            is GroupResult.Ok -> onDismiss()
                            is GroupResult.Err -> {
                                errorMessage = result.message
                                isBusy = false
                            }
                        }
                    }
                },
                enabled = code.isNotBlank() && !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isBusy) "Joining…" else "Join group",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
