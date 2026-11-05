package com.classmemory.app.ui

import com.classmemory.app.review.ReviewRun
import com.classmemory.app.review.ReviewUnit
import com.classmemory.app.review.ReviewQaThread
import com.classmemory.app.review.ReviewQaTurn

internal data class ReviewQuestionEntry(val threadId: String, val turn: ReviewQaTurn)

internal fun reviewQuestions(threads: List<ReviewQaThread>, unitId: String?): List<ReviewQuestionEntry> =
    threads.filter { it.targetUnitId == unitId }.flatMap { thread -> thread.turns.map { ReviewQuestionEntry(thread.id, it) } }

// Length prefixes keep model-provided identifiers from colliding with one another.
internal fun reviewDisclosureKey(snapshot: String, kind: String, vararg ids: String): String =
    (listOf(snapshot, kind) + ids).joinToString("") { "${it.length}:$it" }

internal fun reviewSnapshotKey(run: ReviewRun): String =
    reviewDisclosureKey(run.input.sessionId, "snapshot", run.input.sourceHash)

internal fun reviewQuestionKey(run: ReviewRun, question: ReviewQuestionEntry): String =
    reviewDisclosureKey(reviewSnapshotKey(run), "question", question.threadId, question.turn.id)

internal fun orderedReviewUnits(run: ReviewRun): List<ReviewUnit> =
    run.outline?.topics?.mapNotNull { run.units[it.id] } ?: run.units.values.toList()

internal fun reviewListKeys(run: ReviewRun?): List<String> = buildList {
    add("header")
    add("actions")
    if (run != null) {
        if (run.errors.isNotEmpty()) add("errors")
        if (run.outline != null) add("outline")
        if (run.plan != null) add("plan")
        orderedReviewUnits(run).forEach { unit ->
            add("unit:${unit.id}")
            reviewQuestions(run.qaThreads, unit.id).forEach { add(reviewQuestionKey(run, it)) }
        }
        add("whole-class-questions")
        reviewQuestions(run.qaThreads, null).forEach { add(reviewQuestionKey(run, it)) }
        add("exercises")
        run.exercises.forEach { add("exercise:${it.id}") }
    }
}

internal fun reviewUnitIndex(run: ReviewRun?, unitId: String): Int? =
    reviewListKeys(run).indexOf("unit:$unitId").takeIf { it >= 0 }

internal fun reviewExerciseIndex(run: ReviewRun?, exerciseId: String? = null): Int? {
    val key = exerciseId?.let { "exercise:$it" } ?: "exercises"
    return reviewListKeys(run).indexOf(key).takeIf { it >= 0 }
}
