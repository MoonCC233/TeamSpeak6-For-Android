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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.MicMode
import dev.tsdroid.data.ServerBookmark
import dev.tsdroid.pad.R
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tsdroid.viewmodel.ServerViewModel
import dev.tslib.ConnectionState
import dev.tslib.User

@Composable
fun WorkspaceScreen(
    serverViewModel: ServerViewModel,
    connectionViewModel: ConnectionViewModel,
    initialChannelName: String?,
    onLeave: () -> Unit,
    onSwitchServer: (ServerBookmark) -> Unit,
) {
    val channels by serverViewModel.channels.collectAsStateWithLifecycle()
    val users by serverViewModel.users.collectAsStateWithLifecycle()
    val serverInfo by serverViewModel.serverInfo.collectAsStateWithLifecycle()
    val serverAddress by serverViewModel.serverAddressLabel.collectAsStateWithLifecycle()
    val connectionState by serverViewModel.connectionState.collectAsStateWithLifecycle()
    val reconnectStatus by serverViewModel.reconnectStatus.collectAsStateWithLifecycle()
    val talkingIds by serverViewModel.talkingUserIds.collectAsStateWithLifecycle()
    val isLocalTalking by serverViewModel.isLocalVoiceActive.collectAsStateWithLifecycle()
    val mutedUserIds by serverViewModel.mutedUserIds.collectAsStateWithLifecycle()
    val userVolumes by serverViewModel.userVolumes.collectAsStateWithLifecycle()
    val avatars by serverViewModel.avatars.collectAsStateWithLifecycle()
    val channelMessages by serverViewModel.channelMessages.collectAsStateWithLifecycle()
    val privateMessages by serverViewModel.privateMessages.collectAsStateWithLifecycle()
    val unreadChannel by serverViewModel.unreadChannel.collectAsStateWithLifecycle()
    val unreadPrivate by serverViewModel.unreadPrivate.collectAsStateWithLifecycle()
    val micMode by serverViewModel.micMode.collectAsStateWithLifecycle()
    val micMuted by serverViewModel.isMicMuted.collectAsStateWithLifecycle()
    val outputMuted by serverViewModel.isOutputMuted.collectAsStateWithLifecycle()
    val micStatus by serverViewModel.micStatus.collectAsStateWithLifecycle()
    val bookmarks by connectionViewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkIcons by connectionViewModel.bookmarkIcons.collectAsStateWithLifecycle()

    val myId = serverViewModel.myClientId
    val myUser = users.firstOrNull { it.id == myId }
    val currentChannelId = myUser?.channelId
    val currentChannel = channels.firstOrNull { it.id == currentChannelId }
    val currentMembers = users
        .filter { it.channelId == currentChannelId && !it.isQuery() }
        .sortedWith(
            compareByDescending<User> { it.id == myId }
                .thenByDescending { it.id in talkingIds }
                .thenBy { it.nickname.lowercase() },
        )

    var chatTarget by remember { mutableStateOf<ChatTarget>(ChatTarget.Channel) }
    var selectedMember by remember { mutableStateOf<User?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showAddServerConfirm by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<ServerBookmark?>(null) }
    var initialChannelApplied by remember { mutableStateOf(false) }
    var wasConnected by remember { mutableStateOf(false) }
    val dmNames = remember { mutableStateMapOf<String, String>() }

    val privateFallback = stringResource(R.string.chat_private)
    val privateThreads = (privateMessages.keys + dmNames.keys).distinct()
        .map { key ->
            val name = users.firstOrNull { it.uid == key }?.nickname
                ?: dmNames[key]
                ?: privateMessages[key]?.lastOrNull { !it.isMe }?.sender
                ?: privateFallback
            PrivateThread(key, name, unreadPrivate[key] ?: 0)
        }
        .sortedBy { it.name.lowercase() }

    LaunchedEffect(channels, initialChannelName) {
        if (!initialChannelApplied && !initialChannelName.isNullOrBlank() && channels.isNotEmpty()) {
            val match = channels.firstOrNull { it.name.equals(initialChannelName.trim(), ignoreCase = true) }
            if (match != null && match.id != currentChannelId) {
                serverViewModel.moveToChannel(match.id)
            }
            initialChannelApplied = true
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            wasConnected = true
        } else if (wasConnected && connectionState == ConnectionState.DISCONNECTED) {
            onLeave()
        }
    }

    val activePrivateMessageCount = (chatTarget as? ChatTarget.Private)
        ?.let { privateMessages[it.key]?.size } ?: 0
    LaunchedEffect(chatTarget, channelMessages.size, activePrivateMessageCount) {
        when (val target = chatTarget) {
            ChatTarget.Channel -> serverViewModel.setChatState(open = true, tab = 0)
            is ChatTarget.Private -> serverViewModel.setChatState(open = true, tab = 1, pmPeerKey = target.key)
        }
    }

    DisposableEffect(Unit) {
        onDispose { serverViewModel.setChatState(open = false, tab = 0) }
    }

    fun openDm(user: User) {
        val key = user.uid ?: return
        dmNames[key] = user.nickname
        chatTarget = ChatTarget.Private(key, user.nickname)
        selectedMember = null
    }

    val serverIcon: ImageBitmap? = bookmarkIcons[serverInfo?.iconId ?: 0L]
    // The current server already owns the first rail slot; do not draw the
    // same bookmark again (switching to it would needlessly reconnect).
    val railBookmarks = bookmarks.filterNot { it.address.equals(serverAddress, ignoreCase = true) }

    Row(
        Modifier
            .fillMaxSize()
            .background(PadColors.Page)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    ) {
        ServerRail(
            serverName = serverInfo?.name ?: stringResource(R.string.server_name_fallback),
            serverIcon = serverIcon,
            bookmarks = railBookmarks,
            bookmarkIcons = bookmarkIcons,
            onSwitchServer = { pendingSwitch = it },
            onAddServer = { showAddServerConfirm = true },
            onOpenSettings = { showSettings = true },
        )

        Column(
            Modifier
                .width(322.dp)
                .fillMaxHeight()
                .background(PadColors.Surface)
                .drawBehind {
                    drawLine(
                        color = PadColors.Border,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        ) {
            PeoplePanel(
                users = users,
                channels = channels,
                currentChannelId = currentChannelId,
                myClientId = myId,
                talkingIds = talkingIds,
                mutedUserIds = mutedUserIds,
                avatars = avatars,
                onSelectChannel = { channel -> serverViewModel.moveToChannel(channel.id) },
                onMemberClick = { member ->
                    if (member.id == myId) showSettings = true else selectedMember = member
                },
                modifier = Modifier.weight(1f),
            )
            AudioDock(
                micMode = micMode,
                micMuted = micMuted,
                outputMuted = outputMuted,
                micStatus = micStatus,
                onToggleMic = { serverViewModel.toggleMicMute() },
                onPushToTalk = { pressed -> serverViewModel.setPushToTalk(pressed) },
                onToggleOutput = { serverViewModel.toggleOutputMute() },
                onOpenSettings = { showSettings = true },
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            WorkspaceTopBar(
                serverName = serverInfo?.name ?: stringResource(R.string.server_name_fallback),
                address = serverAddress,
                channelName = currentChannel?.name
                    ?: stringResource(R.string.workspace_channel_connecting),
                reconnecting = reconnectStatus.active,
                reconnectAttempt = reconnectStatus.attempt,
                nextRetrySeconds = reconnectStatus.nextRetrySeconds,
                onOpenSettings = { showSettings = true },
                onLeave = { showLeaveConfirm = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            VoiceStage(
                                members = currentMembers,
                                channelName = currentChannel?.name
                                    ?: stringResource(R.string.workspace_channel_voice),
                                myClientId = myId,
                                talkingIds = talkingIds,
                                localTalking = isLocalTalking,
                                mutedUserIds = mutedUserIds,
                                avatars = avatars,
                                onMemberClick = { member ->
                                    if (member.id == myId) showSettings = true else selectedMember = member
                                },
                                modifier = Modifier
                                    .widthIn(max = 1120.dp)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            ChatPanel(
                                target = chatTarget,
                                threads = privateThreads,
                                channelName = currentChannel?.name
                                    ?: stringResource(R.string.workspace_channel_default),
                                channelMessages = channelMessages,
                                privateMessages = privateMessages,
                                unreadChannel = unreadChannel,
                                users = users,
                                onSelectTarget = { chatTarget = it },
                                onSend = { text ->
                                    when (val target = chatTarget) {
                                        ChatTarget.Channel -> serverViewModel.sendChannelMessage(text)
                                        is ChatTarget.Private -> serverViewModel.sendPrivateMessage(target.key, text)
                                    }
                                },
                                modifier = Modifier
                                    .widthIn(max = 1120.dp)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AudioSettingsDialog(
            serverViewModel = serverViewModel,
            onDismiss = { showSettings = false },
        )
    }

    if (showLeaveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.leave_title),
            message = stringResource(
                R.string.leave_message,
                serverInfo?.name ?: stringResource(R.string.workspace_current_server),
            ),
            confirmLabel = stringResource(R.string.leave_confirm),
            destructive = true,
            onDismiss = { showLeaveConfirm = false },
            onConfirm = {
                showLeaveConfirm = false
                serverViewModel.disconnect()
                onLeave()
            },
        )
    }

    pendingSwitch?.let { bookmark ->
        ConfirmDialog(
            title = stringResource(R.string.switch_title),
            message = stringResource(
                R.string.switch_message,
                bookmark.serverName ?: bookmark.name,
            ),
            confirmLabel = stringResource(R.string.switch_confirm),
            destructive = false,
            onDismiss = { pendingSwitch = null },
            onConfirm = {
                pendingSwitch = null
                serverViewModel.disconnect()
                onSwitchServer(bookmark)
            },
        )
    }

    if (showAddServerConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.add_server_title),
            message = stringResource(R.string.add_server_message),
            confirmLabel = stringResource(R.string.add_server_confirm),
            destructive = false,
            onDismiss = { showAddServerConfirm = false },
            onConfirm = {
                showAddServerConfirm = false
                serverViewModel.disconnect()
                onLeave()
            },
        )
    }

    selectedMember?.let { member ->
        MemberActionsDialog(
            member = member,
            avatar = member.uid?.let { avatars[it] },
            speaking = member.id in talkingIds,
            mutedLocally = member.id in mutedUserIds,
            volumeDb = userVolumes[member.id] ?: 0f,
            canDm = !member.uid.isNullOrEmpty(),
            onDismiss = { selectedMember = null },
            onToggleLocalMute = { serverViewModel.toggleMuteUser(member.id) },
            onVolumeChange = { db, commit -> serverViewModel.setUserVolumeDb(member.id, db, commit) },
            onOpenDm = { openDm(member) },
        )
    }
}

@Composable
private fun ServerRail(
    serverName: String,
    serverIcon: ImageBitmap?,
    bookmarks: List<ServerBookmark>,
    bookmarkIcons: Map<Long, ImageBitmap>,
    onSwitchServer: (ServerBookmark) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .width(70.dp)
            .fillMaxHeight()
            .background(PadColors.Page)
            .drawBehind {
                drawLine(
                    color = PadColors.Border,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PadLogo(size = 38.dp)
        Spacer(Modifier.height(18.dp))
        RailServerItem(
            name = serverName,
            image = serverIcon,
            active = true,
            onClick = {},
        )
        Spacer(Modifier.height(6.dp))
        bookmarks.take(4).forEach { bookmark ->
            RailServerItem(
                name = bookmark.serverName ?: bookmark.name,
                image = bookmarkIcons[bookmark.iconId],
                active = false,
                onClick = { onSwitchServer(bookmark) },
            )
        }
        Spacer(Modifier.weight(1f))
        PadIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.rail_add_server),
            onClick = onAddServer,
            tint = PadColors.Accent,
            background = PadColors.SurfaceAlt,
            size = 38.dp,
            iconSize = 19.dp,
        )
        Spacer(Modifier.height(8.dp))
        PadIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.audio_settings),
            onClick = onOpenSettings,
            tint = PadColors.Muted,
            background = PadColors.SurfaceAlt,
            size = 38.dp,
            iconSize = 19.dp,
        )
    }
}

@Composable
private fun RailServerItem(
    name: String,
    image: ImageBitmap?,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(PadColors.Accent),
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(3.dp),
        ) {
            PadAvatar(
                name = name,
                size = 40.dp,
                image = image,
                showPresence = active,
                presenceColor = PadColors.Green,
            )
        }
    }
}

