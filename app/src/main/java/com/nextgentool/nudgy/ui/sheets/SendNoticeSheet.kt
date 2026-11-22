package com.nextgentool.nudgy.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.data.repository.MAX_NOTICE_TEXT_LENGTH
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyFieldBorder
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPrimary

/**
 * Sprint 5-3 — send a text-only admin notice (feature 32, spec §6,
 * Q2=(a)). Same sheet pattern as [CreateGroupSheet]: the caller
 * closes on submit (the repository surfaces failures on the shared
 * group-error toast — the sheet has no inline result).
 *
 * Text-only, 2000 chars max (the repo AND the rules enforce the same
 * limit — the field caps input here so the user can never type past
 * it). Images come later, at the Blaze trigger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendNoticeSheet(
    onDismiss: () -> Unit,
    onSendNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NudgyCard,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 28.dp, topEnd = 28.dp
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .imePadding()
        ) {
            Text(
                text = "Send a notice",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NudgyCharcoal
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Only the creator can send notices. Every member sees it in the group.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = NudgyMuted
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NOTICE_TEXT_LENGTH) },
                label = { Text("Notice") },
                placeholder = { Text("e.g. Class starts at 10 tomorrow") },
                minLines = 3,
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NudgyPrimary,
                    unfocusedBorderColor = NudgyFieldBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_notice_field")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${text.length} / $MAX_NOTICE_TEXT_LENGTH",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = NudgyMuted,
                modifier = Modifier.align(Alignment.End)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSendNotice(text.trim()) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_notice_submit")
            ) {
                Text("Send notice")
            }
        }
    }
}
