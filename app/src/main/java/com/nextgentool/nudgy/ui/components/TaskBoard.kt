package com.nextgentool.nudgy.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.model.TaskStatus
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TaskListItem
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** List vs Board view for the Tasks screen, plus the Matrix view that lives in Today's My Day section only (Sprint 4-3, spec §6/Q10). */
enum class TaskViewMode { LIST, BOARD, MATRIX }

/**
 * Groups filtered list items by Board column. Pure — unit-tested
 * without Compose. The Board shows the SAME filtered items as the List
 * (Feature 2 filters apply in Board too, spec Feature 5).
 *
 * [sortByBoardOrder]: true (default) sorts each column by boardOrder
 * (S1-4g — the Tasks Board's manual order). False keeps the input order
 * (the My Day Board — plannedStart order, the day's plan, not the
 * manual order). sortedWith is stable, so ties (rows that never got a
 * distinct boardOrder, or tests that never set one) keep input order.
 */
fun groupBoardItems(
    items: List<TaskListItem>,
    sortByBoardOrder: Boolean = true
): Map<TaskStatus, List<TaskListItem>> =
    TaskStatus.entries.associateWith { status ->
        val column = items.filter { it.task.status == status }
        if (sortByBoardOrder) column.sortedWith(compareBy { it.task.boardOrder }) else column
    }

/**
 * The drop index among a column's OTHER cards, from MEASURED positions.
 * Pure — unit-tested without Compose.
 *
 * [cardCenterViewportYs] are the other cards' vertical centres in
 * board-viewport px (top = 0); [pointerViewportY] is the pointer in the
 * same space. The index is the count of centres above the pointer:
 * above the first centre → 0, between two centres → between them,
 * below the last centre → size (append). Card heights are never
 * assumed — the centres come from LayoutCoordinates (see TaskBoard).
 */
fun boardDropIndex(cardCenterViewportYs: List<Float>, pointerViewportY: Float): Int =
    cardCenterViewportYs.count { it < pointerViewportY }

/** The result of resolving a Board drop to a position: the dragged task, the target column, and the drop index among that column's OTHER cards. */
data class BoardDropTarget(
    val task: TaskEntity,
    val status: TaskStatus,
    val index: Int
)

/**
 * Resolves a Board drop against the FULL task list, to a POSITION.
 * Pure — unit-tested without Compose.
 *
 * [allItems] is the Board's items in DISPLAY order (the order the user
 * sees — boardOrder for the Tasks Board, plannedStart for My Day);
 * [targetIndex] counts the target column's OTHER cards (dragged card
 * excluded — the UI counts other cards only, see [boardDropIndex]).
 *
 * Returns null for an unknown id, and for a same-column drop at the
 * card's own position (a no-op — no write). Otherwise the index is
 * clamped into the target column: below the last other card appends.
 * Cross-column drops keep the existing setTaskStatus side effects
 * (Done → complete + reminder cancel + recurrence) in the repository.
 */
fun resolveBoardDropPosition(
    allItems: List<TaskListItem>,
    draggedId: String?,
    targetStatus: TaskStatus,
    targetIndex: Int
): BoardDropTarget? {
    if (draggedId == null) return null
    val task = allItems.firstOrNull { it.task.id == draggedId }?.task ?: return null
    val targetColumn = allItems.filter { it.task.status == targetStatus }
    if (task.status == targetStatus) {
        val others = targetColumn.filter { it.task.id != draggedId }
        val currentIndex = targetColumn.indexOfFirst { it.task.id == draggedId }
        if (targetIndex == currentIndex) return null
        return BoardDropTarget(task, targetStatus, targetIndex.coerceIn(0, others.size))
    }
    return BoardDropTarget(task, targetStatus, targetIndex.coerceIn(0, targetColumn.size))
}

/**
 * Resolves a Board drop against the FULL task list. Pure — unit-tested
 * without Compose.
 *
 * Returns the dragged task when it exists and its status differs from
 * the target column, otherwise null (unknown id, or a drop back on the
 * source column — both are no-ops).
 *
 * This is the S1-4b fix for the drop that could never land: the S1-4
 * target looked the dragged id up in the TARGET column's OWN items
 * (`items.firstOrNull { it.task.id == id }`), and a card dragged from
 * another column is never in that list — so every cross-column drop
 * was rejected, and on its own column the status check no-op'd. The
 * drop must resolve against all items, not the target column's.
 */
