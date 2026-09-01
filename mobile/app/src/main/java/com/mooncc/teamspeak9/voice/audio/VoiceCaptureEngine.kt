package com.mooncc.teamspeak9.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.enums.CodecType
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures the microphone, encodes to Opus, and hands frames to ts3j.
 *
 * ts3j polls [Microphone] on its own 20 ms timer, so capture runs on a separate
 * thread and publishes into a small queue: that decouples the recorder's timing
 * from the network send loop without adding meaningful latency.
 */
class VoiceCaptureEngine : Microphone {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)

    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    @Volatile
    private var encoder: OpusVoiceEncoder? = null

    /** Set false to stop transmitting without tearing down the recorder (PTT). */
    @Volatile
    var transmitting: Boolean = false

    @Volatile
    var muted: Boolean = false

    @Volatile
    var inputGainPercent: Int = 100

    /**
     * Voice-activation threshold in dBFS. Frames quieter than this are dropped
     * while [voiceActivationEnabled] is set.
     */
    @Volatile
    var activationThresholdDb: Int = -40

    @Volatile
    var voiceActivationEnabled: Boolean = false

    @Volatile
    var echoCancellation: Boolean = true

    @Volatile
    var noiseSuppression: Boolean = true

    @Volatile
    var autoGainControl: Boolean = true

    /**
     * While set, encoded frames are handed to [onWhisperFrame] instead of the
     * ts3j queue, so the audio leaves as a whisper packet and the normal voice
     * path stays silent.
     */
    @Volatile
    var whisperActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Anything already queued belongs to the previous mode.
            queue.clear()
        }

    /** Receives encoded frames while [whisperActive] is set. */
    @Volatile
    var onWhisperFrame: ((frame: ByteArray) -> Unit)? = null

    /** Reports the local input level so the UI can draw a meter. */
    @Volatile
    var onInputLevel: ((db: Float) -> Unit)? = null

    /** Reports whether the local user is currently being transmitted. */
    @Volatile
    var onTalkingChanged: ((talking: Boolean) -> Unit)? = null

    private var lastTalking = false
    private var whisperBurstOpen = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            return false
        }

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioFormat.SAMPLE_RATE,
                AndroidAudioFormat.CHANNEL_IN_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, AudioFormat.SAMPLES_PER_FRAME * AudioFormat.BYTES_PER_SAMPLE * 4),
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            running.set(false)
            return false
        }

        record = recorder
        encoder = OpusVoiceEncoder()
        attachEffects(recorder.audioSessionId)
        recorder.startRecording()
        thread(name = "ts-voice-capture", isDaemon = true) { captureLoop(recorder) }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        releaseEffects()
        record?.runCatching {
            stop()
            release()
        }
        record = null
        encoder = null
        whisperBurstOpen = false
        if (lastTalking) {
            lastTalking = false
            onTalkingChanged?.invoke(false)
        }
    }

    fun setBitrate(bitrate: Int) {
        encoder?.bitrate = bitrate
    }

    // --- ts3j Microphone -----------------------------------------------------

    override fun isMuted(): Boolean = muted

    override fun isReady(): Boolean = running.get() && !whisperActive && queue.isNotEmpty()

    override fun getCodec(): CodecType = CodecType.OPUS_VOICE

    override fun provide(): ByteArray = queue.poll() ?: EMPTY

    // -------------------------------------------------------------------------

    private fun captureLoop(recorder: AudioRecord) {
        val pcm = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        while (running.get()) {
            var offset = 0
            while (offset < pcm.size && running.get()) {
                val read = recorder.read(pcm, offset, pcm.size - offset)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        return
                    }
                    break
                }
                offset += read
            }
            if (offset < pcm.size) continue

            if (inputGainPercent != 100) {
                AudioMixer.applyGain(pcm, inputGainPercent)
            }
            val levelDb = AudioMixer.levelDb(pcm)
            onInputLevel?.invoke(levelDb)

            val gateOpen = !voiceActivationEnabled || levelDb >= activationThresholdDb
            val shouldSend = transmitting && !muted && gateOpen
            updateTalking(shouldSend)
            if (!shouldSend) {
                endWhisperBurst()
                continue
            }

            val payload = runCatching { encoder?.encode(pcm) }.getOrNull() ?: continue
            if (whisperActive) {
                whisperBurstOpen = true
                onWhisperFrame?.invoke(payload)
                continue
            }

            endWhisperBurst()
            if (!queue.offer(payload)) {
                // Prefer fresh audio over stale audio when the sender falls behind.
                queue.poll()
                queue.offer(payload)
            }
        }
        endWhisperBurst()
    }

    /**
     * TeamSpeak marks the end of a talk burst with an empty payload; ts3j does
     * that for normal voice, so whisper bursts have to terminate themselves.
     */
    private fun endWhisperBurst() {
        if (!whisperBurstOpen) return
        whisperBurstOpen = false
        onWhisperFrame?.invoke(EMPTY)
    }

    private fun updateTalking(talking: Boolean) {
        if (lastTalking == talking) return
        lastTalking = talking
        onTalkingChanged?.invoke(talking)
    }

    private fun attachEffects(sessionId: Int) {
        if (echoCancellation && AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
        if (autoGainControl && AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        }
    }

    private fun releaseEffects() {
        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    private companion object {
        const val QUEUE_CAPACITY = 8
        val EMPTY = ByteArray(0)
    }
}
