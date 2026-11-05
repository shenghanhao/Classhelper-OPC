package com.classmemory.app.ui

import com.classmemory.app.review.ReviewInput
import com.classmemory.app.review.ReviewPlan
import com.classmemory.app.review.ReviewRun
import org.junit.Assert.*
import org.junit.Test

class ReviewPhotoConsentStateTest {
    private val plain = ReviewInput("class", "text-hash", "zh", "课堂", "2026-11-07", null, emptyList(), emptyList(), emptyList())
    private val withPhotos = plain.copy(sourceHash = "photo-hash", selectedVisionPhotoIds = listOf("photo-1", "photo-2"))

    @Test
    fun failedPhotoRunKeepsItsSelectionButCompletedRunStartsFresh() {
        val failed = ReviewRun(withPhotos, errors = mapOf("photo:photo-2" to "request failed"), updatedAt = 1L)
        assertTrue(reviewRetryIncludesPhotos(failed))
        assertFalse(reviewRetryIncludesPhotos(ReviewRun(plain, errors = mapOf("outline" to "request failed"), updatedAt = 1L)))
        assertFalse(reviewRetryIncludesPhotos(failed.copy(errors = emptyMap(), plan = ReviewPlan("", "", "", emptyList()))))
        assertFalse(ReviewPhotoConsentState().includeRequested)
    }

    @Test
    fun failedOptInCannotSendTextSilentlyAndCanExplicitlyFallBack() {
        val requested = ReviewPhotoConsentState().request(true)
        assertFalse(requested.canConfirm(withPhotos))
        val failed = requested.finish("photo unavailable")
        assertTrue(failed.includeRequested)
        assertFalse(failed.canConfirm(plain))
        assertFalse(failed.canConfirm(withPhotos))

        val plainRequested = failed.request(false)
        assertFalse(plainRequested.includeRequested)
        assertNull(plainRequested.error)
        assertFalse(plainRequested.canConfirm(plain))
        val plainReady = plainRequested.finish()
        assertTrue(plainReady.canConfirm(plain))
        assertFalse(plainReady.canConfirm(withPhotos))
    }

    @Test
    fun resumedPhotosRequireSuccessfulPreparationAndMatchingInput() {
        val ready = ReviewPhotoConsentState().request(true).finish()
        assertTrue(ready.canConfirm(withPhotos))
        assertFalse(ready.canConfirm(plain))
        assertFalse(ready.finish("source changed").canConfirm(withPhotos))
    }
}
