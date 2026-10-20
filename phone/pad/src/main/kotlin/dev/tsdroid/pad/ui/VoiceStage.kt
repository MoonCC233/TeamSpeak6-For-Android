package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.pad.R
import dev.tslib.User
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceStage(
    members: List<User>,
    channelName: String,
    myClientId: Int?,
    talkingIds: Set<Int>,
    localTalking: Boolean,
    mutedUserIds: Set<Int>,
    avatars: Map<String, ImageBitmap>,
    onMemberClick: (User) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyoneSpeaking = localTalking || members.any { it.id != myClientId && it.id in talkingIds }

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SectionKicker(stringResource(R.string.voice_activity_kicker))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = PadColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.voice_online_count, members.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Muted,
                    )
                }
            }
            StatusPill(
                text = if (anyoneSpeaking) {
                    stringResource(R.string.voice_speaking)
                } else {
                    stringResource(R.string.voice_waiting)
                },
                color = if (anyoneSpeaking) PadColors.Green else PadColors.Muted,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (members.isEmpty()) {
            PadCard(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyHint(
                        icon = Icons.Rounded.Groups,
                        title = stringResource(R.string.voice_empty_title),
                        subtitle = stringResource(R.string.voice_empty_subtitle),
                    )
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 12.dp
                val minCardWidth = 150.dp
                val columns = max(1, ((maxWidth + gap) / (minCardWidth + gap)).toInt())
                val cardWidth = (maxWidth - gap * (columns - 1)) / columns

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    maxItemsInEachRow = columns,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    members.forEach { member ->
                        val speaking = member.id in talkingIds ||
                            (member.id == myClientId && localTalking)
                        MemberCard(
                            member = member,
                            isSelf = member.id == myClientId,
                            speaking = speaking,
                            muted = member.id in mutedUserIds || member.isInputMuted,
                            avatar = member.uid?.let { avatars[it] },
                            onClick = { onMemberClick(member) },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: User,
    isSelf: Boolean,
    speaking: Boolean,
    muted: Boolean,
    avatar: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(162.dp)
            .clip(shape)
            .background(if (speaking) PadColors.Green.copy(alpha = 0.055f) else PadColors.Surface)
            .border(
                1.dp,
                if (speaking) PadColors.Green.copy(alpha = 0.5f) else PadColors.Border,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (isSelf) {
                Text(
                    text = stringResource(R.string.common_you),
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Accent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(50))
                        .background(PadColors.Accent.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (muted) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(PadColors.Page.copy(alpha = 0.75f))
                        .border(
                            1.dp,
                            PadColors.Border,
                            RoundedCornerShape(7.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MicOff,
                        contentDescription = stringResource(R.string.presence_mic_muted),
                        tint = PadColors.Red,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        PadAvatar(
            name = member.nickname,
            size = 62.dp,
            image = avatar,
            speaking = speaking,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isSelf) stringResource(R.string.common_you) else member.nickname,
            style = MaterialTheme.typography.titleSmall,
            color = PadColors.Text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                speaking -> stringResource(R.string.presence_speaking)
                isSelf -> stringResource(R.string.voice_self_connected)
                else -> stringResource(R.string.common_connected)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                speaking -> PadColors.Green
                else -> PadColors.Muted
            },
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
    }
}
