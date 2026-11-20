package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.GroupEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.PendingDropConflict
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.endOfDayMillis
import com.nextgentool.nudgy.domain.nextFreeSlot
import com.nextgentool.nudgy.domain.placedTasksForDay
import com.nextgentool.nudgy.domain.slotBlockMinutes
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.stripTasksForDay
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the Calendar destination (Sprint 2-1). */
data class CalendarUiState(
    val isLoading: Boolean = true,
    /** Events for the selected day only. */
    val events: List<CalendarEventEntity> = emptyList(),
    /** The repository's suggested focus slot for the selected day
     *  (35-min rule — see CalendarRepository.findSuggestedFocusSlot),
     *  or null when the day has no free slot. */
    val suggestedSlot: FocusTimeSlot? = null,
    val isCalendarConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val selectedDayMillis: Long = startOfDayMillis(System.currentTimeMillis()),
    val groupNames: Map<String, String> = emptyMap(),
    /** Sprint 2-2 — the drag strip (Q4(b)) and the blocks already on the
     *  selected day's grid. */
    val stripTasks: List<TaskEntity> = emptyList(),
    val placedTasks: List<TaskEntity> = emptyList(),
    /** Sprint 2-2 — a drop that landed on a busy slot (Q3(a)): the
     *  dialog the screen shows, null when there is no pending drop. */
    val pendingConflict: PendingDropConflict? = null
)

