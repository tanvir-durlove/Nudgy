package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.data.local.entity.TaskEntity
import com.nextgentool.nudgy.domain.usecase.WeeklyReview
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel

/**
 * Sprint 7 — Statistics screen (spec §4, prototype scr-stats).
 *
 * The data already existed (WeeklyReview + WeeklyReviewStats, the same
 * flows Today and the Weekly Review screen read); only this screen
 * wrapper + the nav route are new. The component data is untouched
 * (design-only apart from the wrapper + route).
 *
 * Sections, top to bottom (prototype scr-stats): backbar "Statistics";
 * 4 statboxes; "Completed per day" bar-chart card; "Weekly review"
 * card; "Streaks" card.
 *
 * Entry point — NONE yet, deliberately. The prototype sends Today's
 * "See full statistics" button to Statistics, but in the app that
 * button shares ONE callback ([onWeeklyReviewClick]) with the Today
 * progress ring and the Settings "Weekly review" row, and it opens
 * the Weekly Review screen — a real screen (S4-4, Q9=(a): the ONE
 * report), not a sheet or a placeholder. Rewiring the shared callback
 * would move all three entries, a behaviour change to an established
 * model — a product decision, not a design-only micro-task (the
 * brief's check-first rule says rewire only if it is a
 * sheet/placeholder). The natural entries for later: the prototype's
 * Settings "Statistics" row (Settings micro-task) or an S7-2b tile.
 *
 * Statboxes — Today's This-week numbers, per the brief (done /
 * missed / carried over / completion rate, the same arithmetic as
 * TodayScreen: finished over finished + missed). The prototype's
 * statgrid instead showed "Best streak · Reading" and "Focus minutes
 * this week" — those values are NOT lost: they live in the Weekly
 * review card (focus minutes, best streaks) and the Streaks card
 * below.
 *
 * Bar chart — built from existing data only: [completedPerDay] groups
 * the review's finished tasks by weekday from completedAtEpochMillis
 * (a derived view, no new data source). Bar heights follow the
 * prototype's proportions: count / max × 78% of the chart, a
 * zero-count day renders as an 8% dim bar labelled "–".
 *
 * Weekly review card — [WeeklyReviewCard] reused as-is (the same card
 * the Weekly Review screen shows), so the two screens can never
 * disagree.
 *
 * The wrapper owns the ViewModel; [StatsScreenContent] is pure state
 * so the screen test renders it without Hilt or Room (Focus pattern).
 */
@Composable
fun StatsScreen(
    onBackClick: () -> Unit = {},
    todayViewModel: TodayViewModel = hiltViewModel()
) {
    val uiState by todayViewModel.uiState.collectAsState()
    LaunchedEffect(Unit) {
        todayViewModel.refreshWeeklyStats()
    }
    StatsScreenContent(
        review = uiState.weeklyReview,
        stats = uiState.weeklyReviewStats,
        onBackClick = onBackClick
    )
}

