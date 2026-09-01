package com.mooncc.teamspeak9.ui.screen.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.Client
import com.mooncc.teamspeak9.domain.model.GroupType
import com.mooncc.teamspeak9.domain.model.ServerGroup
import java.util.concurrent.TimeUnit

@Composable
fun ChannelPasswordDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入 ${channel.displayName}") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("频道密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("加入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ClientActionsDialog(
    client: Client,
    isLocal: Boolean,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit,
    onPoke: () -> Unit,
    onInfo: () -> Unit,
    onAudio: () -> Unit,
    onServerGroups: () -> Unit,
    onMove: () -> Unit,
    onKickChannel: () -> Unit,
    onKickServer: () -> Unit,
    onBan: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(client.nickname) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ActionItem("私聊", Icons.AutoMirrored.Filled.Chat, onOpenChat)
                ActionItem("查看信息", Icons.Default.Info, onInfo)
                if (!isLocal) {
                    ActionItem(
                        label = if (client.localMuted) "取消本地静音 / 音量" else "本地静音 / 音量",
                        icon = if (client.localMuted) {
                            Icons.Default.VolumeOff
                        } else {
                            Icons.Default.VolumeUp
                        },
                        onClick = onAudio,
                    )
                    ActionItem("戳一下", Icons.Default.NotificationsActive, onPoke)
                    ActionItem("移动到频道", Icons.AutoMirrored.Filled.Login, onMove)
                    ActionItem("服务器组", Icons.Default.Groups, onServerGroups)
                    ActionItem("踢出频道", Icons.AutoMirrored.Filled.ExitToApp, onKickChannel)
                    ActionItem("踢出服务器", Icons.Default.PersonRemove, onKickServer)
                    ActionItem("封禁", Icons.Default.Block, onBan)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * Local mute and per-client volume. Both are client-side only, so the values
 * come straight from the live client snapshot rather than local dialog state.
 */
@Composable
fun ClientAudioDialog(
    client: Client,
    onDismiss: () -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${client.nickname} 的音频") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clickable { onMutedChange(!client.localMuted) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = if (client.localMuted) {
                            Icons.Default.VolumeOff
                        } else {
                            Icons.Default.VolumeUp
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("本地静音", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = client.localMuted, onCheckedChange = onMutedChange)
                }
                Text(
                    text = "音量 ${client.volumePercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = client.volumePercent.toFloat(),
                    onValueChange = { onVolumeChange(it.toInt()) },
                    valueRange = 0f..200f,
                    steps = 39,
                    enabled = !client.localMuted,
                )
                Text(
                    text = "仅影响本机播放，不会通知服务器或其他用户。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = { onVolumeChange(100) }) { Text("重置音量") }
        },
    )
}

