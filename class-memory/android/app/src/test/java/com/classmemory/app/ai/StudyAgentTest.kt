package com.classmemory.app.ai

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyAgentTest {
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
    private val root = Files.createTempDirectory("study-agent-test").toFile()
    private val store = StudyResultStore(root)
    private var currentSnapshot: StudySnapshot? =
        StudySnapshot.create(session, segments, notes, capturedAt = 10_000)
    private var requests = 0

    private fun response(snapshot: StudySnapshot): String = JSONObject().apply {
        put("schemaVersion", STUDY_SCHEMA_VERSION)
        put("sourceHash", snapshot.sourceHash)
        put("skillVersion", STUDY_SKILL_VERSION)
        put("summary", "本节课比较了栈与队列。")
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

    private fun agent(
        onRequest: () -> Unit = {},
    ): StudyAgent {
        val snapshot = requireNotNull(currentSnapshot)
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += 1
                onRequest()
                Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(response(snapshot).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return StudyAgent(
            snapshotSource = { if (it == session.id) currentSnapshot else null },
            currentSourceHash = { if (it == session.id) currentSnapshot?.sourceHash else null },
            client = StudyAgentClient("https://study.example.com", http),
            store = store,
            now = { 20_000 },
            requestIdFactory = { "req-1" },
        )
    }

    @Test
    fun prepareExposesInputStatsForConsent() = runBlocking {
        val prepared = agent().prepare(session.id, "zh")
        assertTrue(prepared is StudyPreparation.Ready)
        val ready = prepared as StudyPreparation.Ready
        assertEquals(2, ready.input.segments.size)
        assertEquals(1, ready.input.notes.size)
        assertEquals(32, ready.input.charCount)
        assertNull(ready.cached)
    }

    @Test
    fun analyzeStoresTraceableRecordAndReusesItWithoutNetwork() = runBlocking {
        val studyAgent = agent()
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready

        val first = studyAgent.analyze(prepared)
        assertTrue(first is StudyRunResult.Success)
        val success = first as StudyRunResult.Success
        assertEquals(false, success.fromCache)
        assertEquals(1, requests)
        assertEquals("req-1", success.record.requestId)
        assertEquals(20_000L, success.record.analyzedAt)

        val reloaded = store.load(session.id, prepared.snapshot.sourceHash)
        assertNotNull(reloaded)
        assertEquals("本节课比较了栈与队列。", reloaded!!.summary)

        val again = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        assertNotNull(again.cached)
        val second = studyAgent.analyze(again)
        assertTrue(second is StudyRunResult.Success)
        assertEquals(true, (second as StudyRunResult.Success).fromCache)
        assertEquals(1, requests)
    }

    @Test
    fun forceReanalysisCreatesANewRequest() = runBlocking {
        val studyAgent = agent()
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        studyAgent.analyze(prepared)
        studyAgent.analyze(prepared, force = true)
        assertEquals(2, requests)
    }

    @Test
    fun discardsAnalysisWhenContentChangedBeforeTheCall() = runBlocking {
        val studyAgent = agent()
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        currentSnapshot = StudySnapshot.create(session.copy(title = "改过的标题"), segments, notes, 10_000)

        val result = studyAgent.analyze(prepared)
        assertEquals(StudyRunError.STALE, (result as StudyRunResult.Failure).error)
        assertEquals(0, requests)
        assertNull(store.load(session.id, prepared.snapshot.sourceHash))
    }

    @Test
    fun discardsAnalysisWhenContentChangedDuringTheCall() = runBlocking {
        lateinit var studyAgent: StudyAgent
        studyAgent = agent {
            currentSnapshot = StudySnapshot.create(session.copy(title = "改过的标题"), segments, notes, 10_000)
        }
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready

        val result = studyAgent.analyze(prepared)
        assertEquals(StudyRunError.STALE, (result as StudyRunResult.Failure).error)
        assertEquals(1, requests)
        assertNull(store.load(session.id, prepared.snapshot.sourceHash))
    }

    @Test
    fun discardsAnalysisWhenClassWasDeleted() = runBlocking {
        val studyAgent = agent()
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        currentSnapshot = null

        val result = studyAgent.analyze(prepared)
        assertEquals(StudyRunError.DELETED, (result as StudyRunResult.Failure).error)
        assertEquals(0, requests)
    }

    @Test
    fun reportsMissingClassAndEmptyTranscript() = runBlocking {
        val missing = agent().prepare("unknown", "zh")
        assertEquals(
            StudyPrepareError.SESSION_NOT_FOUND,
            (missing as StudyPreparation.Rejected).reason,
        )

        currentSnapshot = StudySnapshot.create(session, emptyList(), notes, 10_000)
        val empty = agent().prepare(session.id, "zh")
        assertEquals(
            StudyPrepareError.NO_FINAL_TRANSCRIPT,
            (empty as StudyPreparation.Rejected).reason,
        )
    }

    @Test
    fun mapsClientRateLimitToRecoverableFailure() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor {
                Response.Builder()
                    .request(it.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("""{"error":{"message":"quota"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val studyAgent = StudyAgent(
            snapshotSource = { currentSnapshot },
            currentSourceHash = { currentSnapshot?.sourceHash },
            client = StudyAgentClient("https://study.example.com", http),
            store = store,
            requestIdFactory = { "req-1" },
        )
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        val result = studyAgent.analyze(prepared)
        assertEquals(StudyRunError.RATE_LIMITED, (result as StudyRunResult.Failure).error)
    }

    @Test
    fun doesNotCreateRecordWhenStoreIsUnavailable() = runBlocking {
        val fileRoot = Files.createTempFile("not-a-directory", ".txt").toFile()
        val brokenStore = StudyResultStore(fileRoot)
        val http = OkHttpClient.Builder()
            .addInterceptor {
                val snapshot = requireNotNull(currentSnapshot)
                Response.Builder()
                    .request(it.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(response(snapshot).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val studyAgent = StudyAgent(
            snapshotSource = { currentSnapshot },
            currentSourceHash = { currentSnapshot?.sourceHash },
            client = StudyAgentClient("https://study.example.com", http),
            store = brokenStore,
            requestIdFactory = { "req-1" },
        )
        val prepared = studyAgent.prepare(session.id, "zh") as StudyPreparation.Ready
        val result = studyAgent.analyze(prepared)
        assertEquals(StudyRunError.STORE_FAILED, (result as StudyRunResult.Failure).error)
    }
}
