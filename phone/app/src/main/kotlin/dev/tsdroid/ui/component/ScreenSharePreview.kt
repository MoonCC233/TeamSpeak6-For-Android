package dev.tsdroid.ui.component

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.tsdroid.core.R
import dev.tsdroid.screen.ScreenShareController
import dev.tsdroid.ui.icon.AppIcons
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

/**
 * 首页顶部的屏幕共享实时预览（仿桌面端「共享的屏幕」卡片）。
 *
 * 与旧版「屏幕共享: 屏幕 0 → 观看」横幅的区别：
 * - 有直播流时自动建立观看会话并把画面直接渲染在卡片里（无需再点「观看」）；
 * - 卡片显示发布者昵称（不再显示流名称「屏幕 0」）；
 * - 左下角常驻实时预览，右下角提供连接信息 / 全屏 / 退出观看三个入口；
 * - 全屏观看窗口与预览共用同一个 WebRTC 会话（预览卡片持有会话生命周期），
 *   进/出全屏不会重新协商。
 */
@Composable
fun ScreenSharePreviewSection(
    streams: List<ScreenShareController.RemoteStream>,
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
    if (streams.size == 1) {
        val stream = streams.first()
        ScreenSharePreviewCard(
            stream = stream,
            displayName = displayNameOf(stream),
            phase = phases[stream.streamId] ?: ScreenShareController.ViewerPhase.JOINING,
            stats = stats[stream.streamId],
            active = activeStreamId != stream.streamId,
            resumed = resumed,
            onExpand = { onExpand(stream) },
            onInfo = { onInfo(stream) },
            onStopWatching = { onStop(stream) },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .aspectRatio(16f / 9f),
        )
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            streams.forEach { stream ->
                ScreenSharePreviewCard(
                    stream = stream,
                    displayName = displayNameOf(stream),
                    phase = phases[stream.streamId] ?: ScreenShareController.ViewerPhase.JOINING,
                    stats = stats[stream.streamId],
                    active = activeStreamId != stream.streamId,
                    resumed = resumed,
                    onExpand = { onExpand(stream) },
                    onInfo = { onInfo(stream) },
                    onStopWatching = { onStop(stream) },
                    modifier = Modifier
                        .width(280.dp)
                        .aspectRatio(16f / 9f),
                )
            }
        }
    }
}

/**
 * 单个共享流预览卡片。
 *
 * [active] 为 false 表示该流正在全屏观看窗口中显示：此时卡片只保留会话，
 * 解挂自己的渲染器，回到预览时再挂回去。
 */
