package dev.tsdroid.data

/**
 * Keys used for private conversations.
 *
 * A conversation is identified by the peer's permanent uid. History written before
 * that was the case is keyed by a session client id, which is reassigned on every
 * reconnect — that is what used to split one person into several conversation tabs.
 * Those old keys are carried over under [CLID_PREFIX] so they can never be confused
 * with a uid, and so they can be folded into the right conversation once the peer's
 * identity becomes known.
 *
 * The mapping lives here, away from [MessageStore] and the ViewModel, because it is
 * the one piece of this scheme that can silently lose history if it is wrong.
 */
object PrivateConversations {

    const val CLID_PREFIX = "clid:"

    /**
     * Gives a stored key its current form. Idempotent: a key that is already a uid
     * (or already prefixed) comes back unchanged.
     */
    fun migrateStoredKey(storedKey: String): String =
        if (storedKey.toIntOrNull() != null) "$CLID_PREFIX$storedKey" else storedKey

    /** True when this key is a client id that never resolved to a uid. */
    fun isLegacy(peerKey: String): Boolean = peerKey.startsWith(CLID_PREFIX)

    /** The client id behind a legacy key, or null when the key is a uid. */
    fun clientIdOf(peerKey: String): Int? =
        if (isLegacy(peerKey)) peerKey.removePrefix(CLID_PREFIX).toIntOrNull() else null
}
