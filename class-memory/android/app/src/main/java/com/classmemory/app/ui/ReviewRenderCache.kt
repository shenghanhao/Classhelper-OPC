package com.classmemory.app.ui

/** Screen-owned memory only; no WebViews, credentials, disk files, or truncated text. */
internal data class ReviewRenderKey(val content: String, val dark: Boolean, val widthPx: Int, val density: Float, val fontScale: Float)
internal data class ReviewRenderedBody(val html: String, val heightCssPx: Int)

// Bound the native view's layout, not its document. Compose packs size constraints in finite bits.
internal const val REVIEW_MAX_VIEW_HEIGHT_PX = 16_000
internal fun reviewViewportHeightDp(contentHeightCssPx: Int, density: Float): Float =
    minOf(contentHeightCssPx.coerceAtLeast(1).toFloat(), REVIEW_MAX_VIEW_HEIGHT_PX / density)

internal fun reviewNeedsInnerScroll(contentHeightCssPx: Int, density: Float): Boolean =
    contentHeightCssPx.toDouble() * density > REVIEW_MAX_VIEW_HEIGHT_PX

internal fun reviewEstimatedHeightCssPx(key: ReviewRenderKey): Int {
    val font = 16.0 * key.fontScale
    val available = (key.widthPx / key.density).coerceAtLeast(1f)
    val lines = key.content.lineSequence().sumOf { line ->
        val width = line.sumOf { if (it.code > 255) font else font * 0.55 }
        kotlin.math.ceil(width / available).toLong().coerceAtLeast(1L)
    }
    return kotlin.math.ceil(lines * font * 1.5).toLong().coerceIn(48L, Int.MAX_VALUE.toLong()).toInt()
}

internal class ReviewRenderCache(private val maxBytes: Long = 8L * 1024 * 1024, private val maxEntries: Int = 32) {
    private val entries = LinkedHashMap<ReviewRenderKey, ReviewRenderedBody>(16, 0.75f, true)
    internal var retainedBytes = 0L
        private set
    internal val size: Int get() = entries.size

    operator fun get(key: ReviewRenderKey): ReviewRenderedBody? = entries[key]

    fun put(key: ReviewRenderKey, value: ReviewRenderedBody) {
        entries.remove(key)?.let { retainedBytes -= bytes(key, it) }
        val cost = bytes(key, value)
        // Oversized material is still rendered in full; only its optional cache is skipped.
        if (value.heightCssPx < 1 || cost > maxBytes || maxEntries < 1) return
        entries[key] = value
        retainedBytes += cost
        val iterator = entries.entries.iterator()
        while ((retainedBytes > maxBytes || entries.size > maxEntries) && iterator.hasNext()) {
            val oldest = iterator.next()
            retainedBytes -= bytes(oldest.key, oldest.value)
            iterator.remove()
        }
    }

    private fun bytes(key: ReviewRenderKey, value: ReviewRenderedBody): Long = (key.content.length.toLong() + value.html.length) * 2L
}

/** Each AndroidView bind/reset invalidates callbacks from its previous document. */
internal class ReviewRenderBinding {
    var generation: Long = 0L
        private set
    var key: ReviewRenderKey? = null
        private set
    fun bind(next: ReviewRenderKey): Long { generation++; key = next; return generation }
    fun reset() { generation++; key = null }
    fun accepts(generation: Long, key: ReviewRenderKey): Boolean = this.generation == generation && this.key == key
}