@Composable
fun ScreenSharePreviewCard(
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

    // 会话生命周期属于预览卡片：卡片消失（用户退出观看 / 流结束）时关闭会话
    DisposableEffect(stream.streamId) {
        onDispose {
            runCatching { ScreenShareController.removeStreamRenderer(stream.streamId, renderer) }
            ScreenShareController.leaveStream(stream.streamId)
            runCatching { renderer.release() }
            runCatching { eglBase.release() }
        }
    }

    // 全屏期间解挂渲染器，回到预览再挂回；应用退到后台时释放会话，回前台重新加入
    DisposableEffect(stream.streamId, active, resumed) {
        when {
            !resumed -> ScreenShareController.leaveStream(stream.streamId)
            active -> ScreenShareController.joinStream(stream.streamId, stream.clientId, renderer)
            else -> ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
        }
        onDispose {
            ScreenShareController.removeStreamRenderer(stream.streamId, renderer)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0E0E12),
        contentColor = Color.White,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VideoSurface(
                renderer = renderer,
                stats = stats,
                fillMode = false,
                modifier = Modifier.fillMaxSize(),
            )

            // 单击画面 = 全屏观看（按钮各自消费点击）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onExpand),
            )

            // 连接中 / 已结束等阶段提示
            if (phase != ScreenShareController.ViewerPhase.WATCHING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        viewerStatusRes(phase)?.let { res ->
                            Text(
                                text = stringResource(res),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (phase.isBusyPhase()) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        }
                        if (phase.isTerminalPhase()) {
                            TextButton(onClick = onStopWatching) {
                                Text(stringResource(R.string.screen_share_close), color = Color.White)
                            }
                        }
                    }
                }
            }

            // 左上角直播角标
            LiveBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            )

            // 底部渐变：发布者昵称 + 连接信息 / 全屏 / 退出观看
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                        ),
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 24.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PreviewControlButton(
                    icon = AppIcons.Info,
                    contentDescription = stringResource(R.string.screen_share_connection_info),
                    onClick = onInfo,
                )
                PreviewControlButton(
                    icon = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.screen_share_watch_fullscreen),
                    onClick = onExpand,
                )
                PreviewControlButton(
                    icon = AppIcons.Exit,
                    contentDescription = stringResource(R.string.screen_share_stop_watching),
                    onClick = onStopWatching,
                    tint = Color(0xFFFF7A7A),
                )
            }

            // 质量角标（右上是信息密度最低的位置，避开底部按钮）
            stats?.let { s ->
                val quality = formatQuality(s)
                if (quality != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                    ) {
                        Text(
                            text = quality,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 让 SurfaceViewRenderer 的 view 尺寸始终与视频宽高比一致。
 *
 * WebRTC 的 SurfaceViewRenderer 在测量阶段使用 ScalingType 计算尺寸；一旦外层
 * 用 fillMaxSize() 强制成任意宽高（例如竖屏全屏），它会直接把画面拉伸到 surface，
 * 造成「人物变瘦 / 任务栏变高」。这里根据远端视频分辨率显式算出显示尺寸：
 * - fit：完整显示，留黑边；
 * - fill：按比例放大到铺满，超出父容器的部分由窗口裁掉。
 */
@Composable
internal fun VideoSurface(
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
            val w = stats?.width ?: 0
            val h = stats?.height ?: 0
            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 16f / 9f
        }
        val containerAspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            videoAspect
        }
        val (videoWidth, videoHeight) = if (videoAspect >= containerAspect) {
            // 视频比容器宽：fit 以宽为准，fill 以高为准
            if (fillMode) {
                (maxHeight * videoAspect) to maxHeight
            } else {
                maxWidth to (maxWidth / videoAspect)
            }
        } else {
            // 视频比容器高：fit 以高为准，fill 以宽为准
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

/** 红色圆点 + 「直播」小角标 */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.screen_share_live_badge),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 预览卡片右下角的圆形半透明控制按钮 */
@Composable
private fun PreviewControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Surface(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 屏幕共享连接信息面板（对应桌面端「连接信息」对话框）。
 *
 * 展示观看链路的实时指标：下游码率 / 抖动 / 解码耗时 / 丢包率 / 重传率、
 * 分辨率与帧率、解码器、累计收包与重传数据，另有音频页签。
 */
@Composable
fun ScreenShareConnectionInfoDialog(
    displayName: String,
    audioSupported: Boolean,
    stats: ScreenShareController.ViewerStats?,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.screen_share_connection_info),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.screen_share_close),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.screen_share_info_video)) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.screen_share_info_audio)) },
                    )
                }
                Spacer(Modifier.height(16.dp))

                when {
                    stats == null -> Text(
                        text = stringResource(R.string.screen_share_info_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    tab == 0 -> VideoStatsContent(stats)

                    else -> if (audioSupported || stats.audioSupported) {
                        AudioStatsContent(stats)
                    } else {
                        Text(
                            text = stringResource(R.string.screen_share_info_no_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoStatsContent(stats: ScreenShareController.ViewerStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = stringResource(R.string.screen_share_info_downstream),
            value = formatKbps(stats.bitrateKbps),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.screen_share_info_loss),
            value = formatLoss(stats.lossPercent),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = stringResource(R.string.screen_share_info_jitter),
            value = formatMs(stats.jitterMs),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.screen_share_info_retransmit_rate),
            value = formatKbps(stats.retransmitKbps),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = stringResource(R.string.screen_share_info_decode_time),
            value = formatDecodeMs(stats.decodeTimeMs),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.screen_share_info_rtt),
            value = formatMs(stats.rttMs),
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(14.dp))
    InfoLine(
        label = stringResource(R.string.screen_share_info_packets_received),
        value = stats.packetsReceived?.toString() ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_total_received),
        value = formatMb(stats.totalReceivedMB),
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_packets_lost),
        value = stats.packetsLost?.toString() ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_quality),
        value = formatQuality(stats) ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_decoder),
        value = formatDecoder(stats),
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_retransmitted_packets),
        value = stats.retransmittedPackets?.toString() ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_retransmitted_total),
        value = formatMb(stats.retransmittedMB),
    )
}

@Composable
private fun AudioStatsContent(stats: ScreenShareController.ViewerStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = stringResource(R.string.screen_share_info_downstream),
            value = formatKbps(stats.audioBitrateKbps),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.screen_share_info_loss),
            value = formatLoss(stats.audioLossPercent),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    InfoLine(
        label = stringResource(R.string.screen_share_info_jitter),
        value = formatMs(stats.audioJitterMs),
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_packets_received),
        value = stats.audioPacketsReceived?.toString() ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_packets_lost),
        value = stats.audioPacketsLost?.toString() ?: "—",
    )
    InfoLine(
        label = stringResource(R.string.screen_share_info_decoder),
        value = stats.audioCodec ?: "—",
    )
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ==================== 数值格式化 ====================

internal fun formatKbps(value: Int?): String =
    value?.let { "$it Kbps" } ?: "—"

internal fun formatMs(value: Int?): String =
    value?.let { "$it ms" } ?: "—"

private fun formatDecodeMs(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"

private fun formatMb(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f MB", it) } ?: "—"

private fun formatLoss(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"

internal fun formatQuality(stats: ScreenShareController.ViewerStats): String? {
    val width = stats.width
    val height = stats.height
    if (width == null || height == null) return null
    val fps = stats.fps?.toInt()
    return if (fps != null) "$width x $height  $fps fps" else "$width x $height"
}

private fun formatDecoder(stats: ScreenShareController.ViewerStats): String {
    val name = stats.decoder ?: stats.codec ?: return "—"
    return when (stats.hardwareDecoder) {
        true -> "$name (HW)"
        false -> "$name (SW)"
        null -> name
    }
}
