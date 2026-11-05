package com.classmemory.app.review

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewLegacyV2Test {
    @Test fun v2IdentityUsesOriginalMetadataAndDetectsChangedContent() {
        val photo = ReviewPhotoRef("p", "class", 5)
        val raw = ReviewInput("class", "", "zh", "课堂", "2026-11-04", null, listOf(ReviewSource("s", "class", "s", 0, 1, "原文", true)), listOf(photo), emptyList())
        val old = raw.copy(sourceHash = raw.contentHash())
        val run = ReviewRun(old, updatedAt = 1)
        val v3 = old.copy(analysisVersion = 3, photos = listOf(photo))
        assertTrue(v3.matchesLegacyReview(run, listOf(photo)))
        assertFalse(v3.copy(sources = v3.sources.map { it.copy(text = "变化") }).matchesLegacyReview(run, listOf(photo)))
        assertFalse(v3.matchesLegacyReview(run, emptyList()))
    }
    @Test fun originalV2ReviewedAndPendingMigrateWithoutLosingMaterial() = runBlocking {
        val directory = Files.createTempDirectory("v2-review").toFile()
        try {
            val store = ReviewStore(directory)
            suspend fun legacy(session: String, reviewedAt: Long?): ReviewRun {
                val raw = ReviewInput(session, "", "zh", "自编课堂", "2026-11-04", null,
                    listOf(ReviewSource("s", session, "segment", 0, 1, "自编原文", true)), emptyList(), emptyList())
                val input = raw.copy(sourceHash = raw.contentHash())
                val outline = ReviewOutline("导览", listOf(ReviewSection("section", "章节", listOf("s"))), listOf(ReviewTopic("t", "概念", listOf("section"), listOf("s"))))
                val unit = ReviewUnit("t", "概念", "解释", "", "重点", null, listOf("s"), emptyList(), listOf("section"))
                val plan = ReviewPlan("方案", "介绍", "2026-11-05", listOf(ReviewStep("第一轮", "任务一", listOf("t")), ReviewStep("第二轮", "任务二", listOf("t"))))
                val run = ReviewRun(input, outline, mapOf("t" to unit), plan, reviewedAt = reviewedAt, updatedAt = 1)
                store.save(run)
                val file = directory.walkTopDown().first { it.name == "${input.sourceHash}.json" }
                val json = JSONObject(file.readText())
                json.remove("completedStepIndices"); json.remove("planFingerprint"); json.remove("photoInsights")
                json.getJSONArray("units").getJSONObject(0).remove("markdown")
                val steps = json.getJSONObject("plan").getJSONArray("steps")
                for (i in 0 until steps.length()) { steps.getJSONObject(i).remove("task"); steps.getJSONObject(i).remove("completionCriteria") }
                file.writeText(json.toString())
                return run
            }
            val done = legacy("done", 100)
            val pending = legacy("pending", null)
            val loaded = store.load("done", done.input.sourceHash)!!
            assertEquals(done.units, loaded.units); assertEquals(done.plan, loaded.plan)
            assertEquals(setOf(0, 1), loaded.completedStepIndices)
            val untouched = store.load("pending", pending.input.sourceHash)!!
            assertEquals(pending.plan, untouched.plan); assertTrue(untouched.completedStepIndices.isEmpty())
            store.setStepCompleted("done", done.input.sourceHash, loaded.plan!!.fingerprint(), 0, false)
            val reopened = ReviewStore(directory).load("done", done.input.sourceHash)!!
            assertEquals(setOf(1), reopened.completedStepIndices); assertNull(reopened.reviewedAt)
            val changed = reopened.plan!!.copy(title = "新方案")
            store.save(reopened.copy(plan = changed, completedStepIndices = emptySet()))
            assertTrue(store.load("done", done.input.sourceHash)!!.completedStepIndices.isEmpty())
        } finally { directory.deleteRecursively() }
        Unit
    }
}
