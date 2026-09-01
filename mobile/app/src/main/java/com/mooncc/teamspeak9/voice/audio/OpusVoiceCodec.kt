package com.mooncc.teamspeak9.voice.audio

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusSignal

/**
 * Thin wrappers over Concentus (pure-Java Opus) with TeamSpeak's parameters
 * baked in. Concentus avoids an NDK dependency; the CPU cost is acceptable for
 * one encoder plus a handful of decoders.
 */
class OpusVoiceEncoder(
    bitrate: Int = AudioFormat.DEFAULT_BITRATE,
    music: Boolean = false,
) {

    private val encoder = OpusEncoder(
        AudioFormat.SAMPLE_RATE,
        AudioFormat.CHANNELS,
        if (music) OpusApplication.OPUS_APPLICATION_AUDIO else OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        this.bitrate = bitrate
        signalType = if (music) OpusSignal.OPUS_SIGNAL_MUSIC else OpusSignal.OPUS_SIGNAL_VOICE
        useVBR = true
        useConstrainedVBR = true
        useInbandFEC = true
        packetLossPercent = DEFAULT_EXPECTED_LOSS
        complexity = DEFAULT_COMPLEXITY
    }

    private val payload = ByteArray(AudioFormat.MAX_PAYLOAD_BYTES)

    var bitrate: Int
        get() = encoder.bitrate
        set(value) {
            encoder.bitrate = value.coerceIn(MIN_BITRATE, MAX_BITRATE)
        }

    /** Encodes exactly one 20 ms frame, returning a right-sized payload. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME) {
            "expected ${AudioFormat.SAMPLES_PER_FRAME} samples, got ${pcm.size}"
        }
        val written = encoder.encode(pcm, 0, AudioFormat.SAMPLES_PER_FRAME, payload, 0, payload.size)
        return payload.copyOf(written)
    }

    fun reset() = encoder.resetState()

    private companion object {
        const val DEFAULT_COMPLEXITY = 5
        const val DEFAULT_EXPECTED_LOSS = 10
        const val MIN_BITRATE = 8_000
        const val MAX_BITRATE = 128_000
    }
}

class OpusVoiceDecoder {

    private val decoder = OpusDecoder(AudioFormat.SAMPLE_RATE, AudioFormat.CHANNELS)

    /**
     * Decodes a frame into [out], returning the sample count.
     * Pass `null` payload to run packet loss concealment for a missing frame.
     */
    fun decode(payload: ByteArray?, out: ShortArray): Int = if (payload == null) {
        decoder.decode(null, 0, 0, out, 0, AudioFormat.SAMPLES_PER_FRAME, false)
    } else {
        decoder.decode(payload, 0, payload.size, out, 0, AudioFormat.SAMPLES_PER_FRAME, false)
    }

    fun reset() = decoder.resetState()
}
