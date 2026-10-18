package dev.tsdroid.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import dev.tsdroid.bridge.TsFileEntry
import dev.tsdroid.ui.icon.AppIcons
import dev.tslib.Channel
import dev.tslib.User
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ShareTarget {
    data object Channel : ShareTarget()

    /** [peerKey] is the peer's permanent uid, not the session client id. */
    data class PrivateMessage(val peerKey: String, val nickname: String) : ShareTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerDialog(
    currentPath: String,
    files: List<TsFileEntry>,
    isLoading: Boolean,
    users: List<User>,
    permissionHints: Long = 0L,
    onNavigateToFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onCreateDirectory: (String) -> Unit,
    onUploadFile: (String, ByteArray) -> Unit,
    onShareFile: (ShareTarget, String, Long) -> Unit,
    onDismiss: () -> Unit,
    onPreviewImage: (String) -> Unit = {},
    wallpaper: ImageBitmap? = null,
) {
    // Permission flags — write operations require explicit permission bits.
    // When permissionHints == 0 (server didn't send hints), hide write buttons.
    // Download/browse are always allowed (CommandError toast handles denials).
    val canUpload = (permissionHints and Channel.PERM_FILE_UPLOAD) != 0L
    val canDelete = (permissionHints and Channel.PERM_FILE_DELETE) != 0L
    val canRename = (permissionHints and Channel.PERM_FILE_RENAME) != 0L
    val canCreateDir = (permissionHints and Channel.PERM_FILE_DIRECTORY_CREATE) != 0L
    val hasHints = permissionHints != 0L
    val canDownload = !hasHints || (permissionHints and Channel.PERM_FILE_DOWNLOAD) != 0L
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var shareTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }

    // The in-place frosted row menu: which entry, where its ⋯ sits (root pixels), plus the panel's own
    // origin and size — those turn the row's root coordinates into the overlay's.
    var menuEntry by remember { mutableStateOf<TsFileEntry?>(null) }
    var menuAnchorRight by remember { mutableFloatStateOf(0f) }
    var menuAnchorBottom by remember { mutableFloatStateOf(0f) }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
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
                // else: unreadable content — previously silent, kept silent
            }
        }
    }

    // Sort: folders first, then files, alphabetically
    val sortedFiles = remember(files) {
        files.sortedWith(compareBy<TsFileEntry> { it.isFile }.thenBy { it.name.lowercase() })
    }

    // Full-screen glass panel: this used to be an opaque `colorScheme.surface` slab. The
    // `contentColor` comes from the wallpaper actually behind the glass, so everything below
    // adapts through LocalContentColor instead of assuming a light backdrop.
    LiquidGlassPanel(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                panelOrigin = coordinates.positionInRoot()
                panelSize = coordinates.size
            },
        wallpaper = wallpaper,
        shape = RoundedCornerShape(0.dp),
        elevation = 0.dp,
        contentPadding = PaddingValues(0.dp),
    ) { contentColor ->
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                // The same compact bar as the rest of the app, but its title and actions keep taking
                // their colour from the glass behind them instead of from the theme.
                CompactTopBar(
                    title = currentPath,
                    titleColor = LocalContentColor.current,
                    iconColor = LocalContentColor.current,
                    navigationIcon = {
                        if (currentPath != "/") {
                            IconButton(onClick = onNavigateUp) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    actions = {
                        if (canCreateDir) {
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.new_folder))
                            }
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                        if (canUpload) {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.upload))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                )

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        sortedFiles.isEmpty() -> {
                            // No opaque backdrop any more: follow the glass instead of assuming
                            // the light-theme `onSurfaceVariant` is readable.
                            Text(
                                text = stringResource(R.string.no_files),
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge,
                                color = LocalContentColor.current.copy(alpha = 0.78f),
                            )
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(sortedFiles, key = { it.name }) { entry ->
                                    FileEntryRow(
                                        entry = entry,
                                        canDownload = canDownload,
                                        onFolderClick = { onNavigateToFolder(entry.name) },
                                        onDownload = { onDownload(entry.name) },
                                        onPreviewImage = { onPreviewImage(entry.name) },
                                        onOpenMenu = { right, bottom ->
                                            menuEntry = entry
                                            menuAnchorRight = right
                                            menuAnchorBottom = bottom
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }
    }
    // (end of LiquidGlassPanel) — menus and dialogs below are still inside the function

    // File row menu, drawn here rather than as a DropdownMenu: a popup is a window of its own, where
    // there is no wallpaper behind it to frost, and it would be clipped by the list it hangs from.
    menuEntry?.let { entry ->
        Box(modifier = Modifier.fillMaxSize().zIndex(3f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { menuEntry = null },
                    ),
            )
            GlassCard(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(176.dp)
                    .offset {
                        IntOffset(
                            x = (menuAnchorRight - panelOrigin.x - panelSize.width).toInt(),
                            y = (menuAnchorBottom - panelOrigin.y).toInt(),
                        )
                    },
                wallpaper = wallpaper,
                shape = RoundedCornerShape(18.dp),
                elevation = 6.dp,
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                Column {
                    if (entry.isFile && canDownload) {
                        GlassMenuItem(Icons.Default.Download, stringResource(R.string.download)) {
                            menuEntry = null
                            onDownload(entry.name)
                        }
                        GlassMenuItem(Icons.Default.Share, stringResource(R.string.share_in_channel)) {
                            menuEntry = null
                            shareTarget = entry.name to entry.size
                        }
                    }
                    if (canRename) {
                        GlassMenuItem(Icons.Default.DriveFileRenameOutline, stringResource(R.string.rename)) {
                            menuEntry = null
                            renameTarget = entry.name
                        }
                    }
                    if (canDelete) {
                        GlassMenuItem(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.delete_file),
                            destructive = true,
                        ) {
                            menuEntry = null
                            deleteTarget = entry.name
                        }
                    }
                }
            }
        }
    }

    // New folder dialog
    if (showNewFolderDialog) {
        InputDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.enter_folder_name),
            confirmText = stringResource(R.string.create),
            wallpaper = wallpaper,
            onConfirm = { name ->
                if (name.isNotBlank()) onCreateDirectory(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    // Rename dialog
    renameTarget?.let { oldName ->
        InputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.enter_new_name),
            initialValue = oldName,
            confirmText = stringResource(R.string.rename),
            wallpaper = wallpaper,
            onConfirm = { newName ->
                if (newName.isNotBlank() && newName != oldName) onRename(oldName, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { name ->
        GlassDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.delete_file),
            wallpaper = wallpaper,
            body = {
                Text(
                    text = stringResource(R.string.confirm_delete, name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete_file), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // Share target selection dialog
    shareTarget?.let { (fileName, fileSize) ->
        ShareTargetDialog(
            fileName = fileName,
            users = users,
            wallpaper = wallpaper,
            onSelect = { target ->
                onShareFile(target, fileName, fileSize)
                shareTarget = null
            },
            onDismiss = { shareTarget = null },
        )
    }
}

@Composable
private fun ShareTargetDialog(
    fileName: String,
    users: List<User>,
    wallpaper: ImageBitmap?,
    onSelect: (ShareTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.share_file_title, fileName),
        wallpaper = wallpaper,
        body = {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 360.dp)) {
                // Channel option
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(ShareTarget.Channel) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.share_in_channel),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                // Users for PM
                items(users.filter { it.clientType.toInt() == 0 }, key = { it.id }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(ShareTarget.PrivateMessage(user.uid, user.nickname))
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            user.nickname,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun FileEntryRow(
    entry: TsFileEntry,
    canDownload: Boolean = true,
    onFolderClick: () -> Unit,
    onDownload: () -> Unit,
    onPreviewImage: () -> Unit,
    /** Hands the ⋯ button's position (root pixels) up, where the frosted menu is drawn. */
    onOpenMenu: (right: Float, bottom: Float) -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!entry.isFile) onFolderClick()
                else if (isImageFile(entry.name)) onPreviewImage()
                else if (canDownload) onDownload()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon
        val icon = when {
            !entry.isFile -> Icons.Default.Folder
            isImageFile(entry.name) -> Icons.Default.Image
            else -> AppIcons.File
        }
        val tint = if (!entry.isFile) MaterialTheme.colorScheme.primary
            else LocalContentColor.current.copy(alpha = 0.78f)
        Icon(
            icon,
            contentDescription = if (!entry.isFile) stringResource(R.string.folder) else null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(12.dp))

        // Name + details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isFile) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatFileSize(context, entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                    Text(
                        text = formatTimestamp(entry.datetime),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // Menu button — the menu itself is drawn at the root of this panel, further down: a
        // DropdownMenu is a window of its own (nothing behind it to frost) and would be clipped by
        // the list it belongs to.
        var anchorRight by remember { mutableFloatStateOf(0f) }
        var anchorBottom by remember { mutableFloatStateOf(0f) }
        IconButton(
            onClick = { onOpenMenu(anchorRight, anchorBottom) },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                anchorRight = position.x + coordinates.size.width
                anchorBottom = position.y + coordinates.size.height
            },
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun InputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmText: String,
    wallpaper: ImageBitmap? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = title,
        wallpaper = wallpaper,
        body = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                // Translucent, so the panel's frost shows through the field instead of the field
                // sitting on it as an opaque slab.
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.44f),
                ),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(confirmText)
            }
        },
    )
}

private fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
}

/**
 * File sizes go through string resources so the units follow the UI language
 * (the hardcoded "Ko"/"Mo"/"Go" were French-only).
 */
private fun formatFileSize(context: android.content.Context, bytes: Long): String {
    return when {
        bytes < 1024 -> context.getString(R.string.file_size_bytes, bytes)
        bytes < 1024 * 1024 -> context.getString(R.string.file_size_kb, bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> context.getString(R.string.file_size_mb, bytes / (1024.0 * 1024.0))
        else -> context.getString(R.string.file_size_gb, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/** Locale-default short date + time instead of a hardcoded French day/month order. */
private fun formatTimestamp(unixSeconds: Long): String {
    return try {
        DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault(),
        ).format(Date(unixSeconds * 1000))
    } catch (_: Exception) {
        ""
    }
}
