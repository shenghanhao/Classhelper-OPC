package com.classmemory.app.review

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class ReviewConnectionTest {
    @Test
    fun directModeRequiresAKeyAndWhitelistedModel() {
        assertEquals(ReviewConnectionMode.DIRECT_DEEPSEEK, ReviewConnection().mode)
        assertFalse(ReviewConnection().isConfigured())
        assertTrue(ReviewConnection(apiKey = "synthetic-key").isConfigured())
        assertTrue(ReviewConnection(apiKey = "synthetic-key", model = "deepseek-v4-pro").isConfigured())
        assertFalse(ReviewConnection(apiKey = "synthetic-key", model = "unknown-model").isConfigured())
        assertFalse(ReviewConnection(apiKey = "synthetic\nkey").isConfigured())
        assertFalse(ReviewConnection(apiKey = "synthetic key").isConfigured())
        assertFalse(ReviewConnection(apiKey = "synthetic密钥").isConfigured())
    }

    @Test
    fun legacyModeMigrationPreservesRemoteProxyAndDoesNotUseProxyTokenAsApiKey() {
        assertEquals(ReviewConnectionMode.DIRECT_DEEPSEEK, restoreReviewConnectionMode(null, ""))
        for (address in listOf("https://localhost:8787", "https://127.0.0.1:8787", "https://[::1]:8787")) {
            assertEquals(ReviewConnectionMode.DIRECT_DEEPSEEK, restoreReviewConnectionMode(null, address))
            assertEquals(ReviewConnectionMode.PROXY, restoreReviewConnectionMode("PROXY", address))
        }
        assertEquals(ReviewConnectionMode.PROXY, restoreReviewConnectionMode(null, "https://service.example"))
        val connection = ReviewConnection(baseUrl = "https://localhost:8787", accessToken = "old-proxy-token")
        assertEquals("", connection.apiKey)
        assertFalse(connection.isConfigured())
    }

    @Test
    fun proxyStillRequiresHttpsAndBothSecretsAreRedacted() {
        val proxy = ReviewConnection("https://service.example", "proxy-secret", ReviewConnectionMode.PROXY)
        assertTrue(proxy.isConfigured())
        assertFalse(proxy.copy(baseUrl = "http://service.example").isConfigured())
        assertFalse(proxy.copy(baseUrl = "https://secret@service.example").isConfigured())
        assertFalse(proxy.copy(accessToken = "proxy\nsecret").isConfigured())
        val printed = proxy.copy(apiKey = "provider-secret").toString()
        assertFalse(printed.contains("provider-secret"))
        assertFalse(printed.contains("proxy-secret"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReviewConnection(apiKey = "private-secret\n").validate()
        }
        assertFalse(error.message.orEmpty().contains("private-secret"))
    }

    @Test
    fun credentialsRoundTripWithFreshIvAndFailOnTamperOrWrongKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "synthetic-provider-key"
        val first = ReviewCredentialCipher.encrypt(plaintext, key)
        val second = ReviewCredentialCipher.encrypt(plaintext, key)
        assertNotEquals(first, second)
        assertFalse(first.contains(plaintext))
        assertEquals(plaintext, ReviewCredentialCipher.decrypt(first, key))
        val parts = first.split(':')
        val payload = java.util.Base64.getDecoder().decode(parts[2])
        payload[0] = (payload[0].toInt() xor 1).toByte()
        val tampered = parts[0] + ":" + parts[1] + ":" + java.util.Base64.getEncoder().encodeToString(payload)
        assertThrows(AEADBadTagException::class.java) { ReviewCredentialCipher.decrypt(tampered, key) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(AEADBadTagException::class.java) { ReviewCredentialCipher.decrypt(first, otherKey) }
        assertThrows(IllegalArgumentException::class.java) { ReviewCredentialCipher.decrypt("plain-key", key) }
    }
}
