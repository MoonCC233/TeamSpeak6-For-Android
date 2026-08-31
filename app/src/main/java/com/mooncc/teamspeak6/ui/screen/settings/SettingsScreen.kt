package com.mooncc.teamspeak6.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                SectionTitle("身份")
                OutlinedTextField(
                    value = settings.defaultNickname,
                    onValueChange = { value ->
                        viewModel.update { it.copy(defaultNickname = value) }
                    },
                    label = { Text("默认昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                SectionTitle("语音")
                SwitchRow(
                    title = "按键说话",
                    subtitle = "关闭时使用声音激活",
                    checked = settings.pushToTalkEnabled,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(pushToTalkEnabled = value) }
                    },
                )
                SliderRow(
                    title = "声音激活阈值",
                    valueLabel = "${settings.voiceActivationThresholdDb} dB",
                    value = settings.voiceActivationThresholdDb.toFloat(),
                    range = -80f..0f,
                    steps = 79,
                    onValueChange = { value ->
                        viewModel.update {
                            it.copy(voiceActivationThresholdDb = value.toInt())
                        }
                    },
                )
                SliderRow(
                    title = "输出音量",
                    valueLabel = "${settings.outputVolumePercent}%",
                    value = settings.outputVolumePercent.toFloat(),
                    range = 0f..200f,
                    steps = 39,
                    onValueChange = { value ->
                        viewModel.update { it.copy(outputVolumePercent = value.toInt()) }
                    },
                )
                SliderRow(
                    title = "麦克风增益",
                    valueLabel = "${settings.inputGainPercent}%",
                    value = settings.inputGainPercent.toFloat(),
                    range = 0f..200f,
                    steps = 39,
                    onValueChange = { value ->
                        viewModel.update { it.copy(inputGainPercent = value.toInt()) }
                    },
                )
                SwitchRow(
                    title = "回声消除",
                    checked = settings.echoCancellation,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(echoCancellation = value) }
                    },
                )
                SwitchRow(
                    title = "噪声抑制",
                    checked = settings.noiseSuppression,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(noiseSuppression = value) }
                    },
                )
                SwitchRow(
                    title = "自动增益",
                    checked = settings.autoGainControl,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(autoGainControl = value) }
                    },
                )
            }

            item {
                SectionTitle("屏幕共享")
                SliderRow(
                    title = "码率",
                    valueLabel = "${settings.screenShareBitrateKbps} kbps",
                    value = settings.screenShareBitrateKbps.toFloat(),
                    range = 500f..8000f,
                    steps = 29,
                    onValueChange = { value ->
                        viewModel.update { it.copy(screenShareBitrateKbps = value.toInt()) }
                    },
                )
                SliderRow(
                    title = "帧率",
                    valueLabel = "${settings.screenShareFps} fps",
                    value = settings.screenShareFps.toFloat(),
                    range = 5f..60f,
                    steps = 10,
                    onValueChange = { value ->
                        viewModel.update { it.copy(screenShareFps = value.toInt()) }
                    },
                )
            }

            item {
                SectionTitle("通知")
                SwitchRow(
                    title = "加入/离开提示",
                    checked = settings.notifyOnJoinLeave,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(notifyOnJoinLeave = value) }
                    },
                )
                SwitchRow(
                    title = "被戳提示",
                    checked = settings.notifyOnPoke,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(notifyOnPoke = value) }
                    },
                )
                SwitchRow(
                    title = "新消息提示",
                    checked = settings.notifyOnMessage,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(notifyOnMessage = value) }
                    },
                )
            }

            item {
                SectionTitle("高级")
                SwitchRow(
                    title = "连接时保持屏幕常亮",
                    checked = settings.keepScreenOnWhileConnected,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(keepScreenOnWhileConnected = value) }
                    },
                )
                SwitchRow(
                    title = "自动订阅所有频道",
                    checked = settings.autoSubscribeChannels,
                    onCheckedChange = { value ->
                        viewModel.update { it.copy(autoSubscribeChannels = value) }
                    },
                )
                OutlinedTextField(
                    value = settings.pollIntervalMs.toString(),
                    onValueChange = { value ->
                        val parsed = value.filter(Char::isDigit).toIntOrNull()
                        if (parsed != null) {
                            viewModel.update { it.copy(pollIntervalMs = parsed) }
                        }
                    },
                    label = { Text("状态刷新间隔（毫秒）") },
                    supportingText = { Text("越小越实时，但更耗流量。范围 500–15000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}
