package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

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
        composeRule.onNodeWithText("Design review").assertExists()
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
        composeRule.onNodeWithText("Design review").performScrollTo()
        composeRule.onNodeWithText("Design review").performClick()
        // The dialog title and the Save label are unique (the event
        // title itself now appears twice: list + text field).
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
}
