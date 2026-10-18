package dev.tsdroid.service

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import dev.tsdroid.ui.icon.AppIcons
import kotlinx.coroutines.withTimeoutOrNull

/** Which of the panel's two lists is showing. Hoisted so it survives collapsing the panel. */
internal enum class OverlayView { Members, Channels }

internal data class FloatingOverlayMember(
    val id: Int,
    val nickname: String,
    val avatar: ImageBitmap?,
    val speaking: Boolean,
    val micMuted: Boolean,
    val outputMuted: Boolean,
)

internal data class FloatingOverlayChannel(
    val id: Long,
    val name: String,
)

/**
 * Everything the floating window draws.
 *
 * Built by the service from its own state plus the client's flows, so the composable below stays a
 * pure function of this.
 */
internal data class FloatingOverlayState(
    val connected: Boolean,
    val serverName: String?,
    val channelName: String?,
    val members: List<FloatingOverlayMember>,
    val channels: List<FloatingOverlayChannel>,
    val currentChannelId: Long,
    val localAvatar: ImageBitmap?,
    val speakingAvatar: ImageBitmap?,
    val speakingIsLocal: Boolean,
    val localSpeaking: Boolean,
    val remoteSpeaking: Boolean,
    /** A push-to-talk hold on the bubble is open right now: the microphone is transmitting. */
    val talking: Boolean,
    /** The capture path is actually running, so a hold really is being sent to the channel. */
    val micReady: Boolean,
    val micMuted: Boolean,
    val outputMuted: Boolean,
    val reconnecting: Boolean,
    val reconnectAttempt: Int,
    val micUnavailable: Boolean,
    val unread: Int,
)

/**
 * The floating window over other apps: a bubble that expands into a small control panel.
 *
 * Deliberate choices, because it draws in an overlay window rather than in the app:
 * * no elevation shadows — inside a `TYPE_APPLICATION_OVERLAY` window they render as a pale rectangle
 *   around the shape instead of a soft shadow;
 * * no ripples either (`indication = null`): their bounds are the rectangular touch target, so every
 *   tap flashed a grey square. Pressed/selected states are explicit colours instead;
 * * surfaces are translucent app colours with a hairline border, which reads the same over a game, a
 *   video or a desktop;
 * * the bubble carries the app's own pink ([AppPink], the launcher icon's background) while nobody is
 *   talking, at half opacity so it does not hide what is underneath;
 * * a push-to-talk hold paints the bubble's rim and a ring around it, and the ring follows the
 *   microphone's level — the user can see that they are live, and that they are being heard,
 *   without having to ask the channel;
 * * while a hold is open the window stays put, however much the hand holding the button shakes —
 *   and the hold steps aside the moment the finger is clearly dragging the bubble somewhere.
 */
@Composable
internal fun FloatingOverlay(
    state: FloatingOverlayState,
    /**
     * The microphone's current loudness, as a state rather than a value: the meter reads it inside
     * a draw lambda, so a moving needle redraws the bubble without recomposing the panel behind it.
     */
    inputLevel: State<Float>,
    expanded: Boolean,
    view: OverlayView,
    onViewChange: (OverlayView) -> Unit,
    onToggleExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChange: (Int, Int) -> Unit,
    onToggleMic: () -> Unit,
    onToggleOutput: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
    onOpenApp: () -> Unit,
    onMoveToChannel: (Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    // The window is WRAP_CONTENT, and the system throttles overlay window resizes hard: animating the
    // *content size* turned a 200ms reveal into about ten seconds. So the layout jumps straight to the
    // target size (one resize), and the reveal is animated *inside* the window instead — the panel
    // fades and scales in, and on the way back it fades out before the window shrinks to the bubble.
    var showPanel by remember { mutableStateOf(expanded) }
    val reveal = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            showPanel = true
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(REVEAL_ANIMATION_MS))
        } else {
            reveal.animateTo(0f, tween(REVEAL_ANIMATION_MS))
            showPanel = false
            // The bubble takes the panel's place only after the window has shrunk, so it fades back in.
            reveal.animateTo(1f, tween(REVEAL_FADE_IN_MS))
        }
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .background(Color.Transparent)
            // The window follows this size. The service only records it here: a window must not be
            // moved from inside a layout callback.
            .onSizeChanged { onSizeChange(it.width, it.height) },
    ) {
        if (showPanel) {
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = reveal.value
                    val scale = REVEAL_MIN_SCALE + (1f - REVEAL_MIN_SCALE) * reveal.value
                    scaleX = scale
                    scaleY = scale
                },
            ) {
                ExpandedPanel(
                    state = state,
                    view = view,
                    onViewChange = onViewChange,
                    onToggleExpand = onToggleExpand,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onToggleMic = onToggleMic,
                    onToggleOutput = onToggleOutput,
                    onOpenApp = onOpenApp,
                    onMoveToChannel = onMoveToChannel,
                    onDisconnect = onDisconnect,
                )
            }
        } else {
            Box(modifier = Modifier.graphicsLayer { alpha = reveal.value }) {
                CollapsedBubble(
                    state = state,
                    inputLevel = inputLevel,
                    onToggleExpand = onToggleExpand,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onTalkStart = onTalkStart,
                    onTalkStop = onTalkStop,
                )
            }
        }
    }
}

