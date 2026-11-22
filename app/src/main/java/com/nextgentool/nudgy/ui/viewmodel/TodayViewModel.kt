package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusHabitRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.dayLabel
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.todayDateString
import com.nextgentool.nudgy.domain.usecase.CarryOverUseCase
import com.nextgentool.nudgy.domain.usecase.GetTodayTimelineUseCase
import com.nextgentool.nudgy.domain.usecase.TimelineEntry
import com.nextgentool.nudgy.domain.usecase.ToggleTaskCompletionUseCase
import com.nextgentool.nudgy.domain.usecase.WeeklyReview
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewUseCase
import com.nextgentool.nudgy.domain.usecase.summarizeWeeklyStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The two tabs of the Today destination (Q3 — exactly two). */
enum class TodayTab { TODAY, UPCOMING }

/** One day group in Upcoming: label + its due tasks. */
data class UpcomingDay(
    val label: String,
    val items: List<TaskListItem>
)

/** One row of the Today timeline, enriched for the UI. */
sealed interface TodayTimelineEntry {
    data class TaskBlock(val item: TaskListItem, val startMillis: Long, val durationMinutes: Int) :
        TodayTimelineEntry

    data class TaskPoint(val item: TaskListItem, val startMillis: Long) : TodayTimelineEntry
    data class EventBlock(val event: CalendarEventEntity) : TodayTimelineEntry
    data class NowLine(val atMillis: Long) : TodayTimelineEntry
}

data class TodayUiState(
    val isLoading: Boolean = true,
    val tab: TodayTab = TodayTab.TODAY,
    /** Plan-picked tasks (plannedDate = today), Q3 — top section of Today. */
    val myDayItems: List<TaskListItem> = emptyList(),
    /** The day's timed schedule (blocks / points / events + now-line). */
    val timelineEntries: List<TodayTimelineEntry> = emptyList(),
    /** Today-due tasks with no time set (due 00:00): the "Anytime" group. */
    val untimedTodayItems: List<TaskListItem> = emptyList(),
    /** Yesterday's (and older) unfinished tasks — the banner. */
    val carryOverItems: List<TaskListItem> = emptyList(),
    /** Next 7 days, today first, grouped by day (not-completed only). */
    val upcomingDays: List<UpcomingDay> = emptyList(),
    /** Tasks with no due date at all (Upcoming's last section). */
    val noDateItems: List<TaskListItem> = emptyList(),
    /** True once any task has plannedDate = today — hides the nudge card. */
    val hasPlannedToday: Boolean = false,
    /** Hero ring: completed / total over today's due tasks. */
    val doneCount: Int = 0,
    val todayTotal: Int = 0,
    /** Sprint 1-5 — this week's review (Feature 17): three plain lists. */
    val weeklyReview: WeeklyReview? = null,
    /** Sprint 4-4 — the review's numbers (Feature 27): focus minutes
     * this week + best streaks, from real session/check-in data.
     * Null when the FocusHabitRepository is not wired (older call
     * sites and tests that construct this ViewModel without it) — the
     * review then shows its lists only, exactly as before. */
    val weeklyReviewStats: WeeklyReviewStats? = null
)

