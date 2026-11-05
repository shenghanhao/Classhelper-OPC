package com.classmemory.app.ui

import com.classmemory.app.review.ReviewRun
import com.classmemory.app.review.ReviewUnit

internal fun orderedReviewUnits(run: ReviewRun): List<ReviewUnit> =
    run.outline?.topics?.mapNotNull { run.units[it.id] } ?: run.units.values.toList()

internal fun reviewListKeys(run: ReviewRun?): List<String> = buildList {
    add("header")
    add("actions")
    if (run != null) {
        if (run.errors.isNotEmpty()) add("errors")
        if (run.outline != null) add("outline")
        if (run.plan != null) add("plan")
        orderedReviewUnits(run).forEach { add("unit:${it.id}") }
        add("whole-class-questions")
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
