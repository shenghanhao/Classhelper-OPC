package com.classmemory.app.ai

import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyResultStoreTest {
    private val root = Files.createTempDirectory("study-store").toFile()
    private val store = StudyResultStore(root)
    private val session = Session(
        id = "lesson-1",
        startedAt = 1_000,
        title = "数据结构",
        courseType = "计算机",
        endedAt = 61_000,
        status = SessionStatus.completed,
        durationSec = 60,
    )
    private val segment = TranscriptSegment(
        id = "seg-1",
        sessionId = session.id,
        startMs = 1_000,
        endMs = 4_000,
        text = "栈是后进先出的线性表",
        createdAt = 1,
        dedupeKey = "a",
    )

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun snapshot(text: String = segment.text): StudySnapshot = StudySnapshot.create(
        session = session,
        segments = listOf(segment.copy(text = text)),
        notes = emptyList(),
        capturedAt = 10_000,
    )

    private fun record(snapshot: StudySnapshot, analyzedAt: Long = 20_000): StudyRecord {
        val input = StudyInput.from(snapshot, "zh", "request-1")
        val analysis = StudyAnalysis(
            schemaVersion = STUDY_SCHEMA_VERSION,
            sourceHash = snapshot.sourceHash,
            skillVersion = STUDY_SKILL_VERSION,
            summary = "栈是后进先出的线性表。",
            knowledgePoints = listOf(
                StudyKnowledgePoint(
                    id = "kp-1",
                    title = "栈",
                    explanation = "后进先出",
                    evidence = listOf(StudyEvidence("seg-1", "后进先出")),
                ),
            ),
        )
        return StudyRecord.create(analysis, snapshot, input, analyzedAt, model = "test-model")
    }

    @Test
    fun saveLoadRoundTripAndAtomicFile() = runBlocking {
        val snapshot = snapshot()
        val saved = record(snapshot)
        store.save(saved)

        assertEquals(saved, store.load(snapshot.sessionId, snapshot.sourceHash))
        val files = store.fileFor(snapshot.sessionId, snapshot.sourceHash).parentFile!!.listFiles()!!.map { it.name }
        assertEquals(listOf("${snapshot.sourceHash}.json"), files)
        assertFalse(files.any { it.endsWith(".tmp") })
    }

    @Test
    fun savingAgainReplacesTheSameHashFile() = runBlocking {
        val snapshot = snapshot()
        store.save(record(snapshot, analyzedAt = 20_000))
        store.save(record(snapshot, analyzedAt = 30_000))

        val loaded = store.load(snapshot.sessionId, snapshot.sourceHash)
        assertEquals(30_000L, loaded?.analyzedAt)
        assertEquals(1, store.listForSession(snapshot.sessionId).size)
    }

    @Test
    fun corruptFileIsIgnored() = runBlocking {
        val snapshot = snapshot()
        val file = store.fileFor(snapshot.sessionId, snapshot.sourceHash)
        file.parentFile!!.mkdirs()
        file.writeText("not-json")

        assertNull(store.load(snapshot.sessionId, snapshot.sourceHash))
        assertTrue(store.listForSession(snapshot.sessionId).isEmpty())
    }

    @Test
    fun listIsNewestFirstAndDeleteRemovesSessionDirectory() = runBlocking {
        val older = snapshot(text = "栈是后进先出的线性表")
        val newer = snapshot(text = "栈是先进先出的线性表")
        store.save(record(older, analyzedAt = 20_000))
        store.save(record(newer, analyzedAt = 30_000))

        assertEquals(listOf(newer.sourceHash, older.sourceHash), store.listForSession(session.id).map { it.sourceHash })

        store.deleteSession(session.id)
        assertTrue(store.listForSession(session.id).isEmpty())
        assertFalse(store.fileFor(session.id, older.sourceHash).parentFile!!.exists())
    }

    @Test
    fun unsafeSessionIdIsRejectedBeforeTouchingDisk() = runBlocking {
        val snapshot = snapshot()
        val failure = try {
            store.save(record(snapshot).copy(sessionId = "../escape"))
            null
        } catch (error: StudyContractException) {
            error
        }
        assertEquals(StudyErrorCode.INVALID_PATH, failure?.code)

        val pathFailure = assertThrows(StudyContractException::class.java) {
            store.fileFor("lesson-1", "../../etc/passwd")
        }
        assertEquals(StudyErrorCode.INVALID_PATH, pathFailure.code)
    }

    @Test
    fun invalidRecordIsRejectedOnSave() = runBlocking {
        val snapshot = snapshot()
        val failure = try {
            store.save(record(snapshot).copy(summary = " "))
            null
        } catch (error: StudyContractException) {
            error
        }
        assertEquals(StudyErrorCode.INVALID_SUMMARY, failure?.code)
    }

    @Test
    fun recordJsonRoundTripPreservesTraceability() {
        val saved = record(snapshot())
        val parsed = StudyRecord.fromJson(saved.toJson())
        assertEquals(saved, parsed)
        assertEquals(saved.sourceHash, parsed.sourceHash)
        assertEquals("seg-1", parsed.knowledgePoints.single().evidence.single().segmentId)
        assertTrue(root.exists())
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun malformedRecordJsonIsRejected() {
        val failure = assertThrows(StudyContractException::class.java) {
            StudyRecord.fromJson("{\"schemaVersion\":1}")
        }
        assertTrue(failure.code == StudyErrorCode.INVALID_RECORD || failure.code == StudyErrorCode.INVALID_KNOWLEDGE_POINTS)
    }
}
