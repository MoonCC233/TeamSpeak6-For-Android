package com.mooncc.teamspeak6.screenshare.signaling

import java.security.MessageDigest

/**
 * Derives the signaling room id from the TeamSpeak location.
 *
 * Everyone in the same TeamSpeak channel derives the same id, so "people in my
 * channel can see my share" falls out without any extra coordination.
 */
object RoomId {

    private const val LENGTH = 32

    fun forChannel(serverUid: String, channelId: Int): String =
        sha256Hex("$serverUid|$channelId").take(LENGTH)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val out = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
