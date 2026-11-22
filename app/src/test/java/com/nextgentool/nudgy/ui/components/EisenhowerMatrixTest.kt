package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.usecase.EisenhowerQuadrant
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4-3 — Eisenhower Matrix UI tests (spec §6).
 *
 * The drag itself is driven here, following the BoardDragTest pattern
 * (long-press a card, drag it, release — and assert the onMoveTask
 * callback fires with the moved task and the target quadrant). The drop
 * decision is pinned pure in EisenhowerMatrixTest (quadrantOf /
 * moveTargetFor); the ViewModel write is pinned in
 * EisenhowerMatrixViewModelTest. The drag delta is computed from the
 * measured bounds of the card and the target quadrant (the quadrants
 * are half the screen width, so there is no fixed pitch to compute
 * from — unlike the Board's 272dp columns).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EisenhowerMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Noon on the local day [daysAhead] days from today (0 = today). */
    private fun noon(daysAhead: Int = 0): Long =
        startOfDayMillis(daysAhead, System.currentTimeMillis()) + 12 * 60 * 60 * 1000L

    private fun item(id: String, priority: Priority, due: Long) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = id,
                dueDateEpochMillis = due,
                priority = priority
            )
        )

    /** One task per quadrant: a → Q1, b → Q2, c → Q3, d → Q4. */
    private val items = listOf(
        item("a", Priority.HIGH, noon()),
        item("b", Priority.HIGH, noon(3)),
        item("c", Priority.MEDIUM, noon()),
        item("d", Priority.LOW, TaskRepository.NO_DUE_DATE)
    )

    private fun setMatrixContent(
        moved: MutableList<Pair<TaskEntity, EisenhowerQuadrant>> = mutableListOf()
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                EisenhowerMatrix(
                    items = items,
                    onMoveTask = { task, quadrant -> moved.add(task to quadrant) },
                    onToggle = {},
                    onTaskClick = {}
                )
            }
        }
    }

    private fun centerOf(tag: String): Offset =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    private fun dragCard(tag: String, delta: Offset) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            // Past the long-press timeout, so the drag starts.
            advanceEventTime(1000)
            moveBy(delta)
            up()
        }
    }

    @Test
    fun `each task renders in its quadrant with the right count`() {
        setMatrixContent()

        composeRule.onNodeWithTag("eisenhower_matrix").assertExists()
        composeRule.onNodeWithText("Do first").assertExists()
        composeRule.onNodeWithText("Schedule").assertExists()
        composeRule.onNodeWithText("Delegate").assertExists()
        composeRule.onNodeWithText("Eliminate").assertExists()
        composeRule.onNodeWithTag("matrix_count_Q1").assertTextEquals("1")
        composeRule.onNodeWithTag("matrix_count_Q2").assertTextEquals("1")
        composeRule.onNodeWithTag("matrix_count_Q3").assertTextEquals("1")
        composeRule.onNodeWithTag("matrix_count_Q4").assertTextEquals("1")
        composeRule.onNodeWithTag("matrix_card_a").assertExists()
        composeRule.onNodeWithTag("matrix_card_b").assertExists()
        composeRule.onNodeWithTag("matrix_card_c").assertExists()
        composeRule.onNodeWithTag("matrix_card_d").assertExists()
    }

    @Test
    fun `an empty matrix shows four empty quadrants`() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                EisenhowerMatrix(
                    items = emptyList(),
                    onMoveTask = { _, _ -> },
                    onToggle = {},
                    onTaskClick = {}
                )
            }
        }

        composeRule.onNodeWithTag("matrix_count_Q1").assertTextEquals("0")
        composeRule.onNodeWithTag("matrix_count_Q4").assertTextEquals("0")
        composeRule.onAllNodesWithText("No tasks here").fetchSemanticsNodes().let {
            assertEquals(4, it.size)
        }
    }

    @Test
    fun `long-press drag from Q1 to Q4 moves the task`() {
        val moved = mutableListOf<Pair<TaskEntity, EisenhowerQuadrant>>()
        setMatrixContent(moved)
        val delta = centerOf("matrix_quadrant_Q4") - centerOf("matrix_card_a")

        dragCard("matrix_card_a", delta)

        composeRule.runOnIdle {
            assertEquals(listOf(items[0].task to EisenhowerQuadrant.Q4), moved)
        }
    }

    @Test
    fun `long-press drag from Q4 to Q1 moves the task`() {
        val moved = mutableListOf<Pair<TaskEntity, EisenhowerQuadrant>>()
        setMatrixContent(moved)
        val delta = centerOf("matrix_quadrant_Q1") - centerOf("matrix_card_d")

        dragCard("matrix_card_d", delta)

        composeRule.runOnIdle {
            assertEquals(listOf(items[3].task to EisenhowerQuadrant.Q1), moved)
        }
    }

    @Test
    fun `long-press drag from Q2 to Q3 moves the task`() {
        val moved = mutableListOf<Pair<TaskEntity, EisenhowerQuadrant>>()
        setMatrixContent(moved)
        val delta = centerOf("matrix_quadrant_Q3") - centerOf("matrix_card_b")

        dragCard("matrix_card_b", delta)

        composeRule.runOnIdle {
            assertEquals(listOf(items[1].task to EisenhowerQuadrant.Q3), moved)
        }
    }

    @Test
    fun `drag released inside the source quadrant does nothing`() {
        val moved = mutableListOf<Pair<TaskEntity, EisenhowerQuadrant>>()
        setMatrixContent(moved)
        val small = with(composeRule.density) { 20.dp.toPx() }

        dragCard("matrix_card_a", Offset(small, 0f))

        composeRule.runOnIdle {
            assertTrue(moved.isEmpty())
        }
    }

    @Test
    fun `drag released outside every quadrant does nothing`() {
        val moved = mutableListOf<Pair<TaskEntity, EisenhowerQuadrant>>()
        setMatrixContent(moved)
        val farUp = with(composeRule.density) { 600.dp.toPx() }

        dragCard("matrix_card_a", Offset(0f, -farUp))

        composeRule.runOnIdle {
            assertTrue(moved.isEmpty())
        }
    }

    // ---- quadrantAtPoint (pure) ----

    @Test
    fun `quadrantAtPoint finds the quadrant whose bounds contain the point`() {
        val bounds = mapOf(
            EisenhowerQuadrant.Q1 to Rect(0f, 0f, 100f, 100f),
            EisenhowerQuadrant.Q2 to Rect(100f, 0f, 200f, 100f),
            EisenhowerQuadrant.Q3 to Rect(0f, 100f, 100f, 200f),
            EisenhowerQuadrant.Q4 to Rect(100f, 100f, 200f, 200f)
        )
        assertEquals(EisenhowerQuadrant.Q1, quadrantAtPoint(bounds, Offset(50f, 50f)))
        assertEquals(EisenhowerQuadrant.Q2, quadrantAtPoint(bounds, Offset(150f, 50f)))
        assertEquals(EisenhowerQuadrant.Q3, quadrantAtPoint(bounds, Offset(50f, 150f)))
        assertEquals(EisenhowerQuadrant.Q4, quadrantAtPoint(bounds, Offset(150f, 150f)))
    }

    @Test
    fun `quadrantAtPoint returns null outside every quadrant`() {
        val bounds = mapOf(
            EisenhowerQuadrant.Q1 to Rect(0f, 0f, 100f, 100f),
            EisenhowerQuadrant.Q2 to Rect(100f, 0f, 200f, 100f),
            EisenhowerQuadrant.Q3 to Rect(0f, 100f, 100f, 200f),
            EisenhowerQuadrant.Q4 to Rect(100f, 100f, 200f, 200f)
        )
        assertNull(quadrantAtPoint(bounds, Offset(250f, 250f)))
        assertNull(quadrantAtPoint(bounds, Offset(-10f, 50f)))
    }
}