/**
 * Sprint 1-2 — Today + Upcoming (Features 4, 15, 16).
 *
 * One combined flow turns the repository's two streams (all tasks +
 * today's calendar events) into the whole Today state. Filtering and
 * grouping run in memory over the loaded list — the list is small and
 * it keeps every Today rule in exactly one place.
 *
 * The day is captured at construction (local midnight boundaries): if
 * the app stays open past midnight, the next open rebuilds the
 * ViewModel and the list changes then (spec device-test).
 *
 * Interpretation note (Feature 14 acceptance): "unpicked tasks don't
 * stay in Today" is read as My Day membership — My Day is Plan-picked
 * only (Q3). The Timeline is the day's full schedule: every today-due
 * task stays on it, picked or not, so a task created after Plan (or
 * one the user left out of the plan) never vanishes from Today — the
 * S1-1 device-test lesson. Unpicked today-due tasks are ALSO listed in
 * Upcoming under "Today", so they are findable from both places.
 */
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val calendarRepository: CalendarRepository,
    private val toggleCompletion: ToggleTaskCompletionUseCase,
    private val carryOverUseCase: CarryOverUseCase,
    private val timelineUseCase: GetTodayTimelineUseCase,
    // Sprint 1-5 — Weekly review (Feature 17), computed from tasks only.
    private val weeklyReviewUseCase: WeeklyReviewUseCase,
    // Sprint 4-4 — the review's numbers (Feature 27): focus minutes +
    // streaks, read from the FocusHabitRepository (S4-0). Nullable with
    // a default on purpose, like TodayScreen's habitViewModel (S4-2):
    // the existing tests construct this ViewModel without it and keep
    // working — the stats are simply absent then. Hilt injects the
    // real repository in production (TaskModule provides it).
    private val focusHabitRepository: FocusHabitRepository? = null
) : ViewModel() {

    private val userId: String = repository.currentUserId()

    /** Local midnight boundaries for "today", captured at construction. */
    private val todayStart: Long = startOfTodayMillis()
    private val todayEnd: Long = startOfTomorrowMillis()
    private val todayString: String = todayDateString()

    private val tabFlow = MutableStateFlow(TodayTab.TODAY)

    /**
     * Sprint 4-4 — the stats refresh trigger. The combine below reads
     * focus sessions + check-ins (suspending repository calls, IO
     * inside), so a plain re-subscription is not enough when the user
     * comes back from a focus session: [refreshWeeklyStats] bumps this
     * flow and the combine re-runs with fresh numbers. The weekly
     * review screen calls it on entry.
     */
    private val statsRefreshFlow = MutableStateFlow(0)

    /** Re-reads the review's numbers (focus minutes + streaks) now. */
    fun refreshWeeklyStats() {
        statsRefreshFlow.value += 1
    }

    val uiState: StateFlow<TodayUiState> = combine(
        repository.observeAllTasks(userId),
        calendarRepository.observeEventsForDay(userId, System.currentTimeMillis()),
        tabFlow,
        statsRefreshFlow
    ) { allTasks, events, tab, _ ->
        val now = System.currentTimeMillis()
        val topLevel = allTasks.filter { it.parentTaskId == null }
        val subtasksByParent = allTasks.filter { it.parentTaskId != null }
            .groupBy { it.parentTaskId!! }
        fun toItem(task: TaskEntity): TaskListItem {
            val subs = subtasksByParent[task.id].orEmpty()
            return TaskListItem(
                task = task,
                subtaskDone = subs.count { it.isCompleted },
                subtaskTotal = subs.size
            )
        }
        val itemsById = topLevel.associate { it.id to toItem(it) }

        // Today-due (for the hero ring): everything due today, done or not.
        val todayDue = topLevel.filter { it.dueDateEpochMillis in todayStart until todayEnd }
        val doneCount = todayDue.count { it.isCompleted }

        // My Day (Q3): Plan-picked only, planned-start order, then due.
        val myDayItems = topLevel
            .filter { it.plannedDate == todayString }
            .map(::toItem)
            .sortedWith(
                compareBy<TaskListItem> { it.task.plannedStartEpochMillis ?: Long.MAX_VALUE }
                    .thenBy { it.task.dueDateEpochMillis }
                    .thenBy { it.task.createdAtEpochMillis }
            )

        // Timeline: today's tasks (not completed only? No — done tasks stay
        // visible, struck through, so the ring and the list agree) + events.
        val timeline = timelineUseCase.buildTimeline(
            todayTasks = todayDue,
            todayEvents = events,
            startOfToday = todayStart,
            now = now
        )
        val timelineEntries = timeline.entries.mapNotNull { entry ->
            when (entry) {
                is TimelineEntry.TaskBlock ->
                    itemsById[entry.task.id]?.let {
                        TodayTimelineEntry.TaskBlock(it, entry.startMillis, entry.durationMinutes)
                    }
                is TimelineEntry.TaskPoint ->
                    itemsById[entry.task.id]?.let {
                        TodayTimelineEntry.TaskPoint(it, entry.startMillis)
                    }
                is TimelineEntry.EventBlock ->
                    TodayTimelineEntry.EventBlock(entry.event)
                is TimelineEntry.NowLine -> TodayTimelineEntry.NowLine(entry.startMillis)
            }
        }
        val untimedTodayItems = timeline.untimedTasks.mapNotNull { itemsById[it.id] }

        // Carry-over: overdue and unfinished (Q2 — banner, never auto).
        val carryOverItems = carryOverUseCase
            .findCarryOver(allTasks, todayStart)
            .map(::toItem)

        // Upcoming: the next 7 days (today first) + "No date".
        // Forward-looking: completed tasks are left out.
        val upcomingDays = (0..6).mapNotNull { offset ->
            val dayStart = startOfDayMillis(offset)
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L
            val items = topLevel
                .filter { !it.isCompleted && it.dueDateEpochMillis in dayStart until dayEnd }
                .map(::toItem)
                .sortedBy { it.task.dueDateEpochMillis }
            if (items.isEmpty()) null else UpcomingDay(
                label = when (offset) {
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> dayLabel(dayStart)
                },
                items = items
            )
        }
        val noDateItems = topLevel
            .filter { !it.isCompleted && it.dueDateEpochMillis == TaskRepository.NO_DUE_DATE }
            .map(::toItem)
            .sortedBy { it.task.createdAtEpochMillis }

        // Weekly review (Feature 17): this week's finished / missed /
        // slipped, from the same task list — no new table.
        val weeklyReview = weeklyReviewUseCase.buildReview(
            tasks = allTasks,
            now = now
        )

        // Sprint 4-4 — the review's numbers (Feature 27): focus minutes
        // for the SAME week as the review above (one `now` for both,
        // so the numbers can never disagree about which week it is)
        // + the habits' streaks. All from real data — completed FOCUS
        // sessions and HabitCheckIn rows — never cached counters.
        // Skipped when the repository is not wired (older call sites).
        val weeklyReviewStats = focusHabitRepository?.let { repo ->
            summarizeWeeklyStats(
                focusMinutesThisWeek = repo.getFocusMinutesForWeek(userId, now),
                habits = repo.getHabitsWithStreaks(userId, now)
            )
        }

        TodayUiState(
            isLoading = false,
            tab = tab,
            myDayItems = myDayItems,
            timelineEntries = timelineEntries,
            untimedTodayItems = untimedTodayItems,
            carryOverItems = carryOverItems,
            upcomingDays = upcomingDays,
            noDateItems = noDateItems,
            hasPlannedToday = myDayItems.isNotEmpty(),
            doneCount = doneCount,
            todayTotal = todayDue.size,
            weeklyReview = weeklyReview,
            weeklyReviewStats = weeklyReviewStats
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState()
    )

    fun setTab(tab: TodayTab) {
        tabFlow.value = tab
    }

    fun toggleTask(task: TaskEntity) {
        viewModelScope.launch { toggleCompletion(task) }
    }

    /** Move one carried-over task to today (same time-of-day). */
    fun moveCarryOverOne(task: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(
                task.copy(
                    dueDateEpochMillis = carryOverUseCase.moveDueToToday(
                        task.dueDateEpochMillis, todayStart
                    ),
                    carriedOverFromDate = carryOverUseCase.carriedOverFromDate(
                        task.dueDateEpochMillis
                    )
                )
            )
        }
    }

    /** Move every carried-over task to today (same time-of-day each). */
    fun moveCarryOverAll() {
        val items = uiState.value.carryOverItems
        if (items.isEmpty()) return
        viewModelScope.launch {
            for (item in items) {
                val task = item.task
                repository.updateTask(
                    task.copy(
                        dueDateEpochMillis = carryOverUseCase.moveDueToToday(
                            task.dueDateEpochMillis, todayStart
                        ),
                        carriedOverFromDate = carryOverUseCase.carriedOverFromDate(
                            task.dueDateEpochMillis
                        )
                    )
                )
            }
        }
    }
}
