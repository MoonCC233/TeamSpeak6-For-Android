package com.mooncc.teamspeak6.ui.screen.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak6.domain.model.Bookmark

/**
 * Create / edit dialog for a server bookmark.
 *
 * @param onSave receives true when the user asked to connect right away.
 */
@Composable
fun BookmarkEditorDialog(
    state: BookmarkEditorState,
    onDraftChange: ((Bookmark) -> Bookmark) -> Unit,
    onDismiss: () -> Unit,
    onSave: (connectAfterSave: Boolean) -> Unit,
) {
    val draft = state.bookmark
    val canSave = draft.label.isNotBlank() && draft.host.isNotBlank() &&
        draft.nickname.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isNew) "添加服务器" else "编辑服务器") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { value -> onDraftChange { it.copy(label = value) } },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { value -> onDraftChange { it.copy(host = value.trim()) } },
                    label = { Text("服务器地址") },
                    placeholder = { Text("ts.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.voicePort.toString(),
                        onValueChange = { value ->
                            onDraftChange {
                                it.copy(voicePort = value.toIntOrNull() ?: it.voicePort)
                            }
                        },
                        label = { Text("语音端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.queryPort.toString(),
                        onValueChange = { value ->
                            onDraftChange {
                                it.copy(queryPort = value.toIntOrNull() ?: it.queryPort)
                            }
                        },
                        label = { Text("查询端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                CheckboxRow(
                    checked = draft.useTls,
                    label = "查询接口使用 HTTPS",
                    onCheckedChange = { value -> onDraftChange { it.copy(useTls = value) } },
                )
                OutlinedTextField(
                    value = draft.nickname,
                    onValueChange = { value -> onDraftChange { it.copy(nickname = value) } },
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { value -> onDraftChange { it.copy(apiKey = value.trim()) } },
                    label = { Text("WebQuery API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.serverPassword,
                    onValueChange = { value -> onDraftChange { it.copy(serverPassword = value) } },
                    label = { Text("服务器密码（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.virtualServerId.toString(),
                        onValueChange = { value ->
                            onDraftChange {
                                it.copy(virtualServerId = value.toIntOrNull() ?: it.virtualServerId)
                            }
                        },
                        label = { Text("虚拟服务器 ID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.defaultChannel,
                        onValueChange = { value ->
                            onDraftChange { it.copy(defaultChannel = value) }
                        },
                        label = { Text("默认频道") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = draft.bridgeUrl,
                    onValueChange = { value -> onDraftChange { it.copy(bridgeUrl = value.trim()) } },
                    label = { Text("语音桥地址（WebRTC）") },
                    placeholder = { Text("wss://bridge.example.com/ws") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "语音与屏幕共享需要配套的 WebRTC 桥接服务；留空则仅启用管控与聊天功能。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckboxRow(
                    checked = draft.autoConnect,
                    label = "启动时自动连接",
                    onCheckedChange = { value -> onDraftChange { it.copy(autoConnect = value) } },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(true) }) {
                Text("保存并连接")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = canSave, onClick = { onSave(false) }) { Text("保存") }
            }
        },
    )
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
