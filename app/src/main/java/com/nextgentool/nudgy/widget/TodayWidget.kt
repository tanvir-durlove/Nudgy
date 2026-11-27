package com.nextgentool.nudgy.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.DpSize
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.model.Priority
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString

/**
 * Sprint 6-5, step 3a — the Today widget (Glance): ONE widget,
 * today's planned tasks, read from Room directly, offline.
 *
 * Scaffold + agenda only (read-only display). The widget reads a
 * fresh snapshot on every [provideGlance] — no Glance state
 * definition, no update scheduling yet; that wiring (refresh) is
 * step 3c. Tap anywhere opens the app (the plain default); the "+"
 * row opens the app's quick-add directly (step 3b) and tapping an
 * agenda row opens that task's detail sheet (step 3d).
 *
 * Brand tokens from the approved design: teal #0F766E, coral
 * #F97360, on a white card. Completed tasks render struck through
 * and muted, the same convention as the Today timeline.
 *
 * Units: Glance has no `dp`/`sp` of its own (`androidx.glance.unit`
 * holds only `ColorProvider`) — its layout and text APIs take the
 * Compose UI units, so `dp`/`sp` come from
 * `androidx.compose.ui.unit`. Do not "fix" these imports back to
 * `androidx.glance.unit` — that does not compile (proven by the
 * first PR run of step 3a).
 *
 * What is NOT covered by unit tests (honest limitation): the
 * Glance rendering itself — this harness cannot render a Glance
 * widget (no Glance test infra in the repo), so the render path is
 * verified by CI compile + the batch-end device test. What IS
 * tested is the agenda rule ([todayAgendaItems] — see
 * TodayWidgetAgendaTest) plus the Room round-trip behind it.
 *
 * Sprint 7 — Widget restyle (prototype scr-widget): the header is
 * the logo mark ([R.drawable.ic_launcher_foreground] — the cascade
 * bars, the same asset as the launcher icon) + "Today · Mon Nov 16"
 * (see [widgetHeaderDateLabel]) + the "N of M done" count, bold;
 * agenda rows are the prototype's single line (pdot + "10:30 AM ·
 * Buy groceries", see [widgetAgendaRowLine]), 3 rows max (see
 * [TODAY_WIDGET_MAX_ITEMS]); the "+" row is a canvas-filled pill
 * carrying "+" + the prototype's "Quick add for today…" prompt.
 *
 * Disclosed deviations (accepted, design-only): Glance cannot host
 * a text field, so the prototype's pill input + teal "Add" button
 * are NOT built — the whole pill is the tap target that opens
 * quick-add (§5 Q7); there is no separate "Add" button because the
 * widget has nothing to submit. The header ADAPTS to the width
 * ([widgetShowsDateHeader]): the full prototype line (logo + "Today
 * · Mon Nov 16" + count) shows at 300dp and above; the default
 * 2-cell width (~180dp) cannot fit logo + date + count (~235dp),
 * so narrow shows logo + "Today" + the count — the date is dropped
 * there, the count (live widget data) is kept. The provider now
 * allows horizontal resize so the 4-cell Medium width is actually
 * reachable (was vertical-only); the default placement stays 2×2.
 * ONE provider only: the prototype's distinct Small 2×2 layout
 * ("Next task + progress ring") is NOT built — that is a second
 * widget provider, a new feature, not a restyle. The prototype's
 * sizes card says "Agenda opens Today; + opens Chat" — that line
 * is NOT followed: the established taps stay (header opens Today,
 * a row opens that task, "+" opens quick-add). Taps, refresh and
 * the agenda rule are untouched.
 */

// Brand teal #0F766E (approved design token).
private val WidgetTeal = Color(0xFF0F766E)

// Brand coral #F97360 (approved design token).
private val WidgetCoral = Color(0xFFF97360)

// Near-black for task titles.
private val WidgetInk = Color(0xFF1F1F1F)

// Muted grey for secondary text + completed tasks.
private val WidgetMuted = Color(0xFF757575)

