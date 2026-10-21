package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tsdroid.pad.R
import dev.tslib.User
import kotlin.math.roundToInt

@Composable
fun MemberActionsDialog(
    member: User,
    avatar: ImageBitmap?,
    speaking: Boolean,
    mutedLocally: Boolean,
    volumeDb: Float,
    canDm: Boolean,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    onToggleLocalMute: () -> Unit,
    onVolumeChange: (Float, Boolean) -> Unit,
    onOpenDm: () -> Unit,
    onWatchStream: () -> Unit,
) {
    var volume by remember(member.id) { mutableFloatStateOf(volumeDb) }
    val presence = when {
        speaking -> stringResource(R.string.presence_speaking)
        member.isAway -> stringResource(R.string.presence_away)
        member.isInputMuted -> stringResource(R.string.presence_mic_muted)
        else -> stringResource(R.string.common_connected)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(410.dp),
            shape = RoundedCornerShape(20.dp),
            color = PadColors.Surface,
            contentColor = PadColors.Text,
            border = androidx.compose.foundation.BorderStroke(1.dp, PadColors.Border),
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PadAvatar(
                        name = member.nickname,
                        size = 52.dp,
                        image = avatar,
                        speaking = speaking,
                        showPresence = true,
                        presenceColor = if (member.isAway) PadColors.Amber else PadColors.Green,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            member.nickname,
                            style = MaterialTheme.typography.titleLarge,
                            color = PadColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            presence,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (speaking) PadColors.Green else PadColors.Muted,
                        )
                    }
                    PadIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        tint = PadColors.Muted,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }

                HorizontalDivider(color = PadColors.Border)

                if (isStreaming) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(PadColors.Red.copy(alpha = 0.09f))
                            .border(
                                1.dp,
                                PadColors.Red.copy(alpha = 0.28f),
                                RoundedCornerShape(13.dp),
                            )
                            .clickable(onClick = onWatchStream)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PadColors.Red.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ScreenShare,
                                contentDescription = null,
                                tint = PadColors.Red,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.member_action_watch_stream),
                                style = MaterialTheme.typography.titleSmall,
                                color = PadColors.Text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.member_action_watch_stream_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = PadColors.Muted,
                            )
                        }
                        PadLiveBadge(
                            label = stringResource(R.string.share_live),
                            compact = true,
                        )
                    }
                    HorizontalDivider(color = PadColors.Border)
                }

                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.member_volume),
                            style = MaterialTheme.typography.labelMedium,
                            color = PadColors.Muted,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                mutedLocally -> stringResource(R.string.member_volume_muted)
                                volume.roundToInt() == 0 -> stringResource(R.string.member_volume_default)
                                volume > 0 -> stringResource(
                                    R.string.member_volume_positive,
                                    volume.roundToInt(),
                                )
                                else -> stringResource(R.string.value_db, volume.roundToInt())
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mutedLocally) PadColors.Red else PadColors.Accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            onVolumeChange(it, false)
                        },
                        onValueChangeFinished = {
                            onVolumeChange(volume, true)
                        },
                        valueRange = -20f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = PadColors.Accent,
                            activeTrackColor = PadColors.Accent,
                            inactiveTrackColor = PadColors.Border,
                        ),
                    )
                    Text(
                        stringResource(R.string.member_volume_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Muted.copy(alpha = 0.75f),
                    )
                }

                HorizontalDivider(color = PadColors.Border)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MemberActionButton(
                        icon = if (mutedLocally) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        label = if (mutedLocally) {
                            stringResource(R.string.member_action_unmute_local)
                        } else {
                            stringResource(R.string.member_action_mute_local)
                        },
                        tint = if (mutedLocally) PadColors.Red else PadColors.Text,
                        onClick = onToggleLocalMute,
                        modifier = Modifier.weight(1f),
                    )
                    if (canDm) {
                        MemberActionButton(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            label = stringResource(R.string.member_action_dm),
                            tint = PadColors.Accent,
                            onClick = onOpenDm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (tint == PadColors.Accent) PadColors.Accent.copy(alpha = 0.12f) else PadColors.SurfaceAlt)
            .border(
                1.dp,
                if (tint == PadColors.Accent) PadColors.Accent.copy(alpha = 0.3f) else PadColors.Border,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}
