package com.mooncc.teamspeak6.voice.identity

import java.math.BigInteger

/**
 * Persistable form of a TeamSpeak client identity.
 *
 * The private key alone is enough to reconstruct the ECC key pair, but the
 * hashcash offsets must be kept too: recomputing them costs minutes of CPU
 * time and they are what actually determines the advertised security level.
 */
data class IdentityRecord(
    val privateKey: BigInteger,
    val keyOffset: Long,
    val lastCheckedKeyOffset: Long,
)

/**
 * Text encoding for [IdentityRecord]. Kept separate from the Keystore-backed
 * storage so the format can be unit tested on the JVM.
 */
object IdentityCodec {

    private const val VERSION = 1
    private const val SEPARATOR = ":"
    private const val RADIX = 16

    fun encode(record: IdentityRecord): String = listOf(
        VERSION.toString(),
        record.privateKey.toString(RADIX),
        record.keyOffset.toString(),
        record.lastCheckedKeyOffset.toString(),
    ).joinToString(SEPARATOR)

    fun decode(encoded: String): IdentityRecord {
        val parts = encoded.split(SEPARATOR)
        require(parts.size == 4) { "malformed identity record" }
        require(parts[0].toIntOrNull() == VERSION) { "unsupported identity version: ${parts[0]}" }
        val privateKey = runCatching { BigInteger(parts[1], RADIX) }
            .getOrElse { throw IllegalArgumentException("malformed identity private key", it) }
        return IdentityRecord(
            privateKey = privateKey,
            keyOffset = parts[2].toLongOrNull()
                ?: throw IllegalArgumentException("malformed key offset"),
            lastCheckedKeyOffset = parts[3].toLongOrNull()
                ?: throw IllegalArgumentException("malformed last checked key offset"),
        )
    }
}