@Composable
fun TalkRequestDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("申请发言权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在受限频道中，频道管理者会收到你的申请。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("附加消息（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(message) }) { Text("申请") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ChannelActionsDialog(
    channel: Channel,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
    onOpenChat: () -> Unit,
    onInfo: () -> Unit,
    onCreateSub: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.displayName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ActionItem("加入频道", Icons.AutoMirrored.Filled.Login, onJoin)
                ActionItem("频道聊天", Icons.AutoMirrored.Filled.Chat, onOpenChat)
                ActionItem("频道信息", Icons.Default.Info, onInfo)
                if (canManage) {
                    ActionItem("创建子频道", Icons.AutoMirrored.Filled.PlaylistAdd, onCreateSub)
                    ActionItem("编辑频道", Icons.Default.Edit, onEdit)
                    ActionItem("删除频道", Icons.Default.Delete, onDelete)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ActionItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun AwayDialog(
    initialAway: Boolean,
    initialMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, String) -> Unit,
) {
    var away by remember { mutableStateOf(initialAway) }
    var message by remember { mutableStateOf(initialMessage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离开状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = away, onCheckedChange = { away = it })
                    Text("标记为离开")
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("离开留言") },
                    enabled = away,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(away, message) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BanDialog(
    client: Client,
    onDismiss: () -> Unit,
    onConfirm: (durationSeconds: Long, reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(BAN_DURATIONS[2]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("封禁 ${client.nickname}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("原因") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("时长", style = MaterialTheme.typography.labelLarge)
                BAN_DURATIONS.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { selected = option },
                        )
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.seconds, reason) }) { Text("封禁") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class BanDuration(val label: String, val seconds: Long)

private val BAN_DURATIONS = listOf(
    BanDuration("5 分钟", TimeUnit.MINUTES.toSeconds(5)),
    BanDuration("1 小时", TimeUnit.HOURS.toSeconds(1)),
    BanDuration("1 天", TimeUnit.DAYS.toSeconds(1)),
    BanDuration("7 天", TimeUnit.DAYS.toSeconds(7)),
    BanDuration("永久", 0L),
)

@Composable
fun ServerGroupsDialog(
    client: Client,
    groups: List<ServerGroup>,
    onDismiss: () -> Unit,
    onToggle: (groupId: Int, add: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${client.nickname} 的服务器组") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                groups.filter { it.type == GroupType.REGULAR }
                    .forEach { group ->
                        val isMember = group.id in client.serverGroups
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isMember,
                                onCheckedChange = { onToggle(group.id, it) },
                            )
                            Text(group.name)
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ClientInfoDialog(client: Client, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(client.nickname) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfoRow("客户端 ID", client.id.toString())
                InfoRow("数据库 ID", client.databaseId.toString())
                InfoRow("唯一标识", client.uniqueIdentifier)
                InfoRow("平台", client.platform.ifBlank { "未知" })
                InfoRow("版本", client.version.ifBlank { "未知" })
                InfoRow("国家", client.country.ifBlank { "未知" })
                InfoRow("Talk Power", client.talkPower.toString())
                InfoRow("在线时长", formatDuration(client.connectedTimeMs))
                InfoRow("空闲时长", formatDuration(client.idleTimeMs))
                InfoRow("麦克风", if (client.isMicMuted) "静音" else "开启")
                InfoRow("扬声器", if (client.isSpeakerMuted) "静音" else "开启")
                InfoRow("频道指挥", yesNo(client.isChannelCommander))
                InfoRow("优先发言", yesNo(client.isPrioritySpeaker))
                InfoRow("录制中", yesNo(client.isRecording))
                InfoRow("屏幕共享", yesNo(client.isSharingScreen))
                InfoRow("申请发言", yesNo(client.isRequestingTalkPower))
                if (client.isRequestingTalkPower && client.talkRequestMessage.isNotBlank()) {
                    InfoRow("申请消息", client.talkRequestMessage)
                }
                InfoRow("本地静音", yesNo(client.localMuted))
                InfoRow("本地音量", "${client.volumePercent}%")
                if (client.description.isNotBlank()) {
                    InfoRow("描述", client.description)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun ChannelInfoDialog(channel: Channel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.displayName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfoRow("频道 ID", channel.id.toString())
                InfoRow("编解码器", channel.codec.label)
                InfoRow("编码质量", channel.codecQuality.toString())
                InfoRow(
                    "人数上限",
                    if (channel.maxClients < 0) "不限" else channel.maxClients.toString(),
                )
                InfoRow("当前人数", channel.totalClients.toString())
                InfoRow(
                    "类型",
                    when {
                        channel.isPermanent -> "永久"
                        channel.isSemiPermanent -> "半永久"
                        else -> "临时"
                    },
                )
                InfoRow("需要密码", yesNo(channel.hasPassword))
                InfoRow("默认频道", yesNo(channel.isDefault))
                InfoRow("发言权限要求", channel.neededTalkPower.toString())
                if (channel.topic.isNotBlank()) InfoRow("主题", channel.topic)
                if (channel.description.isNotBlank()) InfoRow("描述", channel.description)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.6f),
        )
    }
}

private fun yesNo(value: Boolean) = if (value) "是" else "否"

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "-"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分${seconds}秒"
}

/** Create / edit a channel. [existing] null means create. */
@Composable
fun ChannelEditorDialog(
    existing: Channel?,
    parentId: Int,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        password: String,
        topic: String,
        description: String,
        permanent: Boolean,
        semiPermanent: Boolean,
        maxClients: Int,
    ) -> Unit,
    onEdit: (properties: Map<String, String>) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf(existing?.topic.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var permanent by remember { mutableStateOf(existing?.isPermanent ?: false) }
    var semiPermanent by remember { mutableStateOf(existing?.isSemiPermanent ?: false) }
    var maxClientsText by remember {
        mutableStateOf(existing?.maxClients?.takeIf { it >= 0 }?.toString().orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "创建频道" else "编辑频道") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("频道名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("主题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（留空表示无密码）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxClientsText,
                    onValueChange = { maxClientsText = it.filter(Char::isDigit) },
                    label = { Text("人数上限（留空为不限）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = permanent,
                        onCheckedChange = {
                            permanent = it
                            if (it) semiPermanent = false
                        },
                    )
                    Text("永久频道")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = semiPermanent,
                        onCheckedChange = {
                            semiPermanent = it
                            if (it) permanent = false
                        },
                    )
                    Text("半永久频道")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val maxClients = maxClientsText.toIntOrNull() ?: -1
                    if (existing == null) {
                        onCreate(
                            name,
                            password,
                            topic,
                            description,
                            permanent,
                            semiPermanent,
                            maxClients,
                        )
                    } else {
                        onEdit(
                            buildMap {
                                put("channel_name", name)
                                put("channel_topic", topic)
                                put("channel_description", description)
                                put("channel_flag_permanent", if (permanent) "1" else "0")
                                put("channel_flag_semi_permanent", if (semiPermanent) "1" else "0")
                                put("channel_maxclients", maxClients.toString())
                                put(
                                    "channel_flag_maxclients_unlimited",
                                    if (maxClients < 0) "1" else "0",
                                )
                                if (password.isNotBlank()) put("channel_password", password)
                            },
                        )
                    }
                },
            ) { Text(if (existing == null) "创建" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun MoveClientDialog(
    client: Client,
    channels: List<Channel>,
    onDismiss: () -> Unit,
    onConfirm: (channelId: Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动 ${client.nickname}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                channels.filterNot { it.isSpacer }.forEach { channel ->
                    TextButton(
                        onClick = { onConfirm(channel.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(channel.displayName) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Convenience wrapper for the poke dialog. */
@Composable
fun PokeDialog(client: Client, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    TextInputDialog(
        title = "戳 ${client.nickname}",
        label = "消息",
        confirmLabel = "发送",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Convenience wrapper for the kick dialog. */
@Composable
fun KickDialog(
    client: Client,
    fromServer: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    TextInputDialog(
        title = if (fromServer) "踢出服务器：${client.nickname}" else "踢出频道：${client.nickname}",
        label = "原因",
        confirmLabel = "踢出",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
