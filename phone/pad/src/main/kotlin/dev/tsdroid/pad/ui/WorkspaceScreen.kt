package dev.tsdroid.pad.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import dev.tsdroid.screen.ScreenShareController
import dev.tsdroid.service.ScreenShareService
import dev.tsdroid.service.TsConnectionService
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tsdroid.viewmodel.ServerViewModel
import dev.tslib.ConnectionState
import dev.tslib.User
import kotlinx.coroutines.launch

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
    val isPublishing by ScreenShareController.publishing.collectAsStateWithLifecycle()
    val remoteStreams by ScreenShareController.remoteStreams.collectAsStateWithLifecycle()
    val viewerPhases by ScreenShareController.viewerPhases.collectAsStateWithLifecycle()
    val viewerStats by ScreenShareController.viewerStats.collectAsStateWithLifecycle()
    val screenShareError by ScreenShareController.lastError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resumed = rememberPadResumed()

    var fullscreenStream by remember {
        mutableStateOf<ScreenShareController.RemoteStream?>(null)
    }
    var infoStream by remember {
        mutableStateOf<ScreenShareController.RemoteStream?>(null)
    }
    var dismissedStreamIds by remember { mutableStateOf<Set<String>>(emptySet()) }

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
    val streamingClientIds = remember(remoteStreams) {
        remoteStreams.values.map { it.clientId }.toSet()
    }
    val visibleStreams = remoteStreams.values
        .filter { it.streamId !in dismissedStreamIds }
        .sortedBy { it.name.lowercase() }

    fun streamDisplayName(stream: ScreenShareController.RemoteStream): String =
        users.firstOrNull { it.id == stream.clientId }?.nickname ?: stream.name

    fun showNotice(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        when {
            result.resultCode == Activity.RESULT_OK && data != null -> {
                if (TsConnectionService.instance?.tsClient?.isConnected != true) {
                    showNotice(context.getString(R.string.share_error_not_connected))
                } else {
                    val streamName = context.getString(
                        R.string.share_stream_name,
                        myUser?.nickname ?: context.getString(R.string.share_fallback_name),
                    )
                    runCatching {
                        ScreenShareService.start(
                            context = context,
                            resultCode = result.resultCode,
                            data = data,
                            streamName = streamName,
                            withAudio = false,
                        )
                    }.onFailure {
                        showNotice(context.getString(R.string.share_error_start))
                    }
                }
            }

            result.resultCode == Activity.RESULT_OK -> {
                showNotice(context.getString(R.string.share_error_permission))
            }

            else -> {
                showNotice(context.getString(R.string.share_error_permission))
            }
        }
    }

    fun toggleScreenShare() {
        if (isPublishing) {
            ScreenShareService.stop(context)
            return
        }
        if (reconnectStatus.active || TsConnectionService.instance?.tsClient?.isConnected != true) {
            showNotice(context.getString(R.string.share_error_not_connected))
            return
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (manager == null) {
            showNotice(context.getString(R.string.share_error_no_manager))
            return
        }
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    fun watchStream(stream: ScreenShareController.RemoteStream) {
        dismissedStreamIds = dismissedStreamIds - stream.streamId
        fullscreenStream = stream
    }

    LaunchedEffect(remoteStreams.keys) {
        dismissedStreamIds = dismissedStreamIds.intersect(remoteStreams.keys)
        val fullscreenId = fullscreenStream?.streamId
        if (fullscreenId != null && fullscreenId !in remoteStreams) {
            fullscreenStream = null
        }
        val infoId = infoStream?.streamId
        if (infoId != null && infoId !in remoteStreams) {
            infoStream = null
        }
    }

    LaunchedEffect(screenShareError) {
        val error = screenShareError ?: return@LaunchedEffect
        val message = when (error) {
            "start_failed" -> R.string.share_error_start
            "join_rejected" -> R.string.share_error_join
            else -> R.string.share_error_generic
        }
        showNotice(context.getString(message))
        ScreenShareController.clearError()
    }

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

    Box(
        Modifier
            .fillMaxSize()
            .background(PadColors.Page),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(PadColors.Page)
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing,
                ),
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
                    streamingUserIds = streamingClientIds,
                    avatars = avatars,
                    onSelectChannel = { channel -> serverViewModel.moveToChannel(channel.id) },
                    onMemberClick = { member ->
                        if (member.id == myId) {
                            showSettings = true
                        } else {
                            selectedMember = member
                        }
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
                    isPublishing = isPublishing,
                    onToggleShare = { toggleScreenShare() },
                    onOpenSettings = { showSettings = true },
                    onLeave = { showLeaveConfirm = true },
                )
                if (isPublishing) {
                    PadSharePublishingBar(
                        onStop = { ScreenShareService.stop(context) },
                    )
                }
                if (visibleStreams.isNotEmpty()) {
                    PadScreenShareShelf(
                        streams = visibleStreams,
                        liveCount = remoteStreams.size,
                        displayNameOf = { streamDisplayName(it) },
                        phases = viewerPhases,
                        stats = viewerStats,
                        activeStreamId = fullscreenStream?.streamId,
                        resumed = resumed,
                        onExpand = { watchStream(it) },
                        onInfo = { infoStream = it },
                        onStop = { stream ->
                            fullscreenStream = null
                            dismissedStreamIds = dismissedStreamIds + stream.streamId
                            ScreenShareController.leaveStream(stream.streamId)
                        },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PadAmbientBackground(Modifier.fillMaxSize())
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 28.dp,
                            end = 28.dp,
                            top = 22.dp,
                            bottom = 28.dp,
                        ),
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
                                    streamingUserIds = streamingClientIds,
                                    avatars = avatars,
                                    onMemberClick = { member ->
                                        if (member.id == myId) {
                                            showSettings = true
                                        } else {
                                            selectedMember = member
                                        }
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
                                            ChatTarget.Channel ->
                                                serverViewModel.sendChannelMessage(text)
                                            is ChatTarget.Private ->
                                                serverViewModel.sendPrivateMessage(target.key, text)
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )

        fullscreenStream?.let { stream ->
            PadScreenShareViewer(
                stream = stream,
                displayName = streamDisplayName(stream),
                phase = viewerPhases[stream.streamId]
                    ?: ScreenShareController.ViewerPhase.JOINING,
                stats = viewerStats[stream.streamId],
                resumed = resumed,
                onDismiss = {
                    val phase = viewerPhases[stream.streamId]
                    fullscreenStream = null
                    if (phase == null || phase.isShareTerminal()) {
                        dismissedStreamIds = dismissedStreamIds + stream.streamId
                        ScreenShareController.leaveStream(stream.streamId)
                    }
                },
                onInfo = { infoStream = stream },
                onStopWatching = {
                    fullscreenStream = null
                    dismissedStreamIds = dismissedStreamIds + stream.streamId
                    ScreenShareController.leaveStream(stream.streamId)
                },
            )
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

    infoStream?.let { stream ->
        PadStreamInfoDialog(
            displayName = streamDisplayName(stream),
            audioSupported = stream.audio,
            stats = viewerStats[stream.streamId],
            onDismiss = { infoStream = null },
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
            isStreaming = member.id in streamingClientIds,
            onDismiss = { selectedMember = null },
            onToggleLocalMute = { serverViewModel.toggleMuteUser(member.id) },
            onVolumeChange = { db, commit -> serverViewModel.setUserVolumeDb(member.id, db, commit) },
            onOpenDm = { openDm(member) },
            onWatchStream = {
                selectedMember = null
                remoteStreams.values.firstOrNull { it.clientId == member.id }?.let {
                    watchStream(it)
                }
            },
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
    isPublishing: Boolean,
    onToggleShare: () -> Unit,
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
        PadShareButton(
            publishing = isPublishing,
            enabled = isPublishing || !reconnecting,
            onClick = onToggleShare,
        )
        Spacer(Modifier.width(8.dp))
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
