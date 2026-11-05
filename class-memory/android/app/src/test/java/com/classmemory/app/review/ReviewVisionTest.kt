package com.classmemory.app.review

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ReviewVisionTest {
    private fun input(vision: Boolean): ReviewInput {
        val photos = listOf(ReviewPhotoRef("p1", "class", 1, if (vision) "a".repeat(64) else null), ReviewPhotoRef("p2", "class", 2, if (vision) "b".repeat(64) else null))
        val i = ReviewInput("class", "", "zh", null, "2026-11-05", null, listOf(ReviewSource("s", "class", "s", 0, 10, "原文", true)), photos, emptyList(), 3, if (vision) listOf("p1", "p2") else emptyList())
        return i.copy(sourceHash = i.contentHash())
    }
    @Test fun unselectedPhotosRetainOldJsonAndHash() {
        val plain = input(false); val json = ReviewJson.input(plain)
        assertFalse(json.has("selectedVisionPhotoIds")); assertFalse(json.getJSONArray("photos").getJSONObject(0).has("contentSha256"))
        assertEquals(plain, ReviewJson.input(json)); assertEquals(plain.sourceHash, plain.contentHash())
        assertNotEquals(plain.sourceHash, input(true).sourceHash)
        val selected = input(true)
        val changed = selected.copy(photos = selected.photos.map { if (it.id == "p1") it.copy(contentSha256 = "c".repeat(64)) else it })
        assertNotEquals(selected.sourceHash, changed.contentHash())
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(selected.copy(selectedVisionPhotoIds = listOf("unknown"))) }
    }
    @Test fun legacyV3FixtureRetainsItsOriginalFingerprint() {
        val fixture = JSONObject("""{"schemaVersion":2,"sessionId":"class","sourceHash":"ee949469a860a2d98560bc0a9944f9e4d5898ef0bd8f58d4c7a814809df69145","language":"en","title":null,"today":"2026-11-05","course":null,"sources":[{"id":"s","sessionId":"class","segmentId":"seg","startMs":0,"endMs":1,"text":"transcript","isCurrent":true}],"photos":[{"id":"p","sessionId":"class","atMs":0}],"notes":[],"analysisVersion":3}""")
        val restored = ReviewJson.input(fixture)
        assertEquals(fixture.getString("sourceHash"), restored.contentHash())
        assertTrue(restored.selectedVisionPhotoIds.isEmpty()); assertNull(restored.photos.single().contentSha256)
        ReviewContracts.validate(restored)
    }
    @Test fun retriesOnlyFailedPhotoAndPreservesTextCheckpoints() = runBlocking {
        val dir = Files.createTempDirectory("vision-review").toFile()
        try {
            val i = input(true); val store = ReviewStore(dir); val calls = mutableListOf<String>(); var failing = true
            val outline = ReviewOutline("导览", listOf(ReviewSection("section", "主题", listOf("s"))), listOf(ReviewTopic("u", "知识", listOf("section"), listOf("s"))))
            val unit = ReviewUnit("u", "知识", "解释", "", "重点", null, listOf("s"), emptyList(), listOf("section"), "正文")
            val gateway = object : ReviewGateway {
                override suspend fun analyzePhoto(ref: ReviewPhotoRef, bytes: ByteArray): ReviewPhotoAnalysis {
                    calls += ref.id
                    if (ref.id == "p2" && failing) error("offline")
                    return ReviewPhotoAnalysis(ref.id, ref.atMs, "板书", "模糊")
                }
                override fun withPhotoAnalyses(analyses: List<ReviewPhotoAnalysis>): ReviewGateway { assertEquals(setOf("p1", "p2"), analyses.map { it.photoId }.toSet()); return this }
                override suspend fun outline(input: ReviewInput): ReviewOutline { calls += "outline"; return outline }
                override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String): ReviewUnit = unit
                override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = ReviewPlan("方案", "介绍", "2026-11-05", listOf(ReviewStep("回顾", "复习", listOf("u"))))
            }
            val loader: suspend (ReviewPhotoRef) -> ByteArray = { byteArrayOf(1, 2) }
            val first = ReviewCoordinator(store, gateway, loader) { i.sourceHash }.run(i)
            assertEquals(setOf("p1"), first.photoAnalyses.keys); assertTrue(first.errors.containsKey("photo:p2")); assertNull(first.outline)
            failing = false
            val final = ReviewCoordinator(ReviewStore(dir), gateway, loader) { i.sourceHash }.run(i)
            assertEquals(listOf("p1", "p2", "p2", "outline"), calls); assertNotNull(final.plan)
            assertEquals(setOf("p1", "p2"), final.photoAnalyses.keys)
            val disk = ReviewStore(dir).load("class", i.sourceHash)!!; assertEquals(final.photoAnalyses, disk.photoAnalyses)
            val raw = dir.walkTopDown().first { it.extension == "json" }.readText()
            assertFalse(raw.contains("data:image")); assertFalse(raw.contains("base64"))
        } finally { dir.deleteRecursively() }
    }
}
