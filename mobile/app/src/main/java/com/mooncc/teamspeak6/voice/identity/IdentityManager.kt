package com.mooncc.teamspeak6.voice.identity

import com.github.manevolent.ts3j.identity.LocalIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Provides the single [LocalIdentity] used for every connection.
 *
 * TeamSpeak identities carry a hashcash "security level": servers may require a
 * minimum level to join. Raising it is pure CPU work over the public key, so it
 * happens once on first launch and the result is cached.
 */
@Singleton
class IdentityManager @Inject constructor(
    private val store: IdentityStore,
) {

    private val mutex = Mutex()
    private var cached: LocalIdentity? = null

    /** Loads the stored identity, generating one on first use. */
    suspend fun identity(targetSecurityLevel: Int = DEFAULT_SECURITY_LEVEL): LocalIdentity =
        mutex.withLock {
            cached?.let { return@withLock it }
            val identity = withContext(Dispatchers.Default) {
                val restored = store.load()?.let { record ->
                    LocalIdentity.load(record.privateKey).apply {
                        keyOffset = record.keyOffset
                        lastCheckedKeyOffset = record.lastCheckedKeyOffset
                    }
                }
                val local = restored ?: LocalIdentity.generateNew(targetSecurityLevel)
                if (local.securityLevel < targetSecurityLevel) {
                    local.improveSecurity(targetSecurityLevel)
                }
                store.save(local.toRecord())
                local
            }
            cached = identity
            identity
        }

    /**
     * Raises the identity's security level, e.g. after a server rejected the
     * connection for an insufficient level. Returns the level actually reached.
     */
    suspend fun improveSecurity(targetSecurityLevel: Int): Int = mutex.withLock {
        val identity = cached ?: identityLocked()
        withContext(Dispatchers.Default) {
            identity.improveSecurity(targetSecurityLevel)
            store.save(identity.toRecord())
        }
        identity.securityLevel
    }

    /** Discards the identity; the next connection generates a brand new UID. */
    suspend fun reset() = mutex.withLock {
        cached = null
        store.clear()
    }

    private suspend fun identityLocked(): LocalIdentity {
        val identity = withContext(Dispatchers.Default) {
            val record = store.load()
            if (record != null) {
                LocalIdentity.load(record.privateKey).apply {
                    keyOffset = record.keyOffset
                    lastCheckedKeyOffset = record.lastCheckedKeyOffset
                }
            } else {
                LocalIdentity.generateNew(DEFAULT_SECURITY_LEVEL).also { store.save(it.toRecord()) }
            }
        }
        cached = identity
        return identity
    }

    private fun LocalIdentity.toRecord() = IdentityRecord(
        privateKey = privateKey,
        keyOffset = keyOffset,
        lastCheckedKeyOffset = lastCheckedKeyOffset,
    )

    companion object {
        /** Level 8 matches what the desktop client ships with by default. */
        const val DEFAULT_SECURITY_LEVEL = 8
    }
}
