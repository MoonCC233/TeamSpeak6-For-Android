package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.pad.R
import dev.tslib.Channel
import dev.tslib.User

private data class ChannelNode(val channel: Channel, val depth: Int)

private fun buildChannelTree(channels: List<Channel>): List<ChannelNode> {
    val byParent = channels.groupBy { it.parentId }
    val result = mutableListOf<ChannelNode>()
    val seen = mutableSetOf<Long>()

    fun visit(parentId: Long, depth: Int) {
        val children = byParent[parentId]
            .orEmpty()
            .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
        for (channel in children) {
            if (!seen.add(channel.id)) continue
            result.add(ChannelNode(channel, depth))
            visit(channel.id, depth + 1)
        }
    }

    visit(0L, 0)
    // Defensive: show channels whose parent is not in the snapshot.
    channels.filter { it.id !in seen }
        .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
        .forEach {
            seen.add(it.id)
            result.add(ChannelNode(it, 0))
        }
    return result
}

@Composable
fun PeoplePanel(
    users: List<User>,
    channels: List<Channel>,
    currentChannelId: Long?,
    myClientId: Int?,
    talkingIds: Set<Int>,
    mutedUserIds: Set<Int>,
    streamingUserIds: Set<Int>,
    avatars: Map<String, ImageBitmap>,
    onSelectChannel: (Channel) -> Unit,
    onMemberClick: (User) -> Unit,
    modifier: Modifier = Modifier,
) {
    val humanUsers = remember(users) { users.filter { !it.isQuery() } }
    val membersByChannel = remember(humanUsers) { humanUsers.groupBy { it.channelId } }
    val tree = remember(channels) { buildChannelTree(channels) }
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim()

    val visibleGroups = remember(tree, membersByChannel, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            tree.map { it to membersByChannel[it.channel.id].orEmpty() }
        } else {
            tree.mapNotNull { node ->
                val all = membersByChannel[node.channel.id].orEmpty()
                val matches = all.filter { it.nickname.contains(normalizedQuery, ignoreCase = true) }
                val channelMatches = node.channel.name.contains(normalizedQuery, ignoreCase = true)
                when {
                    channelMatches -> node to all
                    matches.isNotEmpty() -> node to matches
                    else -> null
                }
            }
        }
    }

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.people_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = PadColors.Text,
                )
                Text(
                    stringResource(R.string.people_summary, humanUsers.size, channels.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Muted,
                )
            }
            StatusPill(stringResource(R.string.common_online), PadColors.Green)
        }

        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PadColors.SurfaceAlt)
                .border(1.dp, PadColors.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = PadColors.Muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = PadColors.Text,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(PadColors.Accent),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.people_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = PadColors.Muted.copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                PadIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.people_clear_search),
                    onClick = { query = "" },
                    tint = PadColors.Muted,
                    size = 24.dp,
                    iconSize = 14.dp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(
                items = visibleGroups,
                key = { it.first.channel.id },
            ) { (node, members) ->
                ChannelGroup(
                    node = node,
                    members = members,
                    current = node.channel.id == currentChannelId,
                    myClientId = myClientId,
                    talkingIds = talkingIds,
                    mutedUserIds = mutedUserIds,
                    streamingUserIds = streamingUserIds,
                    avatars = avatars,
                    onSelectChannel = onSelectChannel,
                    onMemberClick = onMemberClick,
                )
            }
            if (visibleGroups.isEmpty()) {
                item {
                    Text(
                        if (normalizedQuery.isEmpty()) {
                            stringResource(R.string.people_empty_channels)
                        } else {
                            stringResource(R.string.people_empty_search)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelGroup(
    node: ChannelNode,
    members: List<User>,
    current: Boolean,
    myClientId: Int?,
    talkingIds: Set<Int>,
    mutedUserIds: Set<Int>,
    streamingUserIds: Set<Int>,
    avatars: Map<String, ImageBitmap>,
    onSelectChannel: (Channel) -> Unit,
    onMemberClick: (User) -> Unit,
) {
    Column(Modifier.padding(start = (node.depth * 9).dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (current) PadColors.Accent.copy(alpha = 0.12f) else PadColors.Surface)
                .clickable { onSelectChannel(node.channel) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (node.channel.hasPassword) Icons.Rounded.Lock else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = if (current) PadColors.Accent else PadColors.Muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = node.channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (current) PadColors.Accent else PadColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${members.size}",
                style = MaterialTheme.typography.labelSmall,
                color = if (current) PadColors.Accent else PadColors.Muted,
            )
        }

        if (members.isEmpty()) {
            Text(
                stringResource(R.string.people_channel_empty),
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Muted.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 30.dp, top = 3.dp, bottom = 5.dp),
            )
        } else {
            members.forEach { member ->
                MemberRow(
                    member = member,
                    isSelf = member.id == myClientId,
                    speaking = member.id in talkingIds,
                    locallyMuted = member.id in mutedUserIds,
                    streaming = member.id in streamingUserIds,
                    avatar = member.uid?.let { avatars[it] },
                    onClick = { onMemberClick(member) },
                )
            }
        }
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun MemberRow(
    member: User,
    isSelf: Boolean,
    speaking: Boolean,
    locallyMuted: Boolean,
    streaming: Boolean,
    avatar: ImageBitmap?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (speaking) PadColors.Green.copy(alpha = 0.08f) else PadColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadAvatar(
            name = member.nickname,
            size = 30.dp,
            image = avatar,
            speaking = speaking,
            showPresence = true,
            presenceColor = if (member.isAway) PadColors.Amber else PadColors.Green,
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isSelf) {
                    stringResource(R.string.people_self_name, member.nickname)
                } else {
                    member.nickname
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (speaking) PadColors.Green else PadColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (speaking) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                text = presenceText(member, speaking),
                style = MaterialTheme.typography.labelSmall,
                color = if (speaking) PadColors.Green.copy(alpha = 0.8f) else PadColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) {
                PadLiveBadge(
                    label = stringResource(R.string.share_live_short),
                    compact = true,
                )
            }
            if (member.isAway) {
                TinyFlag(Icons.Rounded.Schedule, stringResource(R.string.presence_away), PadColors.Amber)
            }
            if (member.isChannelCommander) {
                TinyFlag(
                    Icons.Rounded.Shield,
                    stringResource(R.string.flag_channel_commander),
                    PadColors.Accent,
                )
            }
            if (locallyMuted || member.isInputMuted) {
                TinyFlag(
                    Icons.Rounded.MicOff,
                    if (locallyMuted) {
                        stringResource(R.string.flag_locally_muted)
                    } else {
                        stringResource(R.string.presence_mic_muted)
                    },
                    if (locallyMuted) PadColors.Red else PadColors.Amber,
                )
            }
            if (member.isOutputMuted) {
                TinyFlag(
                    Icons.AutoMirrored.Rounded.VolumeOff,
                    stringResource(R.string.flag_output_muted),
                    PadColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun TinyFlag(icon: ImageVector, description: String, color: androidx.compose.ui.graphics.Color) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = color.copy(alpha = 0.85f),
        modifier = Modifier.size(12.dp),
    )
}

@Composable
private fun presenceText(member: User, speaking: Boolean): String = when {
    speaking -> stringResource(R.string.presence_speaking)
    member.isAway -> stringResource(R.string.presence_away)
    member.isInputMuted -> stringResource(R.string.presence_mic_muted)
    else -> stringResource(R.string.common_connected)
}
