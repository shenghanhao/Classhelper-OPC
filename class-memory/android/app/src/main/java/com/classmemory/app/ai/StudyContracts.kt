package com.classmemory.app.ai

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val STUDY_SCHEMA_VERSION = 1
const val STUDY_SKILL_VERSION = "opc-summary-v1"
const val STUDY_MAX_INPUT_CHARS = 20_000
const val STUDY_MAX_SEGMENTS = 400
const val STUDY_MAX_NOTES = 100
const val STUDY_MAX_SUMMARY_CHARS = 4_000
const val STUDY_MAX_KNOWLEDGE_POINTS = 12
const val STUDY_MAX_EVIDENCE_PER_POINT = 5
const val STUDY_MAX_EVIDENCE_QUOTE_CHARS = 240

enum class StudyErrorCode {
    INVALID_JSON,
    UNSUPPORTED_SCHEMA,
    SOURCE_HASH_MISMATCH,
    INVALID_SUMMARY,
    INVALID_KNOWLEDGE_POINTS,
    INVALID_EVIDENCE,
    EMPTY_TRANSCRIPT,
    INPUT_TOO_LARGE,
    INVALID_RECORD,
    INVALID_PATH,
}

class StudyContractException(
    val code: StudyErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** A final transcript segment, detached from Room so it can be hashed and uploaded. */
data class StudySegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** A selected classroom question or comment. Never includes audio or images. */
data class StudyNote(
    val id: String,
    val atMs: Long,
    val kind: String,
    val text: String,
)

/**
 * Immutable, transaction-consistent view of one class at analysis time.
 * [sourceHash] binds any stored analysis to exactly this content.
 */
data class StudySnapshot(
    val sessionId: String,
    val capturedAt: Long,
    val title: String?,
    val courseType: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSec: Long,
    val segments: List<StudySegment>,
    val notes: List<StudyNote>,
    val sourceHash: String,
) {
    companion object {
        fun create(
            session: Session,
            segments: List<TranscriptSegment>,
            notes: List<ClassroomNote>,
            capturedAt: Long,
        ): StudySnapshot {
            val mappedSegments = segments
                .map { StudySegment(it.id, it.startMs, it.endMs, it.text) }
                .sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.id }))
            val mappedNotes = notes
                .map { StudyNote(it.id, it.atMs, it.kind.name, it.text) }
                .sortedWith(compareBy({ it.atMs }, { it.id }))
            return StudySnapshot(
                sessionId = session.id,
                capturedAt = capturedAt,
                title = session.title,
                courseType = session.courseType,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                durationSec = session.durationSec,
                segments = mappedSegments,
                notes = mappedNotes,
                sourceHash = computeSourceHash(
                    sessionId = session.id,
                    title = session.title,
                    courseType = session.courseType,
                    startedAt = session.startedAt,
                    segments = mappedSegments,
                    notes = mappedNotes,
                ),
            )
        }
    }
}

/**
 * Canonical hash input: length-prefixed fields, stable ordering, no timestamps that
 * change without content. Editing a segment, title, course type or note invalidates it.
 */
internal fun computeSourceHash(
    sessionId: String,
    title: String?,
    courseType: String?,
    startedAt: Long,
    segments: List<StudySegment>,
    notes: List<StudyNote>,
): String {
    val payload = StringBuilder()
    fun field(name: String, value: String) {
        payload.append(name.length).append(':').append(name).append('=')
            .append(value.length).append(':').append(value).append('\u001f')
    }
    field("schemaVersion", STUDY_SCHEMA_VERSION.toString())
    field("sessionId", sessionId)
    field("startedAt", startedAt.toString())
    field("title", title?.trim().orEmpty())
    field("courseType", courseType?.trim().orEmpty())
    field("segmentCount", segments.size.toString())
    segments.forEach {
        field("segment.id", it.id)
        field("segment.startMs", it.startMs.toString())
        field("segment.endMs", it.endMs.toString())
        field("segment.text", it.text)
    }
    field("noteCount", notes.size.toString())
    notes.forEach {
        field("note.id", it.id)
        field("note.atMs", it.atMs.toString())
        field("note.kind", it.kind)
        field("note.text", it.text)
    }
    return sha256(payload.toString())
}

