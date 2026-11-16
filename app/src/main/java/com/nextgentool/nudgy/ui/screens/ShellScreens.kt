package com.nextgentool.nudgy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.nextgentool.nudgy.R
import com.nextgentool.nudgy.ui.theme.NudgyTokens

/**
 * Sprint 0 — shell screens for the scratch rebuild (v2).
 *
 * Placeholders only: each destination shows its title + skeleton
 * loading rows. Sprint 1+ fills each shell with the real screen.
 * Content must arrive in steps with skeleton/shimmer covering the
 * load — never pop in all at once (the feel rule).
 */

@Composable
private fun ShellScaffold(title: String, note: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nudgy_background))
            .padding(NudgyTokens.screenPadding),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = title, color = colorResource(R.color.nudgy_ink))
        Spacer(modifier = Modifier.height(16.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(12.dp))
        NudgySkeletonRow()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = note, color = colorResource(R.color.nudgy_muted))
    }
}

/** Shimmer placeholder: a pulsing rounded box (skeleton loading). */
@Composable
fun NudgySkeletonRow() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .alpha(alpha)
            .background(
                color = colorResource(R.color.nudgy_cream),
                shape = NudgyTokens.cardRadius
            )
    )
}

@Composable
fun TodayShell() = ShellScaffold(
    title = "Today",
    note = "Sprint 1 fills this in: due tasks, meetings, carry-over — one timeline."
)

@Composable
fun CalendarShell() = ShellScaffold(
    title = "Calendar",
    note = "Sprint 2 fills this in: Google / Outlook sync, time blocking, drag to slot."
)

@Composable
fun ChatShell() = ShellScaffold(
    title = "Chat",
    note = "Sprint 3 fills this in: Quick Chat sheet expanding to full chat. Confirmation card before anything is saved; ask instead of guessing."
)

@Composable
fun GroupsShell() = ShellScaffold(
    title = "Groups",
    note = "Sprint 5 fills this in: join code, member list, group tasks with badge, completion count, admin notice."
)

@Composable
fun SettingsShell() = ShellScaffold(
    title = "Settings",
    note = "Sprint 6 fills this in: profile, notifications, light/dark/system, language, calendar connections, own Gemini key, Privacy Policy, Help & feedback, Memories."
)
