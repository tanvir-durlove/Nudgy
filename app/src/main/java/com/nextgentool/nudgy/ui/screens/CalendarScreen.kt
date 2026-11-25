package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.CalendarEventEntity
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.data.repository.FocusTimeSlot
import com.nextgentool.nudgy.domain.DEFAULT_SLOT_MINUTES
import com.nextgentool.nudgy.domain.PendingDropConflict
import com.nextgentool.nudgy.domain.dayIndexInWeek
import com.nextgentool.nudgy.domain.minutesOfTime
import com.nextgentool.nudgy.domain.parseStrictDate
import com.nextgentool.nudgy.domain.slotBlockMinutes
import com.nextgentool.nudgy.domain.slotStartForY
import com.nextgentool.nudgy.domain.startOfDayMillis
import com.nextgentool.nudgy.domain.weekOffsetForDay
import com.nextgentool.nudgy.intent.IncomingIntent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.nextgentool.nudgy.ui.theme.NudgyAccent
import com.nextgentool.nudgy.ui.theme.NudgyCard
import com.nextgentool.nudgy.ui.theme.NudgyCardBorder
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyChipBackground
import com.nextgentool.nudgy.ui.theme.NudgyFieldBorder
import com.nextgentool.nudgy.ui.theme.NudgyGreen
import com.nextgentool.nudgy.ui.theme.NudgyLavender
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyPeach
import com.nextgentool.nudgy.ui.theme.NudgyPrimary
import com.nextgentool.nudgy.ui.theme.NudgySage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DynamicDayItem(
    val dayOfWeek: String,
    val dayOfMonth: Int,
    val epochMillis: Long,
    val isToday: Boolean
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CalendarScreen(
    events: List<CalendarEventEntity>,
    // Sprint 2-1: the suggested slot comes from the ViewModel (repository
    // rule, 35-min gap) — the screen no longer computes it, and the
    // `tasks` parameter it was computed from is gone.
    suggestedSlot: FocusTimeSlot? = null,
    isCalendarConnected: Boolean = false,
    isSyncing: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onConnectGoogleCalendar: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onBlockFocusTime: (startTime: Long, endTime: Long, title: String) -> Unit = { _, _, _ -> },
    onAddEvent: (title: String, startTime: Long, endTime: Long, location: String?, type: String) -> Unit = { _, _, _, _, _ -> },
    // Sprint 2-1 (Q8(a)): edit an event — same dialog as add, prefilled.
    onUpdateEvent: (eventId: String, title: String, startTime: Long, endTime: Long, location: String?, type: String) -> Unit = { _, _, _, _, _, _ -> },
    onDeleteEvent: (eventId: String) -> Unit = {},
    onSelectDay: (Long) -> Unit = {},
    groupNames: Map<String, String> = emptyMap(),
    // Sprint 2-2 — drag-to-slot: the strip (Q4(b)) and the blocks already
    // on the selected day's grid come from the ViewModel; a drop hands
    // the task + slot start back (the ViewModel writes plannedStart /
    // plannedDate and owns the busy-slot warn, Q3(a)).
    stripTasks: List<TaskEntity> = emptyList(),
    placedTasks: List<TaskEntity> = emptyList(),
    pendingConflict: PendingDropConflict? = null,
    onDropTask: (task: TaskEntity, slotStartMillis: Long) -> Unit = { _, _ -> },
    onAcceptSuggestedSlot: () -> Unit = {},
    onDismissConflict: () -> Unit = {},
    // Test observability (S2-2, the Board's S1-4c seams): the live drag
    // pointer (screen-viewport px) and the live slot under it, reported
    // synchronously from the gesture callbacks — asserting them asserts
    // the preview position and the highlight without reading semantics
    // mid-gesture (which hangs the test worker). Null in production.
    onDragPositionChanged: ((Offset) -> Unit)? = null,
    onDragSlotChanged: ((Long?) -> Unit)? = null,
    // Test seam (S2-2, like the Board's boardScrollState): an
    // externally owned screen scroll state, so a test can assert the
    // drag auto-scroll moved the screen. Null/default in production.
    screenScrollState: ScrollState? = null,
    // Sprint 2-4 — a pending date link / create-event intent (from
    // the Activity-scoped ShareIntentViewModel, via NudgyApp). The
    // screen jumps to the link's day (+ slot highlight) and opens the
    // add-event dialog prefilled, then consumes them. Null when none.
    pendingDateLink: IncomingIntent.DateLink? = null,
    onDateLinkConsumed: () -> Unit = {},
    pendingInsertEvent: IncomingIntent.InsertEvent? = null,
    onInsertEventConsumed: () -> Unit = {},
    // Sprint 6-4 (step 2f) — search deep-link: the id of the event a
    // search tap opened, and the start-of-day of that event's day.
    // The id alone is not enough — the `events` list is day-scoped
    // (the ViewModel's dayEvents flow), so the screen must jump the
    // strip to the event's day before the event can appear in the
    // list. Defaults to null so ordinary entries (bottom nav, date
    // link) behave exactly as before. Consumed once by the effects
    // below, same state pattern as GroupsScreen's openGroupId (S5-3)
    // and the task deep-link (2e).
    openEventId: String? = null,
    openEventDayMillis: Long? = null,
    onOpenEventIdConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAddEventDialog by remember { mutableStateOf(false) }
    var eventPendingDelete by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var eventPendingEdit by remember { mutableStateOf<CalendarEventEntity?>(null) }
    // Sprint 2-4 — the create-event intent being prefilled into the
    // add-event dialog (set by the insert effect below, cleared on
    // dialog dismiss / save).
    var eventPendingInsert by remember { mutableStateOf<IncomingIntent.InsertEvent?>(null) }
    // Sprint 2-4 — the slot a date link highlighted (slot start +
    // display minutes), or null. Cleared on any manual day/week move
    // or drag start — it is a one-shot cue, not a booking.
    var linkHighlight by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var isBlockingTime by remember { mutableStateOf(false) }

    LaunchedEffect(events) {
        isBlockingTime = false
    }

    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val monthFormatter = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    // Dynamically compute the 7 days of the shown week, keyed by day of
    // year so midnight updates cleanly; weekOffset shifts whole weeks so
    // events beyond the current week can be created (week nav buttons).
    val currentDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    var weekOffset by remember { mutableIntStateOf(0) }
    val (_, daysList) = remember(currentDayOfYear, weekOffset) {
        val cal = Calendar.getInstance()
        val monthStr = monthFormatter.format(cal.time).uppercase()
        val dayOfWeekToday = cal.get(Calendar.DAY_OF_WEEK)
        val diffToMonday = if (dayOfWeekToday == Calendar.SUNDAY) -6 else Calendar.MONDAY - dayOfWeekToday
        cal.add(Calendar.DAY_OF_MONTH, diffToMonday)
        cal.add(Calendar.DAY_OF_MONTH, weekOffset * 7)

        val days = mutableListOf<DynamicDayItem>()
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val todayCal = Calendar.getInstance()

        for (i in 0 until 7) {
            val isToday = cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
            days.add(
                DynamicDayItem(
                    dayOfWeek = dayFormat.format(cal.time),
                    dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                    epochMillis = cal.timeInMillis,
                    isToday = isToday
                )
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        Pair(monthStr, days)
    }

    val todayIndex = daysList.indexOfFirst { it.isToday }.coerceAtLeast(0)
    var selectedDayIndex by remember { mutableIntStateOf(todayIndex) }

    val selectedDayMillis = daysList.getOrNull(selectedDayIndex)?.epochMillis ?: System.currentTimeMillis()
    val headerMonth = remember(selectedDayMillis) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
        monthFormatter.format(cal.time).uppercase()
    }

    // Sprint 2-2 — the selected day's midnight (the grid starts here).
    val dayStartMillis = remember(selectedDayMillis) { startOfDayMillis(selectedDayMillis) }

    // Sprint 2-2 — week-nav fix: tapping the prev/next-week chevron
    // changes the displayed day (daysList recomputes) but never called
    // onSelectDay, so the ViewModel kept the old day and the strip /
    // grid / events showed the wrong day. Sync it whenever the
    // displayed day changes; the tap path calls it too (harmless).
    LaunchedEffect(selectedDayMillis) { onSelectDay(selectedDayMillis) }

    // Sprint 2-4 — jump the week strip to a day (a date link's day,
    // or a create-event intent's begin day). The strip owns the week
    // state — the ViewModel's selectDay alone cannot move it, and the
    // screen's own LaunchedEffect(selectedDayMillis) above then syncs
    // the ViewModel (strip / grid / events all follow).
    fun jumpToDay(dayStartMillis: Long, highlightSlotMillis: Long?) {
        weekOffset = weekOffsetForDay(dayStartMillis)
        selectedDayIndex = dayIndexInWeek(dayStartMillis)
        linkHighlight = highlightSlotMillis?.let { it to DEFAULT_SLOT_MINUTES }
    }

    // Sprint 2-4 — a date link opens the Calendar on its day (and
    // highlights the slot when `t` is present). Consumed right after
    // acting, so a rotation never re-jumps. The parser already
    // validated d/t — the null branches are defense-in-depth only.
    LaunchedEffect(pendingDateLink) {
        val link = pendingDateLink ?: return@LaunchedEffect
        val dayStart = parseStrictDate(link.date)
        if (dayStart == null) {
            onDateLinkConsumed()
            return@LaunchedEffect
        }
        val slot = link.time?.let { minutesOfTime(it) }?.let { dayStart + it * 60_000L }
        jumpToDay(dayStart, slot)
        onDateLinkConsumed()
    }

    // Sprint 2-4 — a create-event intent jumps to the event's day and
    // opens the add-event dialog prefilled (the dialog reads
    // eventPendingInsert below). No begin time → the dialog opens on
    // the currently selected day instead.
    LaunchedEffect(pendingInsertEvent) {
        val insert = pendingInsertEvent ?: return@LaunchedEffect
        insert.beginMillis?.let { begin ->
            jumpToDay(startOfDayMillis(begin), null)
        }
        eventPendingInsert = insert
        onInsertEventConsumed()
    }

    // Sprint 6-4 (step 2f) — search deep-link: jump to the event's
    // day, once, the moment the id arrives (the S2-4 insert-effect
    // pattern above). This effect never consumes — the select effect
    // below consumes once the event appears (or proves stale) — so a
    // re-entry with the same id cannot re-jump after consumption.
    // A null day means the event is on the current day already.
    LaunchedEffect(openEventId) {
        if (openEventId == null) return@LaunchedEffect
        openEventDayMillis?.let { jumpToDay(it, null) }
    }

    // Sprint 6-4 (step 2f) — open the deep-linked event's edit
    // dialog once the day's events contain it. The list is
    // day-scoped (the ViewModel's dayEvents flow), so the event
    // only appears after the jump above moves the strip and the
    // ViewModel re-emits — this effect re-runs on every events
    // change until then. The edit dialog is the tap target: the
    // row tap opens it for personal events only (Sprint 2-1), so
    // a group event (groupId != null) jumps to its day but opens
    // no dialog — same rule as the row tap and the delete rule.
    // A stale id is consumed once the current day IS the event's
    // day and the list still lacks it (the list is authoritative
    // for that day); without a day, it is consumed at once —
    // otherwise a later day change would re-run this effect and
    // the jump effect above would yank the user back.
    LaunchedEffect(openEventId, events) {
        val id = openEventId ?: return@LaunchedEffect
        val event = events.firstOrNull { it.id == id }
        if (event != null) {
            if (event.groupId == null) {
                eventPendingEdit = event
            }
            onOpenEventIdConsumed()
            return@LaunchedEffect
        }
        val day = openEventDayMillis
        if (day == null || dayStartMillis == day) {
            onOpenEventIdConsumed()
        }
    }

    // Sprint 2-2 — drag-to-slot state machine (the Board's S1-4b
    // pattern: manual long-press drag + floating preview + live slot
    // highlight + auto-scroll, never platform DnD). The screen owns
    // the drag; the strip cards only report gesture events.
    val density = LocalDensity.current
    val hourHeightPx = with(density) { CALENDAR_HOUR_HEIGHT.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val scrollStepPx = with(density) { 24.dp.toPx() }
    val previewHalfWidthPx = with(density) { 100.dp.toPx() }
    val previewWidthPx = with(density) { 200.dp.toPx() }
    val previewYOffsetPx = with(density) { 28.dp.toPx() }

    val screenScroll = screenScrollState ?: rememberScrollState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragState = remember { mutableStateOf<CalendarDrag?>(null) }
    val drag = dragState.value
    val boxCoordsState = rememberUpdatedState(boxCoords)
    val gridCoordsState = rememberUpdatedState(gridCoords)

    // The slot under a pointer in screen-viewport coordinates, from
    // the grid's LIVE coordinates (evaluated fresh — the grid moves
    // under a dwelling pointer while the screen auto-scrolls). Null
    // when the pointer is outside the grid (above it, in the strip, or
    // below it) — no highlight, and a release there drops nothing.
    // The strip card and the grid both sit inside the screen's
    // vertical scroll, so the scroll offset cancels in the
    // localToRoot subtraction (the Board's X case, S1-4c): the
    // difference IS the pointer's position in grid coordinates, and
    // no further scroll correction is needed — the grid does not
    // scroll internally.
    val computeSlot: (Offset) -> Long? = { pointer ->
        val grid = gridCoordsState.value
        val box = boxCoordsState.value
        if (grid == null || box == null) {
            null
        } else {
            val gridViewportTop =
                grid.localToRoot(Offset.Zero).y - box.localToRoot(Offset.Zero).y
            val gridHeightPx = 24f * hourHeightPx
            val gridLocalY = pointer.y - gridViewportTop
            if (gridLocalY < 0f || gridLocalY > gridHeightPx) null
            else slotStartForY(gridLocalY, hourHeightPx, dayStartMillis)
        }
    }
    val computeSlotState = rememberUpdatedState(computeSlot)

    // Scroll-on-move (S1-4d pattern): the delay loop is the only scroll
    // mechanism that fires while the pointer dwells, but it cannot be
    // driven in a test — so the drag also scrolls synchronously on
    // movement, throttled to the loop's 32ms cadence by event time.
    // Throttle state is a nullable Long, NOT Long.MIN_VALUE (the first
    // subtraction overflows negative and would never fire — S1-4d).
    val lastMoveScrollUptime = remember { mutableStateOf<Long?>(null) }
    val requestMoveScroll: (Offset, Long) -> Unit = { pointer, eventTimeMillis ->
        val last = lastMoveScrollUptime.value
        if (last == null || eventTimeMillis - last >= 32) {
            lastMoveScrollUptime.value = eventTimeMillis
            calendarAutoScrollTick(pointer, viewportSize, screenScroll, edgePx, scrollStepPx)
        }
    }
    val requestMoveScrollState = rememberUpdatedState(requestMoveScroll)

    // Auto-scroll while dragging (S1-4c/d pattern): the pointer can sit
    // still at an edge waiting for the screen to come to it, so this is
    // a loop, not only a per-move scroll. The slot is recomputed on
    // every tick from the live grid coordinates, so the highlight (and
    // the eventual drop) stays live even when the finger does not move.
    // Robolectric cannot tick this loop's delay during a held gesture
    // (proven by three harness approaches for the Board) — the loop is
    // verified end-to-end on device; the tick itself is pure-tested.
    LaunchedEffect(drag?.taskId) {
        if (drag == null) return@LaunchedEffect
        while (true) {
            val current = dragState.value ?: break
            calendarAutoScrollTick(
                current.pointerViewport, viewportSize, screenScroll, edgePx, scrollStepPx
            )
            val slot = computeSlotState.value(current.pointerViewport)
            if (slot != current.slotStartMillis) {
                dragState.value = current.copy(slotStartMillis = slot)
                onDragSlotChanged?.invoke(slot)
            }
            delay(32)
        }
    }

    // Sprint 2-1b — the day's events split in two: all-day events are
    // labels (a chip row under the Connect & Sync card, never in the
    // schedule), timed events are the schedule (UP NEXT count/list and
    // the hour grid). Both lists share the same day filter.
    val (allDayEvents, displayEvents) = remember(events, selectedDayMillis) {
        val (startOfDay, endOfDay) = run {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            start to end
        }

        // Calendar events only — tasks are never projected into the
        // schedule; they live in the Tasks tab.
        val list = events.filter {
            (it.startEpochMillis in startOfDay..endOfDay) ||
            (it.startEpochMillis <= endOfDay && it.endEpochMillis >= startOfDay)
        }.sortedBy { it.startEpochMillis }
        list.partition { it.isAllDay }
    }

    // Sprint 2-1: the suggested slot arrives from the ViewModel, which
    // uses the repository rule (35-min minimum gap). The screen's old
    // inline calculation (30-min gap) disagreed with it and is deleted.
    // A null slot means the selected day has no free slot — the card
    // below is hidden then.
    val suggestedStart = suggestedSlot?.startEpochMillis ?: 0L
    val suggestedEnd = suggestedSlot?.endEpochMillis ?: 0L
    val suggestedDuration = suggestedSlot?.durationMinutes ?: 0

    // Sprint 2-1b — timed events only: an all-day label must never
    // mark the suggested slot "Blocked" or disable the Block time button.
    val hasBlockedSuggestedTime = remember(displayEvents, suggestedStart, suggestedEnd) {
        displayEvents.any { event ->
            event.startEpochMillis < suggestedEnd && event.endEpochMillis > suggestedStart
        }
    }

    // Sprint 2-2 — the outer Box is the viewport: its size (measured
    // here, outside the scroll) is what the auto-scroll edge zones
    // compare against, and its coordinates anchor the drag pointer.
    // Measuring the scrolling Column instead would report the CONTENT
    // height and put the bottom edge zone out of reach (the Board's
    // S1-4d boardSize lesson).
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .onGloballyPositioned { boxCoords = it }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(screenScroll)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Sprint 2-1: sync / edit failures are shown, not swallowed.
        if (errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color(0xFFFDECEA),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyCharcoal,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = NudgyAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = headerMonth,
                    style = MaterialTheme.typography.labelSmall,
                    color = NudgyAccent
                )
                val isSelectedDayToday = daysList.getOrNull(selectedDayIndex)?.isToday == true
                val scheduleTitle = if (isSelectedDayToday) {
                    "Today’s schedule"
                } else {
                    "${daysList.getOrNull(selectedDayIndex)?.dayOfWeek ?: "Day"}’s schedule"
                }
                Text(
                    text = scheduleTitle,
                    style = MaterialTheme.typography.displayMedium,
                    color = NudgyCharcoal
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable { showAddEventDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add event",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Horizontal Weekly Date Selector (with prev/next week controls)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { weekOffset--; linkHighlight = null },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Previous week",
                    tint = NudgyCharcoal,
                    modifier = Modifier.size(22.dp)
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                daysList.forEachIndexed { index, day ->
                    val isSelected = index == selectedDayIndex

                    Column(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent)
                            .clickable {
                                selectedDayIndex = index
                                // Sprint 2-4 — a manual day move drops
                                // the date-link highlight (one-shot cue).
                                linkHighlight = null
                                onSelectDay(day.epochMillis)
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = day.dayOfWeek,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f) else NudgyMuted
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${day.dayOfMonth}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal
                            )
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { weekOffset++; linkHighlight = null },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Next week",
                    tint = NudgyCharcoal,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Google Calendar Connect & Sync Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (isCalendarConnected) NudgySage else NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isCalendarConnected) Color(0xFFA5D6A7) else NudgyCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, if (isCalendarConnected) Color(0xFFA5D6A7) else NudgyCardBorder, MaterialTheme.shapes.medium)
                        .background(if (isCalendarConnected) NudgyCard else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_google_logo),
                        contentDescription = "Google Calendar",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCalendarConnected) "Calendar synced" else "Bring your calendar in",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isCalendarConnected) "Events from your device calendar stay in sync." else "Allow calendar access to spot conflicts and find open time.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (isCalendarConnected) {
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NudgyGreen),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyGreen)
                    ) {
                        Text(
                            text = if (isSyncing) "Syncing…" else "Sync now",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }
                } else {
                    Button(
                        onClick = onConnectGoogleCalendar,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "Allow access",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        // Sprint 2-1b — all-day events are labels: one read-only chip
        // per event, directly under the Connect & Sync card. Only shown
        // when the day has all-day events; tapping a chip does nothing
        // in v1 (the label is information, not a schedule item).
        if (allDayEvents.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("all_day_row"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                allDayEvents.forEach { event ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("all_day_label_${event.id}"),
                        shape = MaterialTheme.shapes.medium,
                        color = NudgyChipBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(NudgyPrimary)
                            )
                            Text(
                                text = event.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = NudgyCharcoal
                            )
                        }
                    }
                }
            }
        }

        // Schedule Timeline Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "UP NEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = NudgyAccent
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Schedule · ${displayEvents.size} items",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = NudgyCharcoal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, NudgyCardBorder, MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = NudgyCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (displayEvents.isEmpty()) {
                    Text(
                        text = "No items scheduled for this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NudgyMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    // Vertical Timeline Cards
                    displayEvents.forEach { event ->
                        val isMeeting = event.eventType == "MEETING"
                        val cardBg = if (isMeeting) NudgyPeach else NudgyLavender
                        val dotColor = if (isMeeting) NudgyPrimary else Color(0xFF7A68E6)

                        val startTimeStr = timeFormatter.format(event.startEpochMillis)
                        val endTimeStr = timeFormatter.format(event.endEpochMillis)

                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(52.dp)
                            ) {
                                Text(
                                    text = startTimeStr,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = NudgyMuted
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, dotColor, CircleShape)
                                        .background(Color.White)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    // Sprint 2-2: the grid renders the same
                                    // event title as a block, so the schedule
                                    // row needs its own tag for tests.
                                    .testTag("schedule_event_${event.id}")
                                    .then(
                                        // Sprint 2-1 (Q8(a)): tap a personal
                                        // event to edit it. Group rows are
                                        // never edited from here (same rule
                                        // as delete).
                                        if (event.groupId == null) Modifier.clickable { eventPendingEdit = event }
                                        else Modifier
                                    ),
                                shape = MaterialTheme.shapes.large,
                                color = cardBg
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "$startTimeStr – $endTimeStr",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                            color = NudgyMuted
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = event.title,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            ),
                                            color = NudgyCharcoal
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val locationText = event.locationOrLink?.takeIf { it.isNotBlank() }
                                            if (locationText != null) {
                                                Text(
                                                    text = locationText,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                    color = NudgyMuted
                                                )
                                            }
                                            if (event.isFromGoogleCalendar) {
                                                if (locationText != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = "· Google Calendar",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF2E7D32)
                                                    )
                                                )
                                            }
                                        }
                                        // Group v1: origin badge for group schedules.
                                        if (event.groupId != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(NudgyPrimary.copy(alpha = 0.12f))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = groupNames[event.groupId] ?: "Group",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = NudgyAccent
                                                )
                                            }
                                        }
                                    }

                                    // Group v1: members never delete a group schedule
                                    // from here — only the creator, from the Groups
                                    // tab, can delete the shared item.
                                    if (event.groupId == null) {
                                        IconButton(
                                            onClick = { eventPendingDelete = event },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete event",
                                                tint = NudgyMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Suggested Focus Time Card (Figma Screen 2) — hidden when the
        // selected day has no free slot (suggestedSlot is null).
        if (suggestedSlot != null) {
        val suggestedStartStr = timeFormatter.format(suggestedStart)
        val suggestedEndStr = timeFormatter.format(suggestedEnd)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SUGGESTED FOCUS TIME",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasBlockedSuggestedTime) "Blocked: $suggestedStartStr – $suggestedEndStr" else "$suggestedStartStr – $suggestedEndStr",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NudgyCharcoal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (hasBlockedSuggestedTime) "Focus time added to your schedule" else "$suggestedDuration minutes free in your schedule",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = NudgyMuted
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        if (!isBlockingTime && !hasBlockedSuggestedTime) {
                            isBlockingTime = true
                            onBlockFocusTime(suggestedStart, suggestedEnd, "Deep focus session")
                        }
                    },
                    enabled = !hasBlockedSuggestedTime && !isBlockingTime,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasBlockedSuggestedTime) Color(0xFFE0E0E0) else NudgyPrimary
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = if (hasBlockedSuggestedTime) "Blocked" else "Block time",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (hasBlockedSuggestedTime) NudgyMuted else Color.White
                        )
                    )
                }
            }
        }
        }

        // (No bottom clearance spacer: the floating mascot button is gone;
        // Scaffold innerPadding + navigationBarsPadding handle the nav bar.)

        // Sprint 2-2 — drag-to-slot: the strip above the day grid.
        CalendarTaskStrip(
            tasks = stripTasks,
            draggingTaskId = drag?.taskId,
            onDragStart = { task, offsetInCard, cardCoords ->
                // Sprint 2-4 — a drag drops the date-link highlight
                // (the drag's own live highlight takes over).
                linkHighlight = null
                // Pointer in screen-viewport coordinates, evaluated
                // fresh from the live coordinates (the Board's S1-4c
                // fix: never a cached Offset).
                val card = cardCoords
                val box = boxCoordsState.value
                val startViewport = if (card != null && box != null) {
                    card.localToRoot(offsetInCard) - box.localToRoot(Offset.Zero)
                } else {
                    offsetInCard
                }
                val slot = computeSlotState.value(startViewport)
                dragState.value = CalendarDrag(
                    task.id, startViewport, slot, slotBlockMinutes(task)
                )
                onDragPositionChanged?.invoke(startViewport)
                onDragSlotChanged?.invoke(slot)
            },
            onDragMove = { dragAmount, uptimeMillis ->
                val current = dragState.value
                if (current != null) {
                    val pointer = current.pointerViewport + dragAmount
                    val slot = computeSlotState.value(pointer)
                    dragState.value = current.copy(pointerViewport = pointer, slotStartMillis = slot)
                    onDragPositionChanged?.invoke(pointer)
                    onDragSlotChanged?.invoke(slot)
                    requestMoveScrollState.value(pointer, uptimeMillis)
                }
            },
            onDragEnd = { task ->
                val info = dragState.value
                dragState.value = null
                if (info != null && info.taskId == task.id) {
                    // Recompute the slot from the FINAL pointer and the
                    // LIVE grid coordinates — the screen may have
                    // auto-scrolled under a dwelling pointer since the
                    // last move (the Board's S1-4g onDragEnd rule).
                    val slot = computeSlotState.value(info.pointerViewport)
                    if (slot != null) onDropTask(task, slot)
                }
            },
            onDragCancel = { task ->
                if (dragState.value?.taskId == task.id) dragState.value = null
            }
        )

        // Sprint 2-2 — the day time-grid.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = NudgyCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, NudgyCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "TIME GRID",
                    style = MaterialTheme.typography.labelSmall,
                    color = NudgyAccent
                )
                Text(
                    text = "Your day, hour by hour",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NudgyCharcoal
                )
                CalendarHourGrid(
                    dayStartMillis = dayStartMillis,
                    events = displayEvents,
                    placedTasks = placedTasks,
                    highlight = if (drag?.slotStartMillis != null) {
                        drag.slotStartMillis to drag.durationMinutes
                    } else {
                        // Sprint 2-4 — the date link's slot (null when
                        // the link had no `t`, or after any manual move).
                        linkHighlight
                    },
                    modifier = Modifier.onGloballyPositioned { gridCoords = it }
                )
            }
        }
    }
    // Floating preview under the finger while dragging (the Board's
    // pattern): centred on the pointer, clamped inside the viewport.
    val draggedTask = drag?.let { d -> stripTasks.firstOrNull { it.id == d.taskId } }
    if (drag != null && draggedTask != null) {
        Surface(
            modifier = Modifier
                .offset {
                    val x = (drag.pointerViewport.x - previewHalfWidthPx).roundToInt()
                        .coerceIn(0, (viewportSize.width - previewWidthPx).roundToInt().coerceAtLeast(0))
                    val y = (drag.pointerViewport.y - previewYOffsetPx).roundToInt().coerceAtLeast(0)
                    IntOffset(x, y)
                }
                .width(200.dp)
                .testTag("calendar_drag_preview"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp
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

    // Sprint 2-2 — the busy-slot dialog (Q3(a)).
    if (pendingConflict != null) {
        CalendarBusySlotDialog(
            pending = pendingConflict,
            onAccept = onAcceptSuggestedSlot,
            onDismiss = onDismissConflict
        )
    }

        // Delete confirmation: one tap used to delete instantly — and for a
        // repeating event the repository deletes by the series-level Google
        // id, so a single occurrence tap wiped the whole series from Google
        // Calendar. Always confirm, and say plainly when a series is involved.
        eventPendingDelete?.let { pending ->
            val isSeries = pending.googleCalendarEventId != null &&
                events.count { it.googleCalendarEventId == pending.googleCalendarEventId } > 1
            AlertDialog(
                onDismissRequest = { eventPendingDelete = null },
                title = { Text("Delete this event?", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                text = {
                    Text(
                        text = when {
                            isSeries -> "\"${pending.title}\" is one occurrence of a repeating series. Deleting it removes the ENTIRE series, including from Google Calendar."
                            pending.googleCalendarEventId != null -> "\"${pending.title}\" will be deleted, including from Google Calendar."
                            else -> "\"${pending.title}\" will be deleted."
                        },
                        color = NudgyCharcoal
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteEvent(pending.id)
                        eventPendingDelete = null
                    }) {
                        Text("Delete", color = Color(0xFFE85542), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { eventPendingDelete = null }) {
                        Text("Cancel", color = NudgyMuted)
                    }
                }
            )
        }


    if (showAddEventDialog || eventPendingEdit != null || eventPendingInsert != null) {
        // Sprint 2-1 (Q8(a)): when opened from an event card, the dialog
        // is an EDIT — prefilled from the event (title / location /
        // duration / type / start time on the event's own day; the date
        // itself is not editable). Otherwise it is a CREATE.
        // Sprint 2-4: a create-event intent opens the same dialog as a
        // CREATE prefilled from the intent (title / location / begin /
        // end) — same fields, same save path.
        val editingEvent = eventPendingEdit
        val insert = eventPendingInsert
        var eventTitle by remember(editingEvent, insert) {
            mutableStateOf(insert?.title ?: editingEvent?.title ?: "")
        }
        var eventLocation by remember(editingEvent, insert) {
            mutableStateOf(insert?.location ?: editingEvent?.locationOrLink ?: "")
        }
        var selectedDurationMinutes by remember(editingEvent, insert) {
            mutableIntStateOf(
                insert?.let { ins ->
                    if (ins.beginMillis != null && ins.endMillis != null) {
                        ((ins.endMillis - ins.beginMillis) / 60_000L).toInt().coerceAtLeast(5)
                    } else {
                        null
                    }
                }
                    ?: editingEvent?.let { ((it.endEpochMillis - it.startEpochMillis) / 60_000L).toInt().coerceAtLeast(5) }
                    ?: 30
            )
        }
        var eventType by remember(editingEvent) { mutableStateOf(editingEvent?.eventType ?: "MEETING") }

        // Start time defaults: Current hour + 1 rounded to :00, or 10:00 AM
        val now = Calendar.getInstance()
        val defaultHour = now.get(Calendar.HOUR_OF_DAY).let { if (it in 7..21) (it + 1) % 24 else 10 }
        val editingCal = editingEvent?.let { Calendar.getInstance().apply { timeInMillis = it.startEpochMillis } }
        val insertCal = insert?.beginMillis?.let { Calendar.getInstance().apply { timeInMillis = it } }
        var selectedHour by remember(editingEvent, insert) {
            mutableIntStateOf(editingCal?.get(Calendar.HOUR_OF_DAY) ?: insertCal?.get(Calendar.HOUR_OF_DAY) ?: defaultHour)
        }
        var selectedMinute by remember(editingEvent, insert) {
            mutableIntStateOf(editingCal?.get(Calendar.MINUTE) ?: insertCal?.get(Calendar.MINUTE) ?: 0)
        }
        var showTimePicker by remember { mutableStateOf(false) }

        fun formatTimeStr(h: Int, m: Int): String {
            val amPm = if (h < 12) "AM" else "PM"
            val displayH = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(Locale.getDefault(), "%d:%02d %s", displayH, m, amPm)
        }

        val totalEndMins = selectedHour * 60 + selectedMinute + selectedDurationMinutes
        val endHour = (totalEndMins / 60) % 24
        val endMinute = totalEndMins % 60
        val scheduleSummary = "${formatTimeStr(selectedHour, selectedMinute)} – ${formatTimeStr(endHour, endMinute)} ($selectedDurationMinutes min)"

        AlertDialog(
            onDismissRequest = { showAddEventDialog = false; eventPendingEdit = null; eventPendingInsert = null },
            title = { Text(if (editingEvent != null) "Edit event" else "Schedule Event", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = { eventTitle = it },
                        label = { Text("Event Name") },
                        placeholder = { Text("e.g. Design review") },
                        // Sprint 6-4 (step 2f) — test seam: the deep-link
                        // test asserts the dialog opened for THIS event
                        // by reading the prefilled title field (the
                        // title text also sits on the event row behind
                        // the dialog, so text alone is ambiguous).
                        modifier = Modifier.fillMaxWidth().testTag("edit_event_title_field"),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NudgyPrimary,
                            unfocusedBorderColor = NudgyFieldBorder
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = eventLocation,
                        onValueChange = { eventLocation = it },
                        label = { Text("Location / Link") },
                        placeholder = { Text("Google Meet or Room 4B") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NudgyPrimary,
                            unfocusedBorderColor = NudgyFieldBorder
                        ),
                        singleLine = true
                    )

                    // START TIME SELECTOR
                    Text("START TIME", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(NudgyChipBackground)
                            .border(1.dp, NudgyCardBorder, MaterialTheme.shapes.medium)
                            .clickable { showTimePicker = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Pick Time",
                                tint = NudgyPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = formatTimeStr(selectedHour, selectedMinute),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NudgyCharcoal
                            )
                        }
                        Text(
                            text = "Tap to pick",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = NudgyAccent
                        )
                    }

                    // Quick Time Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "9:00 AM" to (9 to 0),
                            "11:00 AM" to (11 to 0),
                            "2:00 PM" to (14 to 0),
                            "4:00 PM" to (16 to 0)
                        ).forEach { (label, pair) ->
                            val isSelected = selectedHour == pair.first && selectedMinute == pair.second
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) NudgyPrimary else NudgyChipBackground)
                                    .clickable {
                                        selectedHour = pair.first
                                        selectedMinute = pair.second
                                    }
                                    .height(48.dp)
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.White else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Text("DURATION", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 30, 45, 60).forEach { mins ->
                            val isSelected = selectedDurationMinutes == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) NudgyPrimary else NudgyChipBackground)
                                    .clickable { selectedDurationMinutes = mins }
                                    .height(48.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$mins min",
                                    color = if (isSelected) Color.White else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Schedule Time Slot Summary
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = NudgyPrimary.copy(alpha = 0.08f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Slot: $scheduleSummary",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = NudgyCharcoal
                            )
                        }
                    }

                    Text("TYPE", style = MaterialTheme.typography.labelSmall, color = NudgyMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("MEETING" to "Meeting", "FOCUS_TIME" to "Focus Time").forEach { (typeKey, typeLabel) ->
                            val isSelected = eventType == typeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.inverseSurface else NudgyChipBackground)
                                    .clickable { eventType = typeKey }
                                    .height(48.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = typeLabel,
                                    color = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else NudgyCharcoal,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventTitle.isNotBlank()) {
                            // Edit keeps the event's own day; a
                            // create-event intent keeps its begin day;
                            // a plain create uses the selected day. The
                            // date is not editable.
                            val baseEpoch = editingEvent?.startEpochMillis
                                ?: insert?.beginMillis
                                ?: daysList.getOrNull(selectedDayIndex)?.epochMillis
                                ?: System.currentTimeMillis()
                            val eventStartCal = Calendar.getInstance().apply {
                                timeInMillis = baseEpoch
                                set(Calendar.HOUR_OF_DAY, selectedHour)
                                set(Calendar.MINUTE, selectedMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val startMillis = eventStartCal.timeInMillis
                            val endMillis = startMillis + (selectedDurationMinutes * 60 * 1000L)

                            if (editingEvent != null) {
                                onUpdateEvent(
                                    editingEvent.id,
                                    eventTitle.trim(),
                                    startMillis,
                                    endMillis,
                                    eventLocation.trim().ifEmpty { null },
                                    eventType
                                )
                                eventPendingEdit = null
                            } else {
                                onAddEvent(
                                    eventTitle.trim(),
                                    startMillis,
                                    endMillis,
                                    eventLocation.trim().ifEmpty { null },
                                    eventType
                                )
                                showAddEventDialog = false
                                eventPendingInsert = null
                            }
                        }
                    },
                    enabled = eventTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NudgyPrimary)
                ) {
                    Text(if (editingEvent != null) "Save" else "Add", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEventDialog = false; eventPendingEdit = null; eventPendingInsert = null }) {
                    Text("Cancel", color = NudgyCharcoal)
                }
            }
        )

        if (showTimePicker) {
            val timePickerState = rememberTimePickerState(
                initialHour = selectedHour,
                initialMinute = selectedMinute,
                is24Hour = false
            )
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text("Pick start time", fontWeight = FontWeight.Bold, color = NudgyCharcoal) },
                text = { TimePicker(state = timePickerState) },
                confirmButton = {
                    TextButton(onClick = {
                        selectedHour = timePickerState.hour
                        selectedMinute = timePickerState.minute
                        showTimePicker = false
                    }) { Text("OK", color = NudgyAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = NudgyCharcoal) }
                }
            )
        }
    }
}
