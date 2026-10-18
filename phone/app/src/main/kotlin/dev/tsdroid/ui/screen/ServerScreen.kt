package dev.tsdroid.ui.screen

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.data.PrivateConversations
import dev.tsdroid.han.R
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.MicMode
import dev.tslib.ConnectionState
import dev.tslib.User
import dev.tsdroid.ui.component.AnimeBackground
import dev.tsdroid.ui.component.AnimeWallpaperState
import dev.tsdroid.ui.component.ChannelTree
import dev.tsdroid.ui.component.ChatView
import dev.tsdroid.ui.component.CompactTopBar
import dev.tsdroid.ui.component.FileManagerDialog
import dev.tsdroid.ui.component.GlassCard
import dev.tsdroid.ui.component.LiquidGlassPanel
import dev.tsdroid.ui.component.LocalGlassWallpaper
import dev.tsdroid.ui.component.ShareTarget
import dev.tsdroid.ui.component.ServerInfoSheet
import dev.tsdroid.ui.component.UserPanelSheet
import dev.tsdroid.ui.component.rememberGlassBackdrop
import dev.tsdroid.ui.icon.AppIcons
import dev.tsdroid.viewmodel.ChatMessage
import dev.tsdroid.viewmodel.DownloadState
import dev.tsdroid.viewmodel.FileAttachment
import dev.tsdroid.viewmodel.ServerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    onBackToHome: () -> Unit,
    onNavigateToAbout: () -> Unit,
    /** What startup read, so this screen opens on the wallpaper instead of an empty frame. */
    initialAnimeBackground: Boolean,
    viewModel: ServerViewModel = viewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val channelIcons by viewModel.channelIcons.collectAsStateWithLifecycle()
    val userAvatars by viewModel.avatars.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val privateMessages by viewModel.privateMessages.collectAsStateWithLifecycle()
    val isPttMode by viewModel.isPttMode.collectAsStateWithLifecycle()
    val micMode by viewModel.micMode.collectAsStateWithLifecycle()
    val userVolumes by viewModel.userVolumes.collectAsStateWithLifecycle()
    val isOutputMuted by viewModel.isOutputMuted.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val unreadChannel by viewModel.unreadChannel.collectAsStateWithLifecycle()
    val unreadPrivate by viewModel.unreadPrivate.collectAsStateWithLifecycle()
    val audioGain by viewModel.audioGain.collectAsStateWithLifecycle()
    val showLinkThumbnails by viewModel.showLinkThumbnails.collectAsStateWithLifecycle()
    val autoLoadImages by viewModel.autoLoadImages.collectAsStateWithLifecycle()
    val animeBackground by viewModel.animeBackground
        .collectAsStateWithLifecycle(initialValue = initialAnimeBackground)
    val noiseSuppression by viewModel.noiseSuppression.collectAsStateWithLifecycle()
    val mutedUserIds by viewModel.mutedUserIds.collectAsStateWithLifecycle()
    val fileManagerOpen by viewModel.fileManagerOpen.collectAsStateWithLifecycle()
    val fileList by viewModel.fileList.collectAsStateWithLifecycle()
    val previewImageBitmap by viewModel.previewImageBitmap.collectAsStateWithLifecycle()
    val previewImageName by viewModel.previewImageName.collectAsStateWithLifecycle()
    val currentFilePath by viewModel.currentFilePath.collectAsStateWithLifecycle()
    val fileManagerLoading by viewModel.fileManagerLoading.collectAsStateWithLifecycle()
    val channelPermissions by viewModel.currentChannelPermissions.collectAsStateWithLifecycle()
    val reconnectStatus by viewModel.reconnectStatus.collectAsStateWithLifecycle()
    val micStatus by viewModel.micStatus.collectAsStateWithLifecycle()

    var chatOpen by remember { mutableStateOf(false) }
    var chatEverOpened by remember { mutableStateOf(false) }
    var chatTab by remember { mutableIntStateOf(0) }
    var messageText by remember { mutableStateOf("") }
    // Conversations are identified by the peer's permanent uid, not by the session
    // client id: a reconnect used to reassign the client id and split one person
    // into several conversation chips.
    var pmTargetUid by remember { mutableStateOf<String?>(null) }
    var showServerInfo by remember { mutableStateOf(false) }

    // Resolve pmTarget User from users list
    val pmTarget = pmTargetUid?.let { uid -> users.find { it.uid == uid } }

    // Build PM conversation list (uid → display name) from the message map + users list
    val context = LocalContext.current
    val pmConversationUsers: List<Pair<String, String>> = remember(privateMessages, users) {
        privateMessages.keys.map { peerKey ->
            val name = users.find { it.uid == peerKey }?.nickname
                ?: privateMessages[peerKey]?.lastOrNull { !it.isMe }?.sender
                // A conversation that never resolved to a uid (the peer was not in
                // the user list yet) keeps the old generic label.
                ?: if (PrivateConversations.isLegacy(peerKey)) {
                    context.getString(
                        R.string.user_fallback,
                        PrivateConversations.clientIdOf(peerKey) ?: 0,
                    )
                } else {
                    peerKey
                }
            peerKey to name
        }
    }

    val totalUnreadPrivate = unreadPrivate.values.sum()

    // Sync chat state to ViewModel for unread tracking
    LaunchedEffect(chatOpen, chatTab, pmTargetUid) {
        viewModel.setChatState(chatOpen, chatTab, pmTargetUid)
    }

    DisposableEffect(Unit) {
        viewModel.bindToService()
        onDispose {}
    }
    // Leave this screen — home button or a session that ended on its own. Going home never drops the
    // connection; only the disconnect button above does that.
    // While the service is auto-reconnecting we stay here and show a banner instead.
    LaunchedEffect(connectionState, reconnectStatus.active) {
        if (connectionState == ConnectionState.DISCONNECTED && !reconnectStatus.active) {
            onBackToHome()
        }
    }
    if (connectionState == ConnectionState.DISCONNECTED && !reconnectStatus.active) return

    // The floating window is no longer driven from here: it belongs to the service, which shows it
    // when the app goes to the background. See TsConnectionService.setAppInForeground.

    val totalUnread = unreadChannel + totalUnreadPrivate
    val serverAddressLabel by viewModel.serverAddressLabel.collectAsStateWithLifecycle()

    // Backdrop for the liquid-glass surfaces on this screen: only when the wallpaper is actually
    // painted behind them, exactly like ConnectionScreen does.
    val glassWallpaper = rememberGlassBackdrop(animeBackground)

    // Per-user panel (volume / mute / info) — opened by long-pressing a row
    var userPanelUserId by remember { mutableStateOf<Int?>(null) }

    if (showServerInfo) {
        ServerInfoSheet(
            info = serverInfo,
            address = serverAddressLabel,
            wallpaper = glassWallpaper,
            onDismiss = { showServerInfo = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimeBackground(enabled = animeBackground)

        Scaffold(
            containerColor = Color.Transparent,
        topBar = {
            Column {
                CompactTopBar(
                    title = serverInfo?.name ?: stringResource(R.string.server),
                    // Leaving the server screen is not disconnecting: the session keeps running in
                    // the service, so it can be picked up again from the home screen.
                    navigationIcon = {
                        IconButton(onClick = onBackToHome) {
                            Icon(
                                AppIcons.Home,
                                contentDescription = stringResource(R.string.back_to_home),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showServerInfo = true }) {
                            Icon(AppIcons.Info, contentDescription = stringResource(R.string.server_info))
                        }
                        IconButton(onClick = { viewModel.toggleFileManager() }) {
                            Icon(AppIcons.File, contentDescription = stringResource(R.string.file_manager))
                        }
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(AppIcons.Exit, contentDescription = stringResource(R.string.disconnect))
                        }
                    },
                )
                // Connection / microphone health, so a silent failure is never invisible again
                if (reconnectStatus.active) {
                    StatusBanner(
                        text = stringResource(
                            R.string.reconnect_in_progress,
                            maxOf(reconnectStatus.attempt, 1),
                        ),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else if (micStatus == AudioBridge.MicStatus.UNAVAILABLE) {
                    StatusBanner(
                        text = stringResource(R.string.mic_unavailable_warning),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // The controls used to float straight on the wallpaper with no panel at all, so
                // their contrast was pure luck. They now sit on a floating glass bar.
                LiquidGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    wallpaper = glassWallpaper,
                    shape = RoundedCornerShape(28.dp),
                    elevation = 6.dp,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Chat FAB with badge
                        Box {
                            IconButton(onClick = {
                                chatOpen = !chatOpen
                            }) {
                                Icon(
                                    AppIcons.Sms,
                                    contentDescription = stringResource(R.string.chat),
                                    tint = Color(0xFF4CAF50),
                                )
                            }
                            if (totalUnread > 0) {
                                Badge(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-4).dp, y = 4.dp),
                                ) {
                                    Text("$totalUnread")
                                }
                            }
                        }

                        // Center button: PTT or Mute depending on MODE (not mute state)
                        if (isPttMode) {
                            // PTT mode: hold to talk — a glass circle whose pressed state is carried
                            // by the icon tint and the rim, not by an opaque fill.
                            var isPressed by remember { mutableStateOf(false) }
                            GlassCard(
                                modifier = Modifier
                                    .size(72.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                isPressed = true
                                                viewModel.setPushToTalk(true)
                                                tryAwaitRelease()
                                                viewModel.setPushToTalk(false)
                                                isPressed = false
                                            },
                                        )
                                    },
                                wallpaper = glassWallpaper,
                                shape = CircleShape,
                                elevation = 4.dp,
                            ) {
                                val pttTint = if (isPressed) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current.copy(alpha = 0.88f)
                                val pttRim = if (isPressed) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current.copy(alpha = 0.30f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(
                                            width = if (isPressed) 2.dp else 1.dp,
                                            color = pttRim,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            AppIcons.MicOn,
                                            contentDescription = stringResource(R.string.push_to_talk),
                                            modifier = Modifier.size(28.dp),
                                            tint = pttTint,
                                        )
                                        Text(
                                            stringResource(R.string.ptt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = pttTint,
                                        )
                                    }
                                }
                            }
                        } else {
                            // Voice activity mode: click to mute and go back to PTT. The error
                            // colour is carried by the icon tint and the rim.
                            GlassCard(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clickable { viewModel.toggleVoiceMode() },
                                wallpaper = glassWallpaper,
                                shape = CircleShape,
                                elevation = 4.dp,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.error,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            AppIcons.MicOff,
                                            contentDescription = stringResource(R.string.mute_mic),
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            stringResource(R.string.mute),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }

                        // Toggle voice mode (PTT →Voice Activity)
                        IconButton(onClick = { viewModel.toggleVoiceMode() }) {
                            Icon(
                                if (isPttMode) AppIcons.MicOff else AppIcons.MicOn,
                                // The three-way mode lives in Settings; here the
                                // label only has to say what a tap will do.
                                contentDescription = stringResource(
                                    when {
                                        isPttMode -> R.string.unmute_mic
                                        micMode == MicMode.VAD -> R.string.mic_mode_vad
                                        else -> R.string.mute_mic
                                    }
                                ),
                                tint = if (isPttMode) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }

                        // Toggle Output Mute (Deafen)
                        IconButton(onClick = { viewModel.toggleOutputMute() }) {
                            Icon(
                                if (isOutputMuted) AppIcons.SpeakerOff else AppIcons.SpeakerOn,
                                contentDescription = stringResource(if (isOutputMuted) R.string.notif_unmute else R.string.notif_mute),
                                tint = if (isOutputMuted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Channel tree —full screen
            ChannelTree(
                channels = channels,
                users = users,
                onChannelClick = { channelId -> viewModel.moveToChannel(channelId) },
                onUserClick = { user ->
                    pmTargetUid = user.uid
                    chatTab = 1
                    chatOpen = true
                },
                onUserLongClick = { user -> viewModel.toggleMuteUser(user.id) },
                onUserLongPress = { user ->
                    // Panel actions (volume/mute/PM) are meaningless on the own row
                    if (user.id != viewModel.myClientId) userPanelUserId = user.id
                },
                mutedUserIds = mutedUserIds,
                channelIcons = channelIcons,
                userAvatars = userAvatars,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            )

            // File manager —slides up from bottom, fills content area
            val fileManagerProgress by animateFloatAsState(
                targetValue = if (fileManagerOpen) 0f else 1f,
                animationSpec = tween(300),
                label = "fileManager",
            )
            if (fileManagerOpen || fileManagerProgress < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = size.height * fileManagerProgress },
                ) {
                    FileManagerDialog(
                        currentPath = currentFilePath,
                        wallpaper = glassWallpaper,
                        files = fileList,
                        isLoading = fileManagerLoading,
                        users = users,
                        permissionHints = channelPermissions,
                        onNavigateToFolder = { viewModel.navigateToFolder(it) },
                        onNavigateUp = { viewModel.navigateUp() },
                        onRefresh = { viewModel.refreshFileList() },
                        onDownload = { viewModel.downloadFileFromManager(it) },
                        onDelete = { viewModel.deleteFileInChannel(it) },
                        onRename = { old, new -> viewModel.renameFileInChannel(old, new) },
                        onCreateDirectory = { viewModel.createDirectoryInChannel(it) },
                        onUploadFile = { name, data -> viewModel.uploadFileToChannel(name, data) },
                        onShareFile = { target, name, size ->
                            when (target) {
                                is ShareTarget.Channel -> viewModel.shareFile(null, name, size)
                                is ShareTarget.PrivateMessage -> viewModel.shareFile(target.peerKey, name, size)
                            }
                        },
                        onDismiss = { viewModel.closeFileManager() },
                        onPreviewImage = { fileName ->
                            viewModel.previewImageFile(fileName)
                        },
                    )
                }
            }

            // Chat panel —slides up from bottom, fills content area
            // Once opened, stay composed so re-opening is instant (no recomposition)
            if (chatOpen) chatEverOpened = true
            val chatProgress by animateFloatAsState(
                targetValue = if (chatOpen) 0f else 1f,
                animationSpec = tween(300),
                label = "chat",
            )
            if (chatEverOpened) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer { translationY = size.height * chatProgress },
                ) {
                    ChatPanel(
                        chatTab = chatTab,
                        onTabChange = { chatTab = it },
                        wallpaper = glassWallpaper,
                        channelMessages = channelMessages,
                        privateMessages = pmTargetUid?.let { uid ->
                            privateMessages[uid] ?: emptyList()
                        } ?: privateMessages.values.flatten().sortedBy { it.timestamp },
                        messageText = messageText,
                        onMessageChange = { messageText = it },
                        pmTarget = pmTarget,
                        pmConversationUsers = pmConversationUsers,
                        onSelectPmUser = { uid -> pmTargetUid = uid },
                        onClearPmTarget = { pmTargetUid = null },
                        onSend = {
                            when (chatTab) {
                                0 -> viewModel.sendChannelMessage(messageText)
                                1 -> pmTargetUid?.let { viewModel.sendPrivateMessage(it, messageText) }
                            }
                            messageText = ""
                        },
                        onClose = { chatOpen = false },
                        unreadChannel = unreadChannel,
                        unreadPrivateTotal = totalUnreadPrivate,
                        unreadPrivatePerUser = unreadPrivate,
                        showLinkThumbnails = showLinkThumbnails,
                        autoLoadImages = autoLoadImages,
                        canUploadFiles = (channelPermissions and dev.tslib.Channel.PERM_FILE_UPLOAD) != 0L,
                        onUploadFile = { fileName, data ->
                            viewModel.uploadAndSendFile(fileName, data, chatTab == 1, pmTargetUid)
                        },
                        onDownload = { attachment -> viewModel.downloadAttachment(attachment) },
                    )
                }
            }
        }
    }

    // Per-user panel (volume / mute / info) — long-press a user row
    val panelUser = users.find { it.id == userPanelUserId }
    UserPanelSheet(
        user = panelUser,
        avatar = panelUser?.uid?.let { userAvatars[it] },
        volumeDb = userPanelUserId?.let { userVolumes[it] } ?: 0f,
        isMuted = userPanelUserId != null && userPanelUserId in mutedUserIds,
        onVolumeChange = { db -> userPanelUserId?.let { viewModel.setUserVolumeDb(it, db, commit = false) } },
        onVolumeCommit = { db -> userPanelUserId?.let { viewModel.setUserVolumeDb(it, db, commit = true) } },
        onToggleMute = { userPanelUserId?.let { viewModel.toggleMuteUser(it) } },
        onPrivateMessage = {
            val id = userPanelUserId
            if (id != null) {
                // Open the conversation under the peer's uid, matching every other
                // entry point into private chat.
                users.find { it.id == id }?.let { pmTargetUid = it.uid }
                chatTab = 1
                chatOpen = true
            }
            userPanelUserId = null
        },
        onDismiss = { userPanelUserId = null },
        wallpaper = glassWallpaper,
    )

    // Image preview overlay (bitmap is pre-decoded off the main thread)
    if (previewImageBitmap != null) {
        Dialog(onDismissRequest = { viewModel.closePreview() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clipToBounds()
                    .clickable { viewModel.closePreview() },
                contentAlignment = Alignment.Center,
            ) {
                previewImageBitmap?.let { bitmap ->
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = previewImageName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    chatTab: Int,
    onTabChange: (Int) -> Unit,
    wallpaper: ImageBitmap? = null,
    channelMessages: List<ChatMessage>,
    privateMessages: List<ChatMessage>,
    messageText: String,
    onMessageChange: (String) -> Unit,
    pmTarget: User?,
    pmConversationUsers: List<Pair<String, String>>,
    onSelectPmUser: (String) -> Unit,
    onClearPmTarget: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    unreadChannel: Int,
    unreadPrivateTotal: Int,
    unreadPrivatePerUser: Map<String, Int>,
    showLinkThumbnails: Boolean,
    autoLoadImages: Boolean = true,
    canUploadFiles: Boolean = true,
    onUploadFile: (String, ByteArray) -> Unit = { _, _ -> },
    onDownload: ((FileAttachment) -> StateFlow<DownloadState>)? = null,
) {
    val context = LocalContext.current
    val uploadScope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadScope.launch {
            var fileName: String? = null
            var data: ByteArray? = null
            var tooLarge = false
            // Reading (up to 10 MB) must not happen on the main thread
            withContext(Dispatchers.IO) {
                try {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) ?: -1
                    val sizeIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.SIZE) ?: -1
                    cursor?.moveToFirst()
                    fileName = if (nameIndex >= 0) cursor?.getString(nameIndex) ?: "file" else "file"
                    val fileSize = if (sizeIndex >= 0) cursor?.getLong(sizeIndex) ?: -1L else -1L
                    cursor?.close()
                    if (fileSize > 10_485_760) {
                        tooLarge = true
                        return@withContext
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                        ?: return@withContext
                    if (bytes.size > 10_485_760) {
                        tooLarge = true
                    } else {
                        data = bytes
                    }
                } catch (_: Exception) {
                }
            }
            when {
                data != null && fileName != null -> onUploadFile(fileName!!, data!!)
                tooLarge -> Toast.makeText(context, R.string.file_too_large, Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Full-screen glass panel: this used to be a near-opaque white slab, the cheapest-looking
    // surface in the app. `contentColor` is derived from the wallpaper actually behind the glass,
    // so the children keep their contrast on any backdrop; nothing else about them changes.
    LiquidGlassPanel(
        modifier = Modifier.fillMaxSize(),
        wallpaper = wallpaper,
        shape = RoundedCornerShape(0.dp),
        elevation = 0.dp,
        contentPadding = PaddingValues(0.dp),
    ) { contentColor ->
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalGlassWallpaper provides wallpaper,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(8.dp),
            ) {
                // Header: tabs + close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabRow(
                        selectedTabIndex = chatTab,
                        modifier = Modifier.weight(1f),
                        containerColor = Color.Transparent,
                    ) {
                        Tab(
                            selected = chatTab == 0,
                            onClick = { onTabChange(0) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.tab_channel))
                                    if (unreadChannel > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        Badge { Text("$unreadChannel") }
                                    }
                                }
                            },
                        )
                        Tab(
                            selected = chatTab == 1,
                            onClick = { onTabChange(1) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.tab_private))
                                    if (unreadPrivateTotal > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        Badge { Text("$unreadPrivateTotal") }
                                    }
                                }
                            },
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                // PM conversation selector
                if (chatTab == 1 && pmConversationUsers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // "All" chip — the selected container is a small glass pill, not an
                        // opaque primary/secondary container fill.
                        GlassFilterChip(selected = pmTarget == null, wallpaper = wallpaper) {
                            val chipColor = if (pmTarget == null) LocalContentColor.current
                                else LocalContentColor.current.copy(alpha = 0.78f)
                            FilterChip(
                                selected = pmTarget == null,
                                onClick = { onClearPmTarget() },
                                label = {
                                    Text(stringResource(R.string.filter_all), color = chipColor)
                                },
                                leadingIcon = if (pmTarget == null) {
                                    {
                                        Icon(
                                            AppIcons.Sms,
                                            null,
                                            Modifier.size(16.dp),
                                            tint = chipColor,
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Transparent,
                                ),
                            )
                        }
                        // One chip per conversation peer (keyed by uid)
                        pmConversationUsers.forEach { (peerKey, nickname) ->
                            val isSelected = pmTarget?.uid == peerKey
                            val userUnread = unreadPrivatePerUser[peerKey] ?: 0
                            GlassFilterChip(selected = isSelected, wallpaper = wallpaper) {
                                val chipColor = if (isSelected) LocalContentColor.current
                                    else LocalContentColor.current.copy(alpha = 0.78f)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectPmUser(peerKey) },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(nickname, color = chipColor)
                                            if (userUnread > 0) {
                                                Spacer(Modifier.width(4.dp))
                                                Badge { Text("$userUnread") }
                                            }
                                        }
                                    },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                Icons.Default.Person,
                                                null,
                                                Modifier.size(16.dp),
                                                tint = chipColor,
                                            )
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Transparent,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Messages
                val messages = when (chatTab) {
                    0 -> channelMessages
                    1 -> privateMessages
                    else -> emptyList()
                }
                // Swipe left/right to switch between the channel and the private tabs, like the app's
                // other tab hosts. Only horizontal drags are claimed, so the list still scrolls
                // vertically; a swipe past a third of the width switches, anything shorter springs
                // back.
                var swipeDrag by remember { mutableFloatStateOf(0f) }
                ChatView(
                    messages = messages,
                    showLinkThumbnails = showLinkThumbnails,
                    autoLoadImages = autoLoadImages,
                    onDownload = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta -> swipeDrag += delta },
                            onDragStopped = {
                                val travelled = swipeDrag
                                swipeDrag = 0f
                                val threshold = 120f
                                if (travelled < -threshold && chatTab == 0) onTabChange(1)
                                else if (travelled > threshold && chatTab == 1) onTabChange(0)
                            },
                        ),
                )

                // Message input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (canUploadFiles) {
                        IconButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            enabled = chatTab == 0 || pmTarget != null,
                        ) {
                            Icon(AppIcons.File, contentDescription = stringResource(R.string.attach_file))
                        }
                    }
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = onMessageChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (chatTab == 0) stringResource(R.string.message_channel_placeholder)
                                else stringResource(R.string.message_private_placeholder, pmTarget?.nickname ?: "?")
                            )
                        },
                        singleLine = true,
                        enabled = chatTab == 0 || pmTarget != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            // Light glass: the panel's refraction shows through, the border keeps
                            // the focus affordance, and every text colour follows the glass.
                            focusedTextColor = LocalContentColor.current,
                            unfocusedTextColor = LocalContentColor.current,
                            disabledTextColor = LocalContentColor.current.copy(alpha = 0.38f),
                            focusedContainerColor = LocalContentColor.current.copy(alpha = 0.10f),
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = LocalContentColor.current.copy(alpha = 0.35f),
                            disabledBorderColor = LocalContentColor.current.copy(alpha = 0.15f),
                            focusedPlaceholderColor = LocalContentColor.current.copy(alpha = 0.55f),
                            unfocusedPlaceholderColor = LocalContentColor.current.copy(alpha = 0.55f),
                            disabledPlaceholderColor = LocalContentColor.current.copy(alpha = 0.30f),
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    IconButton(
                        onClick = onSend,
                        enabled = messageText.isNotBlank() && (chatTab == 0 || pmTarget != null),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

/**
 * Selected filter chips sit on a small glass pill instead of the previous opaque
 * primary/secondary container fill. The chip's own container stays transparent, and the chip's
 * label/icon colours are set explicitly to `LocalContentColor` at the call site, so the selected
 * state reads as "brighter label on glass" and the unselected one stays flat on the panel glass.
 */
@Composable
private fun GlassFilterChip(
    selected: Boolean,
    wallpaper: ImageBitmap?,
    chip: @Composable () -> Unit,
) {
    if (selected) {
        GlassCard(
            wallpaper = wallpaper,
            shape = RoundedCornerShape(8.dp),
            elevation = 2.dp,
        ) { chip() }
    } else {
        chip()
    }
}

/** Inline status strip used for reconnect / microphone health warnings. */
@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
