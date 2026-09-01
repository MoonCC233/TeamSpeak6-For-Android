package com.mooncc.teamspeak9.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.Client
import com.mooncc.teamspeak9.domain.model.SpacerAlignment
import com.mooncc.teamspeak9.ui.theme.TsAway
import com.mooncc.teamspeak9.ui.theme.TsMuted
import com.mooncc.teamspeak9.ui.theme.TsOnSurfaceMuted
import com.mooncc.teamspeak9.ui.theme.TsOnSurfaceVariant
import com.mooncc.teamspeak9.ui.theme.TsTalking

private val INDENT_PER_LEVEL = 14.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    channel: Channel,
    isCollapsed: Boolean,
    hasChildren: Boolean,
    isCurrentChannel: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channel.isSpacer) {
        SpacerRow(channel = channel, modifier = modifier)
        return
    }

    val background = if (isCurrentChannel) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Official client marks the joined channel with a vertical accent bar.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(
                    if (isCurrentChannel) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(3.dp + INDENT_PER_LEVEL * channel.depth))

        if (hasChildren) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = if (isCollapsed) "展开" else "折叠",
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onToggleCollapse),
                tint = TsOnSurfaceMuted,
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }

        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (channel.isFull) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrentChannel) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (channel.hasPassword) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "需要密码",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (channel.isDefault) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "默认频道",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.weight(1f))

        if (channel.maxClients >= 0) {
            Text(
                text = "${channel.totalClients}/${channel.maxClients}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (channel.totalClients > 0) {
            Text(
                text = channel.totalClients.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpacerRow(channel: Channel, modifier: Modifier = Modifier) {
    val label = channel.spacerLabel
    when (channel.spacerAlignment) {
        SpacerAlignment.REPEAT -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .height(1.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        )
        else -> Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = when (channel.spacerAlignment) {
                SpacerAlignment.CENTER -> TextAlign.Center
                SpacerAlignment.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            },
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClientRow(
    client: Client,
    depth: Int,
    isLocal: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                start = 6.dp + INDENT_PER_LEVEL * depth + 24.dp,
                end = 10.dp,
                top = 5.dp,
                bottom = 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = statusIcon(client),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = statusTint(client),
        )
        Text(
            text = client.nickname,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isLocal) FontWeight.SemiBold else FontWeight.Normal,
            color = if (client.isAway) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (client.isChannelCommander) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = "频道指挥",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        if (client.isSharingScreen) {
            Icon(
                imageVector = Icons.Default.ScreenShare,
                contentDescription = "正在共享屏幕",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (client.isAway && client.awayMessage.isNotBlank()) {
            Text(
                text = "[${client.awayMessage}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun statusIcon(client: Client) = when {
    client.isSpeakerMuted -> Icons.Default.HeadsetOff
    client.isMicMuted -> Icons.Default.MicOff
    client.isTalking -> Icons.Default.Mic
    client.isAway -> Icons.Default.Person
    else -> Icons.Default.Headset
}

@Composable
private fun statusTint(client: Client): Color = when {
    client.isSpeakerMuted || client.isMicMuted -> TsMuted
    client.isTalking -> TsTalking
    client.isAway -> TsAway
    else -> TsOnSurfaceVariant
}
