package com.nextgentool.nudgy.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextgentool.nudgy.R
import kotlinx.coroutines.delay

/**
 * Animated launch splash, shown after the system splash is dismissed.
 * Cream background (always light, matching the launcher icon), the big bear
 * logo scaling in at the centre, then the NUDGY wordmark fading in below.
 * Takes ~950ms, then [onFinished] hands over to the main flow.
 */
@Composable
fun NudgySplashScreen(
    onFinished: () -> Unit
) {
    var started by remember { mutableStateOf(false) }
    var textVisible by remember { mutableStateOf(false) }
    val logoScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.85f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 400)
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 400)
    )
    val textOffset by animateFloatAsState(
        targetValue = if (textVisible) 0f else 16f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
    )
    LaunchedEffect(Unit) {
        started = true
        delay(450)
        textVisible = true
        delay(500)
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4EFE4)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.nudgy_bear_foreground),
                contentDescription = "Nudgy",
                modifier = Modifier
                    .size(200.dp)
                    .scale(logoScale)
                    .alpha(logoAlpha)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "NUDGY",
                color = Color(0xFFEE4A40),
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 8.sp,
                modifier = Modifier
                    .alpha(textAlpha)
                    .graphicsLayer { translationY = textOffset }
            )
        }
    }
}
