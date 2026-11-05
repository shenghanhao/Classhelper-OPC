package com.classmemory.app.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ReviewGateway {
    suspend fun outline(input: ReviewInput): ReviewOutline
    suspend fun unit(input: ReviewInput, outline: ReviewOutline, topicId: String): ReviewUnit
    suspend fun qa(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>, targetUnitId: String?, question: String, history: List<ReviewQaTurn>, noteIds: List<String> = emptyList()): ReviewQaResult = error("QA unsupported")
    suspend fun graph(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewGraph = error("Graph unsupported")
    suspend fun notes(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewNotesResult = error("Notes unsupported")
    suspend fun plan(input: ReviewInput, outline: ReviewOutline, units: List<ReviewUnit>): ReviewPlan
}

class ReviewSourceChangedException(val deleted: Boolean) : IllegalStateException(
    if (deleted) "Classroom deleted / 课堂已删除" else "Source or course context changed; analyze the latest content / 原文或课程上下文已更新，请重新分析",
)

/** Every successful stage is durable. Only failed/missing stages are called on explicit retry. */
class ReviewCoordinator(
    private val store: ReviewStore,
    private val gateway: ReviewGateway,
    private val currentHash: suspend (String) -> String?,
) {
    private val lock = Mutex()
    private suspend fun checkCurrent(input: ReviewInput) {
        val hash = currentHash(input.sessionId) ?: throw ReviewSourceChangedException(true)
        if (hash != input.sourceHash) throw ReviewSourceChangedException(false)
    }
    suspend fun run(input: ReviewInput, onProgress: (String) -> Unit = {}): ReviewRun = lock.withLock {
        ReviewContracts.validate(input)
        require(input.sourceHash == input.contentHash()) { "Input hash does not match content" }
        checkCurrent(input)
        var run = store.load(input.sessionId, input.sourceHash) ?: ReviewRun(input = input, updatedAt = System.currentTimeMillis())
        require(run.input.contentHash() == input.contentHash()) { "Cached input differs from requested input" }
        val savedInput = run.input // Keep the original generation day across retries.
        suspend fun persist(next: ReviewRun) {
            checkCurrent(input)
            store.save(next)
            run = next
        }
        suspend fun failure(stage: String, error: Exception) {
            if (error is CancellationException || error is ReviewSourceChangedException) throw error
            checkCurrent(input)
            persist(run.copy(errors = run.errors + (stage to (error.message ?: "Analysis failed")), updatedAt = System.currentTimeMillis()))
        }
        if (run.outline == null) {
            onProgress("outline")
            val outline = try {
                checkCurrent(input)
                gateway.outline(savedInput).also { checkCurrent(input); ReviewContracts.validate(it, savedInput) }
            } catch (error: Exception) {
                failure("outline", error)
                return@withLock run
            }
            persist(run.copy(outline = outline, errors = run.errors - "outline", updatedAt = System.currentTimeMillis()))
        }
        val outline = requireNotNull(run.outline)
        for (topic in outline.topics) {
            if (topic.id in run.units) continue
            val key = "unit:${topic.id}"
            onProgress(key)
            val unit = try {
                checkCurrent(input)
                gateway.unit(savedInput, outline, topic.id).also {
                    checkCurrent(input); require(it.id == topic.id) { "Provider returned another topic" }; ReviewContracts.validate(it, savedInput, outline)
                }
            } catch (error: Exception) {
                failure(key, error)
                continue
            }
            persist(run.copy(units = run.units + (topic.id to unit), errors = run.errors - key, updatedAt = System.currentTimeMillis()))
        }
        if (run.units.size == outline.topics.size && !run.notesProcessed && savedInput.notes.isNotEmpty()) {
            onProgress("notes")
            try {
                val result = gateway.notes(savedInput, outline, outline.topics.map { run.units.getValue(it.id) })
                require(result.items.map { it.noteId }.toSet() == savedInput.notes.map { it.id }.toSet() && result.items.size == savedInput.notes.size)
                require(result.updates.keys.all { it in run.units } && result.updates.values.all { it.isNotBlank() })
                result.items.forEach { item ->
                    val note = savedInput.notes.single { it.id == item.noteId }
                    require(item.disposition in setOf("answered", "clarify", "context"))
                    require(note.kind != "QUESTION" || item.disposition != "context")
                    item.relatedQuestionNoteId?.let { related ->
                        require(note.kind == "COMMENT" && item.disposition == "context" && savedInput.notes.any { it.id == related && it.kind == "QUESTION" })
                    }
                    require(item.disposition == "context" || item.answerMarkdown.isNotBlank())
                    require(item.unitId == null || item.unitId in run.units)
                    require(item.sourceIds.all { id -> savedInput.sources.any { it.id == id } })
                }
                val threads = result.items.filter { it.disposition != "context" }.map { item ->
                    require(item.disposition in setOf("answered", "clarify"))
                    require(item.unitId == null || item.unitId in run.units)
                    require(item.sourceIds.all { id -> savedInput.sources.any { it.id == id } })
                    if (item.disposition == "answered") require(item.unitId != null && item.unitId in result.updates)
                    ReviewQaThread("note:${item.noteId}", item.unitId, listOf(ReviewQaTurn(savedInput.notes.single { it.id == item.noteId }.text, item.answerMarkdown, item.disposition == "clarify", item.sourceIds)), item.noteId, result.items.filter { it.relatedQuestionNoteId == item.noteId }.map { it.noteId })
                }
                persist(run.copy(qaThreads = run.qaThreads.filterNot { it.noteId in result.items.map { item -> item.noteId } } + threads, units = run.units.mapValues { (id, unit) -> result.updates[id]?.let { unit.copy(markdown = it) } ?: unit }, unitVersions = run.unitVersions + result.updates.mapValues { (id, _) -> run.unitVersions[id].orEmpty() + run.units.getValue(id).markdown }, notesProcessed = true, updatedAt = System.currentTimeMillis()))
            } catch (error: Exception) { throw error }
        }
        if (run.plan == null && run.units.size == outline.topics.size) {
            onProgress("plan")
            val units = outline.topics.map { run.units.getValue(it.id) }
            val plan = try {
                checkCurrent(input)
                gateway.plan(savedInput, outline, units).also { checkCurrent(input); ReviewContracts.validate(it, savedInput, units) }
            } catch (error: Exception) {
                failure("plan", error)
                return@withLock run
            }
            persist(run.copy(plan = plan, errors = run.errors - "plan", updatedAt = System.currentTimeMillis()))
        }
        checkCurrent(input)
        // Read back so a user marking it reviewed during the run cannot be overwritten in UI.
        store.load(input.sessionId, input.sourceHash) ?: run
    }
}
