package com.classmemory.app.review

import com.classmemory.app.course.CourseContext
import java.time.LocalDate
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ReviewSource(val id: String, val sessionId: String, val segmentId: String, val startMs: Long, val endMs: Long, val text: String, val isCurrent: Boolean)
data class ReviewPhotoRef(val id: String, val sessionId: String, val atMs: Long)
data class ReviewNote(val id: String, val atMs: Long, val kind: String, val text: String)
data class ReviewInput(val sessionId: String, val sourceHash: String, val language: String, val title: String?, val today: String, val course: CourseContext?, val sources: List<ReviewSource>, val photos: List<ReviewPhotoRef>, val notes: List<ReviewNote>, val analysisVersion: Int = 2)
data class ReviewSection(val id: String, val title: String, val sourceIds: List<String>)
data class ReviewTopic(val id: String, val title: String, val sectionIds: List<String>, val sourceIds: List<String>)
data class ReviewOutline(val overview: String, val sections: List<ReviewSection>, val topics: List<ReviewTopic>)
data class ReviewUnit(val id: String, val title: String, val explanation: String, val conditions: String, val classroomFocus: String, val example: String?, val sourceIds: List<String>, val photoIds: List<String>, val sectionIds: List<String>, val markdown: String = "")
data class ReviewStep(val title: String, val instruction: String, val unitIds: List<String>, val task: String = instruction, val completionCriteria: String = "")
data class ReviewPlan(val title: String, val introduction: String, val suggestedDate: String, val steps: List<ReviewStep>)
data class ReviewRun(val input: ReviewInput, val outline: ReviewOutline? = null, val units: Map<String, ReviewUnit> = emptyMap(), val plan: ReviewPlan? = null, val errors: Map<String, String> = emptyMap(), val reviewedAt: Long? = null, val updatedAt: Long, val completedStepIndices: Set<Int> = emptySet(), val qaThreads: List<ReviewQaThread> = emptyList(), val graph: ReviewGraph? = null, val unitVersions: Map<String, List<String>> = emptyMap(), val notesProcessed: Boolean = false)
data class ReviewQaTurn(val question: String, val answerMarkdown: String, val clarificationNeeded: Boolean = false, val sourceIds: List<String> = emptyList(), val id: String = java.util.UUID.randomUUID().toString(), val status: String = "answered")
data class ReviewQaThread(val id: String, val targetUnitId: String?, val turns: List<ReviewQaTurn>, val noteId: String? = null, val relatedNoteIds: List<String> = emptyList())
data class ReviewGraphEdge(val fromUnitId: String, val toUnitId: String, val relation: String)
data class ReviewGraph(val nodes: List<String>, val edges: List<ReviewGraphEdge>)
data class ReviewQaResult(val answerMarkdown: String, val sourceIds: List<String>, val updates: Map<String, String>, val clarificationNeeded: Boolean)
data class ReviewNoteResult(val noteId: String, val disposition: String, val unitId: String?, val answerMarkdown: String, val sourceIds: List<String>, val relatedQuestionNoteId: String? = null)
data class ReviewNotesResult(val items: List<ReviewNoteResult>, val updates: Map<String, String>)
data class ReviewQaScope(val sessionId: String, val sourceHash: String, val sectionId: String? = null)

