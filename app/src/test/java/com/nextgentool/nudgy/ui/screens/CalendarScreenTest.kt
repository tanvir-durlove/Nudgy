package com.nextgentool.nudgy.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.domain.dateStringOf
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.intent.IncomingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
        // Sprint 7 — the month grid card above pushes the sync card
        // below the fold on the test surface, so assertIsDisplayed
        // fails for a composed-but-not-visible node; assertExists
        // still fails if the card is genuinely missing.
        composeRule.onNodeWithText("Allow access").assertExists()
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
        // Sprint 7 — same below-the-fold reason as the test above:
        // the grid pushes the sync card out of the test viewport.
        composeRule.onNodeWithText("Syncing…").assertExists()
    }

    // ------------------------------------------------------ Sprint 2-4

    @Test
    fun `date link jumps to the link day and highlights its slot`() {
        // Target: five days from today (next week), 15:00 slot.
        val target = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 5) }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(target.time)
        var selectedDay = -1L
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                pendingDateLink = IncomingIntent.DateLink(
                    date = dateStr,
                    time = "15:00",
                    title = null
                ),
                onSelectDay = { selectedDay = it }
            )
        }
        composeRule.waitForIdle()

        // Sprint 7 — the "'s schedule" header is gone; the jump is
        // proven by the selection itself (onSelectDay fires with the
        // link's day), and the grid carries the 15:00 highlight.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            selectedDay != -1L &&
                startOfDayMillis(selectedDay) == startOfDayMillis(target.timeInMillis)
        }
        composeRule.onNodeWithText("3:00 PM – 3:30 PM").assertExists()
    }

    @Test
    fun `date link without a time jumps to the day with no slot highlight`() {
        val target = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 5) }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(target.time)
        var selectedDay = -1L
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                pendingDateLink = IncomingIntent.DateLink(
                    date = dateStr,
                    time = null,
                    title = null
                ),
                onSelectDay = { selectedDay = it }
            )
        }
        composeRule.waitForIdle()

        // Sprint 7 — same replacement as the test above: the "'s
        // schedule" header is gone, the selection proves the jump.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            selectedDay != -1L &&
                startOfDayMillis(selectedDay) == startOfDayMillis(target.timeInMillis)
        }
        composeRule.onNodeWithText("3:00 PM – 3:30 PM").assertDoesNotExist()
    }

    // ------------------------------------------------------ Sprint 2-1b

    private fun sampleAllDayEvent(): CalendarEventEntity {
        val start = todayAt(0)
        return CalendarEventEntity(
            id = "e2",
            userId = "test-user-1",
            title = "Thanksgiving",
            startEpochMillis = start,
            endEpochMillis = start + 24 * 60 * 60 * 1000L,
            locationOrLink = "Google Calendar",
            eventType = "MEETING",
            isAllDay = true,
            isFromGoogleCalendar = true
        )
    }

    @Test
    fun `all-day event shows as a label chip and stays out of the schedule count`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent(), sampleAllDayEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        // The label chip row exists, with the holiday title once.
        composeRule.onNodeWithTag("all_day_row").assertExists()
        composeRule.onNodeWithTag("all_day_label_e2").assertExists()
        composeRule.onNodeWithText("Thanksgiving").assertExists()
        // The schedule counts timed events only: 1 item, not 2.
        composeRule.onNodeWithText("Schedule · 1 items").assertExists()
        // The timed event is still listed; the all-day one never is.
        composeRule.onNodeWithTag("schedule_event_e1").assertExists()
        composeRule.onNodeWithTag("schedule_event_e2").assertDoesNotExist()
    }

    @Test
    fun `no all-day events means no label row`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        composeRule.onNodeWithTag("all_day_row").assertDoesNotExist()
    }

    @Test
    fun `all-day event does not block the suggested focus slot`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleAllDayEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        // Before the fix the 00:00–24:00 label marked the 2:00 PM slot
        // "Blocked" and disabled the button; now it stays bookable.
        composeRule.onNodeWithText("SUGGESTED FOCUS TIME").assertExists()
        composeRule.onNodeWithText("Block time").assertExists()
        composeRule.onNodeWithText("Blocked").assertDoesNotExist()
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

        // The dialog opened, prefilled from the intent. The title is
        // "Schedule Event" (the create title, line 1134 — not "Add
        // Event"); the intent's values appear once each, in the dialog
        // fields only (no events are passed, so nothing else matches).
        composeRule.onNodeWithText("Schedule Event").assertIsDisplayed()
        composeRule.onNodeWithText("Design review").assertExists()
        composeRule.onNodeWithText("Room 4B").assertExists()
    }

    // ------------------------------------------------------ Sprint 7

    @Test
    fun `header shows the month title and the connected sync pill`() {
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                isCalendarConnected = true
            )
        }
        composeRule.waitForIdle()
        val monthTitle = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
        composeRule.onNodeWithText(monthTitle).assertExists()
        composeRule.onNodeWithText("Google Calendar · 2-way sync").assertExists()
        composeRule.onNodeWithTag("calendar_month_grid").assertExists()
    }

    @Test
    fun `not connected shows the muted pill and tapping it connects`() {
        var connected = 0
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                isCalendarConnected = false,
                onConnectGoogleCalendar = { connected++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Google Calendar · not connected").assertExists()
        composeRule.onNodeWithTag("calendar_sync_pill").performClick()
        assertEquals(1, connected)
    }

    @Test
    fun `connected pill tap calls sync now`() {
        var synced = 0
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                isCalendarConnected = true,
                onSyncNow = { synced++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar_sync_pill").performClick()
        assertEquals(1, synced)
    }

    @Test
    fun `seg switches Month to Week and back`() {
        composeRule.setContent { CalendarScreen(events = emptyList()) }
        composeRule.waitForIdle()
        // Default is Month: the grid is there, the strip is not.
        composeRule.onNodeWithTag("calendar_month_grid").assertExists()
        composeRule.onNodeWithTag("calendar_week_strip").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar_view_week").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar_week_strip").assertExists()
        composeRule.onNodeWithTag("calendar_month_grid").assertDoesNotExist()
        composeRule.onNodeWithTag("calendar_view_month").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar_month_grid").assertExists()
        composeRule.onNodeWithTag("calendar_week_strip").assertDoesNotExist()
    }

    @Test
    fun `month grid shows Mon to Sun and dots on days with items`() {
        val plusTwo = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 2) }
        val plusTwoStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(plusTwo.time)
        val plusThree = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 3) }
        val plusThreeStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(plusThree.time)
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                daysWithItems = setOf(startOfDayMillis(plusTwo.timeInMillis))
            )
        }
        composeRule.waitForIdle()
        // Weekday header, Monday first.
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { label ->
            composeRule.onNodeWithText(label).assertExists()
        }
        // The note under the grid (prototype-verbatim).
        composeRule.onNodeWithText("Weeks run Monday–Sunday. Dots mark days with tasks or events.").assertExists()
        // The dot lives inside the clickable day cell, so
        // Modifier.clickable merges it out of the merged tree (the
        // S6-3 lesson) — read it from the unmerged tree. The
        // assertDoesNotExist needs it too: a merged-tree lookup
        // would pass even if a dot existed.
        composeRule.onNodeWithTag("calendar_month_dot_$plusTwoStr", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("calendar_month_dot_$plusThreeStr", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `tapping a grid day selects it`() {
        val plusOne = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val plusOneStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(plusOne.time)
        var selectedDay = -1L
        composeRule.setContent {
            CalendarScreen(
                events = emptyList(),
                onSelectDay = { selectedDay = it }
            )
        }
        composeRule.waitForIdle()
        // performClick, not performSemanticsAction: the semantics
        // action does not reliably deliver the click for a same-week
        // grid-day tap — the identical tap timed out twice on CI
        // (runs 45799918492, 45803759272), while the same tap with
        // performClick passed on run 45803759272 and a cross-week
        // semantics-action tap also passed there. It is a harness
        // delivery quirk, not a product defect: the same onDayClick
        // → jumpToDay → LaunchedEffect chain moves the selection in
        // all three cases. performClick is also the path the
        // sync-pill and seg tests use on this screen. The selection
        // assertion below is unchanged.
        composeRule.onNodeWithTag("calendar_month_day_$plusOneStr").performClick()
        // MICRO-PROBE (temporary, diagnostic only): this wait timed out
        // twice on merge run 1063 and once more on PR #156's run 1064
        // even with waitForIdle + 10 s, while the identical tree passed
        // PR run 1062 — so the question is WHERE it sticks, not how long
        // to wait. Catch the timeout and fail with the stuck state in the
        // message: selectedDay -1L = onSelectDay never fired at all;
        // selectedDay == today = the effect fired on composition but the
        // tap never changed the selection; selectedDay == the expected
        // day = the comparison itself is wrong. Remove this probe once
        // the cause is known.
        var reached = false
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                selectedDay != -1L &&
                    startOfDayMillis(selectedDay) == startOfDayMillis(plusOne.timeInMillis)
            }
            reached = true
        } catch (_: Throwable) {
            // Timeout: fall through to the probe failure below.
            // Throwable, not Exception: ComposeTimeoutException is an
            // AssertionError (an Error), so `catch (_: Exception)` let
            // it escape and the PROBE message never appeared (run 1065).
        }
        val stuckDay = if (selectedDay == -1L) "never-called" else dateStringOf(selectedDay)
        assertTrue(
            "PROBE selectedDay=$stuckDay expected=$plusOneStr",
            reached
        )
    }

    // Month-grid math (pure — no Compose needed): the Monday-start
    // grid, pinned for a Sunday-start month, a Monday-start month
    // and a short February.

    @Test
    fun `month grid math November 2026 starts on Sunday with six leading days`() {
        val november = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cells = monthGridCells(november)
        // 6 leading (Mon 26 Oct – Sat 31 Oct) + 30 November days = 36,
        // padded to 42 (6 whole weeks).
        assertEquals(42, cells.size)
        assertEquals(26, cells.first().dayOfMonth)
        assertFalse(cells.first().isInMonth)
        assertEquals(1, cells[6].dayOfMonth)
        assertTrue(cells[6].isInMonth)
        assertEquals(30, cells[35].dayOfMonth)
        assertTrue(cells[35].isInMonth)
        assertEquals(6, cells[41].dayOfMonth)
        assertFalse(cells[41].isInMonth)
        assertEquals(42, cells.map { it.dayStartMillis }.toSet().size)
    }

    @Test
    fun `month grid math a Monday-start month has no leading days`() {
        val september = Calendar.getInstance().apply {
            set(2025, Calendar.SEPTEMBER, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cells = monthGridCells(september)
        // 0 leading + 30 days = 30, padded to 35 (5 whole weeks).
        assertEquals(35, cells.size)
        assertEquals(1, cells.first().dayOfMonth)
        assertTrue(cells.first().isInMonth)
        assertEquals(5, cells.last().dayOfMonth)
        assertFalse(cells.last().isInMonth)
    }

    @Test
    fun `month grid math February 2026 is five weeks`() {
        val february = Calendar.getInstance().apply {
            set(2026, Calendar.FEBRUARY, 14, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cells = monthGridCells(february)
        // Feb 1 2026 is a Sunday: 6 leading + 28 = 34, padded to 35.
        assertEquals(35, cells.size)
        assertEquals(26, cells.first().dayOfMonth)
        assertFalse(cells.first().isInMonth)
        assertEquals(1, cells[6].dayOfMonth)
        assertTrue(cells[6].isInMonth)
        assertEquals(28, cells[33].dayOfMonth)
        assertTrue(cells[33].isInMonth)
    }

    // ------------------------------------------------------ Sprint 7-2b (Calendar-2)

    @Test
    fun `day event row shows the prototype kv shape`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        // The row keeps its tag; the Google marker is now a tagchip
        // ("Google Calendar event"), not the old "· Google Calendar"
        // inline text; the location survives in the meta row.
        composeRule.onNodeWithTag("schedule_event_e1").assertExists()
        composeRule.onNodeWithText("Google Calendar event").assertExists()
        composeRule.onNodeWithText("· Google Calendar").assertDoesNotExist()
        composeRule.onNodeWithText("Room 4B").assertExists()
        // The meta time appears exactly twice: the row AND the hour
        // grid block (same event, same range). One would mean the row
        // lost its time.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("10:00 AM – 11:00 AM")
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `group event row shows the group tagchip and no delete button`() {
        val start = todayAt(17)
        val groupEvent = CalendarEventEntity(
            id = "e3",
            userId = "test-user-1",
            title = "DIU class group meeting",
            startEpochMillis = start,
            endEpochMillis = start + 60 * 60 * 1000L,
            eventType = "MEETING",
            groupId = "g1"
        )
        composeRule.setContent {
            CalendarScreen(
                events = listOf(groupEvent),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true,
                groupNames = mapOf("g1" to "DIU class group")
            )
        }
        composeRule.onNodeWithTag("schedule_event_e3").assertExists()
        // The group name is a cream grp tagchip in the meta row now,
        // not the separate badge below it. Exact match: the title is
        // "DIU class group meeting", a different node.
        composeRule.onNodeWithText("DIU class group").assertExists()
        // Group rows are never deleted from here (established rule):
        // with only a group event on screen, no delete button exists.
        composeRule.onNodeWithContentDescription("Delete event").assertDoesNotExist()
    }

    @Test
    fun `suggested slot card carries the Schedule a task section title`() {
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                suggestedSlot = sampleSlot(),
                isCalendarConnected = true
            )
        }
        // The prototype's section title, above the card; the card's
        // own copy and its Block time button are unchanged.
        composeRule.onNodeWithText("Schedule a task").assertExists()
        composeRule.onNodeWithText("SUGGESTED FOCUS TIME").assertExists()
        composeRule.onNodeWithText("Block time").assertExists()
    }
}
