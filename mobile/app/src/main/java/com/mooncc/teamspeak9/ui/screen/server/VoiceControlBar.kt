package com.mooncc.teamspeak9.ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak9.domain.model.LocalMediaState
import com.mooncc.teamspeak9.ui.theme.TsChrome
import com.mooncc.teamspeak9.ui.theme.TsOnSurfaceMuted
import com.mooncc.teamspeak9.ui.theme.TsOnSurfaceVariant
import com.mooncc.teamspeak9.ui.theme.TsSurfaceVariant

@Composable
fun VoiceControlBar(
    media: LocalMediaState,
    channelName: String,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onTogglePushToTalk: () -> Unit,
    onPushToTalkPressed: (Boolean) -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleChannelCommander: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TsChrome)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Forum,
                contentDescription = null,
                tint = TsOnSurfaceMuted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = channelName.ifBlank { "未加入频道" },
                style = MaterialTheme.typography.labelSmall,
                color = TsOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToggleButton(
                icon = if (media.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = "麦克风",
                active = !media.micMuted,
                danger = media.micMuted,
                onClick = onToggleMic,
            )
            ToggleButton(
                icon = if (media.speakerMuted) Icons.Default.HeadsetOff else Icons.Default.Headset,
                label = "扬声器",
                active = !media.speakerMuted,
                danger = media.speakerMuted,
                onClick = onToggleSpeaker,
            )
            ToggleButton(
                icon = Icons.Default.KeyboardVoice,
                label = "按键说话",
                active = media.pushToTalkEnabled,
                onClick = onTogglePushToTalk,
            )
            ToggleButton(
                icon = if (media.isSharingScreen) {
                    Icons.Default.StopScreenShare
                } else {
                    Icons.Default.ScreenShare
                },
                label = "共享屏幕",
                active = media.isSharingScreen,
                onClick = onToggleScreenShare,
            )
            ToggleButton(
                icon = Icons.Default.Campaign,
                label = "指挥",
                active = media.isChannelCommander,
                onClick = onToggleChannelCommander,
            )
        }

        if (media.pushToTalkEnabled) {
            PushToTalkButton(active = media.pushToTalkActive, onPressed = onPushToTalkPressed)
        }
    }
}

@Composable
private fun ToggleButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val containerColor = when {
            danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            else -> TsSurfaceVariant
        }
        val contentColor = when {
            danger -> MaterialTheme.colorScheme.error
            active -> MaterialTheme.colorScheme.primary
            else -> TsOnSurfaceVariant
        }
        FilledIconButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TsOnSurfaceMuted,
        )
    }
}

@Composable
private fun PushToTalkButton(active: Boolean, onPressed: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    TsSurfaceVariant
                },
                RoundedCornerShape(8.dp),
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPressed(true)
                    waitForUpOrCancellation()
                    onPressed(false)
                }
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (active) "正在说话…" else "按住说话",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                TsOnSurfaceVariant
            },
        )
    }
}
