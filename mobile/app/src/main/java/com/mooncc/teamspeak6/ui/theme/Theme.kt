package com.mooncc.teamspeak6.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    outline = TsOutline,
    error = TsError,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = TsBlueDark,
    onPrimary = Color.White,
    secondary = TsBlue,
    background = Color(0xFFF5F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6ECF5),
    error = TsError,
)

@Composable
fun TeamSpeakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TeamSpeakTypography,
        content = content,
    )
}
