package com.nextgentool.nudgy.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nextgentool.nudgy.MainActivity
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.repository.TaskRepository
import com.nextgentool.nudgy.domain.todayDateString

/**
 * Sprint 6-5, step 3a — the Today widget (Glance): ONE widget,
 * today's planned tasks, read from Room directly, offline.
 *
 * Scaffold + agenda only (read-only display). The widget reads a
 * fresh snapshot on every [provideGlance] — no Glance state
 * definition, no update scheduling yet; that wiring (refresh +
 * quick-add) is steps 3b/3c. Tap anywhere opens the app (the plain
 * default — tap-to-task is step 3b).
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
 */

// Brand teal #0F766E (approved design token).
private val WidgetTeal = Color(0xFF0F766E)

// Near-black for task titles.
private val WidgetInk = Color(0xFF1F1F1F)

// Muted grey for secondary text + completed tasks.
private val WidgetMuted = Color(0xFF757575)

class TodayWidget : GlanceAppWidget() {

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
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.widget_today_title),
                style = TextStyle(
                    color = ColorProvider(WidgetTeal),
                    fontSize = 16.sp,
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
                    fontSize = 12.sp
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
            items.take(TODAY_WIDGET_MAX_ITEMS).forEach { item ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    if (item.timeLabel != null) {
                        Text(
                            text = item.timeLabel,
                            style = TextStyle(
                                color = ColorProvider(WidgetMuted),
                                fontSize = 12.sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                    }
                    Text(
                        text = item.title,
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
    }
}
