package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.pad.R
import dev.tsdroid.viewmodel.ChatMessage
import dev.tslib.User

@Composable
fun ChatPanel(
    target: ChatTarget,
    threads: List<PrivateThread>,
    channelName: String,
    channelMessages: List<ChatMessage>,
    privateMessages: Map<String, List<ChatMessage>>,
    unreadChannel: Int,
    users: List<User>,
    onSelectTarget: (ChatTarget) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = when (target) {
        ChatTarget.Channel -> channelMessages
        is ChatTarget.Private -> privateMessages[target.key].orEmpty()
    }
    val privateOnline = target !is ChatTarget.Private ||
        users.any { it.uid == target.key }
    val title = when (target) {
        ChatTarget.Channel -> stringResource(R.string.chat_channel_title, channelName)
        is ChatTarget.Private -> target.name
    }
    val listState = rememberLazyListState()
    var draft by remember(target) { mutableStateOf("") }
    // Only follow new messages when the user is already reading the tail;
    // scrolling up to read history must not be yanked back down.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(target) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || !privateOnline) return
        onSend(text)
        draft = ""
    }

    PadCard(modifier, color = PadColors.Surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ChatTabItem(
                    icon = Icons.Rounded.Tag,
                    label = channelName,
                    active = target is ChatTarget.Channel,
                    unread = unreadChannel,
                    onClick = { onSelectTarget(ChatTarget.Channel) },
                )
                threads.forEach { thread ->
                    ChatTabItem(
                        icon = Icons.Rounded.ChatBubbleOutline,
                        label = thread.name,
                        active = target is ChatTarget.Private && target.key == thread.key,
                        unread = thread.unread,
                        onClick = { onSelectTarget(ChatTarget.Private(thread.key, thread.name)) },
                    )
                }
            }

            HorizontalDivider(color = PadColors.Border)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionKicker(
                        if (target is ChatTarget.Private) {
                            stringResource(R.string.chat_private)
                        } else {
                            stringResource(R.string.chat_channel)
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (target is ChatTarget.Private) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Tag,
                            contentDescription = null,
                            tint = PadColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = PadColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    stringResource(R.string.chat_message_count, messages.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Muted,
                )
            }

            HorizontalDivider(color = PadColors.Border)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                if (messages.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHint(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            title = if (target is ChatTarget.Private) {
                                stringResource(R.string.chat_empty_private_title)
                            } else {
                                stringResource(R.string.chat_empty_channel_title)
                            },
                            subtitle = if (target is ChatTarget.Private) {
                                stringResource(R.string.chat_empty_private_subtitle)
                            } else {
                                stringResource(R.string.chat_empty_channel_subtitle)
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(messages) { _, message ->
                            MessageRow(message = message)
                        }
                    }
                }
            }

            HorizontalDivider(color = PadColors.Border)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PadColors.SurfaceAlt)
                        .border(1.dp, PadColors.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 500) draft = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = PadColors.Text,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(PadColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { innerTextField ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = if (privateOnline) {
                                            if (target is ChatTarget.Private) {
                                                stringResource(R.string.chat_placeholder_private)
                                            } else {
                                                stringResource(R.string.chat_placeholder_channel)
                                            }
                                        } else {
                                            stringResource(R.string.chat_offline_placeholder)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PadColors.Muted.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (draft.isNotBlank() && privateOnline) {
                                PadColors.Accent
                            } else {
                                PadColors.SurfaceAlt
                            },
                        )
                        .clickable(enabled = draft.isNotBlank() && privateOnline) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.chat_send),
                        tint = if (draft.isNotBlank() && privateOnline) PadColors.OnAccent else PadColors.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTabItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    unread: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (active) PadColors.Accent.copy(alpha = 0.13f) else PadColors.SurfaceAlt)
            .border(
                1.dp,
                if (active) PadColors.Accent.copy(alpha = 0.35f) else PadColors.Border,
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) PadColors.Accent else PadColors.Muted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) PadColors.Accent else PadColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(PadColors.Red)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (unread > 99) {
                        stringResource(R.string.chat_unread_overflow)
                    } else {
                        unread.toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Page,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
) {
    val mine = message.isMe
    val bubbleShape = if (mine) {
        RoundedCornerShape(13.dp, 4.dp, 13.dp, 13.dp)
    } else {
        RoundedCornerShape(4.dp, 13.dp, 13.dp, 13.dp)
    }
    val senderName = if (mine) stringResource(R.string.common_you) else message.sender

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!mine) {
                PadAvatar(name = message.sender, size = 30.dp)
            }
            Column(
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!mine) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = PadColors.Text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = formatClock(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = PadColors.Muted.copy(alpha = 0.7f),
                    )
                    if (mine) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.common_you),
                            style = MaterialTheme.typography.labelSmall,
                            color = PadColors.Text,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .clip(bubbleShape)
                        .background(if (mine) PadColors.ChatMine else PadColors.SurfaceAlt)
                        .border(
                            1.dp,
                            if (mine) PadColors.Accent.copy(alpha = 0.18f) else PadColors.Border,
                            bubbleShape,
                        )
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = message.fileAttachment?.fileName ?: message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PadColors.Text,
                    )
                }
            }
            if (mine) {
                PadAvatar(name = stringResource(R.string.common_you), size = 30.dp)
            }
        }
    }
}
