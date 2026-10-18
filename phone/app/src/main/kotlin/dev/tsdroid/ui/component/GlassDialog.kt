package dev.tsdroid.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * The screen's overlay slot: at most one [GlassDialog] draws here at a time.
 *
 * A single slot is enough because every dialog in the app is modal — no screen opens a second one
 * while the first is still up.
 */
@Stable
class GlassOverlayHostState {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    internal fun show(block: @Composable () -> Unit) {
        content = block
    }

    internal fun clear() {
        content = null
    }
}

@Composable
fun rememberGlassOverlayHostState(): GlassOverlayHostState = remember { GlassOverlayHostState() }

/**
 * Where [GlassDialog] draws itself.
 *
 * A dialog declared deep in the tree — inside a settings tab, inside a panel that slides over the
 * screen — would otherwise be clipped by whatever contains it, and the floating bar (a sibling of the
 * content, drawn above it) would sit on top of its scrim. Through this host the dialog is composed at
 * the screen root instead, wherever in the tree it was declared.
 */
val LocalGlassOverlayHost = compositionLocalOf<GlassOverlayHostState?> { null }

/**
 * Renders whatever [GlassDialog] currently wants on top. Put one at the root of a screen, after the
 * screen's content so that it draws last.
 */
@Composable
fun GlassOverlayHost(state: GlassOverlayHostState) {
    state.content?.invoke()
}

/**
 * A dialog drawn inside the screen it belongs to instead of in a window of its own.
 *
 * Android gives `Dialog` — and so `AlertDialog`, a `DropdownMenu` and a `ModalBottomSheet` — its own
 * window. The wallpaper is painted in the activity's window, so nothing inside a dialog window has a
 * backdrop to blur: those surfaces could only ever be opaque slabs. This one is the same frosted
 * material as the cards, over a dimming scrim that is part of the composition, which is what lets it
 * blur what is really behind it.
 *
 * Compose it inside an `if` to own its lifetime. It renders through [LocalGlassOverlayHost] when the
 * surrounding screen provides one — recommended, it then covers the whole screen, floating bar
 * included — and in place otherwise.
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    wallpaper: ImageBitmap? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    zIndex: Float = 4f,
    /** Shown between the title and the buttons: a list, a tree, text fields — anything. */
    body: (@Composable ColumnScope.() -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
) {
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        GlassDialogPanel(
            onDismissRequest = onDismissRequest,
            title = title,
            modifier = modifier,
            wallpaper = wallpaper,
            shape = shape,
            zIndex = zIndex,
            body = body,
            dismissButton = dismissButton,
            confirmButton = confirmButton,
        )
        return
    }

    // Hand this dialog to the screen's slot, and drop it again when the dialog leaves the composition.
    // The lambda keeps its identity while its captures are stable, so this does not churn the host.
    SideEffect {
        host.show {
            GlassDialogPanel(
                onDismissRequest = onDismissRequest,
                title = title,
                modifier = modifier,
                wallpaper = wallpaper,
                shape = shape,
                zIndex = zIndex,
                body = body,
                dismissButton = dismissButton,
                confirmButton = confirmButton,
            )
        }
    }
    DisposableEffect(host) { onDispose { host.clear() } }
}

@Composable
private fun GlassDialogPanel(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier,
    wallpaper: ImageBitmap?,
    shape: Shape,
    zIndex: Float,
    body: (@Composable ColumnScope.() -> Unit)?,
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
) {
    // Without this a dialog the user cannot dismiss with the back gesture is a trap.
    BackHandler(enabled = true) { onDismissRequest() }

    Box(modifier = modifier.fillMaxSize().zIndex(zIndex)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        LiquidGlassPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp),
            wallpaper = wallpaper,
            shape = shape,
            elevation = 10.dp,
            contentPadding = PaddingValues(0.dp),
        ) { contentColor ->
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(text = title, style = MaterialTheme.typography.headlineSmall)
                    body?.invoke(this)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * A bottom sheet in the app's frosted material.
 *
 * Material's `ModalBottomSheet` opens a window of its own, so — exactly like `AlertDialog` — there
 * is no wallpaper inside it to blur and it can only ever be an opaque slab. This draws the same
 * [LiquidGlassPanel] the dialogs use, anchored to the bottom edge, and routes through
 * [LocalGlassOverlayHost] so it covers the floating bars too.
 *
 * Compose it inside an `if` to own its lifetime. Drag down (or tap the scrim, or use the back
 * gesture) to dismiss.
 */
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    wallpaper: ImageBitmap? = null,
    modifier: Modifier = Modifier,
    zIndex: Float = 4f,
    body: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        GlassBottomSheetPanel(onDismissRequest, wallpaper, modifier, zIndex, body)
        return
    }

    SideEffect {
        host.show {
            GlassBottomSheetPanel(onDismissRequest, wallpaper, modifier, zIndex, body)
        }
    }
    DisposableEffect(host) { onDispose { host.clear() } }
}

@Composable
private fun GlassBottomSheetPanel(
    onDismissRequest: () -> Unit,
    wallpaper: ImageBitmap?,
    modifier: Modifier,
    zIndex: Float,
    body: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = true) { onDismissRequest() }

    // Drag-to-dismiss. The offset is plain state while the finger is down (the sheet has to track
    // it exactly, which an animation cannot do), and is animated back only on release.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    Box(modifier = modifier.fillMaxSize().zIndex(zIndex)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        LiquidGlassPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    },
                    onDragStopped = {
                        if (dragOffset > dismissThresholdPx) {
                            onDismissRequest()
                        } else {
                            // Suspend animation: no scope needed, and it runs to completion
                            // before the next drag can start.
                            animate(initialValue = dragOffset, targetValue = 0f) { value, _ ->
                                dragOffset = value
                            }
                        }
                    },
                ),
            wallpaper = wallpaper,
            // Only the top corners are rounded: the panel meets the bottom edge of the screen.
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            elevation = 10.dp,
            contentPadding = PaddingValues(0.dp),
        ) { contentColor ->
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The glass itself reaches the bottom edge; only its content has to
                        // clear the gesture bar, otherwise the panel would float above it.
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(top = 10.dp)
                        .padding(bottom = 24.dp),
                ) {
                    // Grabber: the drag affordance Material's sheet draws, so the sheet still
                    // reads as draggable now that it is our own panel.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 12.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(LocalContentColor.current.copy(alpha = 0.28f)),
                    )
                    body()
                }
            }
        }
    }
}
