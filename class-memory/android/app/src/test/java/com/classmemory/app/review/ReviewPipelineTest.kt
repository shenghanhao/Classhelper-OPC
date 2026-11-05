package com.classmemory.app.review

import com.classmemory.app.course.CourseContext
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReviewPipelineTest {
    private val directory = Files.createTempDirectory("review-v2-test").toFile()
    private val store = ReviewStore(directory)
    private val input = ReviewInput("class-1", "", "zh", "课堂", "2026-11-04", CourseContext(id = "course-1", name = "计算机", revision = 1),
        listOf(ReviewSource("s1", "class-1", "seg1", 0, 1000, "栈后进先出", true), ReviewSource("s2", "class-1", "seg2", 1000, 2000, "队列先进先出", true), ReviewSource("old", "class-0", "seg0", 0, 1000, "线性表定义", false)),
        listOf(ReviewPhotoRef("photo1", "class-1", 500)), emptyList()).hashed()
    private fun ReviewInput.hashed() = copy(sourceHash = contentHash())
    private val outline = ReviewOutline("全课导览", listOf(ReviewSection("part1", "第一部分", listOf("s1")), ReviewSection("part2", "第二部分", listOf("s2"))),
        listOf(ReviewTopic("t1", "栈", listOf("part1"), listOf("s1")), ReviewTopic("t2", "队列", listOf("part2"), listOf("s2"))))
    private fun unit(id: String) = if (id == "t1") ReviewUnit(id, "栈", "后进先出", "", "出栈顺序", null, listOf("s1", "old"), listOf("photo1"), listOf("part1"))
        else ReviewUnit(id, "队列", "先进先出", "", "出队顺序", null, listOf("s2"), emptyList(), listOf("part2"))
    private val plan = ReviewPlan("复习题案", "依次复习", "2026-11-05", listOf(ReviewStep("复习", "对照原文回顾概念", listOf("t1", "t2"))))
    private val calls = mutableListOf<String>()
    private var failUnit: String? = null
    private var onUnit: (String) -> Unit = {}
    private val gateway = object : ReviewGateway {
        override suspend fun outline(input: ReviewInput): ReviewOutline { calls += "outline"; return outline }
        override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String): ReviewUnit {
            calls += topicId; onUnit(topicId)
            if (topicId == failUnit) error("Temporary failure")
            return unit(topicId)
        }
        override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewPlan { calls += "plan"; return plan }
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun contentHashTracksContextSourcesAndLanguageButNotDayOrSelf() {
        assertEquals(input.sourceHash, input.copy(today = "2026-12-01", sourceHash = "ignored").contentHash())
        assertNotEquals(input.sourceHash, input.copy(language = "en").contentHash())
        assertNotEquals(input.sourceHash, input.copy(course = input.course!!.copy(revision = 2)).contentHash())
        assertNotEquals(input.sourceHash, input.copy(sources = input.sources.map { if (it.id == "old") it.copy(text = "原文已修正") else it }).contentHash())
        assertNotEquals(input.sourceHash, input.copy(photos = emptyList()).contentHash())
        assertEquals(input, ReviewJson.input(ReviewJson.input(input)))
    }

    @Test fun outlineRequiresCompleteChronologicalPartitionAndTopicCoverage() {
        ReviewContracts.validate(outline, input)
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(outline.copy(sections = outline.sections.reversed()), input) }
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(outline.copy(sections = outline.sections.take(1)), input) }
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(outline.copy(topics = outline.topics.take(1)), input) }
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(outline.copy(topics = listOf(outline.topics[0].copy(sourceIds = listOf("s2")), outline.topics[1])), input) }
    }

    @Test fun multiSectionTopicAndOptionalExampleAreSupported() {
        val combined = outline.copy(topics = listOf(ReviewTopic("t1", "顺序", listOf("part1", "part2"), listOf("s1", "s2"))))
        ReviewContracts.validate(combined, input)
        ReviewContracts.validate(unit("t1").copy(sectionIds = listOf("part1", "part2")), input, combined)
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(unit("t1").copy(sourceIds = listOf("old")), input, outline) }
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(unit("t1").copy(photoIds = listOf("missing")), input, outline) }
    }

    @Test fun planMustCoverEveryUnitAndUseValidFutureDate() {
        val units = listOf(unit("t1"), unit("t2"))
        ReviewContracts.validate(plan, input, units)
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(plan.copy(suggestedDate = "2026-11-03"), input, units) }
        assertThrows(Exception::class.java) { ReviewContracts.validate(plan.copy(suggestedDate = "2026-02-30"), input, units) }
        assertThrows(IllegalArgumentException::class.java) { ReviewContracts.validate(plan.copy(steps = listOf(plan.steps[0].copy(unitIds = listOf("t1")))), input, units) }
    }

    @Test fun retryAfterRestartSkipsDurableSuccessesAndRetainsReviewedMaterial() = runBlocking {
        failUnit = "t2"
        val coordinator = ReviewCoordinator(store, gateway) { input.sourceHash }
        val partial = coordinator.run(input)
        assertEquals(setOf("t1"), partial.units.keys)
        assertEquals(setOf("unit:t2"), partial.errors.keys)
        assertNull(partial.plan)
        failUnit = null
        val reopened = ReviewStore(directory)
        val completed = ReviewCoordinator(reopened, gateway) { input.sourceHash }.run(input.copy(today = "2026-11-06"))
        assertEquals(listOf("outline", "t1", "t2", "t2", "plan"), calls)
        assertEquals("2026-11-04", completed.input.today)
        assertTrue(completed.errors.isEmpty())
        assertEquals(plan, completed.plan)
        reopened.markReviewed(input.sessionId, input.sourceHash)
        val reviewed = reopened.latest(input.sessionId)!!
        assertNotNull(reviewed.reviewedAt)
        assertEquals(plan, reviewed.plan)
        assertEquals(2, reviewed.units.size)
        reopened.save(completed)
        assertNotNull(reopened.latest(input.sessionId)!!.reviewedAt)
        ReviewCoordinator(reopened, gateway) { input.sourceHash }.run(input)
        assertEquals(5, calls.size)
    }

    @Test fun staleDuringStageStopsAndPreservesEarlierStages() = runBlocking {
        var current: String? = input.sourceHash
        onUnit = { if (it == "t2") current = "b".repeat(64) }
        val coordinator = ReviewCoordinator(store, gateway) { current }
        try { coordinator.run(input); fail("Expected stale error") } catch (e: ReviewSourceChangedException) { assertFalse(e.deleted) }
        assertEquals(setOf("t1"), store.latest(input.sessionId)!!.units.keys)
        assertFalse(calls.contains("plan"))
        current = null
        try { coordinator.run(input); fail("Expected deleted error") } catch (e: ReviewSourceChangedException) { assertTrue(e.deleted) }
    }

    @Test fun cancellationDoesNotBecomeAStoredFailure() = runBlocking {
        onUnit = { throw CancellationException("cancelled") }
        try { ReviewCoordinator(store, gateway) { input.sourceHash }.run(input); fail("Expected cancellation") } catch (_: CancellationException) { }
        val partial = store.latest(input.sessionId)!!
        assertNotNull(partial.outline)
        assertTrue(partial.errors.isEmpty())
        assertTrue(partial.units.isEmpty())
    }

    @Test fun perHashHistorySurvivesAndCorruptionIsNotReset() = runBlocking {
        val first = ReviewRun(input, updatedAt = 1)
        val revised = input.copy(title = "新标题").hashed()
        store.save(first)
        store.save(ReviewRun(revised, updatedAt = 2))
        assertEquals(first, store.load(input.sessionId, input.sourceHash))
        assertEquals(revised, store.latest(input.sessionId)!!.input)
        val file = directory.walkTopDown().first { it.name == "${revised.sourceHash}.json" }
        file.writeText("corrupt")
        try { store.latest(input.sessionId); fail("Expected corruption error") } catch (_: org.json.JSONException) { }
        assertEquals("corrupt", file.readText())
    }

    @Test fun classroomDeletionRemovesAllItsVersionsAndPreservesOtherClasses() = runBlocking {
        val changed = input.copy(title = "修订").hashed()
        val other = input.copy(sessionId = "other", sources = input.sources.map {
            if (it.isCurrent) it.copy(sessionId = "other") else it
        }, photos = input.photos.map { if (it.sessionId == input.sessionId) it.copy(sessionId = "other") else it }).hashed()
        store.save(ReviewRun(input, updatedAt = 1))
        store.save(ReviewRun(changed, updatedAt = 2))
        store.save(ReviewRun(other, updatedAt = 3))
        val before = store.changes.value
        store.deleteSession(input.sessionId)
        assertNull(store.latest(input.sessionId))
        assertNull(store.load(input.sessionId, input.sourceHash))
        assertEquals(other, store.latest("other")!!.input)
        assertTrue(store.changes.value > before)
        store.deleteSession(input.sessionId)
    }

    @Test fun invalidJsonTypesCannotBeCoercedToText() {
        val json = ReviewJson.outline(outline).put("overview", 123)
        assertThrows(IllegalStateException::class.java) { ReviewJson.outline(json) }
    }

    @Test fun realDeepSeekSyntheticResponseValidatesThroughAndroidAndPersistsReviewed() = runBlocking {
        val raw = javaClass.getResourceAsStream("/review-v2-synthetic-deepseek.json")!!.bufferedReader().use { it.readText() }
        val fixture = JSONObject(raw)
        assertTrue(fixture.getBoolean("syntheticOnly"))
        val original = fixture.getJSONObject("input")
        // Server authenticates an opaque content hash; canonicalize it with the Android codec for replay.
        original.put("sourceHash", "0".repeat(64))
        val fields = ReviewInput(
            original.getString("sessionId"), "", original.getString("language"), original.getString("title"), original.getString("today"),
            fixtureCourse(original.getJSONObject("course")),
            (0 until original.getJSONArray("sources").length()).map { i -> original.getJSONArray("sources").getJSONObject(i).let {
                ReviewSource(it.getString("id"), it.getString("sessionId"), it.getString("segmentId"), it.getLong("startMs"), it.getLong("endMs"), it.getString("text"), it.getBoolean("isCurrent"))
            } },
            (0 until original.getJSONArray("photos").length()).map { i -> original.getJSONArray("photos").getJSONObject(i).let {
                ReviewPhotoRef(it.getString("id"), it.getString("sessionId"), it.getLong("atMs"))
            } },
            (0 until original.getJSONArray("notes").length()).map { i -> original.getJSONArray("notes").getJSONObject(i).let {
                ReviewNote(it.getString("id"), it.getLong("atMs"), it.getString("kind"), it.getString("text"))
            } },
        ).hashed()
        val generatedOutline = ReviewJson.outline(fixture.getJSONObject("outline"))
        val generatedUnits = (0 until fixture.getJSONArray("units").length()).map { ReviewJson.unit(fixture.getJSONArray("units").getJSONObject(it)) }
        val generatedPlan = ReviewJson.plan(fixture.getJSONObject("plan"))
        val run = ReviewRun(fields, generatedOutline, generatedUnits.associateBy { it.id }, generatedPlan, updatedAt = 20)
        ReviewContracts.validate(run)
        assertTrue(generatedOutline.topics.any { it.sectionIds.size > 1 })
        assertTrue(generatedUnits.any { it.example == null })
        assertTrue(generatedUnits.any { it.photoIds.isNotEmpty() })
        assertTrue(generatedUnits.any { "history-1" in it.sourceIds })
        store.save(run)
        store.markReviewed(fields.sessionId, fields.sourceHash)
        val reopened = ReviewStore(directory).latest(fields.sessionId)!!
        assertNotNull(reopened.reviewedAt)
        assertEquals(generatedPlan, reopened.plan)
    }

    @Test fun progressPersistsAndRejectsChangedPlan() = runBlocking {
        val twoSteps = plan.copy(steps = listOf(plan.steps[0], plan.steps[0].copy(title = "第二轮")))
        val run = ReviewRun(input, outline, mapOf("t1" to unit("t1"), "t2" to unit("t2")), twoSteps, updatedAt = 1)
        store.save(run)
        store.setStepCompleted(input.sessionId, input.sourceHash, twoSteps.fingerprint(), 0, true)
        assertEquals(setOf(0), store.load(input.sessionId, input.sourceHash)!!.completedStepIndices)
        store.setStepCompleted(input.sessionId, input.sourceHash, twoSteps.fingerprint(), 1, true)
        assertNotNull(store.load(input.sessionId, input.sourceHash)!!.reviewedAt)
        store.setStepCompleted(input.sessionId, input.sourceHash, twoSteps.fingerprint(), 0, false)
        assertNull(store.load(input.sessionId, input.sourceHash)!!.reviewedAt)
        try { store.setStepCompleted(input.sessionId, input.sourceHash, "old", 0, true); fail("stale plan accepted") } catch (_: IllegalStateException) { }
        val changed = twoSteps.copy(title = "新方案")
        store.save(run.copy(plan = changed))
        assertTrue(store.load(input.sessionId, input.sourceHash)!!.completedStepIndices.isEmpty())
        Unit
    }

    private fun fixtureCourse(c: JSONObject) = CourseContext(id = c.getString("id"), name = c.getString("name"), revision = c.getLong("revision"),
        background = c.getString("background"), goals = c.getString("goals"), chapters = c.getString("chapters"), terminology = c.getString("terminology"), prerequisites = c.getString("prerequisites"), sources = c.getString("sources"))

    @Test fun httpsGatewaySendsProxyTokenAndReadsLargeUntruncatedResponse() = runBlocking {
        val large = outline.copy(overview = "课".repeat(300_000))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://review.example/v3/study/review", chain.request().url.toString())
            assertEquals("Bearer access-test", chain.request().header("Authorization"))
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val sent = JSONObject(buffer.readUtf8())
            assertEquals("outline", sent.getString("stage"))
            assertEquals(input.sourceHash, sent.getJSONObject("input").getString("sourceHash"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ReviewJson.outline(large).toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val service = ReviewHttpGateway("https://review.example", "access-test", http)
        assertEquals(large, service.outline(input))
        assertThrows(IllegalArgumentException::class.java) { ReviewHttpGateway("http://review.example") }
        Unit
    }
}