fun ReviewPlan.fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(ReviewJson.plan(this).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** V2 identities used photo metadata only; V3 intentionally has a different cache identity. */
fun ReviewInput.matchesLegacyReview(run: ReviewRun, legacyPhotos: List<ReviewPhotoRef>): Boolean =
    run.input.analysisVersion == 2 && copy(photos = legacyPhotos, analysisVersion = 2).contentHash() == run.input.sourceHash

/** Content identity excludes generation day and itself; canonical JSON key ordering is stable. */
fun ReviewInput.contentHash(): String {
    val json = ReviewJson.input(this).apply { remove("sourceHash"); remove("today") }
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(json).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Validates semantic coverage and source identities, never repairs or truncates model output. */
object ReviewContracts {
    private fun requireText(value: String) = require(value.isNotBlank()) { "Required review text is empty" }
    private fun unique(ids: List<String>, allowEmpty: Boolean = false) {
        require(allowEmpty || ids.isNotEmpty()) { "Missing references" }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Duplicate or empty identity" }
    }
    fun date(value: String): LocalDate {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "Date must be YYYY-MM-DD" }
        return LocalDate.parse(value)
    }
    fun validate(input: ReviewInput) {
        requireText(input.sessionId)
        require(Regex("[a-f0-9]{64}").matches(input.sourceHash)) { "Invalid source hash" }
        require(input.language in setOf("zh", "en"))
        date(input.today)
        unique(input.sources.map { it.id })
        require(input.sources.any { it.isCurrent }) { "No current transcript" }
        input.sources.forEach {
            requireText(it.sessionId); requireText(it.segmentId); requireText(it.text)
            require(it.startMs >= 0 && it.endMs >= it.startMs)
            require(it.isCurrent == (it.sessionId == input.sessionId)) { "Invalid current source ownership" }
        }
        require(input.sources.map { it.sessionId to it.segmentId }.distinct().size == input.sources.size)
        unique(input.photos.map { it.id }, true)
        input.photos.forEach { requireText(it.sessionId); require(it.atMs >= 0) }
        unique(input.notes.map { it.id }, true)
        input.notes.forEach { require(it.atMs >= 0 && it.kind in setOf("QUESTION", "COMMENT")); requireText(it.text) }
        input.course?.let { requireText(it.id); requireText(it.name); require(it.revision > 0) }
        require(input.course != null || input.sources.all { it.isCurrent }) { "History requires a course" }
        require(input.sourceHash == input.contentHash()) { "Input content fingerprint mismatch" }
    }
    fun validate(outline: ReviewOutline, input: ReviewInput) {
        validate(input); requireText(outline.overview)
        unique(outline.sections.map { it.id }); unique(outline.topics.map { it.id })
        val current = input.sources.filter { it.isCurrent }.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.id })).map { it.id }
        outline.sections.forEach { requireText(it.title); unique(it.sourceIds) }
        require(outline.sections.flatMap { it.sourceIds } == current) { "Sections must partition every current source in chronological order" }
        val sections = outline.sections.associateBy { it.id }
        outline.topics.forEach { topic ->
            requireText(topic.title); unique(topic.sectionIds); unique(topic.sourceIds)
            require(topic.sectionIds.all { it in sections }) { "Unknown topic section" }
            val allowed = topic.sectionIds.flatMap { sections.getValue(it).sourceIds }.toSet()
            require(topic.sourceIds.all { it in allowed }) { "Topic source outside its sections" }
        }
        require(outline.topics.flatMap { it.sectionIds }.toSet() == sections.keys) { "A section has no topic" }
    }
    fun validate(unit: ReviewUnit, input: ReviewInput, outline: ReviewOutline) {
        val topic = outline.topics.singleOrNull { it.id == unit.id } ?: error("Unknown unit topic")
        requireText(unit.title); requireText(unit.explanation); requireText(unit.classroomFocus)
        unit.example?.let { requireText(it) }
        unique(unit.sourceIds); unique(unit.photoIds, true); unique(unit.sectionIds)
        require(unit.sectionIds.toSet() == topic.sectionIds.toSet()) { "Unit sections differ from topic" }
        val sources = input.sources.associateBy { it.id }
        require(unit.sourceIds.all { it in sources }) { "Unknown unit source" }
        require(unit.sourceIds.any { it in topic.sourceIds && sources.getValue(it).isCurrent }) { "Unit lacks current topic evidence" }
        require(unit.sourceIds.all { !sources.getValue(it).isCurrent || it in topic.sourceIds }) { "Current source outside topic" }
        require(unit.photoIds.all { id -> input.photos.any { it.id == id } }) { "Unknown photo" }
    }
    fun validate(plan: ReviewPlan, input: ReviewInput, units: List<ReviewUnit>) {
        requireText(plan.title); requireText(plan.introduction)
        require(date(plan.suggestedDate) >= date(input.today)) { "Review date precedes generation date" }
        require(plan.steps.isNotEmpty())
        val ids = units.map { it.id }.toSet()
        plan.steps.forEach { step ->
            requireText(step.title); requireText(step.instruction); unique(step.unitIds)
            require(step.unitIds.all { it in ids }) { "Unknown plan unit" }
        }
        require(plan.steps.flatMap { it.unitIds }.toSet() == ids) { "Plan omits a learning unit" }
    }
    fun validate(run: ReviewRun) {
        validate(run.input); require(run.updatedAt >= 0)
        run.reviewedAt?.let { require(it >= 0 && run.plan != null) }
        require(run.completedStepIndices.all { it >= 0 && it < (run.plan?.steps?.size ?: 0) })
        val outline = run.outline
        if (outline == null) require(run.units.isEmpty() && run.plan == null)
        else {
            validate(outline, run.input)
            run.units.forEach { (key, unit) -> require(key == unit.id); validate(unit, run.input, outline) }
            run.plan?.let {
                require(run.units.keys == outline.topics.map { topic -> topic.id }.toSet()) { "Plan requires all units" }
                validate(it, run.input, run.units.values.toList())
            }
        }
        require(run.errors.keys.all { it == "outline" || it == "plan" || (it.startsWith("unit:") && outline?.topics?.any { topic -> it == "unit:${topic.id}" } == true) })
    }
}

