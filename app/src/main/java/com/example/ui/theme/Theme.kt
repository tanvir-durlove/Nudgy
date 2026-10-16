package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NudgyColorScheme = lightColorScheme(
    primary = NudgyPrimary,
    onPrimary = Color.White,
    primaryContainer = NudgyPeach,
    onPrimaryContainer = NudgyPrimaryDark,
    secondary = NudgyLavender,
    onSecondary = NudgyCharcoal,
    tertiary = NudgySage,
    onTertiary = NudgyCharcoal,
    background = NudgyCanvas,
    onBackground = NudgyCharcoal,
    surface = NudgyCard,
    onSurface = NudgyCharcoal,
    surfaceVariant = NudgyCanvas,
    onSurfaceVariant = NudgyMuted,
    outline = NudgyBorder,
    outlineVariant = NudgyCardBorder
)

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
                insetsController.isAppearanceLightStatusBars = true
                insetsController.isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = NudgyColorScheme,
        typography = Typography,
        content = content
    )
}
