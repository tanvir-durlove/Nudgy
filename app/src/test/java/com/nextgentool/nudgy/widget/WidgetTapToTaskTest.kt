package com.nextgentool.nudgy.widget

import com.nextgentool.nudgy.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 6-5, step 3d — the widget agenda-row tap-to-task.
 *
 * What this covers:
 * - the launch-action parameters: a row carries exactly its task's
 *   id under the key whose NAME is MainActivity's intent extra
 *   ([MainActivity.EXTRA_OPEN_TASK_ID]) — Glance adds action
 *   parameters to the launched intent keyed by the parameter key
 *   name, so this is the contract the Activity reads; different
 *   tasks produce different parameters, so the id actually travels
 *   (not a constant).
 *
 * What it does NOT prove (honest limitations, same as steps 2d/2e
 * and 3b):
 * the Glance rendering itself — this harness cannot render a Glance
 * widget (no Glance test infra in this repo), so the row's
 * nested-click routing (inner clickable wins over the column's
 * open-the-app clickable) is verified by CI compile + the batch-end
 * device test. The MainActivity → NudgyApp → Tasks wiring (the
 * one-shot state + navigate-to-Tasks effect + the 2e openTaskId
 * path, already covered for the search tap by
 * TasksScreenDeepLinkTest) is likewise verified by reading + CI
 * compile — the real NavHost calls hiltViewModel() and cannot be
 * rendered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetTapToTaskTest {

    @Test
    fun `open-task parameters carry the task id under MainActivity's extra key`() {
        assertEquals(
            MainActivity.EXTRA_OPEN_TASK_ID,
            OpenTaskKey.name
        )
        assertEquals("task-123", openTaskActionParameters("task-123")[OpenTaskKey])
    }

    @Test
    fun `different task ids produce different parameters`() {
        val first = openTaskActionParameters("task-a")[OpenTaskKey]
        val second = openTaskActionParameters("task-b")[OpenTaskKey]
        assertEquals("task-a", first)
        assertEquals("task-b", second)
        assertNotEquals(first, second)
    }
}
