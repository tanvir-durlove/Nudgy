package com.nextgentool.nudgy.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager

/**
 * Sprint 6-5 (step 3c) — the widget refresh seam: after a task
 * mutation, the Today widget must re-render (its [TodayWidget]
 * reads a fresh Room snapshot on every provideGlance, but nothing
 * asks Glance to re-provide on its own — updatePeriodMillis is 0
 * on purpose, from 3a).
 *
 * The seam is a SAM interface so tests can substitute a recording
 * fake without touching Glance (there is no Glance test infra in
 * this repo, and a real Glance re-provide inside a repository test
 * would depend on the Robolectric AppWidgetManager shadow). Production
 * gets the real implementation from the Hilt binding
 * (TaskModule.provideWidgetRefresher); manual constructions
 * (ReminderReceiver, tests) get [NoOp] by default.
 *
 * What this covers, instantly: every [com.nextgentool.nudgy.data.repository.TaskRepository]
 * task mutation — create / update / toggle / setStatus / board move /
 * delete / snooze / clear-completed (each calls notifyTasksChanged
 * at the end of its write; createTasksFromTemplate and the subtask
 * helpers funnel through the same functions, so they are covered
 * too). What it does NOT cover instantly: direct DAO writes that
 * bypass TaskRepository — GroupRepository's group creates/sync
 * writes and ReminderReceiver's direct setTaskCompletion. Those
 * refresh on the next app open (MainActivity.onResume), not the
 * moment they land; that lag is accepted for this step, not hidden.
 *
 * A refresh must never fail the mutation that triggered it, so the
 * system implementation swallows Glance failures (logged) instead
 * of propagating them.
 */
fun interface WidgetRefresher {
    suspend fun refresh()

    companion object {
        /** Production: re-provide the widget for every placed instance. */
        fun system(context: Context): WidgetRefresher = WidgetRefresher {
            refreshTodayWidget(context)
        }

        /** Tests / manual constructions: refresh is a no-op. */
        val NoOp: WidgetRefresher = WidgetRefresher {}
    }
}

/**
 * Re-provide the Today widget for all placed instances: look up the
 * placed ids through [GlanceAppWidgetManager] and update each one
 * (Glance has no updateAll — the per-id update IS the API; the
 * first PR run of step 3c proved updateAll does not resolve).
 * When no widget is placed the id list is empty, so this is a
 * cheap no-op. Never throws — see [WidgetRefresher].
 */
suspend fun refreshTodayWidget(context: Context) {
    try {
        val manager = GlanceAppWidgetManager(context)
        val widget = TodayWidget()
        manager.getGlanceIds(TodayWidget::class.java).forEach { glanceId ->
            widget.update(context, glanceId)
        }
    } catch (e: Exception) {
        Log.w("WidgetRefresher", "Today widget refresh failed: ${e.message}")
    }
}
