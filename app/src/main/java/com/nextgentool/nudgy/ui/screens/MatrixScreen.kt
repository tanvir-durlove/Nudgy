package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.usecase.EisenhowerQuadrant
import com.nextgentool.nudgy.ui.components.EisenhowerMatrix
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import com.nextgentool.nudgy.ui.viewmodel.TasksViewModel

/**
 * Sprint 7 — Priority matrix screen (spec §4, prototype scr-matrix).
 *
 * The EisenhowerMatrix COMPONENT already existed (Sprint 4-3) but was
 * ORPHANED since Sprint 7 Q3(a) removed the List/Board/Matrix switcher
 * from Today — TodayScreen's KDoc said "Matrix gets its own screen
 * later in the sprint". This screen is that host: backbar + the
 * prototype-verbatim intro + the existing component full-width. The
 * component, the use case and the ViewModel are untouched (design-only
 * apart from this wrapper + the nav route).
 *
 * Item scope — ALL tasks (TasksViewModel.uiState.items, default empty
 * filter), NOT My Day only. The My Day scope was an artifact of the
 * matrix living inside Today as a view mode; as a standalone screen
 * the prototype's "the same tasks" is the user's task list, and the
 * Eisenhower triage — including the NEITHER quad's drop-or-delegate
 * purpose — only works over the full list. One ViewModel hosts it
 * all: items + moveTaskToQuadrant + toggleTask + selectTask (the
 * detail sheet lives on the Tasks screen, see below).
 *
 * Tap-to-open — the NavHost wires onTaskClick to the established
 * task deep-link (the same searchOpenTaskId pattern Search 2e and
 * the widget 3d use): the tap navigates to Tasks, which opens that
 * task's detail sheet on entry. In Today the tap was a no-op because
 * "the detail sheet lives on the Tasks screen" — that reasoning
 * still holds, so the sheet is NOT duplicated here.
 *
 * Disclosed deviations from the prototype (forced by the no-touch
 * rule on the component):
 * - Quad headers keep the component's established labels ("Do first"
 *   / "Schedule" / "Delegate" / "Eliminate" with captions "Urgent ·
 *   Important" etc.) instead of the prototype's "URGENT + IMPORTANT"
 *   wording — the urgency/importance information is present in the
 *   captions, and changing the labels would mean touching
 *   EisenhowerMatrix.kt / the use case.
 * - No "Good candidates to drop or delegate." line under the NEITHER
 *   quad — the component renders "No tasks here" when a quad is
 *   empty; adding the line would also mean touching the component.
 * - The screen scrolls (Focus pattern): backbar + intro + the 560dp
 *   matrix exceed short viewports. The matrix drag is long-press
 *   based and resolves targets from localToRoot / boundsInRoot, which
 *   already carry scroll offsets — the drag math is scroll-aware.
 *
 * The wrapper owns the ViewModel; [MatrixScreenContent] is pure
 * state + callbacks so the screen test renders it without Hilt or
 * Room (Focus pattern).
 */
@Composable
fun MatrixScreen(
    onBackClick: () -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel(),
    onTaskClick: (TaskEntity) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    MatrixScreenContent(
        items = uiState.items,
        onBackClick = onBackClick,
        onMoveTask = { task, quadrant -> viewModel.moveTaskToQuadrant(task, quadrant) },
        onToggle = { viewModel.toggleTask(it) },
        onTaskClick = onTaskClick
    )
}

@Composable
fun MatrixScreenContent(
    items: List<TaskListItem>,
    onBackClick: () -> Unit,
    onMoveTask: (TaskEntity, EisenhowerQuadrant) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Backbar (prototype): the same back Row TasksScreen uses
        // (back + titleLarge, no TopAppBar).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("matrix_backbar")
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = "Priority matrix",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
        }
        Text(
            text = "The Eisenhower view of the same tasks — nothing new to maintain. Tap a task to open it.",
            style = MaterialTheme.typography.bodyMedium,
            color = NudgyMuted,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        EisenhowerMatrix(
            items = items,
            onMoveTask = onMoveTask,
            onToggle = onToggle,
            onTaskClick = onTaskClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