@Composable
fun StatsScreenContent(
    review: WeeklyReview?,
    stats: WeeklyReviewStats?,
    onBackClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NudgyCanvas)
            .verticalScroll(scrollState)
            .padding(NudgyTokens.screenPadding)
    ) {
        // Backbar (prototype): the same back Row MatrixScreen uses
        // (back + titleLarge, no TopAppBar).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("stats_backbar")
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
        }
        if (review == null) {
            Text(
                text = "Loading this week's statistics…",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            // 4 statboxes — Today's This-week numbers (see KDoc).
            val total = review.finished.size + review.missed.size
            val rate = if (total == 0) 0 else review.finished.size * 100 / total
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsBox(
                        value = "${review.finished.size}",
                        label = "Done this week",
                        testTag = "stats_done",
                        modifier = Modifier.weight(1f)
                    )
                    StatsBox(
                        value = "${review.missed.size}",
                        label = "Missed",
                        testTag = "stats_missed",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsBox(
                        value = "${review.slipped.size}",
                        label = "Carried over",
                        testTag = "stats_carried",
                        modifier = Modifier.weight(1f)
                    )
                    StatsBox(
                        value = "$rate%",
                        label = "Completion rate",
                        testTag = "stats_rate",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            CompletedPerDayCard(review = review)
            Spacer(modifier = Modifier.height(16.dp))
            Column(modifier = Modifier.testTag("stats_weekly_review")) {
                Text(
                    text = "Weekly review",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyCharcoal
                )
                Spacer(modifier = Modifier.height(8.dp))
                WeeklyReviewCard(review = review, stats = stats)
            }
            Spacer(modifier = Modifier.height(16.dp))
            StreaksCard(stats = stats)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One prototype .statbox: mirrors TodayScreen's private StatBox. */
@Composable
private fun StatsBox(
    value: String,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag(testTag),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = NudgyCharcoal,
                modifier = Modifier.testTag("${testTag}_value")
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = NudgyMuted
            )
        }
    }
}

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Pure: completed-per-day counts (Mon = 0 .. Sun = 6) from the week's
 * finished tasks, grouped by completedAtEpochMillis against
 * [weekStartMillis] (Monday 00:00 local). Tasks with no completion
 * time, or completed outside this week, are ignored — the finished
 * list should already be this week's, so this is a guard, not a
 * filter the UI depends on.
 */
internal fun completedPerDay(finished: List<TaskEntity>, weekStartMillis: Long): List<Int> {
    val counts = MutableList(7) { 0 }
    for (task in finished) {
        val at = task.completedAtEpochMillis ?: continue
        val day = ((at - weekStartMillis) / DAY_MILLIS).toInt()
        if (day in 0..6) counts[day]++
    }
    return counts
}

/** "Completed per day" bar-chart card (prototype scr-stats). */
@Composable
private fun CompletedPerDayCard(review: WeeklyReview) {
    val counts = completedPerDay(review.finished, review.weekStartMillis)
    val max = counts.maxOrNull() ?: 0
    val chartHeight = 120.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats_bar_chart"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Completed per day",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = NudgyCharcoal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = review.weekLabel,
                    fontSize = 12.sp,
                    color = NudgyMuted
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                counts.forEachIndexed { index, count ->
                    // Prototype proportions: count / max × 78% of the
                    // chart; a zero day is an 8% dim bar labelled "–".
                    val barHeight = if (max == 0 || count == 0) {
                        chartHeight * 0.08f
                    } else {
                        chartHeight * 0.78f * count / max
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stats_bar_${DAY_LABELS[index]}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barHeight)
                                .background(
                                    color = if (count == 0) {
                                        colorResource(R.color.nudgy_track)
                                    } else {
                                        colorResource(R.color.nudgy_teal)
                                    },
                                    shape = RoundedCornerShape(
                                        topStart = 4.dp,
                                        topEnd = 4.dp
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (count == 0) {
                                "${DAY_LABELS[index]} · –"
                            } else {
                                "${DAY_LABELS[index]} · $count"
                            },
                            fontSize = 11.sp,
                            color = NudgyMuted
                        )
                    }
                }
            }
        }
    }
}

/** "Streaks" card (prototype scr-stats): one kv row per habit. */
@Composable
private fun StreaksCard(stats: WeeklyReviewStats?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats_streaks"),
        shape = NudgyTokens.cardRadius,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = NudgyTokens.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Streaks",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = NudgyCharcoal
            )
            if (stats == null || stats.habits.isEmpty()) {
                Text(
                    text = "No habits yet — streaks start with your first habit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NudgyMuted,
                    modifier = Modifier.testTag("stats_no_habits")
                )
            } else {
                stats.habits.forEach { habitStreak ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stats_streak_${habitStreak.habit.id}")
                    ) {
                        Text(
                            text = habitStreak.habit.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyCharcoal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = streakDaysLabel(habitStreak.currentStreak),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NudgyMuted
                        )
                    }
                }
            }
        }
    }
}

/** "3 days" / "1 day" / "0 days" — the streak value, plainly. */
private fun streakDaysLabel(days: Int): String =
    if (days == 1) "1 day" else "$days days"