// Very light grey (canvas #F4F6F5) — the quick-add pill's fill.
private val WidgetCanvas = Color(0xFFF4F6F5)

/**
 * Sprint 7 — the agenda-row pdot color (prototype .pdot): HIGH coral
 * / MEDIUM teal / LOW muted — the same Eisenhower/Board mapping the
 * Search rows use. Plain (no Glance rendering), so it is unit-testable.
 */
fun widgetPriorityDotColor(priority: Priority): Color = when (priority) {
    Priority.HIGH -> WidgetCoral
    Priority.MEDIUM -> WidgetTeal
    Priority.LOW -> WidgetMuted
}

/**
 * Sprint 6-5 (step 3b) — the widget "+" quick-add flag, as a Glance
 * action-parameters key. The key's NAME is MainActivity's intent
 * extra ([MainActivity.EXTRA_OPEN_QUICK_ADD]): Glance adds action
 * parameters to the launched activity's intent keyed by the parameter
 * key name string, so the "+" action and MainActivity agree on the
 * name by construction, not by a duplicated string.
 */
val OpenQuickAddKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_QUICK_ADD)

/**
 * Sprint 6-5 (step 3b) — the parameters the widget "+" attaches to
 * its launch action: a single true flag under [OpenQuickAddKey].
 * Pure (no Context, no Glance rendering), so it is unit-testable —
 * see WidgetQuickAddTest.
 */
fun quickAddActionParameters(): ActionParameters =
    actionParametersOf(OpenQuickAddKey to true)

/**
 * Sprint 6-5 (step 3d) — the widget agenda-row tap, as a Glance
 * action-parameters key. The key's NAME is MainActivity's intent
 * extra ([MainActivity.EXTRA_OPEN_TASK_ID]): Glance adds action
 * parameters to the launched activity's intent keyed by the parameter
 * key name string, so the row action and MainActivity agree on the
 * name by construction, not by a duplicated string — the same
 * pattern as [OpenQuickAddKey] above.
 */
val OpenTaskKey = ActionParameters.Key<String>(MainActivity.EXTRA_OPEN_TASK_ID)

/**
 * Sprint 6-5 (step 3d) — the parameters an agenda row attaches to
 * its launch action: the tapped task's id under [OpenTaskKey].
 * Pure (no Context, no Glance rendering), so it is unit-testable —
 * see WidgetTapToTaskTest.
 */
fun openTaskActionParameters(taskId: String): ActionParameters =
    actionParametersOf(OpenTaskKey to taskId)

class TodayWidget : GlanceAppWidget() {

