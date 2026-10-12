package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MascotCheek
import com.example.ui.theme.MascotCoral
import com.example.ui.theme.MascotDark
import com.example.ui.theme.MascotSnout

enum class MascotMood {
    IDLE,
    LISTENING,
    THINKING,
    TALKING,
    CELEBRATING
}

/**
 * Procedural Vector Nudgy Mascot matching the Figma design:
 * Cute round coral cat/bear character with rounded ears, rosy cheeks, and expressive smile.
 */
@Composable
fun NudgyMascot(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    mood: MascotMood = MascotMood.IDLE,
    showRosyCheeks: Boolean = true
) {
    val bounceY = remember { Animatable(0f) }
    val earWiggle = remember { Animatable(0f) }

    LaunchedEffect(mood) {
        when (mood) {
            MascotMood.TALKING -> {
                bounceY.animateTo(
                    targetValue = -3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(220, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            MascotMood.LISTENING -> {
                earWiggle.animateTo(
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(350, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            MascotMood.CELEBRATING -> {
                bounceY.animateTo(
                    targetValue = -8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(180, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            else -> {
                bounceY.snapTo(0f)
                earWiggle.snapTo(0f)
            }
        }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasW = this.size.width
            val canvasH = this.size.height
            val centerX = canvasW / 2f
            val centerY = (canvasH / 2f) + bounceY.value

            // 1. Ears
            val earRadius = canvasW * 0.16f
            val earOffsetY = centerY - canvasH * 0.32f
            val leftEarX = centerX - canvasW * 0.30f
            val rightEarX = centerX + canvasW * 0.30f

            // Left ear (Coral outer + lighter inner)
            drawCircle(
                color = MascotCoral,
                radius = earRadius,
                center = Offset(leftEarX, earOffsetY - earWiggle.value)
            )
            drawCircle(
                color = MascotSnout,
                radius = earRadius * 0.55f,
                center = Offset(leftEarX, earOffsetY - earWiggle.value)
            )

            // Right ear
            drawCircle(
                color = MascotCoral,
                radius = earRadius,
                center = Offset(rightEarX, earOffsetY + earWiggle.value)
            )
            drawCircle(
                color = MascotSnout,
                radius = earRadius * 0.55f,
                center = Offset(rightEarX, earOffsetY + earWiggle.value)
            )

            // 2. Main Head Circle
            val headRadius = canvasW * 0.42f
            drawCircle(
                color = MascotCoral,
                radius = headRadius,
                center = Offset(centerX, centerY)
            )

            // 3. Rosy Cheeks
            if (showRosyCheeks) {
                val cheekRadius = canvasW * 0.09f
                val cheekY = centerY + canvasH * 0.08f
                drawCircle(
                    color = MascotCheek.copy(alpha = 0.85f),
                    radius = cheekRadius,
                    center = Offset(centerX - canvasW * 0.25f, cheekY)
                )
                drawCircle(
                    color = MascotCheek.copy(alpha = 0.85f),
                    radius = cheekRadius,
                    center = Offset(centerX + canvasW * 0.25f, cheekY)
                )
            }

            // 4. Snout / Muzzle Area
            val snoutRadius = canvasW * 0.15f
            drawCircle(
                color = MascotSnout.copy(alpha = 0.7f),
                radius = snoutRadius,
                center = Offset(centerX, centerY + canvasH * 0.06f)
            )

            // 5. Eyes
            val eyeY = centerY - canvasH * 0.04f
            val eyeSpacing = canvasW * 0.16f
            val eyeRadius = canvasW * 0.045f

            if (mood == MascotMood.THINKING) {
                // Curved blinking eyes
                val eyeStroke = Stroke(width = canvasW * 0.035f, cap = StrokeCap.Round)
                drawArc(
                    color = MascotDark,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(centerX - eyeSpacing - eyeRadius, eyeY - eyeRadius),
                    size = Size(eyeRadius * 2, eyeRadius * 2),
                    style = eyeStroke
                )
                drawArc(
                    color = MascotDark,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(centerX + eyeSpacing - eyeRadius, eyeY - eyeRadius),
                    size = Size(eyeRadius * 2, eyeRadius * 2),
                    style = eyeStroke
                )
            } else {
                // Friendly round eyes with glint
                drawCircle(
                    color = MascotDark,
                    radius = eyeRadius,
                    center = Offset(centerX - eyeSpacing, eyeY)
                )
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius * 0.35f,
                    center = Offset(centerX - eyeSpacing - eyeRadius * 0.25f, eyeY - eyeRadius * 0.25f)
                )

                drawCircle(
                    color = MascotDark,
                    radius = eyeRadius,
                    center = Offset(centerX + eyeSpacing, eyeY)
                )
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius * 0.35f,
                    center = Offset(centerX + eyeSpacing - eyeRadius * 0.25f, eyeY - eyeRadius * 0.25f)
                )
            }

            // 6. Cute Nose & Smile
            val noseY = centerY + canvasH * 0.02f
            drawCircle(
                color = MascotDark,
                radius = canvasW * 0.038f,
                center = Offset(centerX, noseY)
            )

            // Curved smiling mouth
            val smilePath = Path().apply {
                val mouthStartY = noseY + canvasW * 0.035f
                moveTo(centerX - canvasW * 0.08f, mouthStartY)
                quadraticTo(
                    centerX, mouthStartY + canvasW * 0.07f,
                    centerX + canvasW * 0.08f, mouthStartY
                )
            }
            drawPath(
                path = smilePath,
                color = MascotDark,
                style = Stroke(width = canvasW * 0.032f, cap = StrokeCap.Round)
            )
        }
    }
}
