package com.classmemory.app.ui

import com.classmemory.app.review.ReviewInput
import com.classmemory.app.review.ReviewRun

internal data class ReviewPhotoConsentState(
    val includeRequested: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    fun request(include: Boolean): ReviewPhotoConsentState =
        ReviewPhotoConsentState(includeRequested = include, preparing = true)

    fun finish(error: String? = null): ReviewPhotoConsentState =
        copy(preparing = false, error = error)

    fun canConfirm(input: ReviewInput): Boolean =
        !preparing && error == null && includeRequested == input.selectedVisionPhotoIds.isNotEmpty()
}

internal fun reviewRetryIncludesPhotos(run: ReviewRun?): Boolean =
    run != null && run.input.selectedVisionPhotoIds.isNotEmpty() && (run.plan == null || run.errors.isNotEmpty())