/** Upload payload. Deliberately excludes the local session ID and all device identity. */
data class StudyInput(
    val requestId: String,
    val sourceHash: String,
    val skillVersion: String = STUDY_SKILL_VERSION,
    val language: String,
    val title: String?,
    val courseType: String?,
    val segments: List<StudySegment>,
    val notes: List<StudyNote>,
) {
    val charCount: Int
        get() = segments.sumOf { it.text.length } + notes.sumOf { it.text.length }

    fun toRequestJson(): String = JSONObject().apply {
        put("schemaVersion", STUDY_SCHEMA_VERSION)
        put("requestId", requestId)
        put("sourceHash", sourceHash)
        put("skillVersion", skillVersion)
        put("language", language)
        put("title", title ?: JSONObject.NULL)
        put("courseType", courseType ?: JSONObject.NULL)
        put("segments", JSONArray().apply {
            segments.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("startMs", it.startMs)
                    put("endMs", it.endMs)
                    put("text", it.text)
                })
            }
        })
        put("notes", JSONArray().apply {
            notes.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("atMs", it.atMs)
                    put("kind", it.kind)
                    put("text", it.text)
                })
            }
        })
    }.toString()

    companion object {
        fun from(
            snapshot: StudySnapshot,
            language: String,
            requestId: String = UUID.randomUUID().toString(),
        ): StudyInput {
            if (snapshot.segments.isEmpty()) {
                throw StudyContractException(StudyErrorCode.EMPTY_TRANSCRIPT, "The class has no final transcript")
            }
            require(language == "zh" || language == "en") { "Unsupported study language: $language" }
            val input = StudyInput(
                requestId = requestId,
                sourceHash = snapshot.sourceHash,
                language = language,
                title = snapshot.title,
                courseType = snapshot.courseType,
                segments = snapshot.segments,
                notes = snapshot.notes,
            )
            if (input.segments.size > STUDY_MAX_SEGMENTS ||
                input.notes.size > STUDY_MAX_NOTES ||
                input.charCount > STUDY_MAX_INPUT_CHARS
            ) {
                throw StudyContractException(
                    StudyErrorCode.INPUT_TOO_LARGE,
                    "Study input exceeds the limit: ${input.segments.size} segments, " +
                        "${input.notes.size} notes, ${input.charCount} chars",
                )
            }
            return input
        }
    }
}

data class StudyEvidence(val segmentId: String, val quote: String)

data class StudyKnowledgePoint(
    val id: String,
    val title: String,
    val explanation: String,
    val evidence: List<StudyEvidence>,
)

data class StudyAnalysis(
    val schemaVersion: Int,
    val sourceHash: String,
    val skillVersion: String,
    val summary: String,
    val knowledgePoints: List<StudyKnowledgePoint>,
)

object StudyContracts {
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    private val whitespace = Regex("\\s+")

    /**
     * Parses and fully validates a model response against [snapshot]. Any structural or
     * evidence problem rejects the whole response; partial or repaired results are never stored.
     */
    fun parseAnalysis(json: String, snapshot: StudySnapshot): StudyAnalysis {
        val root = try {
            JSONObject(json)
        } catch (failure: JSONException) {
            throw StudyContractException(StudyErrorCode.INVALID_JSON, "Study response is not a JSON object", failure)
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != STUDY_SCHEMA_VERSION) {
            throw StudyContractException(
                StudyErrorCode.UNSUPPORTED_SCHEMA,
                "Unsupported study schema version: $schemaVersion",
            )
        }
        val sourceHash = root.optString("sourceHash", "")
        val skillVersion = root.optString("skillVersion", "")
        val summary = root.optString("summary", "").trim()
        val pointArray = root.optJSONArray("knowledgePoints") ?: throw StudyContractException(
            StudyErrorCode.INVALID_KNOWLEDGE_POINTS,
            "Missing knowledgePoints array",
        )
        val points = ArrayList<StudyKnowledgePoint>(pointArray.length())
        for (index in 0 until pointArray.length()) {
            val point = pointArray.optJSONObject(index) ?: throw StudyContractException(
                StudyErrorCode.INVALID_KNOWLEDGE_POINTS,
                "knowledgePoints[$index] is not an object",
            )
            val evidenceArray = point.optJSONArray("evidence") ?: throw StudyContractException(
                StudyErrorCode.INVALID_EVIDENCE,
                "knowledgePoints[$index] has no evidence",
            )
            val evidence = ArrayList<StudyEvidence>(evidenceArray.length())
            for (evidenceIndex in 0 until evidenceArray.length()) {
                val item = evidenceArray.optJSONObject(evidenceIndex) ?: throw StudyContractException(
                    StudyErrorCode.INVALID_EVIDENCE,
                    "knowledgePoints[$index].evidence[$evidenceIndex] is not an object",
                )
                evidence += StudyEvidence(
                    segmentId = item.optString("segmentId", "").trim(),
                    quote = item.optString("quote", "").trim(),
                )
            }
            points += StudyKnowledgePoint(
                id = point.optString("id", "").trim(),
                title = point.optString("title", "").trim(),
                explanation = point.optString("explanation", "").trim(),
                evidence = evidence,
            )
        }
        val analysis = StudyAnalysis(
            schemaVersion = schemaVersion,
            sourceHash = sourceHash,
            skillVersion = skillVersion,
            summary = summary,
            knowledgePoints = points,
        )
        validate(analysis, snapshot)
        return analysis
    }

