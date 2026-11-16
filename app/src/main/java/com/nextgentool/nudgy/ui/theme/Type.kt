package com.nextgentool.nudgy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// All 15 M3 type roles. Colors are literal hexes on purpose: Typography objects
// are static vals, so they cannot read the theme-aware token getters in
// Color.kt (those only work inside @Composable functions). The hexes below
// mirror the light/dark palettes — keep them in sync with Color.kt.
// Light text 0xFF1C1B1F, light muted 0xFF6F6A72 (5.01:1 on canvas).
// Dark text 0xFFF5F1EA, dark muted 0xFFB8B2A8 (7.89:1 on background).
private fun nudgyTypeRoles(text: Color, muted: Color) =
    Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.5).sp,
            color = text
        ),
        displayMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.4).sp,
            color = text
        ),
        displaySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.3).sp,
            color = text
        ),
        headlineLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.2).sp,
            color = text
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.2).sp,
            color = text
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.1).sp,
            color = text
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            color = text
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = text
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = text
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.1.sp,
            color = text
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.2.sp,
            color = muted
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.2.sp,
            color = muted
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.3.sp,
            color = text
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
            color = text
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.2.sp, // Tracked all-caps overlines from Figma
            color = muted
        )
    )

val Typography = nudgyTypeRoles(
    text = Color(0xFF1C1B1F),
    muted = Color(0xFF6F6A72)
)

val TypographyDark = nudgyTypeRoles(
    text = Color(0xFFF5F1EA),
    muted = Color(0xFFB8B2A8)
)
