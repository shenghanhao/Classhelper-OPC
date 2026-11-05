package com.classmemory.app.review

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object ReviewCredentialCipher {
    private val associatedData = "ClassMemory.DeepSeek.APIKey.v1".toByteArray(UTF_8)

    fun encrypt(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plaintext.toByteArray(UTF_8))
        val encoder = Base64.getEncoder()
        return "v1:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(encrypted)}"
    }

    fun decrypt(envelope: String, key: SecretKey): String {
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Invalid credential format" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        require(iv.size == 12) { "Invalid credential IV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return String(cipher.doFinal(decoder.decode(parts[2])), UTF_8)
    }
}

internal object AndroidReviewCredentialKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "classmemory.deepseek.api-key.v1"

    fun existing(): SecretKey =
        requireNotNull(store().getKey(ALIAS, null) as? SecretKey) { "Credential key unavailable" }

    fun forWriting(): SecretKey {
        val existing = store().getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    fun clear() {
        store().deleteEntry(ALIAS)
    }

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
}
