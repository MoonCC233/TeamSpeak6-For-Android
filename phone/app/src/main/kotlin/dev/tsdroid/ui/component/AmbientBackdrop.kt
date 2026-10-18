package dev.tsdroid.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The bitmap a surface samples for its frosted backdrop.
 *
 * Returns the wallpaper that is actually painted behind the panes: the surfaces blur it once into a
 * shared frosted copy and composite a milky veil on top — the "milk glass" look. `null` when the
 * wallpaper is switched off, because there is then nothing behind the panes to soften.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberGlassBackdrop(wallpaperEnabled: Boolean): ImageBitmap? =
    if (wallpaperEnabled) AnimeWallpaperState.activeBitmap else null

/**
 * A blurred copy of the wallpaper, ready to be drawn behind a pane, together with the mean
 * luminance of what it shows — the same vibrancy signal iOS uses to pick light or dark labels.
 *
 * The copy is the wallpaper as the background itself lays it out (ContentScale.Crop into the root),
 * at [FROST_SCALE] of the size, so drawing it at the root's size puts it pixel for pixel where the
 * wallpaper is. See [bakeFrosted].
 */
internal class FrostedWallpaper(
    /** The blurred wallpaper, at [FROST_SCALE] of the root's size. */
    val image: ImageBitmap,
    /** Average luminance of the wallpaper, 0..1. */
    val meanLuma: Float,
)

/** How heavily the wallpaper behind a pane is blurred. */
private const val FROST_RADIUS_DP = 18f

/**
 * Resolution of the blurred copy, relative to the root.
 *
 * A frosted backdrop carries no fine detail, so the copy is built at half size and stretched back
 * over the wallpaper when drawn. That keeps the material to *one* small bitmap shared by every pane,
 * instead of a full-screen blur per pane per frame — which is what made switching the wallpaper, and
 * scrolling in general, expensive. It also fixes the blur's visual strength in dp: the copy is always
 * drawn at exactly 2x, whatever the wallpaper's own resolution is.
 */
private const val FROST_SCALE = 2

/** Two box passes are enough to turn the wallpaper into a smooth frosted field. */
private const val FROST_PASSES = 2

/**
 * The blurred copy of the wallpaper that is currently in use.
 *
 * Deliberately one slot keyed by the source bitmap and the root it is laid out in: a wallpaper is
 * decoded once and every pane shares it, so the blur is built on the first pane that asks and reused
 * by all the others — and it survives panes and screens coming and going. Switching the wallpaper
 * off does not throw the entry away, so switching it back on is free.
 */
private object FrostedWallpaperCache {

    private val bakeLock = Mutex()
    private var source: ImageBitmap? = null
    private var rootSize = IntSize.Zero
    private var value: FrostedWallpaper? = null
    private var known = false

    /** The already-built copy for [source], or null when it has not been built yet. */
    @Synchronized
    fun peek(source: ImageBitmap, rootSize: IntSize): FrostedWallpaper? =
        if (known && this.source === source && this.rootSize == rootSize) value else null

    /** The blurred copy for [source], built now if this is the first pane to ask for it. */
    suspend fun of(
        source: ImageBitmap,
        rootSize: IntSize,
        radiusPx: Float,
    ): FrostedWallpaper? = bakeLock.withLock {
        if (known && this.source === source && this.rootSize == rootSize) return value
        val baked = withContext(Dispatchers.Default) { bakeFrosted(source, rootSize, radiusPx) }
        synchronized(this) {
            this.source = source
            this.rootSize = rootSize
            value = baked
            known = true
        }
        baked
    }
}

/**
 * Builds the frosted copy before any pane has asked for it.
 *
 * The splash lays out in the same window-sized root the panes will use, so it can bake the material
 * while it is still waiting on the wallpaper. Without it the first frames after the splash draw every
 * card and bar as a transparent hole over the wallpaper and only then grow the frosted finish (see
 * [LiquidGlassSurface], which paints nothing until the copy exists).
 *
 * A [rootSize] that turns out not to match the panes' root costs one wasted bake and nothing else:
 * the copy is keyed by the root it was built for, so the panes simply build their own.
 */
internal suspend fun prewarmFrostedWallpaper(
    source: ImageBitmap,
    rootSize: IntSize,
    density: Density,
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return
    val radiusPx = with(density) { FROST_RADIUS_DP.dp.toPx() }
    FrostedWallpaperCache.of(source, rootSize, radiusPx)
}

