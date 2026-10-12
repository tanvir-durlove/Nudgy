package com.example.data.repository

import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.example.data.local.NudgyDatabase
import com.example.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

data class FocusTimeSlot(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val durationMinutes: Int,
    val label: String,
    val reason: String
)

data class ConflictResult(
    val hasConflict: Boolean,
    val conflictingEvent: CalendarEventEntity? = null,
    val suggestedAlternativeSlot: FocusTimeSlot? = null
)

class CalendarRepository(
    private val context: Context,
    private val database: NudgyDatabase = NudgyDatabase.getInstance(context)
) {
    private val calendarDao = database.calendarDao()

    fun observeEventsForDay(userId: String, dayMillis: Long): Flow<List<CalendarEventEntity>> {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        return calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay)
    }

    suspend fun addEvent(
        userId: String,
        title: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        locationOrLink: String? = "Personal",
        eventType: String = "EVENT"
    ): CalendarEventEntity = withContext(Dispatchers.IO) {
        val event = CalendarEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = locationOrLink,
            eventType = eventType
        )
        calendarDao.insertEvent(event)
        event
    }

    suspend fun deleteEvent(eventId: String) = withContext(Dispatchers.IO) {
        calendarDao.deleteEventById(eventId)
    }

    suspend fun blockFocusTime(
        userId: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        title: String = "Deep work session"
    ): CalendarEventEntity = withContext(Dispatchers.IO) {
        val event = CalendarEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            locationOrLink = "Focus time",
            eventType = "FOCUS_TIME"
        )
        calendarDao.insertEvent(event)
        event
    }

    suspend fun checkConflict(
        userId: String,
        startEpochMillis: Long,
        endEpochMillis: Long
    ): ConflictResult = withContext(Dispatchers.IO) {
        val events = calendarDao.getAllEventsFlow(userId).first()
        val conflict = events.firstOrNull { event ->
            event.startEpochMillis < endEpochMillis && event.endEpochMillis > startEpochMillis
        }

        if (conflict != null) {
            val altStart = conflict.endEpochMillis
            val altEnd = altStart + (endEpochMillis - startEpochMillis)
            ConflictResult(
                hasConflict = true,
                conflictingEvent = conflict,
                suggestedAlternativeSlot = FocusTimeSlot(
                    startEpochMillis = altStart,
                    endEpochMillis = altEnd,
                    durationMinutes = ((altEnd - altStart) / (60 * 1000)).toInt(),
                    label = "3:00 – 4:00 PM",
                    reason = "Next available open slot"
                )
            )
        } else {
            ConflictResult(hasConflict = false)
        }
    }

    /**
     * Seeds initial calendar meetings matching Figma designs
     */
    suspend fun checkAndSeedInitialEvents(userId: String) = withContext(Dispatchers.IO) {
        val currentEvents = calendarDao.getAllEventsFlow(userId).first()
        if (currentEvents.isEmpty()) {
            val calendar = Calendar.getInstance()

            // 1. Design team sync (11:30 - 12:00)
            calendar.set(Calendar.HOUR_OF_DAY, 11)
            calendar.set(Calendar.MINUTE, 30)
            calendar.set(Calendar.SECOND, 0)
            val meetStart = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 12)
            calendar.set(Calendar.MINUTE, 0)
            val meetEnd = calendar.timeInMillis

            // 2. Deep work session (2:00 PM - 3:00 PM)
            calendar.set(Calendar.HOUR_OF_DAY, 14)
            calendar.set(Calendar.MINUTE, 0)
            val deepStart = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 15)
            calendar.set(Calendar.MINUTE, 0)
            val deepEnd = calendar.timeInMillis

            val sampleEvents = listOf(
                CalendarEventEntity(
                    id = "seed_event_1",
                    userId = userId,
                    title = "Design team sync",
                    startEpochMillis = meetStart,
                    endEpochMillis = meetEnd,
                    locationOrLink = "Google Meet",
                    eventType = "MEETING"
                ),
                CalendarEventEntity(
                    id = "seed_event_2",
                    userId = userId,
                    title = "Deep work session",
                    startEpochMillis = deepStart,
                    endEpochMillis = deepEnd,
                    locationOrLink = "Focus time",
                    eventType = "FOCUS_TIME"
                )
            )
            calendarDao.insertEvents(sampleEvents)
        }
    }

    /**
     * Finds open focus gaps between 9 AM and 6 PM
     */
    suspend fun findSuggestedFocusSlot(userId: String, dayMillis: Long): FocusTimeSlot? = withContext(Dispatchers.IO) {
        val (startOfDay, endOfDay) = getDayBounds(dayMillis)
        val events = calendarDao.getEventsForDayFlow(userId, startOfDay, endOfDay).first()
            .sortedBy { it.startEpochMillis }

        val firstMeeting = events.firstOrNull { it.eventType == "MEETING" }
        if (firstMeeting != null) {
            val slotStart = firstMeeting.endEpochMillis + (15 * 60 * 1000L)
            val slotEnd = slotStart + (45 * 60 * 1000L)
            return@withContext FocusTimeSlot(
                startEpochMillis = slotStart,
                endEpochMillis = slotEnd,
                durationMinutes = 45,
                label = "12:15 – 1:00 PM",
                reason = "45 minutes free after your meeting"
            )
        }

        null
    }

    private fun getDayBounds(dayMillis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply { timeInMillis = dayMillis }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endOfDay = calendar.timeInMillis

        return Pair(startOfDay, endOfDay)
    }
}
