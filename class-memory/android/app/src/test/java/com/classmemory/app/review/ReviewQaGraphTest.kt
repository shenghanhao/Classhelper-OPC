package com.classmemory.app.review

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewQaGraphTest {
    @Before fun setUpMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }
    private fun input(): ReviewInput {
        val i = ReviewInput("class", "", "zh", null, "2026-11-04", null,
            listOf(ReviewSource("s", "class", "seg", 0, 1, "原文", true)), emptyList(), listOf(ReviewNote("n", 0, "QUESTION", "为什么？")), 3)
        return i.copy(sourceHash = i.contentHash())
    }
    private val outline = ReviewOutline("导览", listOf(ReviewSection("s1", "片段", listOf("s"))), listOf(ReviewTopic("u", "主题", listOf("s1"), listOf("s"))))
    private val unit = ReviewUnit("u", "主题", "解释", "", "重点", null, listOf("s"), emptyList(), listOf("s1"), "原版")
    private val plan = ReviewPlan("复习", "介绍", "2026-11-05", listOf(ReviewStep("步骤", "复习", listOf("u"))))

    @Test fun persistedThreadsGraphAndVersionsSurviveReopen() = runBlocking {
        val dir = Files.createTempDirectory("review-qa").toFile()
        try {
            val i = input(); val revised = i.copy(title = "更新").let { it.copy(sourceHash = it.contentHash()) }
            val turn = ReviewQaTurn("问题", "答案", false, listOf("s"), "q1")
            val run = ReviewRun(i, outline, mapOf("u" to unit), plan, updatedAt = 1, qaThreads = listOf(ReviewQaThread("thread", "u", listOf(turn))), graph = ReviewGraph(listOf("u"), emptyList()), unitVersions = mapOf("u" to listOf("原版")), notesProcessed = true)
            val store = ReviewStore(dir); store.save(run); store.save(ReviewRun(revised, updatedAt = 2))
            val versions = ReviewStore(dir).listVersions("class")
            assertEquals(listOf(revised.sourceHash, i.sourceHash), versions.map { it.input.sourceHash })
            assertEquals(run, versions.last())
        } finally { dir.deleteRecursively() }
    }

    @Test fun existingPlanGetsNotesOnceAndSavesUpdatedBody() = runBlocking {
        val dir = Files.createTempDirectory("review-notes").toFile()
        try {
            val base = input(); val i = base.copy(notes = base.notes + ReviewNote("c", 0, "COMMENT", "补充")).let { it.copy(sourceHash = it.contentHash()) }; val store = ReviewStore(dir)
            store.save(ReviewRun(i, outline, mapOf("u" to unit), plan, updatedAt = 1))
            var calls = 0
            val gateway = object : ReviewGateway {
                override suspend fun outline(input: ReviewInput) = error("unexpected")
                override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String) = error("unexpected")
                override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = error("unexpected")
                override suspend fun notes(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewNotesResult {
                    calls++
                    return ReviewNotesResult(listOf(ReviewNoteResult("n", "answered", "u", "答案", listOf("s")), ReviewNoteResult("c", "context", null, "", emptyList(), "n")), mapOf("u" to "融合答案"))
                }
            }
            val coordinator = ReviewCoordinator(store, gateway) { i.sourceHash }
            val run = coordinator.run(i); coordinator.run(i)
            assertEquals(1, calls); assertEquals("融合答案", run.units.getValue("u").markdown)
            assertEquals(listOf("原版"), run.unitVersions["u"]); assertEquals("n", run.qaThreads.single().noteId); assertEquals(listOf("c"), run.qaThreads.single().relatedNoteIds)
            assertTrue(ReviewStore(dir).load("class", i.sourceHash)!!.notesProcessed)
        } finally { dir.deleteRecursively() }
    }
    @Test fun gatewaySendsCompleteContextAndThreadHistory() = runBlocking {
        val i = input()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("qa", body.getString("stage")); assertEquals("u", body.getString("targetUnitId"))
            assertEquals("原文", body.getJSONObject("input").getJSONArray("sources").getJSONObject(0).getString("text"))
            assertEquals("原版", body.getJSONArray("units").getJSONObject(0).getString("markdown"))
            assertEquals("前答", body.getJSONArray("history").getJSONObject(0).getString("answer"))
            val result = """{"answerMarkdown":"答案","sourceIds":["s"],"updates":[{"unitId":"u","markdown":"新正文"}],"clarificationNeeded":false}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(result.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val gateway = ReviewHttpGateway("https://example.test", client = client)
        val result = gateway.qa(i, outline, listOf(unit), "u", "追问", listOf(ReviewQaTurn("前问", "前答")))
        assertEquals("答案", result.answerMarkdown); assertEquals("新正文", result.updates["u"])
    }

    @Test fun failedQuestionReopensAndRetriesWithSameIdentity() = runBlocking {
        val dir = Files.createTempDirectory("review-retry").toFile()
        try {
            val i = input(); val store = ReviewStore(dir); val original = ReviewRun(i, outline, mapOf("u" to unit), plan, updatedAt = 1)
            store.save(original); var offline = true
            val gateway = object : ReviewGateway {
                override suspend fun outline(input: ReviewInput) = error("unexpected")
                override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String) = error("unexpected")
                override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = error("unexpected")
                override suspend fun qa(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, targetUnitId: String?, question: String, history: List<ReviewQaTurn>, noteIds: List<String>): ReviewQaResult {
                    assertEquals(listOf("n"), noteIds)
                    assertTrue(history.isEmpty())
                    if (offline) error("offline")
                    return ReviewQaResult("答案", listOf("s"), mapOf("u" to "新正文"), false)
                }
            }
            val runtime = ReviewRuntime(store, { true }, { gateway })
            try { runtime.ask(original, ReviewConnection(), "问题", "u", newThreadId = "thread-fixed", newTurnId = "turn-fixed"); fail("expected failure") } catch (_: IllegalStateException) { }
            val failed = ReviewStore(dir).load("class", i.sourceHash)!!.qaThreads.single()
            val id = failed.turns.single().id; assertEquals("turn-fixed", id); assertEquals("thread-fixed", failed.id); assertEquals("failed", failed.turns.single().status)
            val saved = store.load("class", i.sourceHash)!!
            store.save(saved.copy(qaThreads = listOf(failed.copy(turns = failed.turns + ReviewQaTurn("后问", "后答", id = "later")))))
            offline = false
            val done = runtime.ask(original, ReviewConnection(), "问题", "u", failed.id, id)
            assertEquals(listOf(id, "later"), done.qaThreads.single().turns.map { it.id })
            assertEquals("answered", done.qaThreads.single().turns.first().status)
            assertEquals("新正文", done.units.getValue("u").markdown)
        } finally { dir.deleteRecursively() }
    }

    @Test fun concurrentQuestionsKeepBothResults() = runBlocking {
        val dir = Files.createTempDirectory("review-concurrent").toFile()
        try {
            val i = input(); val store = ReviewStore(dir); val original = ReviewRun(i, outline, mapOf("u" to unit), plan, updatedAt = 1); store.save(original)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val gateway = object : ReviewGateway {
                override suspend fun outline(input: ReviewInput) = error("unexpected")
                override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String) = error("unexpected")
                override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = error("unexpected")
                override suspend fun qa(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, targetUnitId: String?, question: String, history: List<ReviewQaTurn>, noteIds: List<String>): ReviewQaResult {
                    if (question == "一") { entered.complete(Unit); release.await() }
                    return ReviewQaResult("答$question", listOf("s"), emptyMap(), false)
                }
            }
            val runtime = ReviewRuntime(store, { true }, { gateway })
            val first = async { runtime.ask(original, ReviewConnection(), "一") }; entered.await()
            val second = async { runtime.ask(original, ReviewConnection(), "二") }; release.complete(Unit)
            first.await(); second.await()
            assertEquals(setOf("一", "二"), store.load("class", i.sourceHash)!!.qaThreads.map { it.turns.single().question }.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun staleGraphResponseIsNotSaved() = runBlocking {
        val dir = Files.createTempDirectory("review-graph-stale").toFile()
        try {
            val i = input(); val store = ReviewStore(dir); val original = ReviewRun(i, outline, mapOf("u" to unit), plan, updatedAt = 1); store.save(original)
            var current = true
            val gateway = object : ReviewGateway {
                override suspend fun outline(input: ReviewInput) = error("unexpected")
                override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String) = error("unexpected")
                override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = error("unexpected")
                override suspend fun graph(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewGraph { current = false; return ReviewGraph(listOf("u"), emptyList()) }
            }
            val runtime = ReviewRuntime(store, { current }, { gateway })
            try { runtime.generateGraph(original, ReviewConnection()); fail("expected stale response") } catch (_: IllegalStateException) { }
            assertNull(store.load("class", i.sourceHash)!!.graph)
        } finally { dir.deleteRecursively() }
    }

}
