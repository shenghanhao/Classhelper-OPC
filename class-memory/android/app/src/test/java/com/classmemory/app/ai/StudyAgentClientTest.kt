package com.classmemory.app.ai

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyAgentClientTest {
    private val session = Session(
        id = "lesson-1",
        startedAt = 1_000,
        title = "数据结构",
        courseType = "计算机",
        endedAt = 61_000,
        status = SessionStatus.completed,
        durationSec = 60,
    )
    private val segments = listOf(
        TranscriptSegment(
            id = "seg-1",
            sessionId = session.id,
            startMs = 1_000,
            endMs = 4_000,
            text = "栈是后进先出的线性表",
            createdAt = 1,
            dedupeKey = "a",
        ),
        TranscriptSegment(
            id = "seg-2",
            sessionId = session.id,
            startMs = 5_000,
            endMs = 9_000,
            text = "队列是先进先出的线性表",
            createdAt = 2,
            dedupeKey = "b",
        ),
    )
    private val notes = listOf(
        ClassroomNote(
            id = "note-1",
            sessionId = session.id,
            atMs = 6_000,
            kind = ClassroomNoteKind.QUESTION,
            text = "栈和队列的区别是什么？",
            createdAt = 3,
        )
    )
    private val snapshot = StudySnapshot.create(session, segments, notes, capturedAt = 10_000)
    private val input = StudyInput.from(snapshot, language = "zh", requestId = "req-1")

    private fun validResponse(
        sourceHash: String = snapshot.sourceHash,
        summary: String = "本节课比较了栈与队列。",
    ): String = JSONObject().apply {
        put("schemaVersion", STUDY_SCHEMA_VERSION)
        put("sourceHash", sourceHash)
        put("skillVersion", STUDY_SKILL_VERSION)
        put("summary", summary)
        put("knowledgePoints", JSONArray().put(JSONObject().apply {
            put("id", "kp-1")
            put("title", "栈与队列的顺序")
            put("explanation", "栈后进先出，队列先进先出。")
            put("evidence", JSONArray().put(JSONObject().apply {
                put("segmentId", "seg-1")
                put("quote", "后进先出")
            }))
        }))
    }.toString()

    private fun client(
        code: Int = 200,
        body: String = validResponse(),
        onRequest: (Request) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun readsResponseBeyondOldByteCapWithoutTruncation() = runBlocking {
        val summary = "a".repeat(300_000)
        val service = StudyAgentClient("https://study.example.com", client(body = validResponse(summary = summary)))
        assertEquals(summary, service.analyze(input, snapshot).summary)
    }

    @Test
    fun sendsTraceableRequestAndParsesEvidence() = runBlocking {
        var payload: JSONObject? = null
        val client = StudyAgentClient(
            baseUrl = "https://study.example.com/",
            client = client { request ->
                assertEquals("https://study.example.com/v1/study/analyze", request.url.toString())
                assertEquals("POST", request.method)
                payload = JSONObject(request.body!!.let { body ->
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readUtf8()
                })
            },
        )

        val analysis = client.analyze(input, snapshot)

        assertEquals("本节课比较了栈与队列。", analysis.summary)
        assertEquals("seg-1", analysis.knowledgePoints.single().evidence.single().segmentId)
        val sent = requireNotNull(payload)
        assertEquals(snapshot.sourceHash, sent.getString("sourceHash"))
        assertEquals("req-1", sent.getString("requestId"))
        assertEquals(2, sent.getJSONArray("segments").length())
        assertFalse(sent.has("sessionId"))
        assertTrue(sent.getJSONArray("segments").toString().contains("后进先出"))
    }

    @Test
    fun rejectsMissingOrNonHttpsConfiguration() {
        val blank = assertThrows(StudyAgentException::class.java) {
            StudyAgentClient("")
        }
        assertEquals(StudyClientError.NOT_CONFIGURED, blank.kind)

        val insecure = assertThrows(StudyAgentException::class.java) {
            StudyAgentClient("http://study.example.com")
        }
        assertEquals(StudyClientError.INSECURE_URL, insecure.kind)
    }

    @Test
    fun mapsRateLimitAndServerErrorMessages() = runBlocking {
        val rateLimit = StudyAgentClient(
            "https://study.example.com",
            client(code = 429, body = """{"error":{"message":"today's quota is used up"}}"""),
        )
        val limited = assertThrows(StudyAgentException::class.java) {
            runBlocking { rateLimit.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.RATE_LIMITED, limited.kind)
        assertEquals(429, limited.httpStatus)
        assertEquals("today's quota is used up", limited.message)

        val server = StudyAgentClient(
            "https://study.example.com",
            client(code = 503, body = """{"error":{"message":"provider unavailable"}}"""),
        )
        val failed = assertThrows(StudyAgentException::class.java) {
            runBlocking { server.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.SERVER, failed.kind)
        assertEquals(503, failed.httpStatus)
    }

    @Test
    fun mapsUnauthorizedStatus() = runBlocking {
        val client = StudyAgentClient("https://study.example.com", client(code = 401, body = "{}"))
        val error = assertThrows(StudyAgentException::class.java) {
            runBlocking { client.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.UNAUTHORIZED, error.kind)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun rejectsInvalidAnalysisInsteadOfRepairingIt() = runBlocking {
        val missingPoints = JSONObject(validResponse()).apply { remove("knowledgePoints") }.toString()
        val client = StudyAgentClient("https://study.example.com", client(body = missingPoints))
        val error = assertThrows(StudyAgentException::class.java) {
            runBlocking { client.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.BAD_RESPONSE, error.kind)
        assertTrue(error.cause is StudyContractException)
    }

    @Test
    fun rejectsAnalysisForDifferentContent() = runBlocking {
        val client = StudyAgentClient(
            "https://study.example.com",
            client(body = validResponse(sourceHash = "other-hash")),
        )
        val error = assertThrows(StudyAgentException::class.java) {
            runBlocking { client.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.BAD_RESPONSE, error.kind)
    }

    @Test
    fun mapsTimeoutWithoutRetrying() = runBlocking {
        val attempts = AtomicInteger()
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor {
                attempts.incrementAndGet()
                throw SocketTimeoutException("read timed out")
            }
            .build()
        val client = StudyAgentClient("https://study.example.com", timeoutClient)
        val error = assertThrows(StudyAgentException::class.java) {
            runBlocking { client.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.TIMEOUT, error.kind)
        assertEquals(1, attempts.get())
    }

    @Test
    fun mapsNetworkFailureWithoutRetrying() = runBlocking {
        val attempts = AtomicInteger()
        val networkClient = OkHttpClient.Builder()
            .addInterceptor {
                attempts.incrementAndGet()
                throw IOException("offline")
            }
            .build()
        val client = StudyAgentClient("https://study.example.com", networkClient)
        val error = assertThrows(StudyAgentException::class.java) {
            runBlocking { client.analyze(input, snapshot) }
        }
        assertEquals(StudyClientError.NETWORK, error.kind)
        assertEquals(1, attempts.get())
    }
}