    /** Re-validates a stored analysis against current class content. */
    fun validate(analysis: StudyAnalysis, snapshot: StudySnapshot) {
        if (analysis.schemaVersion != STUDY_SCHEMA_VERSION) {
            throw StudyContractException(StudyErrorCode.UNSUPPORTED_SCHEMA, "Unsupported stored schema")
        }
        if (analysis.sourceHash != snapshot.sourceHash) {
            throw StudyContractException(StudyErrorCode.SOURCE_HASH_MISMATCH, "Analysis belongs to different class content")
        }
        if (analysis.skillVersion != STUDY_SKILL_VERSION) {
            throw StudyContractException(StudyErrorCode.UNSUPPORTED_SCHEMA, "Unsupported skill version")
        }
        if (analysis.summary.isBlank() || analysis.summary.length > STUDY_MAX_SUMMARY_CHARS) {
            throw StudyContractException(StudyErrorCode.INVALID_SUMMARY, "Summary is empty or too long")
        }
        validatePoints(analysis.knowledgePoints, snapshot.segments.associateBy { it.id })
    }

    /** Structural validation for stored records where the original snapshot is unavailable. */
    fun validateStoredPoints(points: List<StudyKnowledgePoint>) = validatePoints(points, null)

    private fun validatePoints(points: List<StudyKnowledgePoint>, segments: Map<String, StudySegment>?) {
        if (points.isEmpty() || points.size > STUDY_MAX_KNOWLEDGE_POINTS) {
            throw StudyContractException(
                StudyErrorCode.INVALID_KNOWLEDGE_POINTS,
                "Knowledge point count must be 1..$STUDY_MAX_KNOWLEDGE_POINTS",
            )
        }
        val ids = HashSet<String>(points.size)
        points.forEachIndexed { index, point ->
            if (!idPattern.matches(point.id)) {
                throw StudyContractException(StudyErrorCode.INVALID_KNOWLEDGE_POINTS, "Invalid knowledge point id at $index")
            }
            if (!ids.add(point.id)) {
                throw StudyContractException(StudyErrorCode.INVALID_KNOWLEDGE_POINTS, "Duplicate knowledge point id: ${point.id}")
            }
            if (point.title.isBlank() || point.title.length > 120) {
                throw StudyContractException(StudyErrorCode.INVALID_KNOWLEDGE_POINTS, "Invalid title at $index")
            }
            if (point.explanation.isBlank() || point.explanation.length > 800) {
                throw StudyContractException(StudyErrorCode.INVALID_KNOWLEDGE_POINTS, "Invalid explanation at $index")
            }
            if (point.evidence.isEmpty() || point.evidence.size > STUDY_MAX_EVIDENCE_PER_POINT) {
                throw StudyContractException(StudyErrorCode.INVALID_EVIDENCE, "Evidence count is out of range at $index")
            }
            val segmentIds = HashSet<String>(point.evidence.size)
            point.evidence.forEach { evidence ->
                if (evidence.segmentId.isBlank() || !segmentIds.add(evidence.segmentId)) {
                    throw StudyContractException(StudyErrorCode.INVALID_EVIDENCE, "Duplicate or empty segment id at $index")
                }
                if (evidence.quote.isBlank() || evidence.quote.length > STUDY_MAX_EVIDENCE_QUOTE_CHARS) {
                    throw StudyContractException(StudyErrorCode.INVALID_EVIDENCE, "Invalid evidence quote at $index")
                }
            }
        }
        if (segments == null) return
        points.forEachIndexed { index, point ->
            point.evidence.forEach { evidence ->
                val segment = segments[evidence.segmentId] ?: throw StudyContractException(
                    StudyErrorCode.INVALID_EVIDENCE,
                    "Evidence at $index references an unknown segment",
                )
                if (!normalize(segment.text).contains(normalize(evidence.quote))) {
                    throw StudyContractException(
                        StudyErrorCode.INVALID_EVIDENCE,
                        "Evidence at $index is not a quote from the selected transcript",
                    )
                }
            }
        }
    }

    private fun normalize(value: String): String = value.replace(whitespace, " ").trim()
}

private const val HEX = "0123456789abcdef"

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach {
            val number = it.toInt() and 0xFF
            append(HEX[number shr 4])
            append(HEX[number and 0x0F])
        }
    }
}