@Composable
private fun WorkspaceTopBar(
    serverName: String,
    address: String?,
    channelName: String,
    reconnecting: Boolean,
    reconnectAttempt: Int,
    nextRetrySeconds: Int,
    onOpenSettings: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(PadColors.Surface)
            .drawBehind {
                drawLine(
                    color = PadColors.Border,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = PadColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    serverName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PadColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = PadColors.Muted.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    channelName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PadColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                address ?: stringResource(R.string.top_address_loading),
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Muted.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 20.dp),
            )
        }

        if (reconnecting) {
            StatusPill(
                text = if (nextRetrySeconds > 0) {
                    stringResource(
                        R.string.top_reconnect_retry,
                        reconnectAttempt,
                        nextRetrySeconds,
                    )
                } else {
                    stringResource(R.string.top_reconnect, reconnectAttempt)
                },
                color = PadColors.Amber,
            )
        } else {
            StatusPill(stringResource(R.string.common_connected), PadColors.Green)
        }
        Spacer(Modifier.width(12.dp))
        PadIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.audio_settings),
            onClick = onOpenSettings,
            tint = PadColors.Muted,
            size = 36.dp,
        )
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(PadColors.Red.copy(alpha = 0.10f))
                .border(1.dp, PadColors.Red.copy(alpha = 0.26f), RoundedCornerShape(9.dp))
                .clickable(onClick = onLeave)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Logout,
                contentDescription = null,
                tint = PadColors.Red,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.leave_confirm),
                style = MaterialTheme.typography.labelMedium,
                color = PadColors.Red,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PadColors.Surface,
        titleContentColor = PadColors.Text,
        textContentColor = PadColors.Muted,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) PadColors.Red else PadColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = PadColors.Muted)
            }
        },
    )
}
