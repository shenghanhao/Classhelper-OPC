package com.classmemory.app.review

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectDeepSeekGatewayTest {
    private fun input(): ReviewInput {
        val value = ReviewInput("class", "", "zh", null, "2026-11-05", null,
            listOf(ReviewSource("s1", "class", "a", 0, 1, "完整课堂原文一", true), ReviewSource("s2", "class", "b", 1, 2, "完整课堂原文二", true)), emptyList(), listOf(ReviewNote("n1", 0, "QUESTION", "为什么")), 3)
        return value.copy(sourceHash = value.contentHash())
    }
    private val outline = ReviewOutline("导览", listOf(ReviewSection("section", "片段", listOf("s1", "s2"))), listOf(ReviewTopic("unit", "主题", listOf("section"), listOf("s1", "s2"))))
    private val unit = ReviewUnit("unit", "主题", "解释", "", "重点", null, listOf("s1"), emptyList(), listOf("section"), "正文")
    private fun client(handler: (JSONObject) -> Pair<Int, String>): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("https://api.deepseek.com/chat/completions", chain.request().url.toString())
        assertEquals("Bearer test-key", chain.request().header("Authorization"))
        val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); val request = JSONObject(buffer.readUtf8())
        val (status, body) = handler(request)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun response(content: JSONObject, finish: String = "stop") = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content.toString())))).toString()

    @Test fun allStagesUseJsonModeAndKeepFullContext() = runBlocking {
        val i = input(); var count = 0
        val outputs = listOf(
            JSONObject("""{"overview":"导览","sections":[{"id":"section","title":"片段","startIndex":0,"endIndex":1}],"topics":[{"id":"unit","title":"主题","sectionIds":["section"]}]}"""), ReviewJson.unit(unit),
            JSONObject("""{"items":[{"noteId":"n1","disposition":"answered","unitId":"unit","answerMarkdown":"答","sourceIds":["s1"],"relatedQuestionNoteId":null}],"updates":[{"unitId":"unit","markdown":"融入"}]}"""),
            JSONObject("""{"title":"复习","introduction":"介绍","suggestedDate":"2026-11-05","steps":[{"title":"步骤","task":"回顾","completionCriteria":"说明","unitIds":["unit"]}]}"""),
            JSONObject("""{"answerMarkdown":"答","sourceIds":["s1"],"updates":[],"clarificationNeeded":false}"""),
            JSONObject("""{"nodes":[{"unitId":"unit"}],"edges":[]}""")
        )
        val gateway = DirectDeepSeekGateway("test-key", client = client { request ->
            assertEquals("deepseek-flash", request.getString("model")); assertFalse(request.has("max_tokens"))
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            val messages = request.getJSONArray("messages"); assertTrue(messages.getJSONObject(0).getString("content").contains("JSON"))
            val payload = JSONObject(messages.getJSONObject(1).getString("content")); val sources = payload.getJSONObject("input").getJSONArray("sources")
            assertEquals(2, sources.length()); assertEquals("完整课堂原文二", sources.getJSONObject(1).getString("text"))
            if (count == 4) { assertEquals("追问前答", payload.getJSONArray("history").getJSONObject(0).getString("answer")); assertEquals("n1", payload.getJSONArray("noteIds").getString(0)) }
            200 to response(outputs[count++])
        })
        assertEquals(outline, gateway.outline(i)); assertEquals(unit, gateway.unit(i, outline, "unit"))
        assertEquals("融入", gateway.notes(i, outline, listOf(unit)).updates["unit"])
        assertEquals("复习", gateway.plan(i, outline, listOf(unit)).title)
        assertEquals("答", gateway.qa(i, outline, listOf(unit), "unit", "问题", listOf(ReviewQaTurn("前问", "追问前答")), listOf("n1")).answerMarkdown)
        assertEquals(listOf("unit"), gateway.graph(i, outline, listOf(unit)).nodes); assertEquals(6, count)
    }

    @Test fun rejectsIncompleteOrMalformedResponsesAndSanitizesProviderErrors() = runBlocking {
        val i = input()
        for (status in listOf(401, 402, 429, 500, 302)) {
            val gateway = DirectDeepSeekGateway("test-key", client = client { status to "private test-key response" })
            try { gateway.outline(i); fail("Expected HTTP error") } catch (e: ReviewGatewayException) {
                assertEquals(status, e.httpStatus); assertFalse(e.message!!.contains("test-key")); assertFalse(e.message!!.contains("private"))
            }
        }
        for (content in listOf(response(JSONObject(), "length"), response(JSONObject()), "{}")) {
            val gateway = DirectDeepSeekGateway("test-key", client = client { 200 to content })
            try { gateway.outline(i); fail("Expected invalid result") } catch (e: ReviewGatewayException) { assertTrue(e.code in setOf("INVALID_RESPONSE", "INVALID_RESULT")) }
        }
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("test-key") }.build()
        try { DirectDeepSeekGateway("test-key", client = client).outline(i); fail("Expected network error") } catch (e: ReviewGatewayException) { assertFalse(e.message!!.contains("test-key")) }
    }

    @Test fun rejectsInvalidReferencesAndMissingFields() = runBlocking {
        val i = input()
        val bad = JSONObject("""{"answerMarkdown":"答","sourceIds":["unknown"],"updates":[],"clarificationNeeded":false}""")
        val gateway = DirectDeepSeekGateway("test-key", client = client { 200 to response(bad) })
        try { gateway.qa(i, outline, listOf(unit), null, "问题", emptyList()); fail("Expected invalid reference") } catch (e: ReviewGatewayException) { assertEquals("INVALID_RESULT", e.code) }
        val missing = DirectDeepSeekGateway("test-key", client = client { 200 to response(JSONObject("""{"nodes":[]}""")) })
        try { missing.graph(i, outline, listOf(unit)); fail("Expected invalid graph") } catch (e: ReviewGatewayException) { assertEquals("INVALID_RESULT", e.code) }
    }

    @Test fun outlineRangesCoverHundredsOfSourcesAndRejectGapsOverlapAndBounds() = runBlocking {
        val base = input()
        val changed = base.copy(sources = (0 until 250).reversed().map { ReviewSource("s$it", "class", "seg$it", it.toLong(), it.toLong(), "内容$it", true) })
        val full = changed.copy(sourceHash = changed.contentHash())
        fun ranges(start: Int, end: Int) = JSONObject("""{"overview":"导览","sections":[{"id":"a","title":"一","startIndex":0,"endIndex":99},{"id":"b","title":"二","startIndex":$start,"endIndex":$end}],"topics":[{"id":"u","title":"主题","sectionIds":["a","b"]}]}""")
        val good = DirectDeepSeekGateway("test-key", client = client { request ->
            val payload = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(250, payload.getJSONArray("currentSources").length())
            assertEquals("s0", payload.getJSONArray("currentSources").getJSONObject(0).getString("id"))
            200 to response(ranges(100, 249))
        }).outline(full)
        assertEquals((0 until 250).map { "s$it" }, good.sections.flatMap { it.sourceIds })
        for ((start, end) in listOf(101 to 249, 99 to 249, 100 to 250, 100 to 248)) {
            val gateway = DirectDeepSeekGateway("test-key", client = client { 200 to response(ranges(start, end)) })
            try { gateway.outline(full); fail("Expected invalid range") } catch (e: ReviewGatewayException) { assertEquals("INVALID_RESULT", e.code) }
        }
    }

    @Test fun invalidApiKeyDoesNotAppearInError() = runBlocking {
        val key = "secret中文"
        try { DirectDeepSeekGateway(key).outline(input()); fail("Expected invalid key") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains(key)) }
    }

    @Test fun coordinatorRetainsOutlineAcrossFailureAndRetry() = runBlocking {
        val dir = Files.createTempDirectory("direct-review").toFile()
        try {
            val store = ReviewStore(dir); val i = input(); var outlines = 0; var unitCalls = 0
            val service = DirectDeepSeekGateway("test-key", client = client { request ->
                val system = request.getJSONArray("messages").getJSONObject(0).getString("content")
                when {
                    system.contains("通读全部本课原文") -> {
                        outlines++
                        200 to response(JSONObject("""{"overview":"导览","sections":[{"id":"section","title":"片段","startIndex":0,"endIndex":1}],"topics":[{"id":"unit","title":"主题","sectionIds":["section"]}]}"""))
                    }
                    system.contains("整理指定 topicId") -> {
                        unitCalls++
                        if (unitCalls == 1) 503 to "private failure" else 200 to response(ReviewJson.unit(unit))
                    }
                    system.contains("逐条处理所有原 note") -> 200 to response(JSONObject("""{"items":[{"noteId":"n1","disposition":"answered","unitId":"unit","answerMarkdown":"答","sourceIds":["s1"],"relatedQuestionNoteId":null}],"updates":[{"unitId":"unit","markdown":"融合答复"}]}"""))
                    else -> 200 to response(JSONObject("""{"title":"复习","introduction":"介绍","suggestedDate":"2026-11-05","steps":[{"title":"步骤","task":"回顾","completionCriteria":"说明","unitIds":["unit"]}]}"""))
                }
            })
            val coordinator = ReviewCoordinator(store, service) { i.sourceHash }
            val failed = coordinator.run(i); assertNotNull(failed.outline); assertNull(failed.plan); assertTrue(failed.errors.containsKey("unit:unit"))
            val retried = ReviewCoordinator(ReviewStore(dir), service) { i.sourceHash }.run(i)
            assertEquals(1, outlines); assertEquals(2, unitCalls); assertNotNull(retried.plan)
            assertEquals("融合答复", retried.units.getValue("unit").markdown); assertTrue(retried.errors.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellingRequestStopsCoroutine() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(JSONObject()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val job = async(Dispatchers.Default) { DirectDeepSeekGateway("test-key", client = http).outline(input()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { release.countDown() }
    }

    @Test fun visionUsesFlashAndPassesOnlyAnalysisToLaterTextStages() = runBlocking {
        val original = input(); val p = ReviewPhotoRef("photo", "class", 5, "a".repeat(64))
        val selected = original.copy(photos = listOf(p), selectedVisionPhotoIds = listOf(p.id)).let { it.copy(sourceHash = it.contentHash()) }
        var calls = 0
        val gateway = DirectDeepSeekGateway("test-key", model = "deepseek-reasoner", client = client { request ->
            val user = request.getJSONArray("messages").getJSONObject(1)
            val stage = calls++
            if (stage == 0) {
                assertEquals("deepseek-flash", request.getString("model"))
                val parts = user.getJSONArray("content"); assertEquals("image_url", parts.getJSONObject(1).getString("type"))
                assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
                200 to response(JSONObject("""{"observation":"x=1","uncertainty":"数字模糊"}"""))
            } else {
                assertEquals("deepseek-reasoner", request.getString("model"))
                val data = JSONObject(user.getString("content")); assertEquals("x=1", data.getJSONArray("photoAnalyses").getJSONObject(0).getString("observation"))
                assertFalse(data.toString().contains("data:image"))
                val output = when (stage) {
                    1 -> JSONObject("""{"overview":"导览","sections":[{"id":"section","title":"片段","startIndex":0,"endIndex":1}],"topics":[{"id":"unit","title":"主题","sectionIds":["section"]}]}""")
                    2 -> ReviewJson.unit(unit)
                    3 -> JSONObject("""{"items":[{"noteId":"n1","disposition":"answered","unitId":"unit","answerMarkdown":"答","sourceIds":["s1"],"relatedQuestionNoteId":null}],"updates":[{"unitId":"unit","markdown":"融合"}]}""")
                    4 -> JSONObject("""{"title":"复习","introduction":"介绍","suggestedDate":"2026-11-05","steps":[{"title":"步骤","task":"回顾","completionCriteria":"说明","unitIds":["unit"]}]}""")
                    5 -> JSONObject("""{"answerMarkdown":"答","sourceIds":["s1"],"updates":[],"clarificationNeeded":false}""")
                    else -> JSONObject("""{"nodes":[{"unitId":"unit"}],"edges":[]}""")
                }
                200 to response(output)
            }
        })
        val result = gateway.analyzePhoto(p, byteArrayOf(1, 2, 3)); assertEquals("数字模糊", result.uncertainty)
        val textual = gateway.withPhotoAnalyses(listOf(result))
        assertEquals(outline, textual.outline(selected)); textual.unit(selected, outline, "unit"); textual.notes(selected, outline, listOf(unit))
        textual.plan(selected, outline, listOf(unit)); textual.qa(selected, outline, listOf(unit), null, "问题", emptyList())
        textual.graph(selected, outline, listOf(unit)); assertEquals(7, calls)
    }
}
