package dev.tsdroid.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.tsdroid.ui.component.AnimeWallpaperState
import dev.tsdroid.ui.component.prewarmFrostedWallpaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Branding stays up this long even when everything was ready before the first frame. */
private const val MINIMUM_VISIBLE_MS = 450L

/**
 * How long the wallpaper is waited for before the app is let in anyway.
 *
 * Long enough for a slow mobile connection to finish the download, short enough that a dead network
 * cannot turn the splash into a hang. Past it the app opens without a wallpaper and the image fades
 * in when it finally lands.
 */
private const val WALLPAPER_TIMEOUT_MS = 8_000L

/**
 * The startup gate.
 *
 * It stays up until the app has what it needs to look finished, which — with the wallpaper switched
 * on — means the image has been downloaded, decoded and turned into the theme colour behind it.
 * Previously this screen was decoration: it ran on fixed delays while the app behind it started from
 * an empty background and downloaded the same image a second time, so the wallpaper was still
 * visibly arriving long after the splash was gone.
 *
 * [animeBackground] is the setting the caller has already read, or `null` while that read is still in
 * flight. The splash waits for it on purpose: whether there is anything to wait for at all depends on
 * it, and the screen that follows starts from the same answer (see [AppNavigation]).
 */
@Composable
fun SplashScreen(animeBackground: Boolean?, onReady: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shownAt = remember { TimeSource.Monotonic.markNow() }

    // The root this screen lays out in is the window-sized root the app will use — the wallpaper
    // fills it and every pane measures its frosted backdrop against it.
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    // The spinner is honest: it turns while something is actually pending and never otherwise. With
    // the wallpaper switched off there is nothing to prepare, so it does not flash at all.
    val preparing = when (animeBackground) {
        null -> true // the setting itself is still being read
        true -> AnimeWallpaperState.paintReady.not()
        false -> false
    }

    LaunchedEffect(animeBackground) {
        val wanted = animeBackground ?: return@LaunchedEffect
        AnimeWallpaperState.ensureFetched(context, wanted = wanted)

        if (wanted) {
            // The pipeline outlives this screen, so giving up here only ends the wait: an image that
            // arrives later still paints, with a fade instead of the cold start this waits away.
            withTimeoutOrNull(WALLPAPER_TIMEOUT_MS) {
                val wallpaper = snapshotFlow { AnimeWallpaperState.activeBitmap }
                    .filterNotNull()
                    .first()
                // The frosted material the panes build from it, baked here rather than on the app's
                // first frames — they would otherwise open as transparent holes over the wallpaper
                // and only grow their finish afterwards. Layout has happened by now in practice; if
                // it has not, this waits for the size instead of guessing one.
                val size = snapshotFlow { rootSize }.first { it.width > 0 && it.height > 0 }
                prewarmFrostedWallpaper(wallpaper, size, density)
            }
        }

        val remaining = MINIMUM_VISIBLE_MS - shownAt.elapsedNow().inWholeMilliseconds
        if (remaining > 0) delay(remaining)
        onReady()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootSize = it.size }
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "TS6 Droid",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            if (preparing) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
