package dev.tsdroid.pad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.tsdroid.pad.R
import dev.tsdroid.screen.ScreenShareController
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * A fixed shelf above the workspace content. It keeps every live preview in
 * composition while the chat/voice column scrolls, so opening a fullscreen
 * viewer never tears down the shared WebRTC session.
 */
@Composable
fun PadScreenShareShelf(
    streams: List<ScreenShareController.RemoteStream>,
    liveCount: Int,
    displayNameOf: (ScreenShareController.RemoteStream) -> String,
    phases: Map<String, ScreenShareController.ViewerPhase>,
    stats: Map<String, ScreenShareController.ViewerStats>,
    activeStreamId: String?,
    resumed: Boolean,
    onExpand: (ScreenShareController.RemoteStream) -> Unit,
    onInfo: (ScreenShareController.RemoteStream) -> Unit,
    onStop: (ScreenShareController.RemoteStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (streams.isEmpty()) return
    val cardWidth = if (streams.size == 1) 460.dp else 340.dp

    Column(
        modifier
            .fillMaxWidth()
            .background(PadColors.Surface.copy(alpha = 0.42f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionKicker(stringResource(R.string.share_shelf_kicker))
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.share_shelf_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = PadColors.Text,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.share_shelf_count, liveCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Muted,
                    )
                }
            }
            PadLiveBadge(label = stringResource(R.string.share_live))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            streams.forEach { stream ->
                key(stream.streamId) {
                    PadScreenShareCard(
                        stream = stream,
                        displayName = displayNameOf(stream),
                        phase = phases[stream.streamId]
                            ?: ScreenShareController.ViewerPhase.JOINING,
                        stats = stats[stream.streamId],
                        active = activeStreamId != stream.streamId,
                        resumed = resumed,
                        onExpand = { onExpand(stream) },
                        onInfo = { onInfo(stream) },
                        onStopWatching = { onStop(stream) },
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(16f / 9f),
                    )
                }
            }
        }
        HorizontalDivider(color = PadColors.Border)
    }
}

@Composable
fun PadScreenShareCard(
    stream: ScreenShareController.RemoteStream,
    displayName: String,
    phase: ScreenShareController.ViewerPhase,
    stats: ScreenShareController.ViewerStats?,
    active: Boolean,
    resumed: Boolean,
    onExpand: () -> Unit,
    onInfo: () -> Unit,
    onStopWatching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val eglBase = remember(stream.streamId) { EglBase.create() }
    val renderer = remember(stream.streamId) {
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }

    // The shelf owns the preview sink. A fullscreen viewer adds a second sink
    // to the same controller session; only an explicit stop leaves it.
    DisposableEffect(stream.streamId) {
        onDispose {
            runCatching {
                ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
            }
            runCatching { renderer.release() }
            runCatching { eglBase.release() }
        }
    }

    DisposableEffect(stream.streamId, active, resumed) {
        when {
            !resumed -> ScreenShareController.leaveStream(stream.streamId)
            active -> ScreenShareController.joinStream(
                stream.streamId,
                stream.clientId,
                renderer,
            )
            else -> ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
        }
        onDispose {
            ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
        }
    }

    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color(0xFF0B1110),
        contentColor = Color.White,
        border = BorderStroke(
            1.dp,
            if (phase == ScreenShareController.ViewerPhase.WATCHING) {
                PadColors.Red.copy(alpha = 0.46f)
            } else {
                PadColors.Border
            },
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            PadVideoSurface(
                renderer = renderer,
                stats = stats,
                fillMode = false,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = onExpand),
            )

            if (phase != ScreenShareController.ViewerPhase.WATCHING) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.56f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        viewerPhaseText(phase)?.let { text ->
                            Text(
                                text = text,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (phase.isShareBusy()) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        }
                        if (phase.isShareTerminal()) {
                            TextButton(onClick = onStopWatching) {
                                Text(
                                    stringResource(R.string.share_close),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent),
                        ),
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadLiveBadge(
                    label = stringResource(R.string.share_live),
                    dark = true,
                )
                Spacer(Modifier.weight(1f))
                shareQualityText(stats)?.let { quality ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            quality,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                        ),
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 28.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadAvatar(name = displayName, size = 28.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        viewerPhaseText(phase) ?: stringResource(R.string.share_watching),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                }
                ShareControlButton(
                    icon = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.share_info),
                    onClick = onInfo,
                )
                ShareControlButton(
                    icon = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.share_watch_fullscreen),
                    onClick = onExpand,
                )
                ShareControlButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.share_stop_watching),
                    onClick = onStopWatching,
                    tint = PadColors.Red,
                )
            }
        }
    }
}

