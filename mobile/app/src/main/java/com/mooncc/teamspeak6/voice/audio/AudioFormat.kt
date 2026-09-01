package com.mooncc.teamspeak6.voice.audio

/**
 * Wire format constants shared by the capture and playback chains.
 *
 * TeamSpeak transports 48 kHz mono Opus in 20 ms frames; deviating from this
 * makes packets undecodable for the desktop client.
 */
object AudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1000 * FRAME_MS
    const val BYTES_PER_SAMPLE = 2

    /** Desktop default for voice channels. */
    const val DEFAULT_BITRATE = 64_000

    /** Largest Opus payload we are willing to emit or accept for one frame. */
    const val MAX_PAYLOAD_BYTES = 1275
}
