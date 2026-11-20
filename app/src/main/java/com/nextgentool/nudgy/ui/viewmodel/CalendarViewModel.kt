package com.nextgentool.nudgy.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.CalendarRepository
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.data.repository.GroupRepository
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
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
    val groupNames: Map<String, String> = emptyMap()
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

    val uiState: StateFlow<CalendarUiState> = combine(
        selectedDay.flatMapLatest { day ->
            calendarRepository.observeEventsForDay(userId, day)
        },
        groupRepository.observeGroups(),
        selectedDay,
        isSyncing,
        errorMessage,
        suggestedSlot
    ) { events, groups, day, syncing, error, slot ->
        CalendarUiState(
            isLoading = false,
            events = events,
            suggestedSlot = slot,
            isCalendarConnected = calendarRepository.hasCalendarPermissions(),
            isSyncing = syncing,
            errorMessage = error,
            selectedDayMillis = day,
            groupNames = groups.associate { it.groupId to it.name }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

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
