package com.classmemory.app.ai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class StudyInputStats(
    val segmentCount: Int,
    val noteCount: Int,
    val charCount: Int,
)

/**
 * Persisted analysis plus the audit trail needed to prove which class content it came from.
 * Stored as one small JSON file per (sessionId, sourceHash); Room v6 is never touched.
 */
data class StudyRecord(
    val schemaVersion: Int,
    val sessionId: String,
    val sourceHash: String,
    val capturedAt: Long,
    val analyzedAt: Long,
    val requestId: String,
    val skillVersion: String,
    val model: String?,
    val title: String?,
    val courseType: String?,
    val input: StudyInputStats,
    val summary: String,
    val knowledgePoints: List<StudyKnowledgePoint>,
) {
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("sessionId", sessionId)
        put("sourceHash", sourceHash)
        put("capturedAt", capturedAt)
        put("analyzedAt", analyzedAt)
        put("requestId", requestId)
        put("skillVersion", skillVersion)
        put("model", model ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("courseType", courseType ?: JSONObject.NULL)
        put("input", JSONObject().apply {
            put("segmentCount", input.segmentCount)
            put("noteCount", input.noteCount)
            put("charCount", input.charCount)
        })
        put("summary", summary)
        put("knowledgePoints", JSONArray().apply {
            knowledgePoints.forEach { point ->
                put(JSONObject().apply {
                    put("id", point.id)
                    put("title", point.title)
                    put("explanation", point.explanation)
                    put("evidence", JSONArray().apply {
                        point.evidence.forEach { evidence ->
                            put(JSONObject().apply {
                                put("segmentId", evidence.segmentId)
                                put("quote", evidence.quote)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun validateStructure() {
        if (schemaVersion != STUDY_SCHEMA_VERSION) {
            throw StudyContractException(StudyErrorCode.UNSUPPORTED_SCHEMA, "Unsupported stored record schema")
        }
        StudyPathRules.requireSessionId(sessionId)
        StudyPathRules.requireSourceHash(sourceHash)
        if (capturedAt < 0 || analyzedAt < 0) {
            throw StudyContractException(StudyErrorCode.INVALID_RECORD, "Invalid record timestamp")
        }
        if (requestId.isBlank() || requestId.length > 64) {
            throw StudyContractException(StudyErrorCode.INVALID_RECORD, "Invalid request id")
        }
        if (skillVersion != STUDY_SKILL_VERSION) {
            throw StudyContractException(StudyErrorCode.UNSUPPORTED_SCHEMA, "Unsupported stored skill version")
        }
        if (input.segmentCount < 0 || input.noteCount < 0 || input.charCount < 0) {
            throw StudyContractException(StudyErrorCode.INVALID_RECORD, "Invalid input statistics")
        }
        if (summary.isBlank() || summary.length > STUDY_MAX_SUMMARY_CHARS) {
            throw StudyContractException(StudyErrorCode.INVALID_SUMMARY, "Invalid stored summary")
        }
        if (model != null && (model.isBlank() || model.length > 120)) {
            throw StudyContractException(StudyErrorCode.INVALID_RECORD, "Invalid model name")
        }
        StudyContracts.validateStoredPoints(knowledgePoints)
    }

    companion object {
        fun create(
            analysis: StudyAnalysis,
            snapshot: StudySnapshot,
            input: StudyInput,
            analyzedAt: Long,
            model: String?,
        ): StudyRecord {
            require(analysis.sourceHash == snapshot.sourceHash) { "Analysis does not match snapshot" }
            require(input.sourceHash == snapshot.sourceHash) { "Input does not match snapshot" }
            return StudyRecord(
                schemaVersion = STUDY_SCHEMA_VERSION,
                sessionId = snapshot.sessionId,
                sourceHash = snapshot.sourceHash,
                capturedAt = snapshot.capturedAt,
                analyzedAt = analyzedAt,
                requestId = input.requestId,
                skillVersion = analysis.skillVersion,
                model = model,
                title = snapshot.title,
                courseType = snapshot.courseType,
                input = StudyInputStats(
                    segmentCount = input.segments.size,
                    noteCount = input.notes.size,
                    charCount = input.charCount,
                ),
                summary = analysis.summary,
                knowledgePoints = analysis.knowledgePoints,
            )
        }

        fun fromJson(json: String): StudyRecord {
            val root = try {
                JSONObject(json)
            } catch (failure: JSONException) {
                throw StudyContractException(StudyErrorCode.INVALID_JSON, "Stored study record is not valid JSON", failure)
            }
            val inputObject = root.optJSONObject("input") ?: throw StudyContractException(
                StudyErrorCode.INVALID_RECORD,
                "Stored study record has no input statistics",
            )
            val pointArray = root.optJSONArray("knowledgePoints") ?: throw StudyContractException(
                StudyErrorCode.INVALID_KNOWLEDGE_POINTS,
                "Stored study record has no knowledge points",
            )
            val points = ArrayList<StudyKnowledgePoint>(pointArray.length())
            for (index in 0 until pointArray.length()) {
                val point = pointArray.optJSONObject(index) ?: throw StudyContractException(
                    StudyErrorCode.INVALID_KNOWLEDGE_POINTS,
                    "Stored knowledge point $index is not an object",
                )
                val evidenceArray = point.optJSONArray("evidence") ?: throw StudyContractException(
                    StudyErrorCode.INVALID_EVIDENCE,
                    "Stored knowledge point $index has no evidence",
                )
                val evidence = ArrayList<StudyEvidence>(evidenceArray.length())
                for (evidenceIndex in 0 until evidenceArray.length()) {
                    val item = evidenceArray.optJSONObject(evidenceIndex) ?: throw StudyContractException(
                        StudyErrorCode.INVALID_EVIDENCE,
                        "Stored evidence $evidenceIndex is not an object",
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
            val record = StudyRecord(
                schemaVersion = root.optInt("schemaVersion", -1),
                sessionId = root.optString("sessionId", ""),
                sourceHash = root.optString("sourceHash", ""),
                capturedAt = root.optLong("capturedAt", -1),
                analyzedAt = root.optLong("analyzedAt", -1),
                requestId = root.optString("requestId", ""),
                skillVersion = root.optString("skillVersion", ""),
                model = root.nullableString("model"),
                title = root.nullableString("title"),
                courseType = root.nullableString("courseType"),
                input = StudyInputStats(
                    segmentCount = inputObject.optInt("segmentCount", -1),
                    noteCount = inputObject.optInt("noteCount", -1),
                    charCount = inputObject.optInt("charCount", -1),
                ),
                summary = root.optString("summary", "").trim(),
                knowledgePoints = points,
            )
            record.validateStructure()
            return record
        }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").ifBlank { null }

internal object StudyPathRules {
    private val sessionIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val sourceHashPattern = Regex("^[0-9a-f]{64}$")

    fun requireSessionId(id: String) {
        if (!sessionIdPattern.matches(id) || id == "." || id == "..") {
            throw StudyContractException(StudyErrorCode.INVALID_PATH, "Unsafe study session id")
        }
    }

    fun requireSourceHash(hash: String) {
        if (!sourceHashPattern.matches(hash)) {
            throw StudyContractException(StudyErrorCode.INVALID_PATH, "Unsafe study source hash")
        }
    }
}

/**
 * Small atomic JSON store for study results. One file per content hash keeps an old result
 * readable until a new analysis replaces it, and makes stale-result detection explicit.
 */
class StudyResultStore(private val root: File) {
    private val mutex = Mutex()

    fun fileFor(sessionId: String, sourceHash: String): File {
        StudyPathRules.requireSessionId(sessionId)
        StudyPathRules.requireSourceHash(sourceHash)
        return File(directoryFor(sessionId), "$sourceHash.json")
    }

    suspend fun save(record: StudyRecord) {
        record.validateStructure()
        mutex.withLock {
            withContext(Dispatchers.IO) { writeAtomically(record) }
        }
    }

    suspend fun load(sessionId: String, sourceHash: String): StudyRecord? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = fileFor(sessionId, sourceHash)
                if (!file.isFile) return@withContext null
                runCatching { StudyRecord.fromJson(file.readText(Charsets.UTF_8)) }
                    .getOrNull()
                    ?.takeIf { it.sessionId == sessionId && it.sourceHash == sourceHash }
            }
        }

    suspend fun latestForSession(sessionId: String): StudyRecord? = listForSession(sessionId).firstOrNull()

    suspend fun listForSession(sessionId: String): List<StudyRecord> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = directoryFor(sessionId)
                if (!directory.isDirectory) return@withContext emptyList()
                directory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith(".") }
                    .mapNotNull { file ->
                        runCatching { StudyRecord.fromJson(file.readText(Charsets.UTF_8)) }
                            .getOrNull()
                            ?.takeIf { it.sessionId == sessionId && it.sourceHash == file.name.removeSuffix(".json") }
                    }
                    .sortedWith(compareByDescending<StudyRecord> { it.analyzedAt }.thenBy { it.sourceHash })
                    .toList()
            }
        }

    suspend fun deleteSession(sessionId: String) {
        StudyPathRules.requireSessionId(sessionId)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = directoryFor(sessionId)
                if (directory.exists() && !directory.deleteRecursively()) {
                    throw IOException("Could not delete study results for $sessionId")
                }
            }
        }
    }

    private fun directoryFor(sessionId: String): File {
        StudyPathRules.requireSessionId(sessionId)
        return File(root, sessionId)
    }

    private fun writeAtomically(record: StudyRecord) {
        val directory = directoryFor(record.sessionId)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create study result directory")
        }
        val target = fileFor(record.sessionId, record.sourceHash)
        val temp = File(directory, ".${record.sourceHash}.json.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(record.toJson().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
