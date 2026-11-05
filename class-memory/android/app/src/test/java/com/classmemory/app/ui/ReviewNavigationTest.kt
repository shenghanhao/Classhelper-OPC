package com.classmemory.app.ui

import com.classmemory.app.review.*
import org.junit.Assert.*
import org.junit.Test

class ReviewNavigationTest {
    private val input = ReviewInput("class", "source", "zh", "课堂", "2026-11-07", null, emptyList(), emptyList(), emptyList())
    private fun unit(id: String) = ReviewUnit(id, id, "", "", "", null, emptyList(), emptyList(), emptyList())
    private val outline = ReviewOutline("导览", emptyList(), listOf(
        ReviewTopic("a", "知识一", emptyList(), emptyList()),
        ReviewTopic("b", "知识二", emptyList(), emptyList()),
    ))

    @Test
    fun directoryTracksOutlineOrderAsPlanAndErrorCardsAppear() {
        val run = ReviewRun(input, outline = outline, units = linkedMapOf("b" to unit("b"), "a" to unit("a")), updatedAt = 1L)
        assertEquals(listOf("a", "b"), orderedReviewUnits(run).map { it.id })
        assertEquals(3, reviewUnitIndex(run, "a"))
        assertEquals(4, reviewUnitIndex(run, "b"))
        val withPlan = run.copy(plan = ReviewPlan("计划", "", "2026-11-07", emptyList()))
        assertEquals(4, reviewUnitIndex(withPlan, "a"))
        val withErrors = withPlan.copy(errors = mapOf("photo:p" to "失败"))
        assertEquals(5, reviewUnitIndex(withErrors, "a"))
        assertEquals("unit:b", reviewListKeys(withErrors)[requireNotNull(reviewUnitIndex(withErrors, "b"))])
        assertEquals("whole-class-questions", reviewListKeys(withErrors).last())
        assertEquals(4, reviewUnitIndex(withErrors.copy(errors = emptyMap()), "a"))
    }

    @Test
    fun partialOrChangedReviewDoesNotJumpToAnUnrelatedItem() {
        assertNull(reviewUnitIndex(null, "a"))
        val partial = ReviewRun(input, outline = outline, units = mapOf("b" to unit("b")), updatedAt = 1L)
        assertNull(reviewUnitIndex(partial, "a"))
        assertEquals(3, reviewUnitIndex(partial, "b"))
        assertEquals(listOf("header", "actions", "outline", "unit:b", "whole-class-questions"), reviewListKeys(partial))
        assertEquals(2, reviewUnitIndex(partial.copy(outline = null), "b"))
    }
}
