package com.mooncc.teamspeak9.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = TsBlue,
    onPrimary = Color.White,
    primaryContainer = TsBlueDark,
    onPrimaryContainer = Color.White,
    secondary = TsBlueLight,
    onSecondary = Color(0xFF06122A),
    background = TsBackground,
    onBackground = TsOnSurface,
    surface = TsSurface,
    onSurface = TsOnSurface,
    surfaceVariant = TsSurfaceVariant,
    onSurfaceVariant = TsOnSurfaceVariant,
    surfaceContainerHighest = TsSurfaceHigh,
    outline = TsOutline,
    outlineVariant = TsOutlineStrong,
    error = TsError,
    onError = Color.White,
)

@Composable
fun TeamSpeakTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = DarkColors,
        typography = TeamSpeakTypography,
        content = content,
    )
}
