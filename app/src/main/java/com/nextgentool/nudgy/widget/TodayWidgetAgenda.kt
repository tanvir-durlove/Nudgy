package com.nextgentool.nudgy.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.domain.timeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * Sprint 7 — Widget restyle (prototype scr-widget): the item now
 * carries the task's [Priority] so the row can show the prototype's
 * pdot (see [widgetPriorityDotColor]); the row line is one string
 * ("10:30 AM · Buy groceries", see [widgetAgendaRowLine]); the
 * header date is the prototype's "Mon Nov 16" shape (see
 * [widgetHeaderDateLabel]). The cap is 3 rows (spec §4), down from
 * 5 — the prototype shows 3, and 3 rows + header + quick-add pill
 * is what fits the default 2×2 cell without clipping.
 */
data class TodayWidgetItem(
    val id: String,
    val title: String,
    val isCompleted: Boolean,
    /** "3:00 PM" style label from the planned start, null when unslotted. */
    val timeLabel: String?,
    /** The task's priority — the row pdot (prototype .pdot). */
    val priority: Priority = Priority.MEDIUM
)

/** How many agenda rows the widget shows before the "+N more" line. */
const val TODAY_WIDGET_MAX_ITEMS: Int = 3

/**
 * Sprint 7 — the agenda row's single line (prototype scr-widget:
 * "10:30 AM · Buy groceries"): the planned-start time + the title,
 * or just the title when the task has no start.
 */
fun widgetAgendaRowLine(item: TodayWidgetItem): String =
    item.timeLabel?.let { "$it · " }.orEmpty() + item.title

/**
 * Sprint 7 — the header date (prototype scr-widget: "Today · Mon
 * Nov 16"): "EEE MMM d" in the device locale, e.g. "Mon Nov 16".
 * Pure (the instant is passed in), so it is unit-testable.
 */
fun widgetHeaderDateLabel(millis: Long): String =
    SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(Date(millis))

/**
 * Sprint 7 — the header only fits the full prototype line (logo +
 * date + count) on a wide widget. The default 2-cell widget is
 * ~180dp wide: logo (26) + "Today · Mon Nov 16" (15sp) + the count
 * need ~235dp, so at that width the date is dropped and the header
 * is logo + "Today" + the count (the count is live widget data; the
 * date is context the home screen already shows). The date returns
 * at 300dp and above (the 4-cell Medium width).
 */
val WIDGET_WIDE_HEADER_MIN_WIDTH: Dp = 300.dp

/** Whether a widget of [width] shows the date in its header. */
fun widgetShowsDateHeader(width: Dp): Boolean = width >= WIDGET_WIDE_HEADER_MIN_WIDTH

/** The header title: "Today · Mon Nov 16" wide, "Today" narrow. */
fun widgetHeaderTitle(title: String, dateLabel: String, showDate: Boolean): String =
    if (showDate) "$title · $dateLabel" else title

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
                timeLabel = task.plannedStartEpochMillis?.let { timeLabel(it) },
                priority = task.priority
            )
        }
