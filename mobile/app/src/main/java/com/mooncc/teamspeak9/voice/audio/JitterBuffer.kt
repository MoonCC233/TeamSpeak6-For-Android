package com.mooncc.teamspeak9.voice.audio

/**
 * Reorders TeamSpeak voice packets for a single speaker.
 *
 * Voice packet ids are 16-bit and wrap around, so ordering is always evaluated
 * relative to the last packet that was played out. Packets that arrive too late
 * are dropped; gaps are reported as [Slot.Lost] so the decoder can run packet
 * loss concealment instead of producing a click.
 */
class JitterBuffer(
    private val targetDepth: Int = DEFAULT_TARGET_DEPTH,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {

    sealed interface Slot {
        /** A payload ready for decoding. */
        data class Packet(val sequence: Int, val payload: ByteArray) : Slot {
            override fun equals(other: Any?): Boolean =
                other is Packet && other.sequence == sequence && other.payload.contentEquals(payload)

            override fun hashCode(): Int = 31 * sequence + payload.contentHashCode()
        }

        /** A packet that never arrived; conceal it. */
        data class Lost(val sequence: Int) : Slot

        /** The stream ended (TeamSpeak signals this with an empty payload). */
        data object EndOfStream : Slot
    }

    private val pending = LinkedHashMap<Int, ByteArray>()
    private var nextSequence: Int? = null
    private var endOfStreamAfter: Int? = null
    private var primed = false

    val size: Int get() = pending.size

    /**
     * Offers a packet. An empty [payload] marks the end of the talk burst, which
     * is drained only after the packets before it have been played out.
     */
    fun offer(sequence: Int, payload: ByteArray) {
        val masked = sequence and SEQUENCE_MASK
        if (payload.isEmpty()) {
            endOfStreamAfter = masked
            return
        }

        val expected = nextSequence
        if (expected != null && distance(masked, expected) < 0) {
            // Arrived after its slot was already played out.
            return
        }
        if (expected == null) {
            nextSequence = masked
        }
        pending[masked] = payload
        if (pending.size > maxDepth) {
            dropOldest()
        }
    }

    /**
     * Returns the next slot to play, or `null` while the buffer is still filling
     * up to [targetDepth] (which is what absorbs network jitter).
     */
    fun poll(): Slot? {
        val expected = nextSequence ?: return endOfStreamSlotOrNull()
        if (!primed) {
            if (pending.size < targetDepth) return null
            primed = true
        }
        if (pending.isEmpty()) {
            endOfStreamSlotOrNull()?.let { return it }
            primed = false
            return null
        }

        val payload = pending.remove(expected)
        nextSequence = increment(expected)
        return if (payload != null) {
            Slot.Packet(expected, payload)
        } else {
            Slot.Lost(expected)
        }
    }

    fun reset() {
        pending.clear()
        nextSequence = null
        endOfStreamAfter = null
        primed = false
    }

    private fun endOfStreamSlotOrNull(): Slot? {
        if (endOfStreamAfter == null || pending.isNotEmpty()) return null
        endOfStreamAfter = null
        nextSequence = null
        primed = false
        return Slot.EndOfStream
    }

    private fun dropOldest() {
        val oldest = pending.keys.minByOrNull { key -> distance(key, nextSequence ?: key) } ?: return
        pending.remove(oldest)
        if (oldest == nextSequence) {
            nextSequence = increment(oldest)
        }
    }

    private fun increment(sequence: Int) = (sequence + 1) and SEQUENCE_MASK

    /** Signed distance from [reference] to [sequence] across the wrap point. */
    private fun distance(sequence: Int, reference: Int): Int {
        val raw = (sequence - reference) and SEQUENCE_MASK
        return if (raw > SEQUENCE_HALF) raw - SEQUENCE_SPACE else raw
    }

    companion object {
        const val DEFAULT_TARGET_DEPTH = 3
        const val DEFAULT_MAX_DEPTH = 32

        private const val SEQUENCE_SPACE = 0x10000
        private const val SEQUENCE_MASK = SEQUENCE_SPACE - 1
        private const val SEQUENCE_HALF = SEQUENCE_SPACE / 2
    }
}
