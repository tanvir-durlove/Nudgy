package com.nextgentool.nudgy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nextgentool.nudgy.domain.usecase.WeeklyReview
import com.nextgentool.nudgy.domain.usecase.WeeklyReviewStats
import com.nextgentool.nudgy.ui.theme.NudgyCanvas
import com.nextgentool.nudgy.ui.theme.NudgyCharcoal
import com.nextgentool.nudgy.ui.theme.NudgyMuted
import com.nextgentool.nudgy.ui.theme.NudgyTokens
import com.nextgentool.nudgy.ui.viewmodel.TodayViewModel

/**
 * Sprint 4-4 — the weekly review screen (spec §7, Q9=(a)).
 *
 * The ONE report (Feature 17 + Feature 27): the week's finished /
 * missed / slipped lists plus the numbers — completed count, focus
 * minutes this week, best current + best longest streak — all from
 * real data (completedAt, FocusSession, HabitCheckIn), never cached
 * counters. It is the SAME screen from both entries: the Today
 * progress ring and the Settings "Weekly review" row (Q10=(a)).
 *
 * The wrapper owns its own TodayViewModel (hiltViewModel, scoped to
 * this entry — the same pattern as FocusScreen owning FocusViewModel):
 * it reads the same repository flows as Today's instance, so the
 * review and the numbers are the same data, and it refreshes the
 * stats on entry so a focus session finished just before opening
 * the screen is already counted.
 *
 * The wrapper owns the ViewModel; [WeeklyReviewScreenContent] is pure
 * state + callbacks so the test renders it without Hilt (the
 * FocusScreen pattern). All text uses the theme tokens (PR #86).
 */
@Composable
fun WeeklyReviewScreen(
    onBackClick: () -> Unit = {},
    todayViewModel: TodayViewModel = hiltViewModel()
) {
    val uiState by todayViewModel.uiState.collectAsState()
    LaunchedEffect(Unit) {
        todayViewModel.refreshWeeklyStats()
    }
    WeeklyReviewScreenContent(
        review = uiState.weeklyReview,
        stats = uiState.weeklyReviewStats,
        onBackClick = onBackClick
    )
}

@Composable
fun WeeklyReviewScreenContent(
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("weekly_review_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = NudgyCharcoal
                )
            }
            Text(
                text = "Weekly review",
                style = MaterialTheme.typography.titleLarge,
                color = NudgyCharcoal
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (review == null) {
            Text(
                text = "Loading this week's review…",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted
            )
        } else {
            // The summary line: completed count + focus minutes +
            // streaks, from the same data as the card below.
            Text(
                text = "This week you finished ${review.finished.size} " +
                    if (review.finished.size == 1) "task." else "tasks.",
                style = MaterialTheme.typography.bodyMedium,
                color = NudgyMuted,
                modifier = Modifier.testTag("weekly_review_summary")
            )
            Spacer(modifier = Modifier.height(16.dp))
            WeeklyReviewCard(review = review, stats = stats)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
