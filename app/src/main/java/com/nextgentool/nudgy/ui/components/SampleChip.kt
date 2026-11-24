package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.ui.theme.NudgyAccent

/**
 * Sprint 6-3 (feature 40, Q3=(a)) — the onboarding sample marker.
 *
 * One shared chip, rendered by every surface that shows a task row
 * (Tasks list, Today rows, Board card, Plan rows): a task seeded by
 * the tutorial carries [TaskEntity.isSample], and the chip is the
 * unmistakable label — never the title, so renaming a sample keeps
 * its marker and a real task can never match it by accident.
 * The chip text is a string resource (the S6-2 language setting
 * renders it in বাংলা when "bn" is chosen).
 */
@Composable
fun SampleChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colorResource(R.color.nudgy_teal).copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("sample_chip")
    ) {
        Text(
            text = stringResource(R.string.sample_chip),
            style = MaterialTheme.typography.bodySmall,
            color = NudgyAccent
        )
    }
}

/**
 * The sample row's one-tap delete (spec §6): a single tap on the
 * row's own delete button removes that sample — no detail sheet,
 * no buried menu, no confirm. Deleting a sample deletes only that
 * row (the delete path is the same TaskRepository.deleteTask the
 * Tasks list uses); once deleted it never comes back, because the
 * seed runs once, gated by has_seen_onboarding.
 */
@Composable
fun SampleDeleteButton(
    task: TaskEntity,
    onDeleteSample: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onDeleteSample,
        modifier = modifier.testTag("sample_delete_${task.id}")
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.sample_delete_content),
            tint = NudgyAccent
        )
    }
}
