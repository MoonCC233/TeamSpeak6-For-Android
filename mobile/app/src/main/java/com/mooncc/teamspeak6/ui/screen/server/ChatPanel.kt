package com.mooncc.teamspeak6.ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak6.domain.model.ChatMessage
import com.mooncc.teamspeak6.domain.model.ChatTarget
import com.mooncc.teamspeak6.domain.model.Conversation
import com.mooncc.teamspeak6.domain.model.DeliveryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    conversations: List<Conversation>,
    active: Conversation?,
    onSelectConversation: (Conversation) -> Unit,
    onCloseConversation: (Conversation) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyRowTabs(
            conversations = conversations,
            active = active,
            onSelect = onSelectConversation,
            onClose = onCloseConversation,
        )

        val messages = active?.messages.orEmpty()
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, active?.key) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (active == null) "没有打开的会话" else "还没有消息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message) }
            }
        }

        MessageComposer(enabled = active != null, onSend = onSend)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LazyRowTabs(
    conversations: List<Conversation>,
    active: Conversation?,
    onSelect: (Conversation) -> Unit,
    onClose: (Conversation) -> Unit,
) {
    if (conversations.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(conversations, key = { it.key }) { conversation ->
            val isActive = conversation.key == active?.key
            FilterChip(
                selected = isActive,
                onClick = { onSelect(conversation) },
                label = {
                    BadgedBox(
                        badge = {
                            if (conversation.unreadCount > 0 && !isActive) {
                                Badge { Text(conversation.unreadCount.coerceAtMost(99).toString()) }
                            }
                        },
                    ) { Text(conversation.title) }
                },
                trailingIcon = if (conversation.target != ChatTarget.SERVER) {
                    {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭会话",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onClose(conversation) },
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = when {
        message.isSystem -> MaterialTheme.colorScheme.surfaceVariant
        message.isOutgoing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!message.isOutgoing && !message.isSystem) {
                Text(
                    text = message.senderNickname,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = timeFormat.format(Date(message.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.deliveryState == DeliveryState.FAILED) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "发送失败",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.deliveryState == DeliveryState.SENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun MessageComposer(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("输入消息…") },
            enabled = enabled,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            enabled = enabled && text.isNotBlank(),
            onClick = {
                onSend(text)
                text = ""
            },
        ) {
            Icon(Icons.Default.Send, contentDescription = "发送")
        }
    }
}
