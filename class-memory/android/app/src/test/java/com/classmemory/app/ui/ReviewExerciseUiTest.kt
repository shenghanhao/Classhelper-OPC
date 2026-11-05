package com.classmemory.app.ui

import com.classmemory.app.review.*
import org.junit.Assert.*
import org.junit.Test

class ReviewExerciseUiTest {
    private val question = ReviewExercise("root", "u", "MCQ", "如何选择？", listOf("A. 甲", "B. 乙"))
    private val input = ReviewInput("class", "hash", "zh", "课堂", "2026-11-07", null, emptyList(), emptyList(), emptyList())

    @Test
    fun existingAnswerUsesSavedFeedbackOrItsRetryAction() {
        assertFalse(canSubmitExerciseAnswer(question, "   "))
        assertTrue(canSubmitExerciseAnswer(question, "A，因为条件满足"))
        for (status in listOf("graded", "pending", "failed")) {
            val saved = question.copy(attempts = listOf(ReviewExerciseAttempt("attempt", "A，因为条件满足", status)))
            assertFalse(canSubmitExerciseAnswer(saved, "  A，因为条件满足  "))
            assertTrue(canSubmitExerciseAnswer(saved, "B，我修正了思路"))
        }
    }

    @Test
    fun appendedFollowUpKeepsExistingDirectoryTargetsAndFollowsWholeClassQa() {
        val unit = ReviewUnit("u", "知识点", "", "", "", null, emptyList(), emptyList(), emptyList())
        val original = ReviewRun(input, units = mapOf("u" to unit), exercises = listOf(question), updatedAt = 1L)
        val originalKeys = reviewListKeys(original)
        val child = ReviewExercise("child", "u", "SHORT", "再说明一步。", emptyList(), parentId = question.id)
        val extended = original.copy(exercises = original.exercises + child)
        assertEquals(reviewUnitIndex(original, "u"), reviewUnitIndex(extended, "u"))
        assertEquals(reviewExerciseIndex(original, "root"), reviewExerciseIndex(extended, "root"))
        assertTrue(requireNotNull(reviewExerciseIndex(original)) > originalKeys.indexOf("whole-class-questions"))
        assertEquals(reviewListKeys(extended).lastIndex, reviewExerciseIndex(extended, "child"))
        assertEquals("exercise:child", reviewListKeys(extended).last())
        assertNull(reviewExerciseIndex(extended, "deleted"))
    }
}
