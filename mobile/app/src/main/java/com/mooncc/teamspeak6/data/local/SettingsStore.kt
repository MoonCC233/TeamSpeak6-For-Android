package com.mooncc.teamspeak6.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("teamspeak_settings")

/**
 * User preferences that apply across connections.
 */
data class AppSettings(
    val defaultNickname: String = "AndroidUser",
    val pushToTalkEnabled: Boolean = false,
    val voiceActivationThresholdDb: Int = -40,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
    val outputVolumePercent: Int = 100,
    val inputGainPercent: Int = 100,
    val notifyOnJoinLeave: Boolean = true,
    val notifyOnPoke: Boolean = true,
    val notifyOnMessage: Boolean = true,
    val keepScreenOnWhileConnected: Boolean = true,
    val autoSubscribeChannels: Boolean = true,
    val screenShareBitrateKbps: Int = 2500,
    val screenShareFps: Int = 15,
    /** WebSocket URL of the screen-share signaling service, empty when unset. */
    val signalingUrl: String = "",
    /** Persisted as [com.mooncc.teamspeak6.domain.model.ScreenShareMode] name. */
    val screenShareMode: String = "P2P",
    /** Persisted as [com.mooncc.teamspeak6.domain.model.ScreenSharePrivacy] name. */
    val screenSharePrivacy: String = "PUBLIC",
    /** Persisted as [com.mooncc.teamspeak6.domain.model.ScreenShareResolution] name. */
    val screenShareResolution: String = "P720",
    val screenShareAudio: Boolean = false,
    val screenShareAudioBitrateKbps: Int = 128,
    /** 0 means unlimited. */
    val screenShareViewerLimit: Int = 0,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(read(prefs))
            prefs[KEY_NICKNAME] = next.defaultNickname
            prefs[KEY_PTT] = next.pushToTalkEnabled
            prefs[KEY_VAD_DB] = next.voiceActivationThresholdDb
            prefs[KEY_AEC] = next.echoCancellation
            prefs[KEY_NS] = next.noiseSuppression
            prefs[KEY_AGC] = next.autoGainControl
            prefs[KEY_OUTPUT_VOLUME] = next.outputVolumePercent
            prefs[KEY_INPUT_GAIN] = next.inputGainPercent
            prefs[KEY_NOTIFY_JOIN] = next.notifyOnJoinLeave
            prefs[KEY_NOTIFY_POKE] = next.notifyOnPoke
            prefs[KEY_NOTIFY_MESSAGE] = next.notifyOnMessage
            prefs[KEY_KEEP_SCREEN_ON] = next.keepScreenOnWhileConnected
            prefs[KEY_AUTO_SUBSCRIBE] = next.autoSubscribeChannels
            prefs[KEY_SHARE_BITRATE] = next.screenShareBitrateKbps
            prefs[KEY_SHARE_FPS] = next.screenShareFps
            prefs[KEY_SIGNALING_URL] = next.signalingUrl
            prefs[KEY_SHARE_MODE] = next.screenShareMode
            prefs[KEY_SHARE_PRIVACY] = next.screenSharePrivacy
            prefs[KEY_SHARE_RESOLUTION] = next.screenShareResolution
            prefs[KEY_SHARE_AUDIO] = next.screenShareAudio
            prefs[KEY_SHARE_AUDIO_BITRATE] = next.screenShareAudioBitrateKbps
            prefs[KEY_SHARE_VIEWER_LIMIT] = next.screenShareViewerLimit
        }
    }

    private fun read(prefs: Preferences) = AppSettings(
        defaultNickname = prefs[KEY_NICKNAME] ?: "AndroidUser",
        pushToTalkEnabled = prefs[KEY_PTT] ?: false,
        voiceActivationThresholdDb = prefs[KEY_VAD_DB] ?: -40,
        echoCancellation = prefs[KEY_AEC] ?: true,
        noiseSuppression = prefs[KEY_NS] ?: true,
        autoGainControl = prefs[KEY_AGC] ?: true,
        outputVolumePercent = prefs[KEY_OUTPUT_VOLUME] ?: 100,
        inputGainPercent = prefs[KEY_INPUT_GAIN] ?: 100,
        notifyOnJoinLeave = prefs[KEY_NOTIFY_JOIN] ?: true,
        notifyOnPoke = prefs[KEY_NOTIFY_POKE] ?: true,
        notifyOnMessage = prefs[KEY_NOTIFY_MESSAGE] ?: true,
        keepScreenOnWhileConnected = prefs[KEY_KEEP_SCREEN_ON] ?: true,
        autoSubscribeChannels = prefs[KEY_AUTO_SUBSCRIBE] ?: true,
        screenShareBitrateKbps = prefs[KEY_SHARE_BITRATE] ?: 2500,
        screenShareFps = prefs[KEY_SHARE_FPS] ?: 15,
        signalingUrl = prefs[KEY_SIGNALING_URL] ?: "",
        screenShareMode = prefs[KEY_SHARE_MODE] ?: "P2P",
        screenSharePrivacy = prefs[KEY_SHARE_PRIVACY] ?: "PUBLIC",
        screenShareResolution = prefs[KEY_SHARE_RESOLUTION] ?: "P720",
        screenShareAudio = prefs[KEY_SHARE_AUDIO] ?: false,
        screenShareAudioBitrateKbps = prefs[KEY_SHARE_AUDIO_BITRATE] ?: 128,
        screenShareViewerLimit = prefs[KEY_SHARE_VIEWER_LIMIT] ?: 0,
    )

    private companion object {
        val KEY_NICKNAME = stringPreferencesKey("default_nickname")
        val KEY_PTT = booleanPreferencesKey("push_to_talk")
        val KEY_VAD_DB = intPreferencesKey("vad_threshold_db")
        val KEY_AEC = booleanPreferencesKey("echo_cancellation")
        val KEY_NS = booleanPreferencesKey("noise_suppression")
        val KEY_AGC = booleanPreferencesKey("auto_gain_control")
        val KEY_OUTPUT_VOLUME = intPreferencesKey("output_volume")
        val KEY_INPUT_GAIN = intPreferencesKey("input_gain")
        val KEY_NOTIFY_JOIN = booleanPreferencesKey("notify_join_leave")
        val KEY_NOTIFY_POKE = booleanPreferencesKey("notify_poke")
        val KEY_NOTIFY_MESSAGE = booleanPreferencesKey("notify_message")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_AUTO_SUBSCRIBE = booleanPreferencesKey("auto_subscribe")
        val KEY_SHARE_BITRATE = intPreferencesKey("share_bitrate_kbps")
        val KEY_SHARE_FPS = intPreferencesKey("share_fps")
        val KEY_SIGNALING_URL = stringPreferencesKey("signaling_url")
        val KEY_SHARE_MODE = stringPreferencesKey("share_mode")
        val KEY_SHARE_PRIVACY = stringPreferencesKey("share_privacy")
        val KEY_SHARE_RESOLUTION = stringPreferencesKey("share_resolution")
        val KEY_SHARE_AUDIO = booleanPreferencesKey("share_audio")
        val KEY_SHARE_AUDIO_BITRATE = intPreferencesKey("share_audio_bitrate_kbps")
        val KEY_SHARE_VIEWER_LIMIT = intPreferencesKey("share_viewer_limit")
    }
}
