package com.classmemory.app.review

import org.json.JSONArray
import org.json.JSONObject

data class ReviewExercise(val id: String, val unitId: String, val kind: String, val question: String, val options: List<String>, val parentId: String? = null, val attempts: List<ReviewExerciseAttempt> = emptyList())
data class ReviewExerciseAttempt(val id: String, val answer: String, val status: String, val verdict: String? = null, val feedback: String? = null, val reasoning: String? = null, val sourceIds: List<String> = emptyList())
data class ReviewExerciseNotice(val id: String, val exerciseId: String, val consumed: Boolean = false)
data class ReviewExerciseDraft(val unitId: String, val kind: String, val question: String, val options: List<String>)
data class ReviewExerciseGrade(val verdict: String, val feedback: String, val reasoning: String, val sourceIds: List<String>, val followUp: ReviewExerciseDraft?)

fun reviewExerciseTargetCount(unitCount: Int): Int {
    require(unitCount >= 0)
    return when (unitCount) { 0 -> 0; in 1..2 -> 2; in 3..4 -> 4; in 5..7 -> 6; else -> 8 }
}

fun reviewExerciseCountRange(unitCount: Int): IntRange = when (reviewExerciseTargetCount(unitCount)) {
    0 -> 0..0
    2 -> 2..3
    4 -> 3..4
    6 -> 5..6
    else -> 7..8
}

internal fun validateNewExerciseMix(drafts: List<ReviewExerciseDraft>, unitCount: Int) {
    val mcq = drafts.count { it.kind == "MCQ" }; val short = drafts.count { it.kind == "SHORT" }
    require(unitCount > 0 && drafts.size in reviewExerciseCountRange(unitCount) && mcq > 0 && short > 0 && mcq + short == drafts.size && kotlin.math.abs(mcq - short) <= 1) { "Unexpected exercise count or mix" }
}

internal object ReviewExerciseCodec {
    private fun array(values: Iterable<*>): JSONArray = JSONArray().also { a -> values.forEach { a.put(it) } }
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.get(it) as? String ?: error("Invalid string") }
    private fun str(o: JSONObject, key: String): String = o.get(key) as? String ?: error("Invalid $key")
    private fun nullable(o: JSONObject, key: String): String? = if (o.isNull(key)) null else str(o, key)
    fun exercises(values: List<ReviewExercise>): JSONArray = array(values.map { e -> JSONObject().put("id", e.id).put("unitId", e.unitId).put("kind", e.kind).put("question", e.question).put("options", array(e.options)).put("parentId", e.parentId ?: JSONObject.NULL).put("attempts", array(e.attempts.map { a -> JSONObject().put("id", a.id).put("answer", a.answer).put("status", a.status).put("verdict", a.verdict ?: JSONObject.NULL).put("feedback", a.feedback ?: JSONObject.NULL).put("reasoning", a.reasoning ?: JSONObject.NULL).put("sourceIds", array(a.sourceIds)) })) })
    fun exercises(a: JSONArray): List<ReviewExercise> = (0 until a.length()).map { a.getJSONObject(it).let { e ->
        val attempts = e.getJSONArray("attempts")
        ReviewExercise(str(e, "id"), str(e, "unitId"), str(e, "kind"), str(e, "question"), strings(e.getJSONArray("options")), nullable(e, "parentId"), (0 until attempts.length()).map { attempts.getJSONObject(it).let { x -> ReviewExerciseAttempt(str(x, "id"), str(x, "answer"), str(x, "status"), nullable(x, "verdict"), nullable(x, "feedback"), nullable(x, "reasoning"), strings(x.getJSONArray("sourceIds"))) } })
    } }
    fun notices(values: List<ReviewExerciseNotice>): JSONArray = array(values.map { JSONObject().put("id", it.id).put("exerciseId", it.exerciseId).put("consumed", it.consumed) })
    fun notices(a: JSONArray): List<ReviewExerciseNotice> = (0 until a.length()).map { a.getJSONObject(it).let { x -> ReviewExerciseNotice(str(x, "id"), str(x, "exerciseId"), x.get("consumed") as? Boolean ?: error("Invalid notice")) } }
    fun validate(run: ReviewRun) {
        val all = run.exercises; val roots = all.filter { it.parentId == null }; val children = all.filter { it.parentId != null }
        require(roots.size <= 8 && all.map { it.id }.distinct().size == all.size && all.all { it.id.isNotBlank() && it.unitId in run.units })
        fun normalize(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        require(all.map { listOf(it.unitId, it.kind, normalize(it.question)) + it.options.map(::normalize) }.distinct().size == all.size)
        require(children.map { it.parentId }.distinct().size == children.size)
        all.forEach { e ->
            require(e.kind in setOf("MCQ", "SHORT") && e.question.isNotBlank() && e.options.all { it.isNotBlank() } && (if (e.kind == "MCQ") e.options.size in 2..6 else e.options.isEmpty()))
            e.parentId?.let { id -> require(roots.any { it.id == id && it.unitId == e.unitId }) }
            require(e.attempts.map { it.id }.distinct().size == e.attempts.size)
            e.attempts.forEach { a ->
                require(a.id.isNotBlank() && a.answer.isNotBlank() && a.status in setOf("pending", "failed", "graded"))
                require(a.sourceIds.distinct().size == a.sourceIds.size && a.sourceIds.all { id -> run.input.sources.any { it.id == id && it.isCurrent } })
                if (a.status == "graded") require(a.verdict != null && a.verdict in setOf("correct", "partial", "incorrect", "unclear") && !a.feedback.isNullOrBlank() && !a.reasoning.isNullOrBlank() && a.sourceIds.isNotEmpty())
                else require(a.verdict == null && a.feedback == null && a.reasoning == null && a.sourceIds.isEmpty())
            }
        }
        require(all.flatMap { it.attempts }.map { it.id }.distinct().size == all.flatMap { it.attempts }.size)
        require(run.exerciseNotices.map { it.id }.distinct().size == run.exerciseNotices.size && run.exerciseNotices.map { it.exerciseId }.distinct().size == run.exerciseNotices.size && run.exerciseNotices.all { it.id.isNotBlank() && children.any { child -> child.id == it.exerciseId } })
    }
}
