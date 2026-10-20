package dev.tsdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy-key mapping is the one part of uid-keyed conversations that can
 * silently lose a user's private history if it is wrong, so it is pinned here.
 */
class PrivateConversationsTest {

    @Test
    fun `numeric legacy key gains the client-id prefix`() {
        assertEquals("clid:42", PrivateConversations.migrateStoredKey("42"))
    }

    @Test
    fun `uid keys are left untouched`() {
        // Real uids are base64-ish and never parse as a number.
        val uid = "W5s0R2nQ+7hK1cZ8vYqL3w=="
        assertEquals(uid, PrivateConversations.migrateStoredKey(uid))
    }

    @Test
    fun `migration is idempotent`() {
        val once = PrivateConversations.migrateStoredKey("7")
        val twice = PrivateConversations.migrateStoredKey(once)
        assertEquals(once, twice)
    }

    @Test
    fun `empty and non-numeric keys survive`() {
        assertEquals("", PrivateConversations.migrateStoredKey(""))
        assertEquals("abc", PrivateConversations.migrateStoredKey("abc"))
        // A uid that merely looks like a number is not a thing TeamSpeak produces,
        // but if it ever happened the prefixed form keeps it addressable.
        assertEquals("clid:0", PrivateConversations.migrateStoredKey("0"))
    }

    @Test
    fun `legacy detection matches the prefix only`() {
        assertTrue(PrivateConversations.isLegacy("clid:9"))
        assertFalse(PrivateConversations.isLegacy("9"))
        assertFalse(PrivateConversations.isLegacy("W5s0R2=="))
    }

    @Test
    fun `client id is recovered from a legacy key and absent for a uid`() {
        assertEquals(9, PrivateConversations.clientIdOf("clid:9"))
        assertNull(PrivateConversations.clientIdOf("W5s0R2=="))
        assertNull(PrivateConversations.clientIdOf("clid:notanumber"))
    }
}