    // Sprint 7 — responsive sizes: the header adapts to the width
    // (see [widgetShowsDateHeader]) — the narrow 2-cell layout and
    // the wide 4-cell Medium layout. The provider allows horizontal
    // resize so the Medium width is actually reachable.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 110.dp), DpSize(360.dp, 110.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Same construction as MorningBriefWorker (the other
        // background entry point that reads tasks): the repository
        // resolves the active user (guest when signed out) and the
        // Room singleton behind it.
        val repository = TaskRepository(context)
        val userId = repository.currentUserId()
        val items = todayAgendaItems(
            tasks = repository.getAllTasksOnce(userId),
            todayString = todayDateString()
        )
        provideContent { TodayWidgetContent(items) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
private fun TodayWidgetContent(items: List<TodayWidgetItem>) {
    val context = LocalContext.current
    val done = items.count { it.isCompleted }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color.White))
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        // Sprint 7 — prototype header: logo mark + "Today · Mon Nov
        // 16" (15sp bold ink) + the "N of M done" count (12sp bold
        // muted) on the right — the full line only on a wide widget
        // (see [widgetShowsDateHeader]): the default 2-cell width
        // cannot fit logo + date + count, so narrow shows logo +
        // "Today" + the count.
        val showDate = widgetShowsDateHeader(LocalSize.current.width)
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size(26.dp)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = widgetHeaderTitle(
                    context.getString(R.string.widget_today_title),
                    widgetHeaderDateLabel(System.currentTimeMillis()),
                    showDate
                ),
                style = TextStyle(
                    color = ColorProvider(WidgetInk),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = context.getString(
                    R.string.widget_today_done_count,
                    done,
                    items.size
                ),
                style = TextStyle(
                    color = ColorProvider(WidgetMuted),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        if (items.isEmpty()) {
            Text(
                text = context.getString(R.string.widget_today_empty),
                modifier = GlanceModifier.padding(top = 12.dp),
                style = TextStyle(
                    color = ColorProvider(WidgetMuted),
                    fontSize = 14.sp
                )
            )
        } else {
            // Sprint 7 — prototype rows: 12dp below the header, 9dp
            // gaps; each row is the pdot + the single "10:30 AM ·
            // Buy groceries" line (14sp, struck through + muted when
            // done — the established completed convention, now over
            // the whole line).
            Column(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                items.take(TODAY_WIDGET_MAX_ITEMS).forEachIndexed { index, item ->
                    // Sprint 6-5 (step 3d) — tap-to-task: a tap on this
                    // row opens the app on that task's detail sheet, via
                    // the launch action's parameters (see
                    // [openTaskActionParameters]) and the same 2e
                    // deep-link path the search tap uses. This clickable
                    // nests inside the column's own open-the-app
                    // clickable — the innermost clickable wins for its
                    // bounds, so a tap here takes the task path and a tap
                    // anywhere else takes the plain path. The nested-click
                    // routing is NOT provable on this harness (no Glance
                    // test infra) — the batch-end device test covers it.
                    Row(
                        modifier = GlanceModifier.fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else 9.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    openTaskActionParameters(item.id)
                                )
                            ),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .size(9.dp)
                                .background(
                                    ColorProvider(widgetPriorityDotColor(item.priority))
                                )
                                .cornerRadius(4.5.dp)
                        )
                        Spacer(modifier = GlanceModifier.width(9.dp))
                        Text(
                            text = widgetAgendaRowLine(item),
                            maxLines = 1,
                            style = TextStyle(
                                color = ColorProvider(
                                    if (item.isCompleted) WidgetMuted else WidgetInk
                                ),
                                fontSize = 14.sp,
                                textDecoration = if (item.isCompleted) {
                                    TextDecoration.LineThrough
                                } else {
                                    null
                                }
                            )
                        )
                    }
                }
            }
            if (items.size > TODAY_WIDGET_MAX_ITEMS) {
                Text(
                    text = context.getString(
                        R.string.widget_today_more,
                        items.size - TODAY_WIDGET_MAX_ITEMS
                    ),
                    modifier = GlanceModifier.padding(top = 8.dp),
                    style = TextStyle(
                        color = ColorProvider(WidgetMuted),
                        fontSize = 12.sp
                    )
                )
            }
        }
        // Sprint 6-5 (step 3b) — the "+" quick-add: opens the app on
        // the Today screen with the quick-add field focused (keyboard
        // up), via the launch action's parameters (see
        // [quickAddActionParameters]). This clickable nests inside
        // the column's own open-the-app clickable — the innermost
        // clickable wins for its bounds, so a tap here takes the
        // quick-add path and a tap anywhere else takes the plain
        // path. The nested-click routing is NOT provable on this
        // harness (no Glance test infra) — the batch-end device test
        // covers it.
        // Sprint 7 — the "+" row is the prototype's field as far as
        // Glance allows: a canvas-filled pill ("+" + the prototype's
        // "Quick add for today…" prompt). The whole pill is the tap
        // target; there is no separate "Add" button because the
        // widget has nothing to submit (see the file KDoc).
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 14.dp)
                .background(ColorProvider(WidgetCanvas))
                .cornerRadius(999.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>(quickAddActionParameters())),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = "+",
                style = TextStyle(
                    color = ColorProvider(WidgetTeal),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = context.getString(R.string.widget_today_quick_add_hint),
                style = TextStyle(
                    color = ColorProvider(WidgetMuted),
                    fontSize = 14.sp
                )
            )
        }
    }
}
