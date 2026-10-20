package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.MicMode
import dev.tsdroid.pad.R

@Composable
fun AudioDock(
    micMode: MicMode,
    micMuted: Boolean,
    outputMuted: Boolean,
    micStatus: AudioBridge.MicStatus,
    onToggleMic: () -> Unit,
    onPushToTalk: (Boolean) -> Unit,
    onToggleOutput: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val topBorder = PadColors.Border
    Row(
        Modifier
            .fillMaxWidth()
            .background(PadColors.Surface)
            .drawBehind {
                drawLine(
                    color = topBorder,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (micMode == MicMode.PTT) {
            var pressed by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (pressed) PadColors.Accent else PadColors.SurfaceAlt)
                    .border(
                        1.dp,
                        if (pressed) PadColors.Accent else PadColors.Border,
                        RoundedCornerShape(13.dp),
                    )
                    .pointerInput(micMode) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                onPushToTalk(true)
                                tryAwaitRelease()
                                onPushToTalk(false)
                                pressed = false
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = stringResource(R.string.audio_ptt_hold),
                    tint = if (pressed) PadColors.OnAccent else PadColors.Text,
                    modifier = Modifier.size(21.dp),
                )
                MicStatusDot(micStatus, if (pressed) PadColors.Accent else PadColors.SurfaceAlt)
            }
        } else {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (micMuted) PadColors.Red.copy(alpha = 0.12f) else PadColors.Accent.copy(alpha = 0.14f))
                    .border(
                        1.dp,
                        if (micMuted) PadColors.Red.copy(alpha = 0.4f) else PadColors.Accent.copy(alpha = 0.4f),
                        RoundedCornerShape(13.dp),
                    )
                    .clickable(onClick = onToggleMic),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = if (micMuted) {
                        stringResource(R.string.mic_unmute)
                    } else {
                        stringResource(R.string.mic_mute)
                    },
                    tint = if (micMuted) PadColors.Red else PadColors.Accent,
                    modifier = Modifier.size(21.dp),
                )
                MicStatusDot(micStatus, if (micMuted) PadColors.Red.copy(alpha = 0.12f) else PadColors.Accent.copy(alpha = 0.14f))
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(PadColors.SurfaceAlt)
                .border(1.dp, PadColors.Border, RoundedCornerShape(13.dp))
                .clickable(onClick = onToggleOutput),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (outputMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (outputMuted) {
                    stringResource(R.string.output_unmute)
                } else {
                    stringResource(R.string.output_mute)
                },
                tint = if (outputMuted) PadColors.Red else PadColors.Text,
                modifier = Modifier.size(21.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = when (micMode) {
                    MicMode.PTT -> stringResource(R.string.audio_ptt_hold)
                    MicMode.VAD -> stringResource(R.string.audio_mode_vad)
                    MicMode.OPEN -> stringResource(R.string.audio_mode_open)
                },
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = micStatusLabel(micStatus, micMuted, micMode),
                style = MaterialTheme.typography.labelSmall,
                color = micStatusColor(micStatus),
            )
        }
        Spacer(Modifier.width(6.dp))
        PadIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.audio_settings),
            onClick = onOpenSettings,
            tint = PadColors.Muted,
            size = 38.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun BoxScope.MicStatusDot(
    status: AudioBridge.MicStatus,
    background: Color,
) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(x = (-5).dp, y = (-5).dp)
            .size(9.dp)
            .clip(CircleShape)
            .background(micStatusColor(status))
            .border(2.dp, background, CircleShape),
    )
}

private fun micStatusColor(status: AudioBridge.MicStatus): Color = when (status) {
    AudioBridge.MicStatus.RUNNING -> PadColors.Green
    AudioBridge.MicStatus.STARTING,
    AudioBridge.MicStatus.RECOVERING -> PadColors.Amber
    AudioBridge.MicStatus.UNAVAILABLE -> PadColors.Red
    AudioBridge.MicStatus.IDLE -> PadColors.Muted
}

@Composable
private fun micStatusLabel(
    status: AudioBridge.MicStatus,
    micMuted: Boolean,
    micMode: MicMode,
): String = when (status) {
    AudioBridge.MicStatus.RUNNING -> if (micMuted && micMode != MicMode.PTT) {
        stringResource(R.string.status_muted)
    } else {
        stringResource(R.string.status_mic_ready)
    }
    AudioBridge.MicStatus.STARTING -> stringResource(R.string.status_mic_starting)
    AudioBridge.MicStatus.RECOVERING -> stringResource(R.string.status_mic_recovering)
    AudioBridge.MicStatus.UNAVAILABLE -> stringResource(R.string.status_mic_unavailable)
    AudioBridge.MicStatus.IDLE -> stringResource(R.string.status_mic_idle)
}
