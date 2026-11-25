package com.nextgentool.nudgy.widget

import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.timeLabel

/**
 * Sprint 6-5, step 3a — the Today widget's agenda: today's planned
 * tasks, as display items.
 *
 * The rule is the established My Day rule (TodayViewModel.kt:212):
 * top-level tasks only (`parentTaskId == null`) whose `plannedDate`
 * is today ("yyyy-MM-dd", device-local), ordered by planned start
 * (tasks without a start go last), then due time, then creation
 * time. The widget reads Room directly and applies this rule here —
 * no new DAO query, no new data layer; the DAO round-trip is covered
 * by the repo-level test in TodayWidgetAgendaTest.
 *
 * Pure function (no Android, no clock — [todayString] is passed in,
 * same pattern as the DayDates helpers), so it is unit-testable
 * without Robolectric. The Glance rendering itself is NOT covered
 * here: this harness cannot render a Glance widget (see the test
 * KDoc), so the render path is verified by CI compile + the
 * batch-end device test.
 */
data class TodayWidgetItem(
    val id: String,
    val title: String,
    val isCompleted: Boolean,
    /** "3:00 PM" style label from the planned start, null when unslotted. */
    val timeLabel: String?
)

/** How many agenda rows the widget shows before the "+N more" line. */
const val TODAY_WIDGET_MAX_ITEMS: Int = 5

fun todayAgendaItems(
    tasks: List<TaskEntity>,
    todayString: String
): List<TodayWidgetItem> =
    tasks
        .filter { it.parentTaskId == null && it.plannedDate == todayString }
        .sortedWith(
            compareBy<TaskEntity> { it.plannedStartEpochMillis ?: Long.MAX_VALUE }
                .thenBy { it.dueDateEpochMillis }
                .thenBy { it.createdAtEpochMillis }
        )
        .map { task ->
            TodayWidgetItem(
                id = task.id,
                title = task.title,
                isCompleted = task.isCompleted,
                timeLabel = task.plannedStartEpochMillis?.let { timeLabel(it) }
            )
        }