/** Shared strict JSON codec for HTTP and local storage. */
object ReviewJson {
    private fun JSONObject.s(key: String): String = (get(key) as? String) ?: error("$key must be a string")
    private fun JSONObject.n(key: String): Long {
        val value = get(key)
        require(value is Long || value is Int) { "$key must be an integer" }
        return (value as Number).toLong()
    }
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else s(key)
    private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> = (0 until length()).map { block(getJSONObject(it)) }
    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { a -> (0 until a.length()).map { i -> (a.get(i) as? String) ?: error("$key must contain strings") } }
    private fun array(values: Iterable<*>): JSONArray = JSONArray().also { a -> values.forEach { a.put(it) } }
    fun input(value: ReviewInput): JSONObject = JSONObject().put("schemaVersion", 2).put("sessionId", value.sessionId)
        .put("sourceHash", value.sourceHash).put("language", value.language).put("title", value.title ?: JSONObject.NULL).put("today", value.today)
        .put("course", value.course?.let { c -> JSONObject().put("id", c.id).put("revision", c.revision).put("name", c.name).put("background", c.background)
            .put("goals", c.goals).put("chapters", c.chapters).put("terminology", c.terminology).put("prerequisites", c.prerequisites).put("sources", c.sources) } ?: JSONObject.NULL)
        .put("sources", array(value.sources.map { s -> JSONObject().put("id", s.id).put("sessionId", s.sessionId).put("segmentId", s.segmentId)
            .put("startMs", s.startMs).put("endMs", s.endMs).put("text", s.text).put("isCurrent", s.isCurrent) }))
        .put("photos", array(value.photos.map { p -> JSONObject().put("id", p.id).put("sessionId", p.sessionId).put("atMs", p.atMs) }))
        .put("notes", array(value.notes.map { n -> JSONObject().put("id", n.id).put("atMs", n.atMs).put("kind", n.kind).put("text", n.text) })).apply { if (value.analysisVersion != 2) put("analysisVersion", value.analysisVersion) }
    fun input(o: JSONObject): ReviewInput {
        require(o.n("schemaVersion") == 2L)
        val c = if (o.isNull("course")) null else o.getJSONObject("course").let { c -> CourseContext(id = c.s("id"), revision = c.n("revision"), name = c.s("name"), background = c.s("background"), goals = c.s("goals"), chapters = c.s("chapters"), terminology = c.s("terminology"), prerequisites = c.s("prerequisites"), sources = c.s("sources")) }
        return ReviewInput(o.s("sessionId"), o.s("sourceHash"), o.s("language"), o.nullableString("title"), o.s("today"), c,
            o.getJSONArray("sources").mapObjects { s -> ReviewSource(s.s("id"), s.s("sessionId"), s.s("segmentId"), s.n("startMs"), s.n("endMs"), s.s("text"), s.get("isCurrent") as? Boolean ?: error("isCurrent must be boolean")) },
            o.getJSONArray("photos").mapObjects { p -> ReviewPhotoRef(p.s("id"), p.s("sessionId"), p.n("atMs")) },
            o.getJSONArray("notes").mapObjects { n -> ReviewNote(n.s("id"), n.n("atMs"), n.s("kind"), n.s("text")) }, o.optInt("analysisVersion", 2))
    }
    fun outline(value: ReviewOutline): JSONObject = JSONObject().put("overview", value.overview)
        .put("sections", array(value.sections.map { s -> JSONObject().put("id", s.id).put("title", s.title).put("sourceIds", array(s.sourceIds)) }))
        .put("topics", array(value.topics.map { t -> JSONObject().put("id", t.id).put("title", t.title).put("sectionIds", array(t.sectionIds)).put("sourceIds", array(t.sourceIds)) }))
    fun outline(o: JSONObject): ReviewOutline = ReviewOutline(o.s("overview"), o.getJSONArray("sections").mapObjects { ReviewSection(it.s("id"), it.s("title"), it.strings("sourceIds")) }, o.getJSONArray("topics").mapObjects { ReviewTopic(it.s("id"), it.s("title"), it.strings("sectionIds"), it.strings("sourceIds")) })
    fun unit(value: ReviewUnit): JSONObject = JSONObject().put("id", value.id).put("title", value.title).put("explanation", value.explanation).put("conditions", value.conditions)
        .put("markdown", value.markdown).put("classroomFocus", value.classroomFocus).put("example", value.example ?: JSONObject.NULL).put("sourceIds", array(value.sourceIds)).put("photoIds", array(value.photoIds)).put("sectionIds", array(value.sectionIds))
    fun unit(o: JSONObject): ReviewUnit = ReviewUnit(o.s("id"), o.s("title"), o.s("explanation"), o.s("conditions"), o.s("classroomFocus"), o.nullableString("example"), o.strings("sourceIds"), o.strings("photoIds"), o.strings("sectionIds"), o.optString("markdown", ""))
    fun plan(value: ReviewPlan): JSONObject = JSONObject().put("title", value.title).put("introduction", value.introduction).put("suggestedDate", value.suggestedDate)
        .put("steps", array(value.steps.map { s -> JSONObject().put("title", s.title).put("instruction", s.instruction).put("task", s.task).put("completionCriteria", s.completionCriteria).put("unitIds", array(s.unitIds)) }))
    fun plan(o: JSONObject): ReviewPlan = ReviewPlan(o.s("title"), o.s("introduction"), o.s("suggestedDate"), o.getJSONArray("steps").mapObjects { ReviewStep(it.s("title"), it.optString("instruction", it.optString("task")), it.strings("unitIds"), it.optString("task", it.optString("instruction")), it.optString("completionCriteria", "")) })
    fun run(value: ReviewRun): JSONObject = JSONObject().put("schemaVersion", 2).put("input", input(value.input)).put("outline", value.outline?.let { outline(it) } ?: JSONObject.NULL)
        .put("units", array(value.units.values.map { unit(it) })).put("plan", value.plan?.let { plan(it) } ?: JSONObject.NULL)
        .put("errors", JSONObject(value.errors)).put("reviewedAt", value.reviewedAt ?: JSONObject.NULL).put("updatedAt", value.updatedAt).put("completedStepIndices", array(value.completedStepIndices.sorted())).put("planFingerprint", value.plan?.fingerprint() ?: JSONObject.NULL)
        .put("qaThreads", array(value.qaThreads.map { t -> JSONObject().put("id", t.id).put("targetUnitId", t.targetUnitId ?: JSONObject.NULL).put("noteId", t.noteId ?: JSONObject.NULL).put("relatedNoteIds", array(t.relatedNoteIds)).put("turns", array(t.turns.map { q -> JSONObject().put("question", q.question).put("answerMarkdown", q.answerMarkdown).put("clarificationNeeded", q.clarificationNeeded).put("sourceIds", array(q.sourceIds)).put("id", q.id).put("status", q.status) })) }))
        .put("graph", value.graph?.let { g -> JSONObject().put("nodes", array(g.nodes)).put("edges", array(g.edges.map { e -> JSONObject().put("fromUnitId", e.fromUnitId).put("toUnitId", e.toUnitId).put("relation", e.relation) })) } ?: JSONObject.NULL)
        .put("unitVersions", JSONObject().also { o -> value.unitVersions.forEach { (id, versions) -> o.put(id, array(versions)) } }).put("notesProcessed", value.notesProcessed)
    fun run(o: JSONObject): ReviewRun {
        require(o.n("schemaVersion") == 2L)
        val units = o.getJSONArray("units").mapObjects { unit(it) }
        require(units.map { it.id }.distinct().size == units.size)
        val errors = o.getJSONObject("errors")
        return ReviewRun(input(o.getJSONObject("input")), if (o.isNull("outline")) null else outline(o.getJSONObject("outline")), units.associateBy { it.id },
            if (o.isNull("plan")) null else plan(o.getJSONObject("plan")), errors.keys().asSequence().associateWith { errors.s(it) },
            if (o.isNull("reviewedAt")) null else o.n("reviewedAt"), o.n("updatedAt"),
            if (o.has("completedStepIndices")) { val a = o.getJSONArray("completedStepIndices"); (0 until a.length()).map { a.getInt(it) }.toSet() } else if (!o.isNull("reviewedAt") && !o.isNull("plan")) plan(o.getJSONObject("plan")).steps.indices.toSet() else emptySet(),
            o.optJSONArray("qaThreads")?.mapObjects { t -> ReviewQaThread(t.s("id"), t.nullableString("targetUnitId"), t.getJSONArray("turns").mapObjects { q -> ReviewQaTurn(q.s("question"), q.s("answerMarkdown"), q.getBoolean("clarificationNeeded"), q.strings("sourceIds"), q.optString("id", java.util.UUID.randomUUID().toString()), q.optString("status", "answered")) }, t.nullableString("noteId"), if (t.has("relatedNoteIds")) t.strings("relatedNoteIds") else emptyList()) } ?: emptyList(),
            o.optJSONObject("graph")?.let { g -> ReviewGraph(g.strings("nodes"), g.getJSONArray("edges").mapObjects { ReviewGraphEdge(it.s("fromUnitId"), it.s("toUnitId"), it.s("relation")) }) },
            o.optJSONObject("unitVersions")?.let { v -> v.keys().asSequence().associateWith { v.strings(it) } } ?: emptyMap(), o.optBoolean("notesProcessed", false)).also { ReviewContracts.validate(it) }
    }
}
