package com.nextgentool.nudgy.widget

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.ui.components.QuickAddTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-5, step 3b — the widget "+" quick-add.
 *
 * What this covers:
 * - the launch-action parameters: the "+" carries exactly one flag,
 *   `true` under the key whose NAME is MainActivity's intent extra
 *   ([MainActivity.EXTRA_OPEN_QUICK_ADD]) — Glance adds action
 *   parameters to the launched intent keyed by the parameter key
 *   name, so this is the contract the Activity reads.
 * - the field side: QuickAddTask with `autofocus` focuses the
 *   quick-add field once and consumes the one-shot signal; without
 *   it the field stays unfocused and nothing is consumed.
 *
 * What it does NOT prove (honest limitations, same as steps 2d/2e):
 * the Glance rendering itself — this harness cannot render a Glance
 * widget (no Glance test infra in this repo), so the "+" row's
 * nested-click routing (inner clickable wins over the column's
 * open-the-app clickable) is verified by CI compile + the batch-end
 * device test. The MainActivity → NudgyApp → Today wiring (the
 * one-shot state + navigate-to-Today effect) is likewise verified by
 * reading + CI compile — the real NavHost calls hiltViewModel() and
 * cannot be rendered here.
 *
 * Harness rules, all proven in this repo — do NOT "fix" them:
 * - NO performScrollTo(): it hangs the test worker on this harness.
 *   The field is asserted with assertIsFocused, not with a scroll.
 * - NO waitForIdle() on a screen with an always-on animation — this
 *   test renders only QuickAddTask (no mascot, no infinite
 *   transition), so waitForIdle is safe here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetQuickAddTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `quick-add parameters carry the flag under MainActivity's extra key`() {
        assertEquals(
            MainActivity.EXTRA_OPEN_QUICK_ADD,
            OpenQuickAddKey.name
        )
        assertEquals(true, quickAddActionParameters()[OpenQuickAddKey])
    }

    @Test
    fun `autofocus focuses the field and consumes once`() {
        var consumed = 0
        composeRule.setContent {
            QuickAddTask(
                onCreate = {},
                isCreating = false,
                lastCreatedTask = null,
                onLastCreatedDue = {},
                onLastCreatedPriority = {},
                onDismissLastCreated = {},
                autofocus = true,
                onAutofocusConsumed = { consumed++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_add_field").assertIsFocused()
        assertEquals(1, consumed)
    }

    @Test
    fun `without autofocus the field stays unfocused and nothing is consumed`() {
        var consumed = 0
        composeRule.setContent {
            QuickAddTask(
                onCreate = {},
                isCreating = false,
                lastCreatedTask = null,
                onLastCreatedDue = {},
                onLastCreatedPriority = {},
                onDismissLastCreated = {},
                autofocus = false,
                onAutofocusConsumed = { consumed++ }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_add_field").assertIsNotFocused()
        assertEquals(0, consumed)
    }
}
