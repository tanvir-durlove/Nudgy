package com.nextgentool.nudgy.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.domain.startOfDayMillis
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-4, step 2f — the search tap deep-link for schedules: a tap
 * on a schedule result in the global search opens that EXACT calendar
 * event's edit dialog — not just the Calendar screen.
 *
 * What this test proves: CalendarScreen honours the incoming
 * [CalendarScreen.openEventId] + [CalendarScreen.openEventDayMillis] —
 * with a real event id the edit dialog (title "Edit event", prefilled
 * title field `edit_event_title_field`) appears for that event; the
 * screen jumps to the event's day first (the list is
 * day-scoped, so the event only appears once the day's list emits —
 * the other-day test simulates that emission by updating the events
 * list after composition; the group test passes the event in the
 * static list, which is enough — the group rule never looks at the
 * day, and the wait-for-emission path is proven by the other-day
 * test); a stale id opens nothing and is consumed; a group event
 * jumps to its day but opens no dialog (the row-tap rule from
 * Sprint 2-1: group rows are never edited from here). Sprint 7: the
 * dialog opens on the default Month mode — the deep link needs no
 * Week switch, because the day events and the hour grid are shared
 * in both modes. The screen is rendered directly (the
 * CalendarScreenTest pattern: pure params, no ViewModel, no Room).
 *
 * What it does NOT prove (honest limitation, same as steps 2d/2e):
 * the real NudgyNavHost is not rendered here — there is no Hilt test
 * infra in this repo (no HiltAndroidTest anywhere) and the NavHost
 * calls hiltViewModel(). The NavHost wiring (the Search tap sets the
 * NavHost's searchOpenEventId / searchOpenEventDayMillis state; the
 * Calendar destination passes them as openEventId /
 * openEventDayMillis) is verified by reading + CI compile, not by a
 * navigation test.
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness.
 *   The dialog is asserted with assertExists (presence in the tree),
 *   never scrolled to.
 * - NO waitForIdle() for the dialog: the day's events stream in on
 *   Dispatchers.IO in production and waitForIdle does not wait for
 *   background IO. The dialog is awaited with waitUntil on the tree
 *   itself (the step-2c waitForRow pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarScreenDeepLinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dayAt(daysAhead: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun sampleEvent(
        id: String = "e1",
        title: String = "Design review",
        daysAhead: Int = 0,
        groupId: String? = null
    ): CalendarEventEntity {
        val start = dayAt(daysAhead, 10)
        return CalendarEventEntity(
            id = id,
            userId = "test-user-1",
            title = title,
            startEpochMillis = start,
            endEpochMillis = start + 60 * 60 * 1000L,
            locationOrLink = "Room 4B",
            eventType = "MEETING",
            groupId = groupId,
            isFromGoogleCalendar = true
        )
    }

    private fun waitForEditDialog() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Edit event")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The prefilled value of the dialog's title field. */
    private fun editDialogTitleField(): String =
        composeRule.onNodeWithTag("edit_event_title_field")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `openEventId opens that event's edit dialog`() {
        val event = sampleEvent()
        val consumed = mutableStateOf(false)
        composeRule.setContent {
            CalendarScreen(
                events = listOf(event),
                openEventId = event.id,
                openEventDayMillis = startOfDayMillis(event.startEpochMillis),
                onOpenEventIdConsumed = { consumed.value = true }
            )
        }
        waitForEditDialog()
        composeRule.onNodeWithText("Edit event").assertExists()
        // The dialog is for THIS event, not just any dialog: the
        // title field is prefilled with this event's title.
        assertEquals("Design review", editDialogTitleField())
        assertTrue(consumed.value)
    }

    @Test
    fun `openEventId for an event on another day jumps to its day and opens the dialog`() {
        val event = sampleEvent(daysAhead = 1)
        val eventsState = mutableStateOf<List<CalendarEventEntity>>(emptyList())
        val consumed = mutableStateOf(false)
        var selectedDay = -1L
        composeRule.setContent {
            CalendarScreen(
                events = eventsState.value,
                openEventId = event.id,
                openEventDayMillis = startOfDayMillis(event.startEpochMillis),
                onOpenEventIdConsumed = { consumed.value = true },
                onSelectDay = { selectedDay = it }
            )
        }
        // Sprint 7 — the "'s schedule" header is gone; the jump is
        // proven by the selection itself (onSelectDay fires with the
        // event's day). The jump effect runs on entry, before any
        // list exists for that day.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            selectedDay != -1L &&
                startOfDayMillis(selectedDay) == startOfDayMillis(event.startEpochMillis)
        }
        // Then the day's list emits (production: the ViewModel's
        // dayEvents flow) and the dialog opens for the event.
        composeRule.runOnUiThread { eventsState.value = listOf(event) }
        waitForEditDialog()
        composeRule.onNodeWithText("Edit event").assertExists()
        assertEquals("Design review", editDialogTitleField())
        assertTrue(consumed.value)
    }

    @Test
    fun `stale openEventId opens no dialog and is consumed`() {
        val consumed = mutableStateOf(false)
        composeRule.setContent {
            CalendarScreen(
                events = listOf(sampleEvent()),
                openEventId = "no-such-event",
                openEventDayMillis = startOfDayMillis(dayAt(0, 10)),
                onOpenEventIdConsumed = { consumed.value = true }
            )
        }
        // The id is consumed once the current day IS the event's day
        // and the list still lacks it (the list is authoritative).
        composeRule.waitUntil(timeoutMillis = 10_000) { consumed.value }
        composeRule.onNodeWithText("Edit event").assertDoesNotExist()
    }

    @Test
    fun `group event jumps to its day but opens no edit dialog`() {
        // The event is in the list from the start (no emission to
        // wait for): the group rule is day-agnostic — the effect
        // finds the event on entry and consumes without opening the
        // dialog. The wait-for-emission path is proven by the
        // other-day test above (same first-run logic, which never
        // looks at groupId); the jump itself is group-agnostic too
        // (same jumpToDay call for every event).
        val event = sampleEvent(daysAhead = 1, groupId = "g1")
        val consumed = mutableStateOf(false)
        var selectedDay = -1L
        composeRule.setContent {
            CalendarScreen(
                events = listOf(event),
                openEventId = event.id,
                openEventDayMillis = startOfDayMillis(event.startEpochMillis),
                onOpenEventIdConsumed = { consumed.value = true },
                onSelectDay = { selectedDay = it }
            )
        }
        // Sprint 7 — same replacement as the other-day test: the
        // jump is proven by the selection, not the old header.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            selectedDay != -1L &&
                startOfDayMillis(selectedDay) == startOfDayMillis(event.startEpochMillis)
        }
        // The id is consumed — but the edit dialog never opens for
        // a group event (the row-tap rule from Sprint 2-1: group
        // rows are never edited here).
        composeRule.waitUntil(timeoutMillis = 10_000) { consumed.value }
        composeRule.onNodeWithText("Edit event").assertDoesNotExist()
    }
}
