package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 1-4g — Board reorder gesture tests (Feature 5).
 *
 * Drives the actual gesture — long-press a card, drag it, release —
 * and asserts the onMoveTask callback fires with the moved task, the
 * target status, the drop INDEX among the visible cards, and those
 * visible cards in display order. The index math itself is pinned pure
 * in TaskBoardTest (resolveBoardDropPosition / boardDropIndex); this
 * pins the gesture wiring + the live insertion indicator.
 *
 * Fully-visible columns only (S1-4f's half-visible scroll is a parked
 * known issue — no off-screen / half-visible gesture tests here).
 * Distances are computed from measured card centres, never assumed
 * card heights: card heights vary, so a fixed-dp drop would be a lie.
 *
 * Clock: manual (autoAdvance = false, the BoardScrollReproTest
 * pattern) so a held gesture can be observed mid-drag — the insertion
 * indicator only exists while the drag is in flight. The indicator
 * test ends with the pointer still held (no up()); that is harmless
 * in Robolectric — each test gets fresh content — and avoids the
 * open question of whether a second performTouchInput block can end
 * a pointer started in the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoardReorderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class Move(
        val task: TaskEntity,
        val status: TaskStatus,
        val index: Int,
        val visibleIds: List<String>
    )

    private fun item(id: String, status: TaskStatus, completed: Boolean = false) =
        TaskListItem(
            task = TaskEntity(
                id = id,
                userId = "u1",
                title = id,
                status = status,
                isCompleted = completed
            )
        )

    private fun setBoard(items: List<TaskListItem>, moved: MutableList<Move>) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                TaskBoard(
                    items = items,
                    onMoveTask = { task, status, index, visible ->
                        moved.add(Move(task, status, index, visible.map { it.id }))
                    },
                    onToggle = {},
                    onTaskClick = {}
                )
            }
        }
        composeRule.runOnIdle {}
    }

    /** Finger position (board-viewport px) of a card's centre, read before any gesture. */
    private fun fingerOf(cardTag: String): Offset {
        val board = composeRule.onNodeWithTag("task_board")
            .fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag(cardTag)
            .fetchSemanticsNode().boundsInRoot
        return Offset(card.center.x - board.left, card.center.y - board.top)
    }

    private fun pitchPx(): Float = with(composeRule.density) { 284.dp.toPx() }

    /** Long-press a card and drag it by [delta], releasing at the end. */
    private fun dragCard(tag: String, delta: Offset) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            // Past the long-press timeout, so the drag starts.
            advanceEventTime(1000)
            moveBy(delta)
            up()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.runOnIdle {}
    }

    @Test
    fun `same-column drag moves card 1 to position 3`() {
        val moved = mutableListOf<Move>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.TODO),
            item("c", TaskStatus.TODO),
            item("d", TaskStatus.TODO)
        )
        setBoard(items, moved)
        val start = fingerOf("board_card_a")
        // Between c's and d's centres: below c, above d → index 2.
        val targetY = (fingerOf("board_card_c").y + fingerOf("board_card_d").y) / 2f
        dragCard("board_card_a", Offset(0f, targetY - start.y))
        assertEquals(
            listOf(Move(items[0].task, TaskStatus.TODO, 2, listOf("b", "c", "d"))),
            moved
        )
    }

    @Test
    fun `cross-column drop lands at the drop index`() {
        val moved = mutableListOf<Move>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("x", TaskStatus.IN_PROGRESS),
            item("y", TaskStatus.IN_PROGRESS)
        )
        setBoard(items, moved)
        val start = fingerOf("board_card_a")
        // One column right, between x's and y's centres → index 1.
        val targetX = fingerOf("board_card_x").x
        val targetY = (fingerOf("board_card_x").y + fingerOf("board_card_y").y) / 2f
        dragCard("board_card_a", Offset(targetX - start.x, targetY - start.y))
        assertEquals(
            listOf(Move(items[0].task, TaskStatus.IN_PROGRESS, 1, listOf("x", "y"))),
            moved
        )
    }

    @Test
    fun `drop into an empty column lands at index 0`() {
        val moved = mutableListOf<Move>()
        val items = listOf(item("a", TaskStatus.TODO))
        setBoard(items, moved)
        dragCard("board_card_a", Offset(2 * pitchPx(), 0f))
        assertEquals(
            listOf(Move(items[0].task, TaskStatus.DONE, 0, emptyList())),
            moved
        )
    }

    @Test
    fun `insertion indicator appears mid-gesture at the drop position`() {
        val moved = mutableListOf<Move>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.TODO),
            item("c", TaskStatus.TODO),
            item("d", TaskStatus.TODO)
        )
        setBoard(items, moved)
        val start = fingerOf("board_card_a")
        val targetY = (fingerOf("board_card_c").y + fingerOf("board_card_d").y) / 2f
        // Held gesture: down + move, NO release — the indicator only
        // exists while the drag is in flight. One frame lets the
        // recomposition land, then the indicator must be there exactly
        // once (in the To do column, at index 2).
        composeRule.onNodeWithTag("board_card_a").performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, targetY - start.y))
        }
        composeRule.mainClock.advanceTimeBy(16)
        composeRule.onNodeWithTag("board_insertion_indicator").assertIsDisplayed()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("board_insertion_indicator")
                .fetchSemanticsNodes().size
        )
        // The test ends with the pointer still held — the drop itself is
        // covered by the sibling tests, and fresh content per test makes
        // the dangling pointer harmless.
    }

    @Test
    fun `small drag inside the same slot calls nothing`() {
        val moved = mutableListOf<Move>()
        val items = listOf(
            item("a", TaskStatus.TODO),
            item("b", TaskStatus.TODO)
        )
        setBoard(items, moved)
        val small = with(composeRule.density) { 20.dp.toPx() }
        dragCard("board_card_a", Offset(0f, small))
        assertTrue(moved.isEmpty())
    }
}
