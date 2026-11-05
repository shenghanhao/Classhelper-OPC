package com.classmemory.app.review

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class ReviewGatewayException(val code: String, message: String, val httpStatus: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/** Provider credentials never enter this class. Access token authenticates only to the proxy. */
class ReviewHttpGateway(
    baseUrl: String,
    private val accessToken: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES).writeTimeout(120, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).build(),
) : ReviewGateway {
    private val endpoint: String
    init {
        val uri = URI(baseUrl.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Review service requires an HTTPS base URL without credentials / 请填写不含密钥的 HTTPS 服务地址"
        }
        endpoint = baseUrl.trim().trimEnd('/') + "/v3/study/review"
    }
    private fun request(stage: String, input: ReviewInput): JSONObject {
        ReviewContracts.validate(input)
        return JSONObject().put("stage", stage).put("input", ReviewJson.input(input).put("schemaVersion", 3))
    }
    override suspend fun outline(input: ReviewInput): ReviewOutline = ReviewJson.outline(call(request("outline", input))).also { ReviewContracts.validate(it, input) }
    override suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String): ReviewUnit {
        ReviewContracts.validate(outline, input)
        require(outline.topics.any { it.id == topicId })
        val body = request("unit", input).put("outline", ReviewJson.outline(outline)).put("topicId", topicId)
        return ReviewJson.unit(call(body)).also { require(it.id == topicId); ReviewContracts.validate(it, input, outline) }
    }
    override suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewPlan {
        ReviewContracts.validate(outline, input)
        require(units.map { it.id }.distinct().size == units.size && units.map { it.id }.toSet() == outline.topics.map { it.id }.toSet())
        units.forEach { ReviewContracts.validate(it, input, outline) }
        val body = request("plan", input).put("outline", ReviewJson.outline(outline)).put("units", JSONArray().also { a -> units.forEach { a.put(ReviewJson.unit(it)) } })
        return ReviewJson.plan(call(body)).also { ReviewContracts.validate(it, input, units) }
    }
    private fun full(stage: String, input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>) = request(stage, input).put("outline", ReviewJson.outline(outline)).put("units", JSONArray().also { a -> units.forEach { a.put(ReviewJson.unit(it)) } })
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    private fun updates(o: JSONObject): Map<String, String> { val a = o.getJSONArray("updates"); return (0 until a.length()).associate { a.getJSONObject(it).let { x -> x.getString("unitId") to x.getString("markdown") } } }
    override suspend fun qa(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, targetUnitId: String?, question: String, history: List<ReviewQaTurn>, noteIds: List<String>): ReviewQaResult {
        val body = full("qa", input, outline, units).put("targetUnitId", targetUnitId ?: JSONObject.NULL).put("question", question).put("history", JSONArray().also { a -> history.forEach { a.put(JSONObject().put("question", it.question).put("answer", it.answerMarkdown)) } }).put("noteIds", JSONArray(noteIds))
        val o = call(body); return ReviewQaResult(o.getString("answerMarkdown"), strings(o.getJSONArray("sourceIds")), updates(o), o.getBoolean("clarificationNeeded"))
    }
    override suspend fun graph(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewGraph {
        val o = call(full("graph", input, outline, units)); val n = o.getJSONArray("nodes"); val e = o.getJSONArray("edges")
        return ReviewGraph((0 until n.length()).map { n.getJSONObject(it).getString("unitId") }, (0 until e.length()).map { e.getJSONObject(it).let { x -> ReviewGraphEdge(x.getString("fromUnitId"), x.getString("toUnitId"), x.getString("relation")) } })
    }
    override suspend fun notes(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewNotesResult {
        val o = call(full("notes", input, outline, units)); val a = o.getJSONArray("items")
        return ReviewNotesResult((0 until a.length()).map { a.getJSONObject(it).let { x -> ReviewNoteResult(x.getString("noteId"), x.getString("disposition"), if (x.isNull("unitId")) null else x.getString("unitId"), x.getString("answerMarkdown"), strings(x.getJSONArray("sourceIds")), if (x.isNull("relatedQuestionNoteId")) null else x.getString("relatedQuestionNoteId")) } }, updates(o))
    }
    private suspend fun call(body: JSONObject): JSONObject {
        val request = Request.Builder().url(endpoint).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken") }.build()
        val response = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ReviewGatewayException("NETWORK", "Review service connection failed / 复习服务连接失败", cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            val text = it.body?.string() ?: throw ReviewGatewayException("EMPTY_RESPONSE", "Review service returned no body")
                            if (!it.isSuccessful) {
                                val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                                throw ReviewGatewayException(error?.optString("code") ?: "HTTP_ERROR", error?.optString("message")?.takeIf { m -> m.isNotBlank() } ?: "Review service HTTP ${it.code}", it.code)
                            }
                            text
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        return try { JSONObject(response) } catch (error: Exception) {
            throw ReviewGatewayException("INVALID_JSON", "Review service returned invalid JSON / 服务返回了无效数据", cause = error)
        }
    }
}
