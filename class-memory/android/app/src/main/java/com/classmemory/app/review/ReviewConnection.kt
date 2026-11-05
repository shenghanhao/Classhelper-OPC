package com.classmemory.app.review

import android.content.Context
import java.net.URI

data class ReviewConnection(val baseUrl: String = "", val accessToken: String = "") {
    fun validate() {
        if (baseUrl.isBlank()) return
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "请填写不含密钥或查询参数的 HTTPS 服务地址 / Use an HTTPS base URL without credentials or query parameters"
        }
        require(accessToken.none { it == '\r' || it == '\n' }) { "访问令牌格式无效 / Invalid access token" }
    }
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && runCatching { validate() }.isSuccess
}

/** Proxy token only; the model provider API key must remain on the server. */
object ReviewConnectionPreferences {
    private const val NAME = "review_proxy_connection"
    fun load(context: Context): ReviewConnection {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return ReviewConnection(prefs.getString("base_url", "").orEmpty(), prefs.getString("access_token", "").orEmpty())
    }
    fun save(context: Context, connection: ReviewConnection) {
        connection.validate()
        check(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("base_url", connection.baseUrl.trim().trimEnd('/')).putString("access_token", connection.accessToken.trim()).commit()) {
            "无法保存服务设置 / Could not save service settings"
        }
    }
}
