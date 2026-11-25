package com.nextgentool.nudgy.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Animated launch splash, shown after the system splash is dismissed.
 * Cream background (always light, matching the launcher icon), the big
 * brand logo mark (NudgyLogoMark at 220x96 — ~4x the 55x24 top-bar
 * mark) scaling in at the centre. Takes ~950ms, then [onFinished]
 * hands over to the main flow.
 *
 * Step 5a-wire (polish, Tanvir's decision) — the bear is GONE: no
 * mascot on the splash, only the logo mark, big and centred. The
 * "NUDGY" text is NOT back. The entrance is one stage now (the old
 * two-stage bear-then-wordmark shape collapsed with the bear): the
 * mark scales/fades in over 450ms, holds, then onFinished fires at
 * ~950ms total. Wired into the launch flow by MainActivity
 * (once per cold start — see shouldShowSplash).
 */
@Composable
fun NudgySplashScreen(
    onFinished: () -> Unit
) {
    var started by remember { mutableStateOf(false) }
    val logoScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.85f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 400)
    )
    LaunchedEffect(Unit) {
        started = true
        delay(450)
        delay(500)
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4EFE4))
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        NudgyLogoMark(
            width = 220.dp,
            height = 96.dp,
            modifier = Modifier
                .scale(logoScale)
                .alpha(logoAlpha)
        )
    }
}
