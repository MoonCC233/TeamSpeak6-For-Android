package com.mooncc.teamspeak6.voice.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted at-rest storage for the client identity.
 *
 * The identity private key is the user's persistent server-side reputation
 * (server groups, permissions, bans are keyed off its UID), so it is wrapped
 * with an AES-GCM key that never leaves the Android Keystore.
 */
class IdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): IdentityRecord? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching { IdentityCodec.decode(decrypt(payload)) }.getOrNull()
    }

    fun save(record: IdentityRecord) {
        prefs.edit()
            .putString(KEY_PAYLOAD, encrypt(IdentityCodec.encode(record)))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return encodeBase64(iv) + IV_SEPARATOR + encodeBase64(cipherText)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(IV_SEPARATOR)
        require(parts.size == 2) { "malformed encrypted identity payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, decodeBase64(parts[0])),
        )
        return String(cipher.doFinal(decodeBase64(parts[1])), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun encodeBase64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "teamspeak_identity"
        const val KEY_PAYLOAD = "identity_v1"
        const val KEY_ALIAS = "teamspeak_identity_key"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_SEPARATOR = "."
    }
}