fun resolveBoardDrop(
    allItems: List<TaskListItem>,
    draggedId: String?,
    targetStatus: TaskStatus
): TaskEntity? {
    if (draggedId == null) return null
    val task = allItems.firstOrNull { it.task.id == draggedId }?.task ?: return null
    if (task.status == targetStatus) return null
    return task
}

/**
 * The column under a board-content x (px, i.e. viewport x + board
 * scroll offset). Pure — unit-tested without Compose. The pitch is
 * column width + gap; x outside the board clamps to the edge column.
 */
fun boardColumnAt(contentXPx: Float, pitchPx: Float): TaskStatus {
    val index = (contentXPx / pitchPx).toInt()
        .coerceIn(0, TaskStatus.entries.size - 1)
    return TaskStatus.entries[index]
}

private val BOARD_COLUMN_WIDTH = 272.dp
private val BOARD_COLUMN_GAP = 12.dp
private val BOARD_HEIGHT = 520.dp

/**
 * One auto-scroll tick (S1-4c, made synchronous in S1-4d): given the
 * pointer in board-viewport coordinates, scroll the board and the
 * hovered column one step when the pointer dwells near an edge.
 * Extracted so the edge detection + scroll can be driven directly
 * in a test — Robolectric cannot tick the loop's delay during a held
 * gesture (proven by three harness approaches in
 * BoardScrollReproTest), so the loop itself is verified end-to-end
 * on device.
 *
 * Synchronous on purpose (S1-4d): it scrolls via
 * ScrollState.dispatchRawDelta, which applies the delta immediately,
 * clamped to [0, maxValue] — the same bound scrollBy respects — so
 * the tick can be called from the drag's onDrag as well as from the
 * loop, with no coroutine. dispatchRawDelta bypasses nested scroll
 * and the scroll mutex; that is fine here (the board IS the scrolled
 * container, there is no fling during a drag, and the loop uses this
 * same path). The loop calls this tick every 32ms while a drag is in
 * flight (dwelling); onDrag calls it on movement (see
 * requestMoveScroll in TaskBoard).
 *
 * boardSize MUST be the viewport size (the outer Box): the pointer
 * is in board-viewport coordinates. Passing the content width
 * (the Row inside horizontalScroll, 840dp) would put the right
 * edge zone beyond any viewport pointer and right auto-scroll
 * would never fire.
 */
internal fun boardAutoScrollTick(
    pointerViewport: Offset,
    boardSize: IntSize,
    boardScrollState: ScrollState,
    columnScrollStates: Map<TaskStatus, ScrollState>,
    pitchPx: Float,
    edgeXPx: Float,
    edgeYPx: Float,
    scrollStepPx: Float
) {
    if (pointerViewport.x > boardSize.width - edgeXPx) {
        boardScrollState.dispatchRawDelta(scrollStepPx)
    } else if (pointerViewport.x < edgeXPx) {
        boardScrollState.dispatchRawDelta(-scrollStepPx)
    }
    val hovered = boardColumnAt(
        pointerViewport.x + boardScrollState.value, pitchPx
    )
    val columnState = columnScrollStates.getValue(hovered)
    if (pointerViewport.y > boardSize.height - edgeYPx) {
        columnState.dispatchRawDelta(scrollStepPx)
    } else if (pointerViewport.y < edgeYPx) {
        columnState.dispatchRawDelta(-scrollStepPx)
    }
}

/**
 * In-flight Board drag: the dragged id, the pointer position in the
 * board viewport (px), and the drop target live-computed from it
 * (S1-4g: target column + index among the target column's OTHER cards,
 * updated on every move and on every auto-scroll tick, so the insertion
 * indicator and the drop always agree with the finger).
 */
private data class BoardDrag(
    val taskId: String,
    val pointerViewport: Offset,
    val targetStatus: TaskStatus,
    val targetIndex: Int
)