// ── Collapsed ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsedBubble(
    state: FloatingOverlayState,
    inputLevel: State<Float>,
    onToggleExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val talking = state.talking
    // A hold with an unusable capture path is worse than a closed microphone: the user believes
    // they are being heard. The rings say so in the error colour instead.
    val liveColor = if (state.micReady) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val speaking = state.localSpeaking || state.remoteSpeaking
    // Whoever is talking gets the bubble; the app icon sits there the rest of the time.
    val avatar = when {
        state.remoteSpeaking -> state.speakingAvatar
        state.localSpeaking -> state.localAvatar
        else -> null
    }
    // A drag must not also read as a tap, and neither must a push-to-talk press.
    var dragged by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }
    // True while a hold owns the bubble: the window must not follow a shaking hand. The hold's own
    // escape hatch clears it, handing the gesture over to the drag.
    var holdOwnsBubble by remember { mutableStateOf(false) }
    // Only a drag that actually moved the window may dock it against the edge on release.
    var windowMoved by remember { mutableStateOf(false) }

    // Read inside the gestures, so neither ever acts on a stale microphone state.
    val micClosed by rememberUpdatedState(state.micMuted)
    val latestTalkStart by rememberUpdatedState(onTalkStart)
    val latestTalkStop by rememberUpdatedState(onTalkStop)
    val haptics = LocalHapticFeedback.current

    // The live ring breathes so a hold is obvious even in silence. It only animates while the hold
    // is open: the bubble sits on screen for hours and must not tick away in the background.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(talking) {
        pulse.snapTo(0f)
        while (talking) {
            pulse.animateTo(1f, tween(LIVE_PULSE_MS))
            pulse.animateTo(0f, tween(LIVE_PULSE_MS))
        }
    }

    // The bubble's own box is a size larger than the circle so the badge can sit on the rim without
    // being clipped by it — and so the drag/click target is the whole square, not just the circle.
    Box(
        modifier = Modifier
            .size(BubbleTouchSize)
            // Both rings are drawn in the margin between the circle and the box, so a hold changes
            // nothing about the window's size — an overlay window resize is throttled by the system
            // and would slide the bubble out from under the finger mid-sentence.
            .drawBehind {
                if (!talking) return@drawBehind
                val stroke = LiveRingWidth.toPx()
                val radius = size.minDimension / 2f - stroke
                val diameter = radius * 2f
                // The needle: it follows the microphone, which is the honest answer to "can they
                // hear me?". Silence keeps a faint full ring so the meter reads as idle, not broken.
                drawCircle(
                    color = liveColor.copy(alpha = LIVE_IDLE_ALPHA + LIVE_PULSE_ALPHA * pulse.value),
                    radius = radius,
                    style = Stroke(width = stroke),
                )
                val level = inputLevel.value.coerceIn(0f, 1f)
                if (level > 0.01f) {
                    drawArc(
                        color = liveColor,
                        startAngle = -90f,
                        sweepAngle = 360f * level,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            .pointerInput(Unit) {
                // Hold to talk. This handler waits on its own terms (see [awaitHoldStart]) instead
                // of using Compose's `awaitLongPressOrCancellation`/`waitForUpOrCancellation`: those
                // treat *any* consumed change, or a finger that leaves the node, as a cancellation —
                // and the drag handler next to this one consumes every movement past the touch slop.
                // That is what used to close the microphone mid-sentence.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A new gesture starts here: nothing from the previous one may keep suppressing
                    // taps or moving the window.
                    dragged = false
                    suppressClick = false
                    windowMoved = false
                    // Only a microphone that starts out closed can be opened by a hold, so a press
                    // can never "restore" a state it did not change.
                    if (!micClosed) return@awaitEachGesture
                    if (!awaitHoldStart(down, dragStarted = { dragged })) return@awaitEachGesture

                    suppressClick = true
                    holdOwnsBubble = true
                    // In the hand, not on the screen: the fastest possible "you are live".
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestTalkStart()
                    try {
                        val escape = awaitHoldEnd(down)
                        if (escape != null) {
                            // The finger is dragging the bubble rather than holding it: hand the
                            // gesture over, taking the hold's own movement with it so the bubble
                            // lands under the finger instead of behind it.
                            holdOwnsBubble = false
                            windowMoved = true
                            onDrag(escape.x, escape.y)
                        }
                    } finally {
                        holdOwnsBubble = false
                        // A gesture the system cancels, the window going away and a normal lift all
                        // have to close the microphone.
                        latestTalkStop()
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragged = true },
                    onDragEnd = { if (windowMoved) onDragEnd() },
                    onDragCancel = { if (windowMoved) onDragEnd() },
                ) { change, amount ->
                    change.consume()
                    // A hold owns the bubble until its escape hatch fires. Dropping the movement
                    // here is the point: a hand that trembles while holding a button must not throw
                    // the window across the screen.
                    if (holdOwnsBubble) return@detectDragGestures
                    windowMoved = true
                    onDrag(amount.x, amount.y)
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (dragged || suppressClick) {
                        dragged = false
                        suppressClick = false
                    } else {
                        onToggleExpand()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(BubbleSize),
            shape = CircleShape,
            // The launcher icon's own pink, half transparent: the icon stays legible while a game or a
            // desktop keeps showing through. A talking avatar simply covers it.
            color = AppPink.copy(alpha = AppPinkAlpha),
            border = BorderStroke(
                width = when {
                    talking -> 2.5.dp
                    speaking -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    talking -> liveColor
                    speaking -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.fillMaxSize(0.78f),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // Outside the circle's clip, so it is drawn whole. The hold has to be unmistakable, so it
        // outranks everything else the badge can say — unread messages are still counted while it
        // is open, they simply wait their turn.
        when {
            talking -> BubbleBadge(
                modifier = Modifier.align(Alignment.BottomEnd),
                color = liveColor,
            ) {
                Icon(
                    imageVector = if (state.micReady) AppIcons.MicOn else AppIcons.MicOff,
                    contentDescription = stringResource(
                        if (state.micReady) R.string.floating_overlay_talking
                        else R.string.floating_overlay_mic_not_working,
                    ),
                    tint = if (state.micReady) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onError
                    },
                    modifier = Modifier.size(12.dp),
                )
            }
            // Unread wins over "muted": the user can hear that they are muted, but not that someone
            // wrote to them.
            state.unread > 0 -> BubbleBadge(
                modifier = Modifier.align(Alignment.BottomEnd),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = if (state.unread > 99) "99+" else state.unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            state.micMuted -> BubbleBadge(
                modifier = Modifier.align(Alignment.BottomEnd),
                color = MaterialTheme.colorScheme.error,
            ) {
                Icon(
                    imageVector = AppIcons.MicOff,
                    contentDescription = stringResource(R.string.floating_overlay_muted),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ── The bubble's gesture ─────────────────────────────────────────────────────────

/**
 * Waits out the long-press timeout that turns a press into push-to-talk.
 *
 * Reads on the [PointerEventPass.Initial] pass and ignores whether the event was consumed, which
 * is the whole point: the drag handler beside this one consumes every movement past the touch
 * slop, and Compose's own `awaitLongPressOrCancellation` reads exactly that consumption as
 * "cancelled".
 *
 * @param dragStarted whether the drag handler has taken the gesture over already. A pointer's
 *   position is measured inside the window the drag is moving, so on its own it cannot always tell
 *   a moving finger from a moving window.
 * @return true when the finger is still down — and still — once the timeout has run out.
 */
private suspend fun AwaitPointerEventScope.awaitHoldStart(
    down: PointerInputChange,
    dragStarted: () -> Boolean,
): Boolean {
    val slop = viewConfiguration.touchSlop
    var released = false
    var moved = false
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (!released && !moved) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || change.changedToUpIgnoreConsumed()) {
                released = true
            } else {
                // Nothing is being transmitted yet, so a finger already on its way somewhere is a
                // drag — exactly as it was before the hold existed.
                moved = dragStarted() ||
                    (change.position - down.position).getDistance() > slop
            }
        }
    }
    return !released && !moved
}

/**
 * Waits for a push-to-talk hold to end.
 *
 * Nothing a hand does while holding a button down may end it: trembling, drifting past the touch
 * slop, wandering off the bubble, the window being clamped against a screen edge. Every one of
 * those used to read as "cancelled" and closed the microphone mid-sentence.
 *
 * The single exception is a finger that has clearly been moved on purpose — that is a drag, and the
 * window has to obey it.
 *
 * @return the movement to hand over to the drag, or null when the hold ended by itself.
 */
private suspend fun AwaitPointerEventScope.awaitHoldEnd(down: PointerInputChange): Offset? {
    val escape = HoldDragEscape.toPx()
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.changedToUpIgnoreConsumed()) return null
        val moved = change.position - down.position
        if (moved.getDistance() >= escape) return moved
    }
}

@Composable
private fun BubbleBadge(
    modifier: Modifier = Modifier,
    color: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.size(20.dp),
        shape = CircleShape,
        color = color,
        border = BorderStroke(1.5.dp, overlaySurface()),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

// ── Expanded ─────────────────────────────────────────────────────────────────────

@Composable
private fun ExpandedPanel(
    state: FloatingOverlayState,
    view: OverlayView,
    onViewChange: (OverlayView) -> Unit,
    onToggleExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleOutput: () -> Unit,
    onOpenApp: () -> Unit,
    onMoveToChannel: (Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(PanelWidth)
            .heightIn(max = PanelMaxHeight),
        shape = RoundedCornerShape(20.dp),
        color = overlaySurface(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PanelHeader(
                state = state,
                onToggleExpand = onToggleExpand,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onOpenApp = onOpenApp,
            )

            if (state.reconnecting || state.micUnavailable) {
                WarningStrip(
                    text = if (state.reconnecting) {
                        stringResource(R.string.reconnect_in_progress, maxOf(state.reconnectAttempt, 1))
                    } else {
                        stringResource(R.string.mic_unavailable_short)
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SegmentChip(
                    text = stringResource(R.string.floating_overlay_members, state.members.size),
                    selected = view == OverlayView.Members,
                    onClick = { onViewChange(OverlayView.Members) },
                    modifier = Modifier.weight(1f),
                )
                SegmentChip(
                    text = stringResource(R.string.floating_overlay_channels),
                    selected = view == OverlayView.Channels,
                    onClick = { onViewChange(OverlayView.Channels) },
                    modifier = Modifier.weight(1f),
                )
            }

            Box(modifier = Modifier.weight(1f, fill = false)) {
                when (view) {
                    OverlayView.Members -> MembersList(state.members)
                    OverlayView.Channels -> ChannelsList(state.channels, state.currentChannelId, onMoveToChannel)
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 1.dp,
            )

            ActionBar(
                state = state,
                onToggleMic = onToggleMic,
                onToggleOutput = onToggleOutput,
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun PanelHeader(
    state: FloatingOverlayState,
    onToggleExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Dragging by the header is the usual way to move a panel; the bubble is dragged whole.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(connected = state.connected)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.serverName ?: stringResource(R.string.floating_overlay_offline),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.channelName ?: stringResource(R.string.floating_overlay_no_channel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeaderIconButton(
            icon = AppIcons.Home,
            contentDescription = stringResource(R.string.floating_overlay_open_app),
            onClick = onOpenApp,
        )
        Spacer(Modifier.width(4.dp))
        HeaderIconButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.floating_overlay_collapse),
            onClick = onToggleExpand,
            iconSize = 20.dp,
        )
    }
}

/** A 32dp icon button with a press colour instead of a ripple (see the note on [FloatingOverlay]). */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (pressed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun WarningStrip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = CircleShape,
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun MembersList(members: List<FloatingOverlayMember>) {
    if (members.isEmpty()) {
        EmptyHint(stringResource(R.string.floating_overlay_alone))
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ListMaxHeight)
            .padding(vertical = 2.dp),
    ) {
        items(members, key = { it.id }) { member ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .then(
                            if (member.speaking) {
                                Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (member.avatar != null) {
                        Image(
                            bitmap = member.avatar,
                            contentDescription = null,
                            modifier = Modifier
                                .size(if (member.speaking) 19.dp else 24.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = member.nickname,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (member.speaking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (member.micMuted) {
                    Icon(
                        imageVector = AppIcons.MicOff,
                        contentDescription = stringResource(R.string.floating_overlay_muted),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (member.outputMuted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = AppIcons.SpeakerOff,
                        contentDescription = stringResource(R.string.floating_overlay_deafened),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelsList(
    channels: List<FloatingOverlayChannel>,
    currentChannelId: Long,
    onMoveToChannel: (Long) -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyHint(stringResource(R.string.floating_overlay_no_channels))
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ListMaxHeight)
            .padding(vertical = 2.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            val current = channel.id == currentChannelId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !current) { onMoveToChannel(channel.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Channel,
                    contentDescription = null,
                    tint = if (current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (current) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

/**
 * The panel's actions: microphone, sound, disconnect.
 *
 * One microphone control only — the push-to-talk duplicate was confusing, and the toggle it sat next
 * to does the same job.
 */
@Composable
private fun ActionBar(
    state: FloatingOverlayState,
    onToggleMic: () -> Unit,
    onToggleOutput: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            icon = if (state.micMuted) AppIcons.MicOff else AppIcons.MicOn,
            contentDescription = stringResource(
                if (state.micMuted) R.string.notif_unmute else R.string.notif_mute,
            ),
            active = state.micMuted,
            onClick = onToggleMic,
        )
        ActionButton(
            icon = if (state.outputMuted) AppIcons.SpeakerOff else AppIcons.SpeakerOn,
            contentDescription = stringResource(
                if (state.outputMuted) R.string.notif_unmute else R.string.notif_mute,
            ),
            active = state.outputMuted,
            onClick = onToggleOutput,
        )
        ActionButton(
            icon = AppIcons.Exit,
            contentDescription = stringResource(R.string.disconnect),
            active = true,
            onClick = onDisconnect,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = CircleShape,
        color = when {
            active -> MaterialTheme.colorScheme.error.copy(alpha = if (pressed) 0.34f else 0.22f)
            pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Shared ───────────────────────────────────────────────────────────────────────

private val BubbleSize = 46.dp

/** The bubble's touch/total box: the circle plus room for the badge on its rim. */
private val BubbleTouchSize = 56.dp

/** The live ring and the level arc both live in the margin that box leaves around the circle. */
private val LiveRingWidth = 3.dp

/** Half of the live ring's breathing period, in milliseconds. */
private const val LIVE_PULSE_MS = 750

/** A silent hold still shows a faint ring — an empty meter that flickers reads as a broken one. */
private const val LIVE_IDLE_ALPHA = 0.16f

/** How much louder the ring gets at the top of each breath. */
private const val LIVE_PULSE_ALPHA = 0.18f

/**
 * How far a holding finger has to travel before the bubble is being dragged rather than held.
 *
 * Comfortably above the touch slop (≈8dp) and above what a hand does while holding a button down,
 * so a tremor can no longer cut the microphone — while a deliberate drag still moves the window.
 */
private val HoldDragEscape = 24.dp

private val PanelWidth = 264.dp
private val PanelMaxHeight = 360.dp
private val ListMaxHeight = 200.dp

/** How long expanding/collapsing takes. Must match the anchor window in the service. */
private const val REVEAL_ANIMATION_MS = 200

/** How long the bubble takes to fade back in once the window has shrunk to it. */
private const val REVEAL_FADE_IN_MS = 120

/** The panel grows into place from this much of its size. */
private const val REVEAL_MIN_SCALE = 0.88f

/**
 * The app icon's background colour (`ic_launcher_background`), which is what the bubble wears while
 * nobody is talking.
 */
private val AppPink = Color(0xFFFF69B4)

/** Half transparent on purpose: the bubble must not hide much of what is behind it. */
private const val AppPinkAlpha = 0.5f

/**
 * The overlay's surface colour: a translucent version of the app's own surface, so the window follows
 * the system theme while still letting a game or a desktop show through at its edges.
 */
@Composable
private fun overlaySurface(): Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
