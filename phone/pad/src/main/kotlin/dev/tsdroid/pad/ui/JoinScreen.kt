package dev.tsdroid.pad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.data.ServerBookmark
import dev.tsdroid.pad.BuildConfig
import dev.tsdroid.pad.R
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tslib.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun JoinScreen(
    viewModel: ConnectionViewModel,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onConnected: () -> Unit,
) {
    val address by viewModel.address.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkIcons by viewModel.bookmarkIcons.collectAsStateWithLifecycle()

    val connecting = connectionState == ConnectionState.CONNECTING
    var showPassword by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val validationMessage = stringResource(R.string.join_validation)
    val bookmarkSavedMessage = stringResource(R.string.join_bookmark_saved)

    fun connect() {
        viewModel.connect {
            onConnected()
        }
    }

    fun saveBookmark() {
        if (address.isBlank() || nickname.isBlank()) {
            scope.launch { snackbar.showSnackbar(validationMessage) }
            return
        }
        viewModel.saveBookmark(resetForm = false)
        scope.launch { snackbar.showSnackbar(bookmarkSavedMessage) }
    }

    Box(Modifier.fillMaxSize()) {
        PadAmbientBackground()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
        ) {

            val wide = maxWidth >= 980.dp
            val viewportHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (wide) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight)
                            .padding(horizontal = 56.dp, vertical = 36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        JoinHero(
                            compact = false,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 56.dp),
                        )
                        JoinCard(
                            address = address,
                            onAddressChange = { viewModel.address.value = it },
                            nickname = nickname,
                            onNicknameChange = { viewModel.nickname.value = it },
                            password = password,
                            onPasswordChange = { viewModel.password.value = it },
                            channel = channel,
                            onChannelChange = { viewModel.channel.value = it },
                            showPassword = showPassword,
                            onTogglePassword = { showPassword = !showPassword },
                            connecting = connecting,
                            error = error,
                            micGranted = micGranted,
                            onRequestMic = onRequestMic,
                            onOpenAppSettings = onOpenAppSettings,
                            bookmarks = bookmarks,
                            bookmarkIcons = bookmarkIcons,
                            onConnect = { if (micGranted) connect() },
                            onConnectBookmark = { bookmark ->
                                if (micGranted) viewModel.connectBookmark(bookmark) { onConnected() }
                            },
                            onRemoveBookmark = { index -> viewModel.removeBookmark(index) },
                            onSaveBookmark = { saveBookmark() },
                            modifier = Modifier.width(480.dp),
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        JoinHero(compact = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(28.dp))
                        JoinCard(
                            address = address,
                            onAddressChange = { viewModel.address.value = it },
                            nickname = nickname,
                            onNicknameChange = { viewModel.nickname.value = it },
                            password = password,
                            onPasswordChange = { viewModel.password.value = it },
                            channel = channel,
                            onChannelChange = { viewModel.channel.value = it },
                            showPassword = showPassword,
                            onTogglePassword = { showPassword = !showPassword },
                            connecting = connecting,
                            error = error,
                            micGranted = micGranted,
                            onRequestMic = onRequestMic,
                            onOpenAppSettings = onOpenAppSettings,
                            bookmarks = bookmarks,
                            bookmarkIcons = bookmarkIcons,
                            onConnect = { if (micGranted) connect() },
                            onConnectBookmark = { bookmark ->
                                if (micGranted) viewModel.connectBookmark(bookmark) { onConnected() }
                            },
                            onRemoveBookmark = { index -> viewModel.removeBookmark(index) },
                            onSaveBookmark = { saveBookmark() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 560.dp),
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        )
    }
}

