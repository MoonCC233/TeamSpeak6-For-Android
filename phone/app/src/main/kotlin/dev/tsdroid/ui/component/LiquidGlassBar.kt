package dev.tsdroid.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * One entry of [LiquidGlassNavBar].
 */
data class LiquidGlassNavItem(
    val icon: ImageVector,
    val label: String,
)

/**
 * The frosted surface every panel and card is built from: the wallpaper actually behind the pane,
 * blurred, with the reference's inset bottom shadow, a specular rim and a soft drop shadow on top.
 *
 * When [wallpaper] is null — the anime background is switched off, or nothing has been decoded yet
 * — the pane paints its own `backgroundColor` instead, so a card still reads as a surface.
 */
@Composable
fun LiquidGlassPanel(
    modifier: Modifier = Modifier,
    wallpaper: ImageBitmap? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable BoxScope.(contentColor: Color) -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(shape),
    ) {
        LiquidGlassSurface(
            wallpaper = wallpaper,
            shape = shape,
            // Deliberately not matchParentSize(): the content must be able to size this panel.
            // The backdrop is painted in the draw phase, which never takes part in measurement, so
            // a pane can no longer be inflated by its own backdrop.
            modifier = Modifier,
        ) { contentColor ->
            Box(modifier = Modifier.padding(contentPadding)) {
                content(contentColor)
            }
        }
    }
}

