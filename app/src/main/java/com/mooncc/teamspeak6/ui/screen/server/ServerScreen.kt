package com.mooncc.teamspeak6.ui.screen.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mooncc.teamspeak6.domain.model.Channel
import com.mooncc.teamspeak6.domain.model.ChannelTreeRow
import com.mooncc.teamspeak6.domain.model.ChatTarget
import com.mooncc.teamspeak6.domain.model.Client
import com.mooncc.teamspeak6.domain.model.ConnectionStatus
import com.mooncc.teamspeak6.domain.model.ServerEvent
import com.mooncc.teamspeak6.ui.component.ChannelRow
import com.mooncc.teamspeak6.ui.component.ClientRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    bookmarkId: Long,
    onDisconnected: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val serverGroups by viewModel.serverGroups.collectAsState()
    val channelTree by viewModel.channelTree.collectAsState()
    val clients by viewModel.clients.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(bookmarkId) {
        if (bookmarkId > 0) viewModel.connectToBookmark(bookmarkId)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            describeEvent(event)?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }

    LaunchedEffect(state.connection.status) {
        if (state.connection.status == ConnectionStatus.DISCONNECTED && bookmarkId <= 0) {
            onDisconnected()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.connection.server?.name
                                ?: state.connection.bookmark?.label
                                ?: "未连接",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.connection.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("修改昵称") },
                            onClick = {
                                menuExpanded = false
                                viewModel.showNicknameDialog()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("离开状态") },
                            onClick = {
                                menuExpanded = false
                                viewModel.showAwayDialog()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("服务器聊天") },
                            onClick = {
                                menuExpanded = false
                                viewModel.openConversation(ChatTarget.SERVER, 0, "服务器")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("断开连接") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                viewModel.disconnect()
                                onDisconnected()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                VoiceControlBar(
                    media = state.media,
                    channelName = currentChannelName(state),
                    onToggleMic = viewModel::toggleMic,
                    onToggleSpeaker = viewModel::toggleSpeaker,
                    onTogglePushToTalk = viewModel::togglePushToTalk,
                    onPushToTalkPressed = viewModel::setPushToTalkActive,
                    onToggleScreenShare = {
                        viewModel.showStatus("屏幕共享需要配置 WebRTC 桥，将在下一阶段接入")
                    },
                    onToggleChannelCommander = viewModel::toggleChannelCommander,
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = state.selectedTab == ServerTab.CHANNELS,
                        onClick = { viewModel.selectTab(ServerTab.CHANNELS) },
                        icon = { Icon(Icons.Default.Tag, contentDescription = null) },
                        label = { Text("频道") },
                    )
                    NavigationBarItem(
                        selected = state.selectedTab == ServerTab.CHAT,
                        onClick = { viewModel.selectTab(ServerTab.CHAT) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (state.totalUnread > 0) {
                                        Badge {
                                            Text(state.totalUnread.coerceAtMost(99).toString())
                                        }
                                    }
                                },
                            ) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) }
                        },
                        label = { Text("聊天") },
                    )
                    NavigationBarItem(
                        selected = state.selectedTab == ServerTab.SCREEN_SHARE,
                        onClick = { viewModel.selectTab(ServerTab.SCREEN_SHARE) },
                        icon = { Icon(Icons.Default.PresentToAll, contentDescription = null) },
                        label = { Text("屏幕") },
                    )
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (state.selectedTab) {
                ServerTab.CHANNELS -> ChannelTreePane(
                    rows = state.rows,
                    currentChannelId = state.connection.currentChannelId,
                    localClientId = state.connection.localClientId,
                    onChannelClick = viewModel::joinChannel,
                    onChannelLongClick = viewModel::onChannelLongPressed,
                    onToggleCollapse = viewModel::toggleChannelCollapsed,
                    onClientClick = viewModel::onClientClicked,
                    onClientLongClick = viewModel::showClientInfo,
                )

                ServerTab.CHAT -> ChatPanel(
                    conversations = state.conversations,
                    active = state.activeConversation,
                    onSelectConversation = viewModel::selectConversation,
                    onCloseConversation = viewModel::closeConversation,
                    onSend = viewModel::sendMessage,
                )

                ServerTab.SCREEN_SHARE -> ScreenSharePane(
                    sharingClients = clients.filter { it.isSharingScreen },
                )
            }
        }
    }

    ServerDialogHost(
        state = state,
        serverGroups = serverGroups,
        allChannels = channelTree,
        viewModel = viewModel,
    )
}

@Composable
private fun ChannelTreePane(
    rows: List<ChannelTreeRow>,
    currentChannelId: Int,
    localClientId: Int,
    onChannelClick: (Channel) -> Unit,
    onChannelLongClick: (Channel) -> Unit,
    onToggleCollapse: (Int) -> Unit,
    onClientClick: (Client) -> Unit,
    onClientLongClick: (Client) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无频道数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ChannelTreeRow.ChannelRow -> ChannelRow(
                    channel = row.channel,
                    isCollapsed = row.isCollapsed,
                    hasChildren = row.hasChildren,
                    isCurrentChannel = row.channel.id == currentChannelId,
                    onClick = { onChannelClick(row.channel) },
                    onLongClick = { onChannelLongClick(row.channel) },
                    onToggleCollapse = { onToggleCollapse(row.channel.id) },
                )

                is ChannelTreeRow.ClientRow -> ClientRow(
                    client = row.client,
                    depth = row.depth,
                    isLocal = row.client.id == localClientId,
                    onClick = { onClientClick(row.client) },
                    onLongClick = { onClientLongClick(row.client) },
                )
            }
        }
    }
}

private fun statusLabel(state: ServerUiState): String = when (state.connection.status) {
    ConnectionStatus.DISCONNECTED -> "已断开"
    ConnectionStatus.CONNECTING -> "正在连接…"
    ConnectionStatus.CONNECTED -> {
        val server = state.connection.server
        if (server != null) {
            "在线 ${server.voiceClientsOnline}/${server.maxClients} · ${server.channelsOnline} 个频道"
        } else {
            "已连接"
        }
    }
    ConnectionStatus.RECONNECTING -> "正在重连…"
    ConnectionStatus.ERROR -> state.connection.errorMessage ?: "连接错误"
}

private fun currentChannelName(state: ServerUiState): String {
    val id = state.connection.currentChannelId
    if (id == 0) return ""
    val row = state.rows.filterIsInstance<ChannelTreeRow.ChannelRow>()
        .firstOrNull { it.channel.id == id }
    return row?.channel?.displayName.orEmpty()
}

private fun describeEvent(event: ServerEvent): String? = when (event) {
    is ServerEvent.ClientJoined -> "${event.client.nickname} 加入了 ${event.channelName}"
    is ServerEvent.ClientLeft -> "${event.client.nickname} 离开了服务器"
    is ServerEvent.ClientMoved ->
        "${event.client.nickname} 从 ${event.fromChannelName} 移动到 ${event.toChannelName}"
    is ServerEvent.Poked -> "${event.fromNickname} 戳了你：${event.message}"
    is ServerEvent.ScreenShareStarted -> "${event.nickname} 开始共享屏幕"
    is ServerEvent.ScreenShareStopped -> "${event.nickname} 停止了屏幕共享"
    is ServerEvent.Kicked ->
        if (event.fromServer) "你被踢出服务器：${event.reason}" else "你被踢出频道：${event.reason}"
    is ServerEvent.Error -> event.message
    is ServerEvent.MessageReceived -> null
}
