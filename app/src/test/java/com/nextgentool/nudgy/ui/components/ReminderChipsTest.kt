package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 5c (polish item 3) — VERIFICATION ONLY. The prototype's
 * reminder chips clip on a narrow phone screen ('1 day before'
 * 39px past the edge, 'Custom time…' 168px). That defect does
 * NOT exist in the app, and this test pins why:
 *
 * The nine timing chips (ReminderOption in TaskDetailReminders.kt)
 * render in a LazyRow, not a fixed Row — a chip past the
 * viewport is reached by horizontal scrolling, never cut off
 * and unreachable. The repeat-reminder and recurrence chips are
 * LazyRows too, and TaskDetailSheet.kt:232 is the only call site.
 *
 * Production code is UNCHANGED by this step — the verdict comes
 * from reading the code (LazyRow, one call site), and this test
 * only pins that all nine options actually render.
 *
 * Two harness facts, both proven in this repo — do NOT "fix" them:
 * - A LazyRow composes only what fits its viewport, so on the
 *   default narrow test surface the last chips would never enter
 *   the semantics tree. The test therefore renders the section
 *   in a Box 1600dp wide (test-only layout; the section itself
 *   is untouched) so all nine chips compose at once.
 * - Presence is asserted with assertExists, never
 *   assertIsDisplayed: chips overflowing the root surface are
 *   composed but not "displayed". NO waitForIdle() and NO
 *   performScrollTo() (both hang this harness — OnboardingScreenTest
 *   KDoc, runs 851/852); rows are awaited with waitUntil
 *   (the SearchScreenTest pattern).
 *
 * Honest limit: this test pins that all nine options render. It
 * does not measure pixels on a real narrow screen — the
 * no-clipping conclusion rests on LazyRow's scroll behaviour
 * (code reading) plus the batch-end device test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderChipsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `all nine reminder timing chips render`() {
        val due = System.currentTimeMillis() + 5 * 3_600_000L
        val task = TaskEntity(
            id = "t1",
            userId = "u1",
            title = "Dentist",
            dueDateEpochMillis = due,
            reminderSet = false
        )
        composeRule.setContent {
            // Test-only widening: the LazyRow's viewport must fit
            // all nine chips at once, or the last ones never
            // compose (see KDoc). Production code untouched.
            Box(Modifier.requiredWidth(1600.dp)) {
                ReminderSection(
                    task = task,
                    exactAlarmsAllowed = true,
                    enabled = true,
                    onUpdateTask = {}
                )
            }
        }
        // A late chip proves the wide viewport composed the row.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 day before")
                .fetchSemanticsNodes().isNotEmpty()
        }
        listOf(
            "Off",
            "2 hours before",
            "1 hour before",
            "30 min before",
            "10 min before",
            "5 min before",
            "1 day before",
            "At due time",
            "Custom…"
        ).forEach { label ->
            composeRule.onNodeWithText(label).assertExists()
        }
    }
}
