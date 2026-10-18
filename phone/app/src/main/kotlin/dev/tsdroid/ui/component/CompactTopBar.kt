package dev.tsdroid.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of [CompactTopBar] below the status bar, where Material's own bar reserves 64dp. */
private val DefaultTopBarHeight = 48.dp

/**
 * The app's top bar.
 *
 * `TopAppBar` from Material reserves 64dp on top of the status-bar inset, which over a wallpaper
 * reads as an empty band with the title floating in the middle of it. This bar keeps the inset — the
 * app is edge-to-edge, so the bar still has to clear the status bar — but centres the title inside
 * [height] instead, so the bar is shorter and the title sits higher without negative padding.
 *
 * Colours match what `TopAppBarDefaults.topAppBarColors()` produced before: an `onSurface` title
 * with `onSurfaceVariant` icons.
 */
@Composable
fun CompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    height: Dp = DefaultTopBarHeight,
    /** Overridable for bars that sit on glass and take their colour from what is behind them. */
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides iconColor) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(height)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    // The 4dp above is the icon-button gutter; a title without an icon still needs
                    // the standard 16dp text inset.
                    .padding(start = if (navigationIcon == null) 12.dp else 0.dp),
            )
            actions()
        }
    }
}
