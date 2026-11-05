package com.classmemory.app.review

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewTextV3Test {
    @Test fun indexedPhotoRequiresNoFileOrPhotoStageAndWireContainsOnlyMetadata() = runBlocking {
        val raw = ReviewInput("class", "", "zh", "课堂", "2026-11-04", null,
            listOf(ReviewSource("s", "class", "s", 0, 1, "原文", true)),
            listOf(ReviewPhotoRef("p", "class", 5)), emptyList(), 3)
        val input = raw.copy(sourceHash = raw.contentHash())
        val dir = Files.createTempDirectory("review-text").toFile()
        try {
            val stages = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val sent = JSONObject(buffer.readUtf8()); stages += sent.getString("stage")
                assertEquals(3, sent.getJSONObject("input").getInt("schemaVersion"))
                val photo = sent.getJSONObject("input").getJSONArray("photos").getJSONObject(0)
                assertEquals("p", photo.getString("id")); assertEquals(3, photo.length())
                assertFalse(sent.has("imageDataUrl")); assertFalse(sent.has("photoInsights"))
                val response = """{"overview":"导览","sections":[{"id":"sec","title":"章节","sourceIds":["s"]}],"topics":[{"id":"t","title":"概念","sectionIds":["sec"],"sourceIds":["s"]}]}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
            }.build()
            val gateway = ReviewHttpGateway("https://example.com", "", client)
            val result = ReviewCoordinator(ReviewStore(dir), gateway) { input.sourceHash }.run(input)
            assertNotNull(result.outline)
            assertEquals("outline", stages.first())
            assertFalse(stages.contains("photo"))
        } finally { dir.deleteRecursively() }
        Unit
    }
}