/**
 * Sprint 1-4b — Kanban board (Feature 5, spec §6), drag fixed.
 *
 * Three columns (To do / In progress / Done) over the SAME `status`
 * column as the List. A card is dragged with a long-press — a MANUAL
 * drag (detectDragGesturesAfterLongPress with offset tracking), NOT
 * platform drag-and-drop: the platform session exposes no drag
 * position, so it can neither auto-scroll nor be driven in a test,
 * and the S1-4 platform drop never landed (see [resolveBoardDrop]).
 *
 * While dragging, a loop auto-scrolls the board: near the horizontal
 * edge the board scrolls sideways to reveal the other columns; near
 * the vertical edge the hovered column scrolls to reveal more cards —
 * without that, an off-screen column or card could never be reached.
 * Dropping calls [onMoveTask]; dropping in Done sets `isCompleted =
 * true` + `completedAtEpochMillis` via the repository's single write
 * path (setTaskStatus). The checkbox on a card toggles completion the
 * same way the List does. The dragged card dims in place and a
 * floating preview follows the finger.
 */
@Composable
fun TaskBoard(
    items: List<TaskListItem>,
    onMoveTask: (TaskEntity, TaskStatus, Int, List<TaskEntity>) -> Unit,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit,
    modifier: Modifier = Modifier,
    // Test observability (S1-4c): reports the live drag pointer
    // position (board-viewport px) at drag start and on every move.
    // Null in production — no behaviour change. The floating preview
    // is centered on exactly this value, so asserting it asserts the
    // preview position without reading semantics mid-gesture (which
    // hangs the test worker). Invoked synchronously from the gesture
    // callbacks, never via recomposition.
    onDragPositionChanged: ((Offset) -> Unit)? = null,
    // Test seams (S1-4c): an externally owned board scroll state and
    // board height, so a test can render the board already scrolled
    // (ScrollState(initial = …)) or scroll it after layout
    // (scrollTo) without performScrollTo — which hangs the test
    // worker when the main clock is paused. Null/default in
    // production — no behaviour change.
    boardScrollState: ScrollState? = null,
    columnScrollStates: Map<TaskStatus, ScrollState>? = null,
    boardHeight: Dp = BOARD_HEIGHT,
    // S1-4g — the Board's display order. True (default): columns sort
    // by boardOrder (Tasks Board). False: the input order is kept (My
    // Day Board — plannedStart order, the day's plan, not the manual
    // order). The drop index is always counted in display order.
    sortByBoardOrder: Boolean = true
) {
    val density = LocalDensity.current
    val pitchPx = with(density) { (BOARD_COLUMN_WIDTH + BOARD_COLUMN_GAP).toPx() }
    val edgeXPx = with(density) { 48.dp.toPx() }
    val edgeYPx = with(density) { 56.dp.toPx() }
    val scrollStepPx = with(density) { 24.dp.toPx() }
    val previewHalfWidthPx = with(density) { (BOARD_COLUMN_WIDTH / 2).toPx() }
    val previewYOffsetPx = with(density) { 28.dp.toPx() }

    val dragState = remember { mutableStateOf<BoardDrag?>(null) }
    val drag = dragState.value
    val boardScroll = boardScrollState ?: rememberScrollState()
    val columnScrolls = columnScrollStates ?: remember {
        TaskStatus.entries.associateWith { ScrollState(0) }
    }
    // S1-4c fix: the board's LayoutCoordinates, evaluated at drag
    // time — never a cached Offset. The cached root Offset went
    // stale/wrong once the board was scrolled, so the drag start
    // (and the preview, and the auto-scroll edges) were off by the
    // scroll amount. LayoutCoordinates.localToRoot is evaluated
    // fresh on every drag start, so it always reflects the current
    // scroll.
    var boardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    // S1-4g: every card's live LayoutCoordinates, by id — the drop
    // index is computed from MEASURED card centres (never an assumed
    // card height). Written by each card's onGloballyPositioned,
    // removed on dispose, so a dropped card's stale position can never
    // be counted.
    val cardCoordsById = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    val grouped = remember(items, sortByBoardOrder) {
        groupBoardItems(items, sortByBoardOrder)
    }

    // Fresh reads for the gesture callbacks (the pointerInput block is
    // keyed on the task id only, so it must not close over stale values).
    val onMoveTaskState = rememberUpdatedState(onMoveTask)
    val boardCoordsState = rememberUpdatedState(boardCoords)
    val groupedState = rememberUpdatedState(grouped)

    // S1-4g: the drop target (column + index among OTHER cards) for a
    // pointer in board-viewport coordinates. Column from X
    // (boardColumnAt); index from the target column's MEASURED card
    // centres in the same viewport space (boardDropIndex) — the dragged
    // card excluded. The pointer and the centres share one coordinate
    // space (board viewport, S1-4e: no scroll correction on Y), so no
    // column-content-top offset is needed — it cancels in the
    // comparison. A card with no coordinates yet (not laid out) is
    // skipped, never guessed.
    val computeDropTarget: (String, Offset) -> Pair<TaskStatus, Int> =
        { draggedId, pointer ->
            val target = boardColumnAt(pointer.x + boardScroll.value, pitchPx)
            val board = boardCoords
            val centers = grouped[target].orEmpty()
                .filter { it.task.id != draggedId }
                .mapNotNull { item ->
                    if (board == null) return@mapNotNull null
                    val coords = cardCoordsById[item.task.id] ?: return@mapNotNull null
                    val center = Offset(
                        coords.size.width / 2f,
                        coords.size.height / 2f
                    )
                    coords.localToRoot(center).y - board.localToRoot(Offset.Zero).y
                }
            target to boardDropIndex(centers, pointer.y)
        }
    val computeDropTargetState = rememberUpdatedState(computeDropTarget)

    // Scroll-on-move (S1-4d): the delay loop is the only scroll
    // mechanism that fires while the pointer dwells, but it cannot be
    // driven in a test and it is the single point of failure for the
    // moving phase — on the device the left edge never scrolled while
    // the right edge did, with the tick and the positions both proven
    // correct. So the drag also scrolls synchronously on movement:
    // each move event whose pointer sits in an edge zone scrolls one
    // step, throttled to the loop's 32ms cadence by event time
    // (change.uptimeMillis). The tick is synchronous (dispatchRawDelta)
    // so this runs inside onDrag with no coroutine and is directly
    // testable; the loop stays for dwelling.
    // Throttle state is a nullable Long, NOT Long.MIN_VALUE: the
    // first subtraction (eventTime - MIN_VALUE) overflows to a
    // negative number for any non-negative uptime, so a MIN_VALUE
    // sentinel would never fire at all (caught by tests h/l in
    // round 4 — scroll-on-move never ran). null means "no move
    // scrolled yet", so the first move always fires.
    val lastMoveScrollUptime = remember { mutableStateOf<Long?>(null) }
    val requestMoveScroll: (Offset, Long) -> Unit = { pointer, eventTimeMillis ->
        val last = lastMoveScrollUptime.value
        if (last == null || eventTimeMillis - last >= 32) {
            lastMoveScrollUptime.value = eventTimeMillis
            boardAutoScrollTick(
                pointer,
                boardSize,
                boardScroll,
                columnScrolls,
                pitchPx,
                edgeXPx,
                edgeYPx,
                scrollStepPx
            )
        }
    }
    val requestMoveScrollState = rememberUpdatedState(requestMoveScroll)

    // Auto-scroll while dragging. A drag session scrolls nothing by
    // itself, and the pointer can sit still at an edge waiting for the
    // board to come to it — so this is a loop, not only a per-move
    // scroll: it ticks while a drag is in flight and scrolls whenever
    // the pointer dwells near an edge. (S1-4d: the same tick is also
    // called synchronously from onDrag — requestMoveScroll — so the
    // moving phase no longer depends on this loop alone; the loop
    // remains for dwelling.)
    LaunchedEffect(drag?.taskId) {
        if (drag == null) return@LaunchedEffect
        while (true) {
            val current = dragState.value ?: break
            boardAutoScrollTick(
                current.pointerViewport,
                boardSize,
                boardScroll,
                columnScrolls,
                pitchPx,
                edgeXPx,
                edgeYPx,
                scrollStepPx
            )
            // S1-4g: the column may have scrolled under a dwelling
            // pointer — recompute the drop target so the insertion
            // indicator (and the eventual drop) stays live even when
            // the finger does not move.
            val (targetStatus, targetIndex) = computeDropTargetState.value(
                current.taskId, current.pointerViewport
            )
            if (targetStatus != current.targetStatus || targetIndex != current.targetIndex) {
                dragState.value = current.copy(
                    targetStatus = targetStatus,
                    targetIndex = targetIndex
                )
            }
            delay(32)
        }
    }

    val draggedTask = drag?.let { d -> items.firstOrNull { it.task.id == d.taskId }?.task }

    // boardSize is the VIEWPORT size (the outer Box), not the
    // content size: the tick's edge zones are in board-viewport
    // coordinates, so they must be compared against the viewport.
    // Measuring the Row (inside horizontalScroll) would report the
    // CONTENT width (840dp) and put the right edge zone at x > 792dp
    // — unreachable from any viewport pointer, so right auto-scroll
    // would never fire (caught by tests h/l in round 4: the left
    // edge, which does not depend on the width, scrolled fine while
    // the right edge never moved).
    Box(
        modifier = modifier
            .testTag("task_board")
            .onSizeChanged { boardSize = it }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(BOARD_COLUMN_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .height(boardHeight)
                .horizontalScroll(boardScroll)
                .onGloballyPositioned { boardCoords = it }
        ) {
            TaskStatus.entries.forEach { status ->
                BoardColumn(
                    status = status,
                    items = grouped[status].orEmpty(),
                    columnScrollState = columnScrolls.getValue(status),
                    dragState = dragState,
                    boardCoordsState = boardCoordsState,
                    boardScrollState = boardScroll,
                    pitchPx = pitchPx,
                    onMoveTaskState = onMoveTaskState,
                    onDragPositionChanged = onDragPositionChanged,
                    requestMoveScrollState = requestMoveScrollState,
                    // S1-4g: the live insertion indicator lives only in
                    // the column under the pointer, at the computed index.
                    insertionIndex = if (drag?.targetStatus == status) {
                        drag.targetIndex
                    } else {
                        null
                    },
                    groupedState = groupedState,
                    computeDropTargetState = computeDropTargetState,
                    cardCoordsById = cardCoordsById,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick
                )
            }
        }
        // Floating preview under the finger while dragging.
        if (drag != null && draggedTask != null) {
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.pointerViewport.x - previewHalfWidthPx).roundToInt(),
                            (drag.pointerViewport.y - previewYOffsetPx).roundToInt()
                        )
                    }
                    .width(BOARD_COLUMN_WIDTH)
                    .testTag("board_drag_preview"),
                shape = NudgyTokens.cardRadius,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = NudgyTokens.cardElevation
            ) {
                Text(
                    text = draggedTask.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    maxLines = 2,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun BoardColumn(
    status: TaskStatus,
    items: List<TaskListItem>,
    columnScrollState: ScrollState,
    dragState: MutableState<BoardDrag?>,
    boardCoordsState: State<LayoutCoordinates?>,
    boardScrollState: ScrollState,
    pitchPx: Float,
    onMoveTaskState: State<(TaskEntity, TaskStatus, Int, List<TaskEntity>) -> Unit>,
    onDragPositionChanged: ((Offset) -> Unit)?,
    requestMoveScrollState: State<(Offset, Long) -> Unit>,
    // S1-4g: the live drop position in THIS column (null = not the
    // column under the pointer) — the insertion indicator renders here.
    insertionIndex: Int?,
    groupedState: State<Map<TaskStatus, List<TaskListItem>>>,
    computeDropTargetState: State<(String, Offset) -> Pair<TaskStatus, Int>>,
    cardCoordsById: SnapshotStateMap<String, LayoutCoordinates>,
    onToggle: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit
) {
    // The column under the pointer highlights while a card is dragged
    // over it — the same affordance the S1-4 drop target had.
    val hovered = dragState.value?.let {
        boardColumnAt(it.pointerViewport.x + boardScrollState.value, pitchPx) == status
    } ?: false
    Surface(
        modifier = Modifier
            .width(BOARD_COLUMN_WIDTH)
            .testTag("board_column_${status.name}"),
        shape = NudgyTokens.cardRadius,
        color = if (hovered) {
            colorResource(R.color.nudgy_teal).copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (status) {
                        TaskStatus.TODO -> "To do"
                        TaskStatus.IN_PROGRESS -> "In progress"
                        TaskStatus.DONE -> "Done"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${items.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(columnScrollState)
            ) {
                // S1-4g — live insertion indicator (v1): a teal line at
                // the computed drop index, updated on every move. Live
                // reflow of the other cards is a follow-up — the line
                // alone shows where the card will land. In an empty
                // column the line sits above the empty text (index 0).
                if (items.isEmpty()) {
                    if (insertionIndex == 0) BoardInsertionIndicator()
                    Text(
                        text = "No tasks here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    for (i in 0..items.size) {
                        if (insertionIndex == i) BoardInsertionIndicator()
                        if (i < items.size) {
                            val item = items[i]
                            BoardTaskCard(
                                item = item,
                                dragState = dragState,
                                boardCoordsState = boardCoordsState,
                                boardScrollState = boardScrollState,
                                onMoveTaskState = onMoveTaskState,
                                onDragPositionChanged = onDragPositionChanged,
                                requestMoveScrollState = requestMoveScrollState,
                                groupedState = groupedState,
                                computeDropTargetState = computeDropTargetState,
                                cardCoordsById = cardCoordsById,
                                isDragging = dragState.value?.taskId == item.task.id,
                                onToggle = { onToggle(item.task) },
                                onClick = { onTaskClick(item.task) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardTaskCard(
    item: TaskListItem,
    dragState: MutableState<BoardDrag?>,
    boardCoordsState: State<LayoutCoordinates?>,
    boardScrollState: ScrollState,
    onMoveTaskState: State<(TaskEntity, TaskStatus, Int, List<TaskEntity>) -> Unit>,
    onDragPositionChanged: ((Offset) -> Unit)?,
    requestMoveScrollState: State<(Offset, Long) -> Unit>,
    groupedState: State<Map<TaskStatus, List<TaskListItem>>>,
    computeDropTargetState: State<(String, Offset) -> Pair<TaskStatus, Int>>,
    cardCoordsById: SnapshotStateMap<String, LayoutCoordinates>,
    isDragging: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val task = item.task
    // S1-4c fix: keep the card's LayoutCoordinates, not a cached
    // Offset. The drag start evaluates localToRoot fresh, so the
    // position follows the board's current scroll.
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // S1-4g: publish these coordinates for the drop-index computation
    // (measured centres) and remove them on dispose.
    DisposableEffect(task.id) {
        onDispose { cardCoordsById.remove(task.id) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isDragging) 0.35f else 1f }
            .onGloballyPositioned {
                cardCoords = it
                cardCoordsById[task.id] = it
            }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offsetInCard ->
                        // Pointer in board-viewport coordinates,
                        // evaluated fresh from the live coordinates.
                        // The card and the board share the same root,
                        // but both sit inside the board's horizontal
                        // scroll container, so their X difference is
                        // the pointer's position in board-CONTENT
                        // coordinates — the scroll offset cancels in
                        // the subtraction — so subtract the board
                        // scroll (x) to land in viewport coordinates.
                        // The Y difference needs NO such correction:
                        // the board does NOT sit inside this column's
                        // vertical scroll, so the card's localToRoot
                        // already carries the column scroll and the
                        // difference IS the viewport y. Subtracting
                        // the column scroll would double-count it and
                        // put the pointer too high by exactly the
                        // column scroll (S1-4e: that made the bottom
                        // edge zone unreachable, so vertical
                        // auto-scroll never fired). Later moves only
                        // add deltas, so this stays right even as the
                        // board auto-scrolls under the finger.
                        val card = cardCoords
                        val board = boardCoordsState.value
                        val startViewport = if (card != null && board != null) {
                            card.localToRoot(offsetInCard) -
                                board.localToRoot(Offset.Zero) -
                                Offset(boardScrollState.value.toFloat(), 0f)
                        } else {
                            offsetInCard
                        }
                        // S1-4g: the drop target at drag start — the
                        // card's own slot, so the insertion indicator
                        // is already showing where it sits.
                        val (startTarget, startIndex) =
                            computeDropTargetState.value(task.id, startViewport)
                        dragState.value = BoardDrag(
                            task.id, startViewport, startTarget, startIndex
                        )
                        onDragPositionChanged?.invoke(startViewport)
                    },
                    // Required by the detector (no default in 1.7).
                    onDrag = { change, dragAmount ->
                        val current = dragState.value
                        if (current != null && current.taskId == task.id) {
                            val pointer = current.pointerViewport + dragAmount
                            val (targetStatus, targetIndex) =
                                computeDropTargetState.value(task.id, pointer)
                            val next = current.copy(
                                pointerViewport = pointer,
                                targetStatus = targetStatus,
                                targetIndex = targetIndex
                            )
                            dragState.value = next
                            onDragPositionChanged?.invoke(next.pointerViewport)
                            requestMoveScrollState.value(
                                next.pointerViewport, change.uptimeMillis
                            )
                        }
                    },
                    onDragEnd = {
                        val info = dragState.value
                        dragState.value = null
                        if (info != null && info.taskId == task.id) {
                            // S1-4g: recompute the target from the
                            // FINAL pointer and the LIVE centres — the
                            // column may have auto-scrolled under a
                            // dwelling pointer since the last move.
                            // The drop resolves to a POSITION now, not
                            // just a column (resolveBoardDrop stays as
                            // the S1-4b regression pin, tested pure).
                            val (targetStatus, targetIndex) =
                                computeDropTargetState.value(task.id, info.pointerViewport)
                            val displayedItems = TaskStatus.entries.flatMap {
                                groupedState.value[it].orEmpty()
                            }
                            val visibleOthers = groupedState.value[targetStatus].orEmpty()
                                .filter { it.task.id != info.taskId }
                                .map { it.task }
                            resolveBoardDropPosition(
                                displayedItems, info.taskId, targetStatus, targetIndex
                            )?.let { target ->
                                onMoveTaskState.value(
                                    target.task, target.status, target.index, visibleOthers
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        if (dragState.value?.taskId == task.id) dragState.value = null
                    }
                )
            }
            .clickable { onClick() }
            .testTag("board_card_${task.id}"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colorResource(R.color.nudgy_teal)
                )
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(boardPriorityDotColor(task.priority))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (task.isCompleted) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    ),
                    color = if (task.isCompleted) {
                        NudgyMuted
                    } else {
                        NudgyCharcoal
                    },
                    maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = boardDueLabel(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = NudgyMuted
                    )
                    if (item.subtaskTotal > 0) {
                        Text(
                            text = "· ${item.subtaskDone}/${item.subtaskTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
                if (task.groupId != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                colorResource(R.color.nudgy_teal).copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Group",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyAccent
                        )
                    }
                    // Sprint 5-5 (feature 36): the assignee's name,
                    // denormalized on the row — blank reads "Member".
                    if (task.assigneeUid != null) {
                        Text(
                            text = "· ${task.assigneeName?.ifBlank { "Member" } ?: "Member"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NudgyMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * S1-4g — live insertion indicator: a teal line showing exactly where
 * the dragged card will land, at the computed drop index. Rendered
 * inside the target column on every move (and on every auto-scroll
 * tick while dwelling). Live reflow of the other cards is a follow-up.
 */
@Composable
private fun BoardInsertionIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(colorResource(R.color.nudgy_teal), CircleShape)
            .testTag("board_insertion_indicator")
    )
}

@Composable
private fun boardPriorityDotColor(priority: Priority) = when (priority) {
    Priority.HIGH -> colorResource(R.color.nudgy_coral)
    Priority.MEDIUM -> colorResource(R.color.nudgy_teal)
    Priority.LOW -> colorResource(R.color.nudgy_muted)
}

private fun boardDueLabel(task: TaskEntity): String {
    if (task.dueDateEpochMillis == TaskRepository.NO_DUE_DATE) return "No date"
    return SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        .format(task.dueDateEpochMillis)
}
