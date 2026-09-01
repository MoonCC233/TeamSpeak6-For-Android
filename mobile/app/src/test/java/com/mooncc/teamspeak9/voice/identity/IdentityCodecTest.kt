package com.mooncc.teamspeak9.voice.identity

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityCodecTest {

    private val record = IdentityRecord(
        privateKey = BigInteger("6553505c8d1a3d6f7a1b2c3d4e5f60718293a4b5", 16),
        keyOffset = 123_456_789L,
        lastCheckedKeyOffset = 987_654_321L,
    )

    @Test
    fun `round trips a record`() {
        assertEquals(record, IdentityCodec.decode(IdentityCodec.encode(record)))
    }

    @Test
    fun `round trips very large offsets`() {
        val extreme = record.copy(keyOffset = Long.MAX_VALUE, lastCheckedKeyOffset = Long.MAX_VALUE)
        assertEquals(extreme, IdentityCodec.decode(IdentityCodec.encode(extreme)))
    }

    @Test
    fun `encodes as a versioned colon separated record`() {
        val parts = IdentityCodec.encode(record).split(":")
        assertEquals(4, parts.size)
        assertEquals("1", parts[0])
        assertEquals("123456789", parts[2])
        assertEquals("987654321", parts[3])
    }

    @Test
    fun `rejects a record with the wrong field count`() {
        assertThrows(IllegalArgumentException::class.java) { IdentityCodec.decode("1:ff:0") }
    }

    @Test
    fun `rejects an unknown version`() {
        assertThrows(IllegalArgumentException::class.java) { IdentityCodec.decode("2:ff:0:0") }
    }

    @Test
    fun `rejects a non hexadecimal private key`() {
        assertThrows(IllegalArgumentException::class.java) { IdentityCodec.decode("1:zzz:0:0") }
    }

    @Test
    fun `rejects a non numeric offset`() {
        assertThrows(IllegalArgumentException::class.java) { IdentityCodec.decode("1:ff:x:0") }
    }
}
