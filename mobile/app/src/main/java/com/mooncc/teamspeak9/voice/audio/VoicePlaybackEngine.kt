package com.mooncc.teamspeak9.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Decodes and mixes every incoming voice stream onto a single [AudioTrack].
 *
 * One decoder and jitter buffer exist per speaking client; both are created
 * lazily on the first packet and torn down when the stream ends.
 */
class VoicePlaybackEngine {

    private class Stream {
        val jitterBuffer = JitterBuffer()
        val decoder = OpusVoiceDecoder()
        val frame = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        var gainPercent: Int = 100
        var muted: Boolean = false
        var active: Boolean = false
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    private val running = AtomicBoolean(false)
    private val mixBuffer = ShortArray(AudioFormat.SAMPLES_PER_FRAME)

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    var masterVolumePercent: Int = 100

    @Volatile
    var muted: Boolean = false

    /** Invoked whenever a client starts or stops producing audio. */
    @Volatile
    var onTalkingChanged: ((clientId: Int, talking: Boolean) -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_OUT_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(AudioFormat.SAMPLES_PER_FRAME * AudioFormat.BYTES_PER_SAMPLE * 4)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioFormat.SAMPLE_RATE)
                    .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = audioTrack
        audioTrack.play()

        thread(name = "ts-voice-playback", isDaemon = true) { renderLoop(audioTrack) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        streams.keys.toList().forEach { removeStream(it) }
        track?.runCatching {
            pause()
            flush()
            release()
        }
        track = null
    }

    /**
     * Feeds a packet straight off the network. [payload] empty means the talk
     * burst ended.
     */
    fun submit(clientId: Int, sequence: Int, payload: ByteArray) {
        if (!running.get()) return
        val stream = streams.getOrPut(clientId) { Stream() }
        stream.jitterBuffer.offer(sequence, payload)
    }

    fun setClientGain(clientId: Int, percent: Int) {
        streams.getOrPut(clientId) { Stream() }.gainPercent = percent
    }

    fun setClientMuted(clientId: Int, muted: Boolean) {
        streams.getOrPut(clientId) { Stream() }.muted = muted
    }

    fun removeStream(clientId: Int) {
        val stream = streams.remove(clientId) ?: return
        if (stream.active) {
            stream.active = false
            onTalkingChanged?.invoke(clientId, false)
        }
    }

    private fun renderLoop(audioTrack: AudioTrack) {
        val ready = mutableListOf<ShortArray>()
        while (running.get()) {
            ready.clear()
            var sawAnyPacket = false

            for ((clientId, stream) in streams) {
                when (val slot = stream.jitterBuffer.poll()) {
                    is JitterBuffer.Slot.Packet -> {
                        sawAnyPacket = true
                        markTalking(clientId, stream, true)
                        decodeInto(stream, slot.payload)?.let { ready += it }
                    }

                    is JitterBuffer.Slot.Lost -> {
                        sawAnyPacket = true
                        decodeInto(stream, null)?.let { ready += it }
                    }

                    JitterBuffer.Slot.EndOfStream -> {
                        stream.decoder.reset()
                        markTalking(clientId, stream, false)
                    }

                    null -> Unit
                }
            }

            if (ready.isEmpty()) {
                if (!sawAnyPacket) {
                    Thread.sleep(IDLE_SLEEP_MS)
                }
                continue
            }

            AudioMixer.mix(ready, mixBuffer)
            if (masterVolumePercent != 100) {
                AudioMixer.applyGain(mixBuffer, masterVolumePercent)
            }
            runCatching {
                audioTrack.write(mixBuffer, 0, mixBuffer.size)
            }.onFailure { return }
        }
    }

    private fun decodeInto(stream: Stream, payload: ByteArray?): ShortArray? {
        if (muted || stream.muted) return null
        val decoded = runCatching { stream.decoder.decode(payload, stream.frame) }.getOrNull() ?: return null
        if (decoded <= 0) return null
        if (stream.gainPercent != 100) {
            AudioMixer.applyGain(stream.frame, stream.gainPercent, decoded)
        }
        return if (decoded == stream.frame.size) stream.frame else stream.frame.copyOf(decoded)
    }

    private fun markTalking(clientId: Int, stream: Stream, talking: Boolean) {
        if (stream.active == talking) return
        stream.active = talking
        onTalkingChanged?.invoke(clientId, talking)
    }

    private companion object {
        const val IDLE_SLEEP_MS = 5L
    }
}
