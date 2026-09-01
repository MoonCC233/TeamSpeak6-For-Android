package com.mooncc.teamspeak6.ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mooncc.teamspeak6.domain.model.RemoteShare
import com.mooncc.teamspeak6.domain.model.ScreenShareMode
import com.mooncc.teamspeak6.domain.model.ScreenShareSignalingState
import com.mooncc.teamspeak6.domain.model.ScreenShareState
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Screen-share tab: the signaling status, pending viewer requests, and one card
 * per share in the current channel with the live video once watched.
 */
@Composable
fun ScreenSharePane(
    state: ScreenShareState,
    remoteTracks: Map<String, VideoTrack>,
    eglBaseContext: EglBase.Context,
    onWatch: (String) -> Unit,
    onStopWatching: (String) -> Unit,
    onApproveViewer: (String) -> Unit,
    onDenyViewer: (String) -> Unit,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SignalingBanner(state = state, onOpenOptions = onOpenOptions) }

        if (state.pendingViewerRequests.isNotEmpty()) {
            items(state.pendingViewerRequests, key = { it.peerId }) { request ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${request.nickname} 想观看你的屏幕",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onApproveViewer(request.peerId) }) {
                                Text("允许")
                            }
                            TextButton(onClick = { onDenyViewer(request.peerId) }) {
                                Text("拒绝")
                            }
                        }
                    }
                }
            }
        }

        if (state.remoteShares.isEmpty()) {
            item { EmptyShares(state = state) }
        } else {
            items(state.remoteShares, key = { it.publisherId }) { share ->
                RemoteShareCard(
                    share = share,
                    track = remoteTracks[share.publisherId],
                    eglBaseContext = eglBaseContext,
                    onWatch = { onWatch(share.publisherId) },
                    onStopWatching = { onStopWatching(share.publisherId) },
                )
            }
        }
    }
}

@Composable
private fun SignalingBanner(state: ScreenShareState, onOpenOptions: () -> Unit) {
    val (label, detail) = when {
        !state.isConfigured -> "未配置信令服务" to "在设置中填写信令服务地址后才能共享或观看屏幕"
        state.signaling == ScreenShareSignalingState.ONLINE ->
            "信令已连接" to signalingDetail(state)
        state.signaling == ScreenShareSignalingState.CONNECTING -> "正在连接信令服务" to state.signalingUrl
        state.signaling == ScreenShareSignalingState.RECONNECTING -> "信令重连中" to state.signalingUrl
        state.signaling == ScreenShareSignalingState.FAILED ->
            "信令连接失败" to (state.errorMessage ?: state.signalingUrl)
        else -> "信令离线" to state.signalingUrl
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenOptions) { Text("共享选项") }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isSharing) {
                Text(
                    text = "正在共享屏幕 · ${state.viewerCount} 位观众 · " +
                        if (state.config.mode == ScreenShareMode.SERVER) "服务器中转" else "P2P",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun signalingDetail(state: ScreenShareState): String {
    val relay = if (state.serverModeAvailable) "支持服务器中转" else "仅支持 P2P"
    val roomLabel = if (state.roomId.isNotBlank()) " · room=${state.roomId}" else ""
    return "${state.signalingUrl} · $relay$roomLabel"
}

@Composable
private fun EmptyShares(state: ScreenShareState) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (state.isConfigured) {
                    Icons.Default.ScreenShare
                } else {
                    Icons.Default.CloudOff
                },
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("当前频道没有人共享屏幕", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "屏幕共享使用本项目自有协议，仅能与同样实现该协议的客户端互通，" +
                    "无法与 TeamSpeak 官方客户端互看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RemoteShareCard(
    share: RemoteShare,
    track: VideoTrack?,
    eglBaseContext: EglBase.Context,
    onWatch: () -> Unit,
    onStopWatching: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(share.nickname, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = describeShare(share),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    share.isConnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )

                    track != null -> OutlinedButton(onClick = onStopWatching) { Text("停止观看") }
                    else -> Button(onClick = onWatch) { Text("观看") }
                }
            }

            if (track != null) {
                VideoRenderer(
                    track = track,
                    eglBaseContext = eglBaseContext,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }

            if (share.hasAudio) {
                AssistChip(onClick = {}, label = { Text("含音频") })
            }
        }
    }
}

private fun describeShare(share: RemoteShare): String = buildList {
    add(if (share.mode == ScreenShareMode.SERVER) "服务器中转" else "P2P")
    if (share.resolutionLabel.isNotEmpty()) add(share.resolutionLabel)
    if (share.fps > 0) add("${share.fps} FPS")
    if (share.bitrateKbps > 0) add("${share.bitrateKbps} Kbps")
}.joinToString(" · ")

/**
 * Renders a [VideoTrack] into a [SurfaceViewRenderer].
 *
 * The track must be detached before the renderer is released, otherwise libwebrtc
 * keeps delivering frames into a dead surface.
 */
@Composable
private fun VideoRenderer(
    track: VideoTrack,
    eglBaseContext: EglBase.Context,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                track.addSink(this)
            }
        },
        onRelease = { view ->
            track.removeSink(view)
            view.release()
        },
    )
}
