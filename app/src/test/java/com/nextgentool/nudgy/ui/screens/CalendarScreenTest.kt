package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.intent.IncomingIntent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 2-1 — smoke tests for the wired Calendar screen: it renders
 * its events + the repository-rule suggested slot, tapping a personal
 * event opens the edit dialog prefilled (Q8(a)), and sync/edit
 * failures are shown with a dismiss action. Group rows are never
 * editable from here — that rule is pinned at the repository level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun todayAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun sampleEvent(): CalendarEventEntity {
        val start = todayAt(10)
        return CalendarEventEntity(
            id = "e1",
            userId = "test-user-1",
            title = "Design review",
            startEpochMillis = start,
            endEpochMillis = start + 60 * 60 * 1000L,
            locationOrLink = "Room 4B",
            eventType = "MEETING",
            isFromGoogleCalendar = true
        )
    }

    private fun sampleSlot(): FocusTimeSlot {
        val start = todayAt(14)
        return FocusTimeSlot(
            startEpochMillis = start,
            endEpochMillis = start + 45 * 60 * 1000L,
            durationMinutes = 45,
            label = "2:00 PM – 2:45 PM",
            reason = "45 minutes free in your schedule"
        )
    }

    @Test
    fun `screen renders the day events and the suggested focus card`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        // The event list and the focus card sit below the fold in the
        // test viewport: assertExists (in the tree), not assertIsDisplayed.
        // The title is asserted by testTag: the S2-2 grid renders the
        // same event title as a block, so onNodeWithText would match twice.
        composeRule.onNodeWithTag("schedule_event_e1").assertExists()
        composeRule.onNodeWithText("SUGGESTED FOCUS TIME").assertExists()
        composeRule.onNodeWithText("Sync now").assertExists()
    }

    @Test
    fun `tapping a personal event opens the prefilled edit dialog`() {
        var updatedId: String? = null
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true,
                onUpdateEvent = { eventId, _, _, _, _, _ -> updatedId = eventId }
            )
        }
        composeRule.onNodeWithTag("schedule_event_e1").performScrollTo()
        composeRule.onNodeWithTag("schedule_event_e1").performClick()
        // The dialog title and the Save label are unique (the event
        // title itself now appears three times: schedule list + grid
        // block + text field).
        composeRule.onNodeWithText("Edit event").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()
        assertTrue(updatedId == "e1")
    }

    @Test
    fun `error banner shows and dismisses`() {
        var dismissed = false
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                suggestedSlot = null,
                isCalendarConnected = true,
                errorMessage = "Saved on this device — Google Calendar will update on the next sync.",
                onDismissError = { dismissed = true }
            )
        }
        composeRule.onNodeWithText(
            "Saved on this device — Google Calendar will update on the next sync."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `connect card offers access when disconnected`() {
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                suggestedSlot = null,
                isCalendarConnected = false
            )
        }
        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
    }

    @Test
    fun `connect card shows a syncing state while syncing`() {
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                suggestedSlot = null,
                isCalendarConnected = true,
                isSyncing = true
            )
        }
        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    // ------------------------------------------------------ Sprint 2-4

    @Test
    fun `date link jumps to the link day and highlights its slot`() {
        // Target: five days from today (next week), 15:00 slot.
        val target = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 5) }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(target.time)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(target.time)
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                pendingDateLink = IncomingIntent.DateLink(
                    date = dateStr,
                    time = "15:00",
                    title = null
                )
            )
        }
        composeRule.waitForIdle()

        // The schedule header proves the strip jumped to the link's
        // day (not today), and the grid carries the 15:00 highlight.
        composeRule.onNodeWithText("$dayName’s schedule").assertExists()
        composeRule.onNodeWithText("3:00 PM – 3:30 PM").assertExists()
    }

    @Test
    fun `date link without a time jumps to the day with no slot highlight`() {
        val target = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 5) }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(target.time)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(target.time)
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                pendingDateLink = IncomingIntent.DateLink(
                    date = dateStr,
                    time = null,
                    title = null
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("$dayName’s schedule").assertExists()
        composeRule.onNodeWithText("3:00 PM – 3:30 PM").assertDoesNotExist()
    }

    @Test
    fun `create-event intent opens the add-event dialog prefilled`() {
        val begin = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 2)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                pendingInsertEvent = IncomingIntent.InsertEvent(
                    title = "Design review",
                    beginMillis = begin,
                    endMillis = begin + 60 * 60_000L,
                    location = "Room 4B",
                    description = null
                )
            )
        }
        composeRule.waitForIdle()

        // The dialog opened, prefilled from the intent.
        composeRule.onNodeWithText("Add Event").assertExists()
        composeRule.onNodeWithText("Design review").assertExists()
        composeRule.onNodeWithText("Room 4B").assertExists()
    }
}
