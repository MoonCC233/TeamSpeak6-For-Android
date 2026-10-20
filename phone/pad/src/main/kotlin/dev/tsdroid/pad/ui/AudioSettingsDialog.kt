package dev.tsdroid.pad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.bridge.AudioBridge
import dev.tsdroid.bridge.MicMode
import dev.tsdroid.pad.R
import dev.tsdroid.viewmodel.ServerViewModel
import kotlin.math.roundToInt

@Composable
fun AudioSettingsDialog(
    serverViewModel: ServerViewModel,
    onDismiss: () -> Unit,
) {
    val micMode by serverViewModel.micMode.collectAsStateWithLifecycle()
    val micGain by serverViewModel.micGain.collectAsStateWithLifecycle()
    val audioGain by serverViewModel.audioGain.collectAsStateWithLifecycle()
    val noiseSuppression by serverViewModel.noiseSuppression.collectAsStateWithLifecycle()
    val vadThreshold by serverViewModel.vadThresholdDb.collectAsStateWithLifecycle()
    val outputMuted by serverViewModel.isOutputMuted.collectAsStateWithLifecycle()
    val micStatus by serverViewModel.micStatus.collectAsStateWithLifecycle()
    val serverInfo by serverViewModel.serverInfo.collectAsStateWithLifecycle()
    val serverAddress by serverViewModel.serverAddressLabel.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(680.dp)
                .heightIn(max = 690.dp),
            shape = RoundedCornerShape(22.dp),
            color = PadColors.Surface,
            contentColor = PadColors.Text,
            border = BorderStroke(1.dp, PadColors.Border),
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionKicker(stringResource(R.string.settings_kicker))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(R.string.audio_settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = PadColors.Text,
                        )
                    }
                    MicStatusBadge(micStatus)
                    Spacer(Modifier.width(12.dp))
                    PadIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        tint = PadColors.Muted,
                        size = 34.dp,
                        iconSize = 17.dp,
                    )
                }

                HorizontalDivider(color = PadColors.Border)

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    SettingsSection(title = stringResource(R.string.settings_microphone)) {
                        Text(
                            stringResource(R.string.mic_mode_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = PadColors.Text,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PadColors.Page)
                                .border(1.dp, PadColors.Border, RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MicMode.entries.forEach { mode ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (micMode == mode) PadColors.Accent else Color.Transparent)
                                        .clickable { serverViewModel.setMicMode(mode) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        when (mode) {
                                            MicMode.PTT -> stringResource(R.string.mic_mode_ptt)
                                            MicMode.VAD -> stringResource(R.string.mic_mode_vad)
                                            MicMode.OPEN -> stringResource(R.string.mic_mode_open_short)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (micMode == mode) PadColors.OnAccent else PadColors.Muted,
                                        fontWeight = if (micMode == mode) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        Text(
                            when (micMode) {
                                MicMode.PTT -> stringResource(R.string.mic_mode_ptt_help)
                                MicMode.VAD -> stringResource(R.string.mic_mode_vad_help)
                                MicMode.OPEN -> stringResource(R.string.mic_mode_open_help)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PadColors.Muted,
                        )

                        if (micMode == MicMode.VAD) {
                            GainSlider(
                                label = stringResource(R.string.vad_threshold),
                                value = vadThreshold,
                                range = -80f..-20f,
                                valueLabel = stringResource(
                                    R.string.value_dbfs,
                                    vadThreshold.roundToInt(),
                                ),
                                onCommit = { serverViewModel.setVadThresholdDb(it) },
                            )
                        }

                        GainSlider(
                            label = stringResource(R.string.input_gain),
                            value = micGain,
                            range = 0f..4f,
                            valueLabel = stringResource(
                                R.string.value_percent,
                                (micGain * 100).roundToInt(),
                            ),
                            onCommit = { serverViewModel.setMicGain(it) },
                        )

                        SettingSwitch(
                            title = stringResource(R.string.noise_suppression),
                            subtitle = stringResource(R.string.noise_suppression_next),
                            checked = noiseSuppression,
                            onCheckedChange = { serverViewModel.setNoiseSuppression(it) },
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_speaker)) {
                        GainSlider(
                            label = stringResource(R.string.overall_volume),
                            value = audioGain,
                            range = 0f..2f,
                            valueLabel = stringResource(
                                R.string.value_percent,
                                (audioGain * 100).roundToInt(),
                            ),
                            onCommit = { serverViewModel.setAudioGain(it) },
                        )
                        SettingSwitch(
                            title = stringResource(R.string.mute_speaker),
                            subtitle = stringResource(R.string.mute_speaker_subtitle),
                            checked = outputMuted,
                            onCheckedChange = { serverViewModel.toggleOutputMute() },
                        )
                    }

                    SettingsSection(title = stringResource(R.string.settings_connection)) {
                        InfoRow(
                            stringResource(R.string.info_server),
                            serverInfo?.name ?: stringResource(R.string.info_fetching),
                        )
                        InfoRow(
                            stringResource(R.string.info_address),
                            serverAddress ?: stringResource(R.string.value_none),
                        )
                        InfoRow(
                            stringResource(R.string.info_online),
                            if (serverInfo != null) {
                                stringResource(
                                    R.string.info_online_counts,
                                    serverInfo!!.clientsOnline,
                                    serverInfo!!.maxClients,
                                    serverInfo!!.channelsOnline,
                                )
                            } else {
                                stringResource(R.string.value_none)
                            },
                        )
                        InfoRow(
                            stringResource(R.string.info_core),
                            stringResource(R.string.info_core_value),
                        )
                    }
                }

                HorizontalDivider(color = PadColors.Border)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.common_close),
                            color = PadColors.Accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PadColors.Accent,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(9.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PadColors.SurfaceAlt.copy(alpha = 0.55f))
                .border(1.dp, PadColors.Border, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun GainSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = PadColors.Text,
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = PadColors.Accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = PadColors.Accent,
                activeTrackColor = PadColors.Accent,
                inactiveTrackColor = PadColors.Border,
            ),
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = PadColors.Text,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PadColors.Muted.copy(alpha = 0.8f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PadColors.OnAccent,
                checkedTrackColor = PadColors.Accent,
                uncheckedThumbColor = PadColors.Muted,
                uncheckedTrackColor = PadColors.SurfaceHigh,
                uncheckedBorderColor = PadColors.Border,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = PadColors.Muted,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = PadColors.Text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MicStatusBadge(status: AudioBridge.MicStatus) {
    val color = when (status) {
        AudioBridge.MicStatus.RUNNING -> PadColors.Green
        AudioBridge.MicStatus.STARTING,
        AudioBridge.MicStatus.RECOVERING -> PadColors.Amber
        AudioBridge.MicStatus.UNAVAILABLE -> PadColors.Red
        AudioBridge.MicStatus.IDLE -> PadColors.Muted
    }
    val text = when (status) {
        AudioBridge.MicStatus.RUNNING -> stringResource(R.string.status_mic_ready)
        AudioBridge.MicStatus.STARTING -> stringResource(R.string.status_starting_short)
        AudioBridge.MicStatus.RECOVERING -> stringResource(R.string.status_recovering_short)
        AudioBridge.MicStatus.UNAVAILABLE -> stringResource(R.string.status_mic_unavailable)
        AudioBridge.MicStatus.IDLE -> stringResource(R.string.status_idle_short)
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
