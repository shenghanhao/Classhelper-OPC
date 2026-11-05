package com.classmemory.app.review

import android.content.Context
import java.net.URI

enum class ReviewConnectionMode { DIRECT_DEEPSEEK, PROXY }

data class ReviewConnection(
    val baseUrl: String = "",
    val accessToken: String = "",
    val mode: ReviewConnectionMode = ReviewConnectionMode.DIRECT_DEEPSEEK,
    val apiKey: String = "",
    val model: String = "deepseek-flash",
) {
    fun validate() {
        if (mode == ReviewConnectionMode.DIRECT_DEEPSEEK) {
            require(apiKey.isNotBlank() && apiKey.all { it.code in 33..126 }) {
                "请填写单行 API Key，不含空格或换行 / Enter a single-line API key without spaces or line breaks"
            }
            require(model in listOf("deepseek-flash", "deepseek-v4-pro")) {
                "请选择支持的 DeepSeek 模型 / Choose a supported DeepSeek model"
            }
            return
        }
        if (baseUrl.isBlank()) return
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "请填写不含密钥或查询参数的 HTTPS 服务地址 / Use an HTTPS base URL without credentials or query parameters"
        }
        require(accessToken.all { it.code in 33..126 }) {
            "访问令牌格式无效 / Invalid access token"
        }
    }

    fun isConfigured(): Boolean =
        (mode == ReviewConnectionMode.DIRECT_DEEPSEEK || baseUrl.isNotBlank()) && runCatching { validate() }.isSuccess

    override fun toString(): String =
        "ReviewConnection(mode=$mode, model=$model, hasApiKey=${apiKey.isNotBlank()}, hasAccessToken=${accessToken.isNotBlank()})"
}

data class ReviewConnectionLoadResult(
    val connection: ReviewConnection,
    val credentialWarning: String? = null,
)

internal fun restoreReviewConnectionMode(savedMode: String?, baseUrl: String): ReviewConnectionMode {
    ReviewConnectionMode.entries.find { it.name == savedMode }?.let { return it }
    val host = runCatching { URI(baseUrl.trim()).host?.removeSurrounding("[", "]") }.getOrNull()
    return if (baseUrl.isBlank() || host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1") {
        ReviewConnectionMode.DIRECT_DEEPSEEK
    } else {
        ReviewConnectionMode.PROXY
    }
}

/** Provider credentials are encrypted with an Android Keystore key before persistence. */
object ReviewConnectionPreferences {
    private const val NAME = "review_proxy_connection"
    private const val ENCRYPTED_API_KEY = "deepseek_api_key_ciphertext"

    fun load(context: Context): ReviewConnection = loadWithStatus(context).connection

    fun loadWithStatus(context: Context): ReviewConnectionLoadResult {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("base_url", "").orEmpty()
        val storedKey = prefs.getString(ENCRYPTED_API_KEY, "").orEmpty()
        val keyResult = runCatching {
            if (storedKey.isBlank()) "" else ReviewCredentialCipher.decrypt(storedKey, AndroidReviewCredentialKey.existing())
        }
        val warning = if (keyResult.isFailure) {
            "已保存的 API Key 无法解密，请重新填写或清除 / The saved API key is unavailable. Enter it again or clear it."
        } else null
        val connection = ReviewConnection(
            baseUrl = baseUrl,
            accessToken = prefs.getString("access_token", "").orEmpty(),
            mode = restoreReviewConnectionMode(prefs.getString("mode", null), baseUrl),
            apiKey = keyResult.getOrDefault(""),
            model = prefs.getString("model", "deepseek-flash").orEmpty(),
        )
        return ReviewConnectionLoadResult(connection, warning)
    }

    fun save(context: Context, connection: ReviewConnection) {
        connection.validate()
        try {
            val encrypted = if (connection.apiKey.isBlank()) "" else
                ReviewCredentialCipher.encrypt(connection.apiKey, AndroidReviewCredentialKey.forWriting())
            check(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putString("base_url", connection.baseUrl.trim().trimEnd('/'))
                .putString("access_token", connection.accessToken)
                .putString("mode", connection.mode.name)
                .putString("model", connection.model)
                .putString(ENCRYPTED_API_KEY, encrypted)
                .commit())
        } catch (_: Exception) {
            throw IllegalStateException("无法安全保存设置，请清除旧密钥后重试 / Could not securely save settings. Clear the old key and try again.")
        }
    }

    fun clearApiKey(context: Context) {
        try {
            check(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .remove(ENCRYPTED_API_KEY).commit())
        } catch (_: Exception) {
            throw IllegalStateException("无法清除 API Key，请重试 / Could not clear the API key. Try again.")
        }
        // The credential is already removed. Key cleanup must not report it as still saved.
        runCatching { AndroidReviewCredentialKey.clear() }
    }
}