/**
 * Sprint 2-1 — the Calendar destination owns its state here; the
 * screen only renders it.
 *
 * Sync triggers (spec §6 feature 7 (খ), v1 behaviour): sign-in,
 * permission grant, and the manual "Sync now" button. There is no auth
 * flow to observe in the rebuild yet (auth UI is Sprint 6), so the
 * sign-in trigger is the first Calendar-tab open: init syncs once when
 * the permission is already granted. NO sync on resume, NO periodic
 * sync — both are out of scope for S2-1.
 *
 * The suggested slot is the REPOSITORY's rule
 * (findSuggestedFocusSlot, 35-min minimum gap). The screen's old
 * inline calculation (30-min gap) is deleted in S2-1 — the two rules
 * disagreed, and the repository is the truth (spec §3).
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val taskRepository: TaskRepository,
    private val groupRepository: GroupRepository
) : ViewModel() {

    private val userId: String = taskRepository.currentUserId()

    private val selectedDay = MutableStateFlow(startOfDayMillis(System.currentTimeMillis()))
    private val isSyncing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val suggestedSlot = MutableStateFlow<FocusTimeSlot?>(null)
    // Sprint 2-2 — the pending busy-slot drop (Q3(a)). A separate flow
    // so it does not push the inner combine past 5 flows (a single
    // 6-flow combine does not resolve in this codebase — see below).
    private val pendingConflict = MutableStateFlow<PendingDropConflict?>(null)

    // The day's events, switching flow whenever the selected day
    // changes. Kept out of the combine below so its arguments stay
    // plain flows (the TasksViewModel nesting pattern).
    private val dayEvents: kotlinx.coroutines.flow.Flow<List<CalendarEventEntity>> =
        selectedDay.flatMapLatest { day ->
            calendarRepository.observeEventsForDay(userId, day)
        }

    // NOTE: a single 6-flow combine does NOT resolve in this codebase
    // (the compiler falls back to the vararg overload — proven by two
    // failed CI rounds on this file). Nested combines, exactly like
    // TasksViewModel: an inner 5-flow combine builds the state, an
    // outer 4-flow combine fills in the suggested slot, the strip /
    // placed tasks and the pending conflict.
    val uiState: StateFlow<CalendarUiState> = combine(
        combine(
            dayEvents,
            groupRepository.observeGroups(),
            selectedDay,
            isSyncing,
            errorMessage
        ) { events: List<CalendarEventEntity>, groups: List<GroupEntity>, day: Long, syncing: Boolean, error: String? ->
            CalendarUiState(
                isLoading = false,
                events = events,
                suggestedSlot = null,
                isCalendarConnected = calendarRepository.hasCalendarPermissions(),
                isSyncing = syncing,
                errorMessage = error,
                selectedDayMillis = day,
                groupNames = groups.associate { it.groupId to it.name }
            )
        },
        suggestedSlot,
        taskRepository.observeAllTasks(userId),
        pendingConflict
    ) { state, slot, tasks, conflict ->
        val dayEnd = endOfDayMillis(state.selectedDayMillis)
        state.copy(
            suggestedSlot = slot,
            stripTasks = stripTasksForDay(tasks, state.selectedDayMillis, dayEnd),
            placedTasks = placedTasksForDay(tasks, state.selectedDayMillis, dayEnd),
            pendingConflict = conflict
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init {
        // Recompute the suggested slot whenever the selected day, the
        // day's events, or the tasks change (timed tasks are busy
        // intervals for slot-finding, even though they never render on
        // the schedule).
        viewModelScope.launch {
            combine(
                calendarRepository.observeAllEvents(userId),
                taskRepository.observeAllTasks(userId),
                selectedDay
            ) { _, tasks, day -> tasks to day }.collect { (tasks, day) ->
                refreshSuggestedSlot(day, tasks)
            }
        }
        // Sign-in trigger (see the class KDoc): sync once on first open
        // when the permission is already granted.
        viewModelScope.launch {
            if (calendarRepository.hasCalendarPermissions()) {
                syncNow()
            }
        }
    }

    fun selectDay(dayMillis: Long) {
        selectedDay.value = startOfDayMillis(dayMillis)
    }

    /** Permission-grant trigger: called by the route after the user
     *  grants calendar permission. */
    fun onCalendarPermissionGranted() {
        viewModelScope.launch { syncNow() }
    }

    /** Manual "Sync now" trigger. */
    fun syncNow() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            isSyncing.value = true
            try {
                calendarRepository.syncGoogleCalendar(userId)
                errorMessage.value = null
            } catch (e: Exception) {
                errorMessage.value = "Calendar sync failed. Please try again."
            } finally {
                isSyncing.value = false
            }
        }
    }

    /** Adds an event (create path of the add-event dialog). */
    fun addEvent(
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String? = "Personal",
        eventType: String = "EVENT"
    ) {
        viewModelScope.launch {
            calendarRepository.addEvent(userId, title, startEpochMillis, endEpochMillis, locationOrLink, eventType)
        }
    }

    /**
     * Edits an event (Q8(a)). Returns through the state: on provider
     * failure the edit is still saved locally and retried on the next
     * sync, and errorMessage tells the user the Google copy is pending.
     */
    fun updateEvent(
        eventId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String?,
        eventType: String
    ) {
        viewModelScope.launch {
            val ok = calendarRepository.updateEvent(
                userId, eventId, title, startEpochMillis, endEpochMillis, locationOrLink, eventType
            )
            if (!ok) {
                errorMessage.value = "Saved on this device — Google Calendar will update on the next sync."
            }
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            calendarRepository.deleteEvent(eventId, userId)
        }
    }

    fun blockFocusTime(title: String, startEpochMillis: Long, endEpochMillis: Long) {
        viewModelScope.launch {
            calendarRepository.blockFocusTime(userId, startEpochMillis, endEpochMillis, title)
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }

    /**
     * Sprint 2-2 — drop a strip task onto a grid slot (Feature 7 (ঘ)).
     *
     * Writes plannedStartEpochMillis = slot start + plannedDate =
     * selected day (yyyy-MM-dd) through the existing updateTask path
     * (same as Plan, S1-2). MUST NOT touch dueDateEpochMillis, status
     * or boardOrder — the copy below only sets the two planned fields,
     * and updateTask leaves the rest alone (status/boardOrder are only
     * rewritten on a real status transition). No reminder is set on a
     * drop: reminders stay due-based (updateTask re-schedules only the
     * reminder the task already has; a task with none gets none).
     *
     * Q3(a) busy slot: a drop on a busy slot warns and suggests the
     * next free slot instead of landing. The event-conflict source is
     * checkConflict, exactly as the spec names it — its suggestion is
     * used when the conflict is a real timed event. One deliberate
     * deviation, documented: an ALL-DAY event is NOT a busy slot.
     * Google imports all-day events as 00:00–24:00, so checkConflict
     * alone would flag every slot on such a day as busy (its first
     * conflicting event is the all-day one, and its suggestion would be
     * next midnight — useless). So the drop also reads the day's events
     * and placed tasks itself: all-day events are excluded from the
     * busy list, and the suggestion is verified free by
     * [nextFreeSlot], which in the event-only case returns exactly
     * checkConflict's suggestedAlternativeSlot (the first conflicting
     * event's end). checkConflict itself is untouched — the chat path
     * (Sprint 3) still uses it as-is.
     */
    fun dropTaskAtSlot(task: TaskEntity, slotStartMillis: Long) {
        viewModelScope.launch {
            val dayStart = startOfDayMillis(slotStartMillis)
            val dayLimit = dayStart + 24 * 60 * 60 * 1000L
            val duration = slotBlockMinutes(task)
            val durationMs = duration * 60_000L
            val start = slotStartMillis.coerceIn(dayStart, (dayLimit - durationMs).coerceAtLeast(dayStart))
            val end = start + durationMs
            val conflict = calendarRepository.checkConflict(userId, start, end)
            val dayEvents = calendarRepository.observeEventsForDay(userId, dayStart).first()
            val allTasks = taskRepository.getAllTasksOnce(userId)
            val placed = placedTasksForDay(allTasks, dayStart, dayLimit - 1)
            // All-day events span 00:00–24:00 and occupy no time slot.
            val overlappingEvent = dayEvents.firstOrNull { event ->
                !event.isAllDay &&
                    event.startEpochMillis < end && event.endEpochMillis > start
            }
            val overlappingTask = placed.firstOrNull { placedTask ->
                if (placedTask.id == task.id) return@firstOrNull false
                val blockStart = placedTask.plannedStartEpochMillis ?: return@firstOrNull false
                val blockEnd = blockStart + slotBlockMinutes(placedTask) * 60_000L
                blockStart < end && blockEnd > start
            }
            if (overlappingEvent == null && overlappingTask == null) {
                placeTask(task, start)
                return@launch
            }
            val busy = dayEvents.filter { !it.isAllDay }
                .map { it.startEpochMillis to it.endEpochMillis } +
                placed.filter { it.id != task.id }.map {
                    val blockStart = it.plannedStartEpochMillis!!
                    blockStart to blockStart + slotBlockMinutes(it) * 60_000L
                }
            // checkConflict's suggestion is the starting candidate when
            // the conflict is a real timed event; otherwise the end of
            // the overlapping item. nextFreeSlot verifies it is actually
            // free (advancing past any further overlap) or returns null
            // when no free slot fits today.
            val candidate = if (overlappingEvent != null && conflict.hasConflict) {
                conflict.suggestedAlternativeSlot?.startEpochMillis
            } else {
                null
            } ?: overlappingTask?.let {
                it.plannedStartEpochMillis!! + slotBlockMinutes(it) * 60_000L
            } ?: overlappingEvent?.endEpochMillis
                ?: start
            val suggested = nextFreeSlot(candidate, duration, busy, dayLimit)
            pendingConflict.value = PendingDropConflict(
                task = task,
                requestedStartMillis = start,
                suggestedStartMillis = suggested,
                conflictingTitle = overlappingEvent?.title ?: overlappingTask?.title
            )
        }
    }

    /** Q3(a) — accept the suggested free slot: the task lands there. */
    fun acceptSuggestedSlot() {
        viewModelScope.launch {
            val pending = pendingConflict.value ?: return@launch
            val suggested = pending.suggestedStartMillis ?: return@launch
            pendingConflict.value = null
            placeTask(pending.task, suggested)
        }
    }

    /** Q3(a) — decline: nothing is written, the task stays in the strip. */
    fun dismissConflict() {
        pendingConflict.value = null
    }

    private suspend fun placeTask(task: TaskEntity, slotStartMillis: Long) {
        taskRepository.updateTask(
            task.copy(
                plannedStartEpochMillis = slotStartMillis,
                plannedDate = dateStringOf(slotStartMillis)
            )
        )
    }

    private suspend fun refreshSuggestedSlot(dayMillis: Long, tasks: List<TaskEntity>) {
        val dayStart = startOfDayMillis(dayMillis)
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1
        // Timed tasks are busy intervals (due + 30m), exactly as the
        // screen's old inline calculation did — only the gap rule moved
        // to the repository.
        val busyIntervals = tasks
            .filter { it.dueDateEpochMillis in dayStart..dayEnd && !it.isCompleted }
            .map { it.dueDateEpochMillis to it.dueDateEpochMillis + 30 * 60 * 1000L }
        suggestedSlot.value = calendarRepository.findSuggestedFocusSlot(userId, dayMillis, busyIntervals)
    }
}
