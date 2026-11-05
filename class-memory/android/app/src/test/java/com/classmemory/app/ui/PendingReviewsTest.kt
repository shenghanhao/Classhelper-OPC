package com.classmemory.app.ui

import com.classmemory.app.review.*
import org.junit.Assert.*
import org.junit.Test

class PendingReviewsTest {
    private fun run(id: String, date: String, reviewed: Long? = null, complete: Boolean = true) = ReviewRun(
        input = ReviewInput(id, "", "zh", id, "2026-11-04", null, emptyList(), emptyList(), emptyList()),
        plan = if (complete) ReviewPlan(id, "Review", date, emptyList()) else null,
        reviewedAt = reviewed, updatedAt = 1,
    )
    @Test fun reviewedDeletedAndIncompleteTasksDisappearButPendingDatesAreSorted() {
        val all = listOf(run("later", "2026-11-07"), run("first", "2026-11-05"),
            run("done", "2026-11-05", reviewed = 1), run("deleted", "2026-11-05"),
            run("partial", "2026-11-05", complete = false))
        val pending = pendingReviews(all, setOf("later", "first", "done", "partial"))
        assertEquals(listOf("first", "later"), pending.map { it.input.sessionId })
        assertEquals(5, all.size) // Filtering tasks does not erase saved material.
    }
}