@Composable
private fun JoinHero(compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.then(
            if (!compact) Modifier.fillMaxSize() else Modifier,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadLogo(size = if (compact) 38.dp else 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.brand_team_speak),
                    style = MaterialTheme.typography.titleLarge,
                    color = PadColors.Accent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.brand_pad_client),
                    style = MaterialTheme.typography.labelSmall,
                    color = PadColors.Muted,
                    letterSpacing = 2.4.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            StatusPill(stringResource(R.string.join_status_native), PadColors.Green)
        }

        Spacer(Modifier.height(if (compact) 34.dp else 60.dp))

        Row(
            Modifier
                .clip(CircleShape)
                .background(PadColors.Accent.copy(alpha = 0.08f))
                .border(1.dp, PadColors.Accent.copy(alpha = 0.24f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(PadColors.Green),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.join_badge_private),
                style = MaterialTheme.typography.labelSmall,
                color = PadColors.Accent,
                letterSpacing = 1.sp,
            )
        }

        Spacer(Modifier.height(if (compact) 18.dp else 24.dp))

        Text(
            text = stringResource(R.string.join_title_line1),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = if (compact) 34.sp else 46.sp,
                lineHeight = if (compact) 42.sp else 54.sp,
            ),
            color = PadColors.Text,
        )
        Text(
            text = stringResource(R.string.join_title_line2),
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = if (compact) 34.sp else 46.sp,
                lineHeight = if (compact) 42.sp else 54.sp,
                shadow = Shadow(
                    color = PadColors.Green.copy(alpha = 0.38f),
                    blurRadius = 20f,
                ),
            ),
            color = Color(0xFF7DFFAE),
        )

        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.join_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFC4D9D3),
            modifier = Modifier.widthIn(max = 520.dp),
        )

        Spacer(Modifier.height(if (compact) 22.dp else 34.dp))

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroFeature(
                icon = Icons.Rounded.GraphicEq,
                title = stringResource(R.string.join_feature_voice_title),
                subtitle = stringResource(R.string.join_feature_voice_subtitle),
            )
            HeroFeature(
                icon = Icons.Rounded.Groups,
                title = stringResource(R.string.join_feature_channels_title),
                subtitle = stringResource(R.string.join_feature_channels_subtitle),
            )
            HeroFeature(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = stringResource(R.string.join_feature_chat_title),
                subtitle = stringResource(R.string.join_feature_chat_subtitle),
            )
            HeroFeature(
                icon = Icons.Rounded.ScreenShare,
                title = stringResource(R.string.join_feature_screen_title),
                subtitle = stringResource(R.string.join_feature_screen_subtitle),
            )
        }

        if (!compact) {
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(top = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = PadColors.Muted.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.join_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = PadColors.Muted.copy(alpha = 0.75f),
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    stringResource(R.string.join_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = PadColors.Muted.copy(alpha = 0.55f),
                )
            }
        } else {
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.join_version_local, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = PadColors.Muted.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun HeroFeature(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(PadColors.Accent.copy(alpha = 0.10f))
                .border(1.dp, PadColors.Accent.copy(alpha = 0.22f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PadColors.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = PadColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PadColors.Muted,
                modifier = Modifier.widthIn(max = 430.dp),
            )
        }
    }
}