/**
 * Fullscreen viewer rendered over the whole Pad workspace. It uses the same
 * WebRTC session as the shelf preview and only removes its own renderer when it
 * closes, so returning to the workspace resumes the preview without a new SDP
 * negotiation.
 */
@Composable
fun PadScreenShareViewer(
    stream: ScreenShareController.RemoteStream,
    displayName: String,
    phase: ScreenShareController.ViewerPhase,
    stats: ScreenShareController.ViewerStats?,
    resumed: Boolean,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onStopWatching: () -> Unit,
) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }

    DisposableEffect(stream.streamId, resumed) {
        if (resumed) {
            ScreenShareController.joinStream(stream.streamId, stream.clientId, renderer)
        } else {
            ScreenShareController.leaveStream(stream.streamId)
        }
        onDispose {
            ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
            }
            runCatching { renderer.release() }
            runCatching { eglBase.release() }
        }
    }

    val activity = remember(context) { context.findPadActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.let {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(onBack = onDismiss)

    var fillMode by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible, phase) {
        if (controlsVisible && phase == ScreenShareController.ViewerPhase.WATCHING) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(phase) {
        if (phase.isShareTerminal()) controlsVisible = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        PadVideoSurface(
            renderer = renderer,
            stats = stats,
            fillMode = fillMode,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .clickable { controlsVisible = !controlsVisible },
        )

        if (phase != ScreenShareController.ViewerPhase.WATCHING) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                viewerPhaseText(phase)?.let { text ->
                    Text(
                        text = text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                if (phase.isShareBusy()) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                if (phase.isShareTerminal()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.share_close),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                        ),
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.share_exit_fullscreen),
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        PadLiveBadge(
                            label = stringResource(R.string.share_live),
                            dark = true,
                            compact = true,
                        )
                    }
                    viewerStatsText(stats)?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.share_info),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { fillMode = !fillMode }) {
                    Icon(
                        if (fillMode) Icons.Filled.ZoomInMap else Icons.Filled.ZoomOutMap,
                        contentDescription = stringResource(
                            if (fillMode) R.string.share_fit else R.string.share_fill,
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.share_exit_fullscreen),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onStopWatching) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.share_stop_watching),
                        tint = PadColors.Red,
                    )
                }
            }
        }

        if (controlsVisible) {
            Text(
                stringResource(R.string.share_controls_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
fun PadSharePublishingBar(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "share-pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "share-dot",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        PadColors.Red.copy(alpha = 0.13f),
                        PadColors.Amber.copy(alpha = 0.045f),
                    ),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(PadColors.Red.copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.share_publishing_title),
                style = MaterialTheme.typography.titleSmall,
                color = PadColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.share_publishing_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Muted,
            )
        }
        Row(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PadColors.Red.copy(alpha = 0.14f))
                .border(
                    1.dp,
                    PadColors.Red.copy(alpha = 0.36f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onStop)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ScreenShare,
                contentDescription = null,
                tint = PadColors.Red,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.share_stop),
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Red,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun PadShareButton(
    publishing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (publishing) PadColors.Red else PadColors.Accent
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(tint.copy(alpha = 0.11f))
            .border(1.dp, tint.copy(alpha = 0.3f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.ScreenShare,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                if (publishing) R.string.share_button_stop else R.string.share_button_start,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun PadStreamInfoDialog(
    displayName: String,
    audioSupported: Boolean,
    stats: ScreenShareController.ViewerStats?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(540.dp)
                .padding(20.dp),
            shape = RoundedCornerShape(22.dp),
            color = PadColors.Surface,
            contentColor = PadColors.Text,
            border = BorderStroke(1.dp, PadColors.Border),
        ) {
            Column(
                Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionKicker(stringResource(R.string.share_info_subtitle))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(R.string.share_info_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = PadColors.Text,
                        )
                        Text(
                            displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = PadColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PadIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.share_close),
                        onClick = onDismiss,
                        tint = PadColors.Muted,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (stats == null) {
                    Text(
                        stringResource(R.string.share_info_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PadColors.Muted,
                    )
                } else {
                    val none = stringResource(R.string.share_value_none)
                    val width = stats.width
                    val height = stats.height
                    val resolution = if (width != null && height != null) {
                        stringResource(
                            R.string.share_resolution_format,
                            width,
                            height,
                        )
                    } else {
                        none
                    }
                    val decoderKind = when (stats.hardwareDecoder) {
                        true -> stringResource(R.string.share_info_hw)
                        false -> stringResource(R.string.share_info_sw)
                        null -> null
                    }
                    val decoderName = stats.decoder
                    val decoder = if (decoderName != null && decoderKind != null) {
                        stringResource(
                            R.string.share_decoder_kind_format,
                            decoderName,
                            decoderKind,
                        )
                    } else {
                        decoderName ?: none
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadStatTile(
                            label = stringResource(R.string.share_info_bitrate),
                            value = formatShareKbps(stats.bitrateKbps),
                            modifier = Modifier.weight(1f),
                        )
                        PadStatTile(
                            label = stringResource(R.string.share_info_loss),
                            value = formatShareLoss(stats.lossPercent),
                            modifier = Modifier.weight(1f),
                        )
                        PadStatTile(
                            label = stringResource(R.string.share_info_rtt),
                            value = formatShareMs(stats.rttMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = PadColors.Border)
                    Spacer(Modifier.height(8.dp))
                    PadInfoLine(
                        stringResource(R.string.share_info_resolution),
                        resolution,
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_fps),
                        stats.fps?.let { formatShareFps(it.toInt()) } ?: none,
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_codec),
                        stats.codec ?: none,
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_decoder),
                        decoder,
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_jitter),
                        formatShareMs(stats.jitterMs),
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_decode),
                        formatShareDecodeTime(stats.decodeTimeMs),
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_packets),
                        stats.packetsReceived?.toString() ?: none,
                    )
                    PadInfoLine(
                        stringResource(R.string.share_info_received),
                        formatShareMb(stats.totalReceivedMB),
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = PadColors.Border)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.share_info_audio),
                        style = MaterialTheme.typography.labelMedium,
                        color = PadColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (audioSupported || stats.audioSupported) {
                        PadInfoLine(
                            stringResource(R.string.share_info_audio_bitrate),
                            formatShareKbps(stats.audioBitrateKbps),
                        )
                        PadInfoLine(
                            stringResource(R.string.share_info_loss),
                            formatShareLoss(stats.audioLossPercent),
                        )
                        PadInfoLine(
                            stringResource(R.string.share_info_jitter),
                            formatShareMs(stats.audioJitterMs),
                        )
                        PadInfoLine(
                            stringResource(R.string.share_info_packets),
                            stats.audioPacketsReceived?.toString()
                                ?: stringResource(R.string.share_value_none),
                        )
                    } else {
                        Text(
                            stringResource(R.string.share_info_no_audio),
                            style = MaterialTheme.typography.bodySmall,
                            color = PadColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PadStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PadColors.SurfaceAlt)
            .border(1.dp, PadColors.Border, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PadColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = PadColors.Text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PadInfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = PadColors.Muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = PadColors.Text,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShareControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.46f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun PadVideoSurface(
    renderer: SurfaceViewRenderer,
    stats: ScreenShareController.ViewerStats?,
    fillMode: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val videoAspect = remember(stats?.width, stats?.height) {
            val width = stats?.width ?: 0
            val height = stats?.height ?: 0
            if (width > 0 && height > 0) {
                width.toFloat() / height.toFloat()
            } else {
                16f / 9f
            }
        }
        val containerAspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            videoAspect
        }
        val (videoWidth, videoHeight) = if (videoAspect >= containerAspect) {
            if (fillMode) {
                (maxHeight * videoAspect) to maxHeight
            } else {
                maxWidth to (maxWidth / videoAspect)
            }
        } else {
            if (fillMode) {
                maxWidth to (maxWidth / videoAspect)
            } else {
                (maxHeight * videoAspect) to maxHeight
            }
        }
        AndroidView(
            factory = { renderer },
            modifier = Modifier.requiredSize(videoWidth, videoHeight),
        )
    }
}

@Composable
private fun viewerPhaseText(phase: ScreenShareController.ViewerPhase): String? = when (phase) {
    ScreenShareController.ViewerPhase.JOINING -> stringResource(R.string.viewer_joining)
    ScreenShareController.ViewerPhase.CONNECTING -> stringResource(R.string.viewer_connecting)
    ScreenShareController.ViewerPhase.WATCHING -> stringResource(R.string.viewer_watching)
    ScreenShareController.ViewerPhase.RECONNECTING -> stringResource(R.string.viewer_reconnecting)
    ScreenShareController.ViewerPhase.ENDED -> stringResource(R.string.viewer_ended)
    ScreenShareController.ViewerPhase.REJECTED -> stringResource(R.string.viewer_rejected)
    ScreenShareController.ViewerPhase.FAILED -> stringResource(R.string.viewer_failed)
}

@Composable
private fun shareQualityText(stats: ScreenShareController.ViewerStats?): String? {
    val width = stats?.width ?: return null
    val height = stats?.height ?: return null
    val fps = stats.fps?.toInt()
    return if (fps != null) {
        stringResource(R.string.share_quality_format, width, height, fps)
    } else {
        stringResource(R.string.share_resolution_format, width, height)
    }
}

@Composable
private fun viewerStatsText(stats: ScreenShareController.ViewerStats?): String? {
    val quality = shareQualityText(stats) ?: return null
    val bitrateKbps = stats?.bitrateKbps
    val bitrate = if (bitrateKbps != null) {
        stringResource(R.string.share_bitrate_format, bitrateKbps)
    } else {
        null
    }
    return if (bitrate != null) {
        stringResource(R.string.share_meta_format, quality, bitrate)
    } else {
        quality
    }
}

private fun ScreenShareController.ViewerPhase.isShareBusy(): Boolean =
    this == ScreenShareController.ViewerPhase.JOINING ||
        this == ScreenShareController.ViewerPhase.CONNECTING ||
        this == ScreenShareController.ViewerPhase.RECONNECTING

internal fun ScreenShareController.ViewerPhase.isShareTerminal(): Boolean =
    this == ScreenShareController.ViewerPhase.ENDED ||
        this == ScreenShareController.ViewerPhase.REJECTED ||
        this == ScreenShareController.ViewerPhase.FAILED

@Composable
private fun formatShareKbps(value: Int?): String =
    if (value != null) {
        stringResource(R.string.share_bitrate_format, value)
    } else {
        stringResource(R.string.share_value_none)
    }

@Composable
private fun formatShareMs(value: Int?): String =
    if (value != null) {
        stringResource(R.string.share_ms_format, value)
    } else {
        stringResource(R.string.share_value_none)
    }

@Composable
private fun formatShareFps(value: Int): String =
    stringResource(R.string.share_fps_format, value)

@Composable
private fun formatShareLoss(value: Double?): String =
    if (value != null) {
        stringResource(R.string.share_percent_format, value)
    } else {
        stringResource(R.string.share_value_none)
    }

@Composable
private fun formatShareDecodeTime(value: Double?): String =
    if (value != null) {
        stringResource(R.string.share_decode_time_format, value)
    } else {
        stringResource(R.string.share_value_none)
    }

@Composable
private fun formatShareMb(value: Double?): String =
    if (value != null) {
        stringResource(R.string.share_mb_format, value)
    } else {
        stringResource(R.string.share_value_none)
    }

private tailrec fun Context.findPadActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPadActivity()
    else -> null
}