/**
 * A compact frosted surface for list rows and grouped settings: the same material as
 * [LiquidGlassPanel], with a lighter shadow so a long list does not read as a pile of slabs.
 *
 * The backdrop-derived colour is published as `LocalContentColor`, so any inner [Text] or [Icon]
 * that does not set an explicit colour adapts to whatever is actually behind the glass instead of
 * assuming a light background.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    wallpaper: ImageBitmap? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    elevation: Dp = 3.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    LiquidGlassPanel(
        modifier = modifier,
        wallpaper = wallpaper,
        shape = shape,
        elevation = elevation,
        contentPadding = contentPadding,
    ) { contentColor ->
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * iOS 26 style floating navigation bar: a capsule surface with a selection pill that glides
 * between items on a spring.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidGlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wallpaper: ImageBitmap? = null,
    barHeight: Dp = 64.dp,
    /** Width given to each item. The capsule hugs the items instead of filling the window. */
    itemWidth: Dp = 92.dp,
    horizontalMargin: Dp = 16.dp,
    topMargin: Dp = 8.dp,
    bottomMargin: Dp = 12.dp,
) {
    if (items.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val capsule = CircleShape

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = horizontalMargin,
                end = horizontalMargin,
                top = topMargin,
                bottom = bottomMargin,
            ),
        // Centred, and only as wide as its items: a two-item bar reads as a compact capsule
        // rather than a rail spanning the whole window.
        contentAlignment = Alignment.Center,
    ) {
        // Hug the items, but never exceed the available width: as entries are added the slot
        // shrinks so the capsule stays inside the window instead of being clipped.
        val capsuleWidth = minOf(itemWidth * items.size, maxWidth)
        val slotWidth = capsuleWidth / items.size
        Box(
            modifier = Modifier
                .width(capsuleWidth)
                .height(barHeight)
                .clip(capsule),
        ) {
            val pillInset = 6.dp

            LiquidGlassSurface(
                wallpaper = wallpaper,
                shape = capsule,
                modifier = Modifier.matchParentSize(),
            ) { contentColor ->
                // Selection pill — a brighter sliver of the same glass.
                val halo = if (contentColor.luminance() > 0.5f) {
                    Color.Black.copy(alpha = 0.45f)
                } else {
                    Color.White.copy(alpha = 0.65f)
                }
                val pillOffset by animateDpAsState(
                    targetValue = slotWidth * selectedIndex + pillInset,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "liquidGlassPill",
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = pillOffset)
                        .width(slotWidth - pillInset * 2)
                        .height(barHeight - 12.dp)
                        .clip(capsule)
                        // A brighter sliver of the same frosted material: a translucent white wash
                        // over the bar's own backdrop, which composites into exactly what a smaller,
                        // brighter pane of the same glass would look like. An opaque theme fill (the
                        // previous secondaryContainer) read as a slab of a different material.
                        .background(Color.White.copy(alpha = if (dark) 0.22f else 0.34f))
                        .border(
                            width = 0.75.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = capsule,
                        ),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        val selected = index == selectedIndex
                        val tint by animateColorAsState(
                            targetValue = if (selected) contentColor
                            else contentColor.copy(alpha = 0.72f),
                            animationSpec = tween(durationMillis = 200),
                            label = "liquidGlassTint$index",
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (selected) 1.08f else 1f,
                            animationSpec = spring(
                                dampingRatio = 0.6f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                            label = "liquidGlassScale$index",
                        )
                        val interaction = remember { MutableInteractionSource() }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = selected,
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onSelect(index) },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = item.label,
                                color = tint,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    shadow = Shadow(
                                        color = halo,
                                        offset = Offset.Zero,
                                        blurRadius = 4f,
                                    ),
                                ),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Size of the composition root: the layout the full-screen wallpaper fills, and therefore the
 * space a pane's backdrop position is measured in.
 */
private fun LayoutCoordinates.rootLayoutSize(): IntSize {
    var current: LayoutCoordinates = this
    while (true) {
        current = current.parentLayoutCoordinates ?: return current.size
    }
}

/**
 * The frosted material itself: the wallpaper behind the pane, blurred, with a legibility veil, the
 * reference's inset bottom shadow and a specular rim on top.
 */
@Composable
private fun LiquidGlassSurface(
    wallpaper: ImageBitmap?,
    shape: Shape,
    modifier: Modifier = Modifier,
    /**
     * Painted behind the content when there is no wallpaper to blur — and only then.
     *
     * Opaque on purpose, and a container token rather than `surface`: the page behind a pane *is*
     * `surface`, so a translucent `surface` fill composites to exactly the background colour and the
     * pane stops reading as a surface at all. With the anime background switched off, that left the
     * cards and the floating bar as hollow outlines with their own shadow showing through the middle.
     * `Color.Transparent` leaves only the rim, for a pane that must stay fully see-through.
     */
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable BoxScope.(contentColor: Color) -> Unit,
) {
    val dark = isSystemInDarkTheme()

    // Where this pane sits inside the root: the offset the backdrop is positioned from. Only ever
    // read in the draw phase below, so scrolling — which changes it every frame — redraws the pane
    // instead of recomposing it.
    var paneLeft by remember { mutableStateOf(0f) }
    var paneTop by remember { mutableStateOf(0f) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    // One blurred copy of the wallpaper, shared by every pane and rebuilt only when the wallpaper or
    // the root changes — never once per pane, and never per frame.
    val frosted = rememberFrostedWallpaper(wallpaper, rootSize)
    // Whether anything is painted behind this pane at all. The blurred copy may still be building;
    // until it arrives the pane simply stays transparent over the wallpaper the background paints.
    val hasBackdrop = wallpaper != null

    // Milky veil over the blurred backdrop — this is the frosted finish. Skipped for a pane that owns
    // no background at all and must stay see-through.
    val veil = when {
        backgroundColor == Color.Transparent -> Color.Transparent
        !hasBackdrop -> Color.Transparent
        dark -> Color.White.copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.46f)
    }
    // Vibrancy: choose the label colour from what the pane actually shows. The wallpaper's mean
    // luminance is measured once, while the blurred copy is built; the veil is nearly white, so it
    // is folded in before the decision.
    val effectiveLuma = frosted?.let { it.meanLuma * (1f - veil.alpha) + veil.alpha }
    val lightBackdrop = effectiveLuma?.let { it >= 0.5f } ?: !dark
    val contentColor = when {
        !hasBackdrop -> MaterialTheme.colorScheme.onSurface
        lightBackdrop -> Color.Black.copy(alpha = 0.88f)
        else -> Color.White.copy(alpha = 0.96f)
    }
    // The sheen and the inset shading only read as material over a backdrop; on the flat fallback
    // they would be a gradient artefact on a solid card.
    val topHighlight = when {
        !hasBackdrop -> 0f
        lightBackdrop -> 0.12f
        else -> 0.24f
    }
    val insetShadow = when {
        !hasBackdrop -> 0f
        lightBackdrop -> 0.07f
        else -> 0.14f
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                paneLeft = position.x
                paneTop = position.y
                rootSize = coordinates.rootLayoutSize()
            }
            .clip(shape)
            // The backdrop is painted here, in the draw phase, clipped to the pane's own size. Its
            // position comes from the pane's draw space instead of from a sized-and-offset child, so
            // it never takes part in measurement: a pane can neither be inflated by its own backdrop
            // nor end up with a strip of bare wallpaper inside it.
            .drawWithContent {
                val image = frosted?.image
                val root = rootSize
                if (image != null && root.width > 0 && root.height > 0) {
                    // The blurred copy is the whole wallpaper as the background lays it out, at half
                    // size, so drawing it over the root puts it exactly behind the pane.
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                        // Move the origin from this pane's top-left corner to the root's, which is
                        // where the wallpaper itself is painted from.
                        translate(left = -paneLeft, top = -paneTop) {
                            drawImage(
                                image = image,
                                dstSize = root,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                    }
                } else if (wallpaper == null) {
                    // Nothing behind the pane at all (the wallpaper switch is off). A see-through
                    // pane would be an invisible ghost on the plain theme surface, so the pane falls
                    // back to a material of its own and still reads as a surface. Neutral near-white
                    // / near-black rather than surfaceContainer: the latter is a fixed
                    // lavender-tinted M3 baseline (#F3EDF7) and reads as a muddy purple block.
                    drawRect(color = backgroundColor)
                }
                // With a wallpaper but no blurred copy yet, nothing is painted: the wallpaper the
                // background is already drawing *is* the backdrop, so the pane is merely not frosted
                // for the frame or two the blur takes to build.
                drawContent()
            },
    ) {

        // Legibility veil over the frosted backdrop — light enough that the wallpaper still reads as
        // texture instead of as a flat tint.
        if (veil != Color.Transparent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(veil),
            )
        }

        // Reference inset shadow: `0 -10px 25px inset rgba(0, 0, 0, 0.15)`.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.62f to Color.Transparent,
                        1f to Color.Black.copy(alpha = insetShadow),
                    )
                ),
        )

        // Specular rim: a light band along the top edge only.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = topHighlight),
                        0.16f to Color.Transparent,
                    )
                ),
        )
        // Hairline edge: bright on dark backdrops, gently dark on light ones.
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 0.75.dp,
                    // No backdrop at all counts as a light one, so the frosted fallback gets the
                    // darker hairline instead of an invisible white one.
                    brush = if (lightBackdrop) {
                        Brush.linearGradient(
                            0f to Color.Black.copy(alpha = 0.12f),
                            0.55f to Color.Black.copy(alpha = 0.04f),
                            1f to Color.Black.copy(alpha = 0.08f),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        )
                    } else {
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = if (dark) 0.30f else 0.50f),
                            0.55f to Color.White.copy(alpha = 0.06f),
                            1f to Color.White.copy(alpha = if (dark) 0.16f else 0.24f),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        )
                    },
                    shape = shape,
                ),
        )

        content(contentColor)
    }
}
