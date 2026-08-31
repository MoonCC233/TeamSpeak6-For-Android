package com.mooncc.teamspeak6.screenshare.webrtc

/**
 * Pure SDP text transforms.
 *
 * `RtpSender.setParameters` is the primary bitrate control, but some peers only
 * honour the session-level `b=AS:` line, so we set both. Codec reordering is
 * done here as well because the WebRTC Android API offers no way to express a
 * preference before the offer is created.
 */
object SdpTransform {

    /**
     * Rewrites the bandwidth lines of the video m-section to [bitrateKbps].
     *
     * `b=AS:` is in kbps, `b=TIAS:` in bps. Existing lines are replaced rather
     * than duplicated; when absent they are inserted directly after the `c=`
     * line, which is where RFC 4566 requires them.
     */
    fun applyVideoBitrate(sdp: String, bitrateKbps: Int): String {
        if (bitrateKbps <= 0) return sdp
        val lines = sdp.split(LINE_BREAK_REGEX).toMutableList()
        val videoStart = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoStart < 0) return sdp

        val sectionEnd = lines
            .drop(videoStart + 1)
            .indexOfFirst { it.startsWith("m=") }
            .let { if (it < 0) lines.size else videoStart + 1 + it }

        val section = lines.subList(videoStart, sectionEnd)
        section.removeAll { it.startsWith("b=AS:") || it.startsWith("b=TIAS:") }

        val insertAt = section.indexOfFirst { it.startsWith("c=") }
            .let { if (it < 0) 0 else it } + 1
        section.addAll(insertAt, listOf("b=AS:$bitrateKbps", "b=TIAS:${bitrateKbps * 1000}"))

        return lines.joinToString(CRLF)
    }

    /**
     * Moves the payload types of [codec] to the front of the video m-line so the
     * remote end picks it first.
     *
     * Returns the SDP unchanged when the codec is absent — the caller must not
     * assume the preference took effect.
     */
    fun preferVideoCodec(sdp: String, codec: String): String {
        val lines = sdp.split(LINE_BREAK_REGEX)
        val videoLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoLineIndex < 0) return sdp

        val payloads = lines
            .mapNotNull { line -> RTPMAP_REGEX.matchEntire(line.trim()) }
            .filter { it.groupValues[2].equals(codec, ignoreCase = true) }
            .map { it.groupValues[1] }
            .toSet()
        if (payloads.isEmpty()) return sdp

        val parts = lines[videoLineIndex].split(' ')
        if (parts.size <= 3) return sdp

        val header = parts.take(3)
        val existing = parts.drop(3)
        val reordered = existing.filter { it in payloads } + existing.filterNot { it in payloads }

        val updated = lines.toMutableList()
        updated[videoLineIndex] = (header + reordered).joinToString(" ")
        return updated.joinToString(CRLF)
    }

    /** Whether the SDP negotiated an audio track. */
    fun hasAudio(sdp: String): Boolean =
        sdp.split(LINE_BREAK_REGEX).any { it.startsWith("m=audio") }

    private const val CRLF = "\r\n"
    private val LINE_BREAK_REGEX = Regex("\r\n|\r|\n")
    private val RTPMAP_REGEX = Regex("^a=rtpmap:(\\d+) ([A-Za-z0-9\\-_]+)/\\d+.*$")
}
