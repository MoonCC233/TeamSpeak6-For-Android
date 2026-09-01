package com.mooncc.teamspeak9.ui.screen.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mooncc.teamspeak9.domain.model.ScreenShareConfig
import com.mooncc.teamspeak9.domain.model.ScreenShareMode
import com.mooncc.teamspeak9.domain.model.ScreenSharePrivacy
import com.mooncc.teamspeak9.domain.model.ScreenShareResolution

/**
 * Share options, mirroring the desktop client's screen-share settings.
 *
 * Changes apply live to an ongoing share for resolution / FPS / bitrate; mode and
 * privacy only take effect for the next share since they are announced once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScreenShareOptionsDialog(
    config: ScreenShareConfig,
    serverModeAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ScreenShareConfig) -> Unit,
) {
    var mode by remember { mutableStateOf(config.mode) }
    var privacy by remember { mutableStateOf(config.privacy) }
    var resolution by remember { mutableStateOf(config.resolution) }
    var fps by remember { mutableStateOf(config.fps) }
    var bitrate by remember { mutableStateOf(config.videoBitrateKbps) }
    var viewerLimit by remember { mutableStateOf(config.viewerLimit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("屏幕共享选项") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("连接模式") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == ScreenShareMode.P2P,
                            onClick = { mode = ScreenShareMode.P2P },
                            label = { Text("P2P") },
                        )
                        FilterChip(
                            selected = mode == ScreenShareMode.SERVER,
                            enabled = serverModeAvailable,
                            onClick = { mode = ScreenShareMode.SERVER },
                            label = { Text("服务器中转") },
                        )
                    }
                    if (!serverModeAvailable) {
                        Hint("当前信令服务未启用服务器中转，只能使用 P2P。")
                    }
                }

                Section("分辨率") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenShareResolution.entries.forEach { option ->
                            FilterChip(
                                selected = resolution == option,
                                onClick = { resolution = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                Section("帧率：$fps FPS") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { option ->
                            FilterChip(
                                selected = fps == option,
                                onClick = { fps = option },
                                label = { Text("$option") },
                            )
                        }
                    }
                }

                Section("视频码率：$bitrate Kbps") {
                    Slider(
                        value = bitrate.toFloat(),
                        onValueChange = { bitrate = it.toInt() / 100 * 100 },
                        valueRange = 500f..8000f,
                    )
                }

                Section("隐私") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenSharePrivacy.entries.forEach { option ->
                            FilterChip(
                                selected = privacy == option,
                                onClick = { privacy = option },
                                label = { Text(privacyLabel(option)) },
                            )
                        }
                    }
                    if (privacy == ScreenSharePrivacy.PRIVATE) {
                        Hint("私人模式下每位观众都需要你手动允许。")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "共享设备音频",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "尚未实现，暂不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = false,
                        onCheckedChange = null,
                        enabled = false,
                    )
                }

                Section(
                    if (viewerLimit == 0) "观众上限：不限制" else "观众上限：$viewerLimit",
                ) {
                    Slider(
                        value = viewerLimit.toFloat(),
                        onValueChange = { viewerLimit = it.toInt() },
                        valueRange = 0f..20f,
                        steps = 19,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        config.copy(
                            mode = mode,
                            privacy = privacy,
                            resolution = resolution,
                            fps = fps,
                            videoBitrateKbps = bitrate,
                            captureAudio = false,
                            viewerLimit = viewerLimit,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun privacyLabel(privacy: ScreenSharePrivacy): String = when (privacy) {
    ScreenSharePrivacy.PUBLIC -> "公开"
    ScreenSharePrivacy.CONTACTS -> "联系人"
    ScreenSharePrivacy.PRIVATE -> "私人"
}
