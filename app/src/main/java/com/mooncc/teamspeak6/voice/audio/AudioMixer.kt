package com.mooncc.teamspeak6.voice.audio

import kotlin.math.roundToInt

/**
 * Sums per-speaker PCM frames into one output frame.
 *
 * Mixing is done in 32-bit and clamped, so several loud speakers attenuate each
 * other instead of wrapping around into distortion.
 */
object AudioMixer {

    private const val SAMPLE_MIN = Short.MIN_VALUE.toInt()
    private const val SAMPLE_MAX = Short.MAX_VALUE.toInt()

    /**
     * @param frames PCM frames to mix, each already gain-adjusted.
     * @param out destination buffer, must hold [AudioFormat.SAMPLES_PER_FRAME].
     */
    fun mix(frames: List<ShortArray>, out: ShortArray) {
        out.fill(0)
        if (frames.isEmpty()) return
        if (frames.size == 1) {
            frames[0].copyInto(out, endIndex = minOf(frames[0].size, out.size))
            return
        }
        for (frame in frames) {
            val limit = minOf(frame.size, out.size)
            for (i in 0 until limit) {
                out[i] = (out[i] + frame[i]).coerceIn(SAMPLE_MIN, SAMPLE_MAX).toShort()
            }
        }
    }

    /** Applies a percentage gain in place. 100 is unity. */
    fun applyGain(frame: ShortArray, percent: Int, length: Int = frame.size) {
        if (percent == 100) return
        val factor = percent / 100f
        for (i in 0 until length) {
            frame[i] = (frame[i] * factor).roundToInt().coerceIn(SAMPLE_MIN, SAMPLE_MAX).toShort()
        }
    }

    /** Root-mean-square level of a frame in dBFS, floored at -100. */
    fun levelDb(frame: ShortArray, length: Int = frame.size): Float {
        if (length <= 0) return MIN_DB
        var sum = 0.0
        for (i in 0 until length) {
            val sample = frame[i].toDouble()
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / length)
        if (rms <= 0.0) return MIN_DB
        val db = 20.0 * Math.log10(rms / SAMPLE_MAX)
        return db.coerceAtLeast(MIN_DB.toDouble()).toFloat()
    }

    const val MIN_DB = -100f
}
