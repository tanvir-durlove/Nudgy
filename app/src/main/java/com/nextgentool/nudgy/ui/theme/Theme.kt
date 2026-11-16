package com.nextgentool.nudgy.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// App shape tokens (M3 slots mapped to the app's radii):
//   small 8dp, medium 14dp, large 16dp, extraLarge 28dp (sheet tops).
// Fully-rounded pills use CircleShape, not a radius token.
val NudgyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun nudgyColorScheme(darkTheme: Boolean) =
    if (darkTheme) {
        darkColorScheme(
            primary = NudgyPrimary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF3A2B2E),
            onPrimaryContainer = Color(0xFFFFB4A6),
            secondary = Color(0xFF2C2C40),
            onSecondary = Color(0xFFF5F1EA),
            tertiary = Color(0xFF22332A),
            onTertiary = Color(0xFFF5F1EA),
            background = Color(0xFF1C1E26),
            onBackground = Color(0xFFF5F1EA),
            surface = Color(0xFF262833),
            onSurface = Color(0xFFF5F1EA),
            surfaceVariant = Color(0xFF1C1E26),
            onSurfaceVariant = Color(0xFFB8B2A8),
            outline = NudgyBorder,
            outlineVariant = NudgyCardBorder
        )
    } else {
        lightColorScheme(
            primary = NudgyPrimary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFDF1F0),
            onPrimaryContainer = Color(0xFFBE3B24),
            secondary = Color(0xFFF1F0FD),
            onSecondary = Color(0xFF1C1B1F),
            tertiary = Color(0xFFEBF7F0),
            onTertiary = Color(0xFF1C1B1F),
            background = Color(0xFFFBF9F4),
            onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFFBF9F4),
            onSurfaceVariant = Color(0xFF6F6A72),
            outline = NudgyBorder,
            outlineVariant = NudgyCardBorder
        )
    }

@Composable
fun NudgyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                // Dark theme => light system bars (icons on dark), and vice versa.
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalNudgyPalette provides nudgyPaletteFor(darkTheme)) {
        MaterialTheme(
            colorScheme = nudgyColorScheme(darkTheme),
            shapes = NudgyShapes,
            typography = if (darkTheme) TypographyDark else Typography,
            content = content
        )
    }
}