/**
 * The blurred wallpaper behind a pane, computed once per wallpaper and shared by every pane.
 *
 * [rootSize] is the layout the wallpaper fills; it is needed to lay the blurred copy out exactly as
 * the background does, and the copy cannot be built before the root has been measured.
 *
 * The result is `null` while there is nothing to blur — the background is switched off, or the
 * wallpaper has not been decoded yet — which the surfaces read as "paint the flat material instead".
 */
@Composable
internal fun rememberFrostedWallpaper(source: ImageBitmap?, rootSize: IntSize): FrostedWallpaper? {
    val density = LocalDensity.current
    val radiusPx = with(density) { FROST_RADIUS_DP.dp.toPx() }
    var frosted by remember(source, rootSize, radiusPx) {
        mutableStateOf(source?.let { FrostedWallpaperCache.peek(it, rootSize) })
    }
    LaunchedEffect(source, rootSize, radiusPx) {
        val wallpaper = source ?: return@LaunchedEffect
        if (rootSize.width <= 0 || rootSize.height <= 0) return@LaunchedEffect
        frosted = FrostedWallpaperCache.of(wallpaper, rootSize, radiusPx)
    }
    return frosted
}

/**
 * Builds the blurred wallpaper off the main thread: lay the wallpaper out over the root exactly as
 * the background does, shrink it, blur it, measure it.
 *
 * Returns null if the bitmap cannot be read back (a hardware bitmap) or cannot be allocated, in
 * which case the panes simply keep showing the wallpaper the background paints.
 */
private fun bakeFrosted(
    source: ImageBitmap,
    rootSize: IntSize,
    radiusPx: Float,
): FrostedWallpaper? = try {
    val rootWidth = rootSize.width
    val rootHeight = rootSize.height
    if (rootWidth <= 0 || rootHeight <= 0) {
        null
    } else {
        val bitmap = source.asAndroidBitmap()
        val width = max(1, rootWidth / FROST_SCALE)
        val height = max(1, rootHeight / FROST_SCALE)
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropped)
        // Bilinear, so shrinking a large wallpaper does not alias.
        val paint = Paint().apply { flags = Paint.FILTER_BITMAP_FLAG }

        // The same ContentScale.Crop layout the background gives the wallpaper, only smaller: the
        // scaled copy is centred and overflows the shorter axis.
        val scale = max(
            cropped.width / bitmap.width.toFloat(),
            cropped.height / bitmap.height.toFloat(),
        )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (cropped.width - scaledWidth) / 2f
        val top = (cropped.height - scaledHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + scaledWidth, top + scaledHeight),
            paint,
        )

        val pixels = IntArray(width * height)
        cropped.getPixels(pixels, 0, width, 0, 0, width, height)
        cropped.recycle()

        // The radius is scaled with the copy, which is drawn at FROST_SCALE times its own size.
        val radius = max(1, (radiusPx / FROST_SCALE).roundToInt())
        // One scratch buffer for both passes: at half the root's size it is a megabyte or two, and
        // allocating a fresh one per pass was churn the collector had to deal with on the frame the
        // app was starting on.
        val scratch = IntArray(pixels.size)
        repeat(FROST_PASSES) { boxBlur(pixels, width, height, radius, scratch) }

        var lumaSum = 0.0
        for (pixel in pixels) {
            lumaSum += (0.2126 * ((pixel shr 16) and 0xFF) +
                0.7152 * ((pixel shr 8) and 0xFF) +
                0.0722 * (pixel and 0xFF)) / 255.0
        }

        FrostedWallpaper(
            image = Bitmap
                .createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                .asImageBitmap(),
            meanLuma = (lumaSum / pixels.size).toFloat(),
        )
    }
} catch (_: Throwable) {
    null
}

/**
 * Separable box blur over ARGB pixels, edges clamped so the border does not fade out. Run twice it
 * is smooth enough to read as frosted glass, at a fraction of a Gaussian's cost.
 *
 * [scratch] is the caller's working buffer, large enough for [pixels] — see [bakeFrosted].
 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, scratch: IntArray) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val window = radius * 2 + 1

    for (y in 0 until height) {
        val row = y * width
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val pixel = pixels[row + i.coerceIn(0, width - 1)]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        for (x in 0 until width) {
            scratch[row + x] = (0xFF shl 24) or
                ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val leaving = pixels[row + (x - radius).coerceIn(0, width - 1)]
            val entering = pixels[row + (x + radius + 1).coerceIn(0, width - 1)]
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }

    for (x in 0 until width) {
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val pixel = scratch[i.coerceIn(0, height - 1) * width + x]
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
        }
        for (y in 0 until height) {
            pixels[y * width + x] = (0xFF shl 24) or
                ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val leaving = scratch[(y - radius).coerceIn(0, height - 1) * width + x]
            val entering = scratch[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
