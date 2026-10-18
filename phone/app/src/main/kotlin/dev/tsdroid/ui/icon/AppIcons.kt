package dev.tsdroid.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's own icon set: 24x24 viewport, 2-unit round-cap outlines, transcribed from the SVG
 * sources in the design hand-off.
 *
 * They stand in for the stock Material icons wherever the app means one of these concepts, so the
 * chrome reads as a single consistent set instead of two mixed families. Every vector is
 * monochrome, so `Icon(tint = ...)` recolours it through a ColorFilter exactly like a Material
 * icon does.
 */
object AppIcons {

    /** 主页 */
    val Home: ImageVector by lazy {
        icon(
            name = "AppIcons.Home",
            strokes = listOf("M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1v-9.5z"),
        )
    }

    /** 设置 */
    val Settings: ImageVector by lazy {
        icon(
            name = "AppIcons.Settings",
            strokes = listOf(
                "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
                "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z",
            ),
        )
    }

    /** 频道 / 天线 */
    val Channel: ImageVector by lazy {
        icon(
            name = "AppIcons.Channel",
            strokes = listOf(
                "M4.9 19.1C1 15.2 1 8.8 4.9 4.9",
                "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5",
                "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5",
                "M19.1 4.9c3.9 3.9 3.9 10.3 0 14.2",
            ),
            fills = listOf("M9.5 12a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0z"),
        )
    }

    /** 麦克风（讲话中） */
    val MicOn: ImageVector by lazy {
        icon(
            name = "AppIcons.MicOn",
            strokes = listOf(
                // Capsule body: the SVG's <rect x=9 y=2 w=6 h=11 rx=3>, i.e. r = w/2.
                "M12 2a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z",
                "M19 10v1a7 7 0 0 1-14 0v-1",
                "M12 18v4",
                "M8 22h8",
            ),
        )
    }

    /** 麦克风关闭 */
    val MicOff: ImageVector by lazy {
        icon(
            name = "AppIcons.MicOff",
            strokes = listOf(
                "M12 2a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z",
                "M19 10v1a7 7 0 0 1-14 0v-1",
                "M12 18v4",
                "M8 22h8",
                "M2 2l20 20",
            ),
        )
    }

    /** 扬声器（正在输出） */
    val SpeakerOn: ImageVector by lazy {
        icon(
            name = "AppIcons.SpeakerOn",
            strokes = listOf(
                "M11 5L6 9H2v6h4l5 4V5z",
                "M15.54 8.46a5 5 0 0 1 0 7.07",
                "M19.07 4.93a10 10 0 0 1 0 14.14",
            ),
        )
    }

    /** 扬声器关闭 */
    val SpeakerOff: ImageVector by lazy {
        icon(
            name = "AppIcons.SpeakerOff",
            strokes = listOf(
                "M11 5L6 9H2v6h4l5 4V5z",
                "M22 9l-6 6",
                "M16 9l6 6",
            ),
        )
    }

    /** 短信 / 消息气泡 */
    val Sms: ImageVector by lazy {
        icon(
            name = "AppIcons.Sms",
            strokes = listOf(
                "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z",
            ),
            fills = listOf(
                "M7.75 11.5a0.75 0.75 0 1 0 1.5 0a0.75 0.75 0 1 0-1.5 0z",
                "M11.25 11.5a0.75 0.75 0 1 0 1.5 0a0.75 0.75 0 1 0-1.5 0z",
                "M14.75 11.5a0.75 0.75 0 1 0 1.5 0a0.75 0.75 0 1 0-1.5 0z",
            ),
        )
    }

    /** 文件 / 文档 */
    val File: ImageVector by lazy {
        icon(
            name = "AppIcons.File",
            strokes = listOf(
                "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
                "M14 2v6h6",
                "M16 13H8",
                "M16 17H8",
                "M10 9H8",
            ),
        )
    }

    /** 退出 / 断开 */
    val Exit: ImageVector by lazy {
        icon(
            name = "AppIcons.Exit",
            strokes = listOf(
                "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
                "M16 17l5-5-5-5",
                "M21 12H9",
            ),
        )
    }

    /** 信息 / 服务器信息 */
    val Info: ImageVector by lazy {
        icon(
            name = "AppIcons.Info",
            strokes = listOf(
                // <circle cx=12 cy=12 r=10>
                "M2 12a10 10 0 1 0 20 0 10 10 0 1 0-20 0z",
                // <line x1=12 y1=16 x2=12 y2=12>
                "M12 16v-4",
            ),
            // The source draws its stem endpoint as <line x1=12 y1=8 x2=12.01 y2=8>, a
            // zero-length segment whose round cap renders as an r=1 disc. A filled-only
            // path reproduces that exactly; going through [strokes] would add the shared
            // 2-unit outline and double the dot.
            plainFills = listOf("M11 8a1 1 0 1 0 2 0a1 1 0 1 0-2 0z"),
        )
    }

    /** 服务器 / 机架 */
    val Server: ImageVector by lazy {
        icon(
            name = "AppIcons.Server",
            strokes = listOf(
                // <rect x=2 y=2 width=20 height=8 rx=2 ry=2>
                "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
                // <rect x=2 y=14 width=20 height=8 rx=2 ry=2>
                "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
            ),
            // The two status LEDs are <line x1=6 y1=6 x2=6.01 y2=6> and the same at y=18:
            // zero-length segments the round cap turns into r=1 discs, so they are filled
            // only — the shared 2-unit outline would double them.
            plainFills = listOf(
                "M5 6a1 1 0 1 0 2 0a1 1 0 1 0-2 0z",
                "M5 18a1 1 0 1 0 2 0a1 1 0 1 0-2 0z",
            ),
        )
    }
}

/**
 * Builds one icon from SVG path data: [strokes] are drawn as round-capped outlines (the SVG's
 * 2px stroke), [fills] as solid shapes that *also* keep that outline, and [plainFills] as solid
 * shapes on their own.
 *
 * The source SVGs draw their dots and the channel hub with `fill="currentColor"` inside a group
 * that sets `stroke-width="2"`, so those elements are stroked and filled at once — a filled-only
 * path would come out visibly thinner than the original. [plainFills] is for the rarer element
 * that is genuinely just a fill. Black is only a placeholder: `Icon` recolours everything through
 * its tint.
 */
private fun icon(
    name: String,
    strokes: List<String> = emptyList(),
    fills: List<String> = emptyList(),
    plainFills: List<String> = emptyList(),
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    strokes.forEach { pathData ->
        builder.addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    fills.forEach { pathData ->
        builder.addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    plainFills.forEach { pathData ->
        builder.addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }
    return builder.build()
}