@Composable
private fun JoinCard(
    address: String,
    onAddressChange: (String) -> Unit,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    channel: String,
    onChannelChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    connecting: Boolean,
    error: String?,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    bookmarks: List<ServerBookmark>,
    bookmarkIcons: Map<Long, androidx.compose.ui.graphics.ImageBitmap>,
    onConnect: () -> Unit,
    onConnectBookmark: (ServerBookmark) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onSaveBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PadCard(
        modifier = modifier,
        color = PadColors.Surface.copy(alpha = 0.95f),
        borderColor = PadColors.Border,
    ) {
        Column(
            Modifier.padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.join_welcome),
                        style = MaterialTheme.typography.headlineMedium,
                        color = PadColors.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.join_welcome_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Muted,
                    )
                }
                PadIconButton(
                    icon = Icons.Rounded.BookmarkAdd,
                    contentDescription = stringResource(R.string.join_save_bookmark),
                    onClick = onSaveBookmark,
                    tint = PadColors.Accent,
                    background = PadColors.Accent.copy(alpha = 0.10f),
                    size = 36.dp,
                )
            }

            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PadColors.Red.copy(alpha = 0.10f))
                        .border(1.dp, PadColors.Red.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = PadColors.Red,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Red,
                    )
                }
            }

            PadInput(
                value = address,
                onValueChange = onAddressChange,
                label = stringResource(R.string.join_address_label),
                placeholder = stringResource(R.string.join_address_placeholder),
                leadingIcon = Icons.Rounded.Dns,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                enabled = !connecting,
            )

            PadInput(
                value = nickname,
                onValueChange = onNicknameChange,
                label = stringResource(R.string.join_nickname_label),
                placeholder = stringResource(R.string.join_nickname_placeholder),
                leadingIcon = Icons.Rounded.Person,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                enabled = !connecting,
            )

            PadInput(
                value = password,
                onValueChange = onPasswordChange,
                label = stringResource(R.string.join_password_label),
                placeholder = stringResource(R.string.join_password_placeholder),
                leadingIcon = Icons.Rounded.Lock,
                isPassword = !showPassword,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                enabled = !connecting,
                required = false,
                trailing = {
                    PadIconButton(
                        icon = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) {
                            stringResource(R.string.join_hide_password)
                        } else {
                            stringResource(R.string.join_show_password)
                        },
                        onClick = onTogglePassword,
                        size = 30.dp,
                        iconSize = 17.dp,
                    )
                },
            )

            PadInput(
                value = channel,
                onValueChange = onChannelChange,
                label = stringResource(R.string.join_channel_label),
                placeholder = stringResource(R.string.join_channel_placeholder),
                leadingIcon = Icons.Rounded.Tag,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                enabled = !connecting,
                required = false,
            )

            if (!micGranted) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PadColors.Amber.copy(alpha = 0.10f))
                        .border(1.dp, PadColors.Amber.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = PadColors.Amber,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        stringResource(R.string.join_mic_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = PadColors.Amber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRequestMic) {
                        Text(
                            stringResource(R.string.join_grant),
                            color = PadColors.Amber,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onOpenAppSettings) {
                        Text(
                            stringResource(R.string.common_settings),
                            color = PadColors.Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Button(
                onClick = onConnect,
                enabled = !connecting && micGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PadColors.Accent,
                    contentColor = PadColors.OnAccent,
                    disabledContainerColor = PadColors.Accent.copy(alpha = 0.45f),
                    disabledContentColor = PadColors.OnAccent.copy(alpha = 0.7f),
                ),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PadColors.OnAccent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.join_connecting),
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else if (!micGranted) {
                    Text(
                        stringResource(R.string.join_need_mic),
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Text(
                        stringResource(R.string.join_connect),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (bookmarks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(PadColors.Border))
                    Text(
                        stringResource(R.string.join_quick),
                        style = MaterialTheme.typography.labelSmall,
                        color = PadColors.Muted,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(PadColors.Border))
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    bookmarks.take(3).forEachIndexed { index, bookmark ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (micGranted) 1f else 0.5f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PadColors.SurfaceAlt)
                                .clickable(enabled = !connecting && micGranted) { onConnectBookmark(bookmark) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PadAvatar(
                                name = bookmark.serverName ?: bookmark.name,
                                size = 32.dp,
                                image = bookmarkIcons[bookmark.iconId],
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    bookmark.serverName ?: bookmark.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = PadColors.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    bookmark.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PadColors.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            PadIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.join_remove_bookmark),
                                onClick = { onRemoveBookmark(index) },
                                tint = PadColors.Muted,
                                size = 28.dp,
                                iconSize = 15.dp,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = PadColors.Muted.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.join_session_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = PadColors.Muted.copy(alpha = 0.7f),
                )
            }
        }
    }
}
