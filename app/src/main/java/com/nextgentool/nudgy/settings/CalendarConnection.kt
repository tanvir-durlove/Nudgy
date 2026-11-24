package com.nextgentool.nudgy.settings

import com.nextgentool.nudgy.data.repository.CalendarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sprint 6-1 — the calendar-connection seam for Settings (spec §2 Q2,
 * v1 behaviour: connected = device-calendar permission granted).
 *
 * The Calendar tab owns the same state through CalendarViewModel; the
 * Settings calendar row needs only two things: whether the device
 * calendar is connected, and a "Sync now" trigger. The permission
 * request itself lives in the NavHost route (the Calendar route's
 * launcher pattern), not here — this seam only re-reads the
 * permission state and runs the sync.
 *
 * [CalendarRepository] cannot be driven in Robolectric (it touches
 * the device Calendar Provider), so the SettingsViewModel depends on
 * this seam and tests substitute a fake.
 */
interface CalendarConnection {
    val isConnected: StateFlow<Boolean>
    fun refresh()
    suspend fun sync(userId: String): Int
}

/** Production [CalendarConnection]: the [CalendarRepository], behind the seam. */
class RealCalendarConnection(
    private val calendarRepository: CalendarRepository
) : CalendarConnection {
    private val _isConnected =
        MutableStateFlow(calendarRepository.hasCalendarPermissions())
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override fun refresh() {
        _isConnected.value = calendarRepository.hasCalendarPermissions()
    }

    override suspend fun sync(userId: String): Int =
        calendarRepository.syncGoogleCalendar(userId)
}
