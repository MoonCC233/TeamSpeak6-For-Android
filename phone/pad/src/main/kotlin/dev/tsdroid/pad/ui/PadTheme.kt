package dev.tsdroid.pad.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The built-in WebSpeak dark skin, translated to Compose. Keep these values in
 * sync with pad/web/src/skins/builtin/dark/skin.css.
 */
object PadColors {
    val Page = Color(0xFF101918)
    val Surface = Color(0xFF172321)
    val SurfaceAlt = Color(0xFF202F2C)
    val SurfaceHigh = Color(0xFF263A36)
    val Text = Color(0xFFE8F3F0)
    val Muted = Color(0xFF9BB0AA)
    val Accent = Color(0xFF69D2C7)
    val AccentSoft = Color(0x2469D2C7)
    val OnAccent = Color(0xFF0B1F1C)
    val Green = Color(0xFF78E489)
    val Amber = Color(0xFFE2B36C)
    val Red = Color(0xFFEE8A82)
    val Border = Color(0xFF30413D)
    val BorderSoft = Color(0x8030413D)
    val Hover = Color(0xFF1E2E2B)
    val ChatMine = Color(0xFF1B4B45)
    val Overlay = Color(0xCC000000)
}

private val PadTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 46.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1.3).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.0).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun PadTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = PadColors.Accent,
        onPrimary = PadColors.OnAccent,
        primaryContainer = Color(0xFF1B4B45),
        onPrimaryContainer = Color(0xFFB9F5EC),
        inversePrimary = PadColors.Accent,
        secondary = PadColors.Green,
        onSecondary = Color(0xFF07240C),
        secondaryContainer = Color(0xFF1E3A24),
        onSecondaryContainer = Color(0xFFC9F5CE),
        tertiary = PadColors.Amber,
        onTertiary = Color(0xFF261A06),
        background = PadColors.Page,
        onBackground = PadColors.Text,
        surface = PadColors.Surface,
        onSurface = PadColors.Text,
        surfaceVariant = PadColors.SurfaceAlt,
        onSurfaceVariant = PadColors.Muted,
        surfaceTint = PadColors.Accent,
        inverseSurface = PadColors.Text,
        inverseOnSurface = PadColors.Page,
        error = PadColors.Red,
        onError = Color(0xFF2A0907),
        errorContainer = Color(0xFF4A1B18),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = PadColors.Border,
        outlineVariant = Color(0xFF23332F),
        scrim = Color.Black,
        surfaceBright = PadColors.SurfaceHigh,
        surfaceDim = PadColors.Page,
        surfaceContainer = PadColors.Surface,
        surfaceContainerHigh = PadColors.SurfaceAlt,
        surfaceContainerHighest = PadColors.SurfaceHigh,
        surfaceContainerLow = PadColors.Surface,
        surfaceContainerLowest = PadColors.Page,
    )
    MaterialTheme(colorScheme = scheme, typography = PadTypography, content = content)
}
