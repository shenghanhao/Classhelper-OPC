package com.classmemory.app.ai

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyContractsTest {
    private val session = Session(
        id = "lesson-1",
        startedAt = 1_000,
        title = "数据结构",
        courseType = "计算机",
        endedAt = 61_000,
        status = SessionStatus.completed,
        durationSec = 60,
    )
    private val firstSegment = TranscriptSegment(
        id = "seg-1",
        sessionId = session.id,
        startMs = 1_000,
        endMs = 4_000,
        text = "栈是后进先出的线性表",
        createdAt = 1,
        dedupeKey = "a",
    )
    private val secondSegment = TranscriptSegment(
        id = "seg-2",
        sessionId = session.id,
        startMs = 5_000,
        endMs = 9_000,
        text = "队列是先进先出的线性表",
        createdAt = 2,
        dedupeKey = "b",
    )
    private val question = ClassroomNote(
        id = "note-1",
        sessionId = session.id,
        atMs = 6_000,
        kind = ClassroomNoteKind.QUESTION,
        text = "栈和队列的区别是什么？",
        createdAt = 3,
    )

    private fun snapshot(
        capturedAt: Long = 10_000,
        title: String? = session.title,
        segments: List<TranscriptSegment> = listOf(firstSegment, secondSegment),
        notes: List<ClassroomNote> = listOf(question),
    ): StudySnapshot = StudySnapshot.create(
        session = session.copy(title = title),
        segments = segments,
        notes = notes,
        capturedAt = capturedAt,
    )

    private fun response(
        snapshot: StudySnapshot,
        summary: String = "本节课比较了栈与队列。",
        points: JSONArray = JSONArray().put(point(snapshot)),
    ): String = JSONObject().apply {
        put("schemaVersion", STUDY_SCHEMA_VERSION)
        put("sourceHash", snapshot.sourceHash)
        put("skillVersion", STUDY_SKILL_VERSION)
        put("summary", summary)
        put("knowledgePoints", points)
    }.toString()

    private fun point(
        snapshot: StudySnapshot,
        id: String = "kp-1",
        segmentId: String = "seg-1",
        quote: String = "后进先出",
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", "栈的后进先出")
        put("explanation", "最后进入的元素最先离开。")
        put("evidence", JSONArray().put(JSONObject().apply {
            put("segmentId", segmentId)
            put("quote", quote)
        }))
    }

    @Test
    fun snapshotHashIsStableAndTracksContent() {
        val first = snapshot(capturedAt = 1)
        val second = snapshot(capturedAt = 999_999)
        assertEquals(first.sourceHash, second.sourceHash)

        val editedTranscript = snapshot(segments = listOf(firstSegment.copy(text = "栈是先进先出的线性表"), secondSegment))
        assertNotEquals(first.sourceHash, editedTranscript.sourceHash)

        val renamed = snapshot(title = "操作系统")
        assertNotEquals(first.sourceHash, renamed.sourceHash)

        val withExtraNote = snapshot(notes = listOf(question, question.copy(id = "note-2", text = "补充")))
        assertNotEquals(first.sourceHash, withExtraNote.sourceHash)
    }

    @Test
    fun inputOmitsSessionIdAndCarriesHash() {
        val snapshot = snapshot()
        val json = JSONObject(StudyInput.from(snapshot, "zh", "request-1").toRequestJson())
        assertFalse(json.has("sessionId"))
        assertEquals(snapshot.sourceHash, json.getString("sourceHash"))
        assertEquals(STUDY_SKILL_VERSION, json.getString("skillVersion"))
        assertEquals(2, json.getJSONArray("segments").length())
        assertEquals(1, json.getJSONArray("notes").length())
    }

    @Test
    fun emptyTranscriptIsRejected() {
        val empty = StudySnapshot.create(session, emptyList(), emptyList(), 1)
        val failure = assertThrows(StudyContractException::class.java) {
            StudyInput.from(empty, "zh")
        }
        assertEquals(StudyErrorCode.EMPTY_TRANSCRIPT, failure.code)
    }

    @Test
    fun oversizedInputIsRejected() {
        val huge = StudySnapshot.create(
            session,
            listOf(firstSegment.copy(text = "a".repeat(STUDY_MAX_INPUT_CHARS + 1))),
            emptyList(),
            1,
        )
        val failure = assertThrows(StudyContractException::class.java) {
            StudyInput.from(huge, "zh")
        }
        assertEquals(StudyErrorCode.INPUT_TOO_LARGE, failure.code)
    }

    @Test
    fun validResponseParsesWithTraceableEvidence() {
        val snapshot = snapshot()
        val analysis = StudyContracts.parseAnalysis(response(snapshot), snapshot)
        assertEquals("本节课比较了栈与队列。", analysis.summary)
        assertEquals(1, analysis.knowledgePoints.size)
        assertEquals("seg-1", analysis.knowledgePoints.single().evidence.single().segmentId)
        assertEquals("后进先出", analysis.knowledgePoints.single().evidence.single().quote)
    }

    @Test
    fun fabricatedQuoteIsRejected() {
        val snapshot = snapshot()
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis(response(snapshot, points = JSONArray().put(point(snapshot, quote = "先进先出"))), snapshot)
        }
        assertEquals(StudyErrorCode.INVALID_EVIDENCE, failure.code)
    }

    @Test
    fun evidenceFromAnotherClassIsRejected() {
        val snapshot = snapshot()
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis(
                response(snapshot, points = JSONArray().put(point(snapshot, segmentId = "seg-unknown"))),
                snapshot,
            )
        }
        assertEquals(StudyErrorCode.INVALID_EVIDENCE, failure.code)
    }

    @Test
    fun staleSourceHashIsRejected() {
        val snapshot = snapshot()
        val body = JSONObject(response(snapshot)).put("sourceHash", "0".repeat(64)).toString()
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis(body, snapshot)
        }
        assertEquals(StudyErrorCode.SOURCE_HASH_MISMATCH, failure.code)
    }

    @Test
    fun duplicateKnowledgePointIdsAreRejected() {
        val snapshot = snapshot()
        val points = JSONArray().put(point(snapshot)).put(point(snapshot, segmentId = "seg-2", quote = "先进先出"))
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis(response(snapshot, points = points), snapshot)
        }
        assertEquals(StudyErrorCode.INVALID_KNOWLEDGE_POINTS, failure.code)
    }

    @Test
    fun missingEvidenceIsRejected() {
        val snapshot = snapshot()
        val point = point(snapshot).put("evidence", JSONArray())
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis(response(snapshot, points = JSONArray().put(point)), snapshot)
        }
        assertEquals(StudyErrorCode.INVALID_EVIDENCE, failure.code)
    }

    @Test
    fun invalidJsonIsRejected() {
        val failure = assertThrows(StudyContractException::class.java) {
            StudyContracts.parseAnalysis("not-json", snapshot())
        }
        assertEquals(StudyErrorCode.INVALID_JSON, failure.code)
    }

    @Test
    fun storedPointsCanBeValidatedWithoutSnapshot() {
        val points = listOf(
            StudyKnowledgePoint(
                id = "kp-1",
                title = "栈",
                explanation = "后进先出",
                evidence = listOf(StudyEvidence("seg-1", "后进先出")),
            ),
        )
        StudyContracts.validateStoredPoints(points)
        assertTrue(points.single().evidence.single().quote.isNotBlank())
    }
}
