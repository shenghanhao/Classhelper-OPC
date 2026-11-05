package com.classmemory.app.ui

import org.junit.Assert.*
import org.junit.Test

class ReviewRenderCacheTest {
    private fun key(text: String) = ReviewRenderKey(text, false, 600, 2f, 1f)

    @Test fun cacheReusesFullRenderedBodyAndInvalidatesForEveryLayoutInput() {
        val cache = ReviewRenderCache()
        val original = key("公式与表格")
        val body = ReviewRenderedBody("<p>complete body</p><table><tr><td>value</td></tr></table>", 432)
        cache.put(original, body)
        assertEquals(body, cache[original])
        listOf(original.copy(content = "新内容"), original.copy(dark = true), original.copy(widthPx = 900),
            original.copy(density = 3f), original.copy(fontScale = 1.3f)).forEach { assertNull(cache[it]) }
    }

    @Test fun boundedCacheEvictsLeastRecentlyUsedWithoutTruncatingOversizedMaterial() {
        val cache = ReviewRenderCache(maxBytes = 80, maxEntries = 2)
        val a = key("a"); val b = key("b"); val c = key("c")
        cache.put(a, ReviewRenderedBody("first", 100))
        cache.put(b, ReviewRenderedBody("second", 100))
        assertNotNull(cache[a])
        cache.put(c, ReviewRenderedBody("third", 100))
        assertNull(cache[b])
        assertNotNull(cache[a])
        val oversized = key("x".repeat(100))
        cache.put(oversized, ReviewRenderedBody("full".repeat(100), 8000))
        assertNull(cache[oversized])
        assertEquals(2, cache.size)
        assertTrue(cache.retainedBytes <= 80)
    }

    @Test fun reusedOrReleasedViewRejectsEarlierDocumentCallbacks() {
        val binding = ReviewRenderBinding()
        val first = key("first"); val second = key("second")
        val old = binding.bind(first)
        assertTrue(binding.accepts(old, first))
        val current = binding.bind(second)
        assertFalse(binding.accepts(old, first))
        assertFalse(binding.accepts(current, first))
        assertTrue(binding.accepts(current, second))
        binding.reset()
        assertFalse(binding.accepts(current, second))
        assertNull(binding.key)
    }

    @Test fun firstLayoutReservesBodySpaceInsteadOfStackingTinyWebViews() {
        val text = "课堂讲解内容".repeat(150)
        val original = key(text)
        assertTrue(reviewEstimatedHeightCssPx(original) > 500)
        assertTrue(reviewEstimatedHeightCssPx(original.copy(widthPx = 300)) > reviewEstimatedHeightCssPx(original))
        assertTrue(reviewEstimatedHeightCssPx(original.copy(fontScale = 1.5f)) > reviewEstimatedHeightCssPx(original))
    }

    @Test fun unlimitedLongTextUsesSafeViewportWithoutClippingOrShorteningItsDocument() {
        val longKey = key("字".repeat(150_000))
        val fullHeight = reviewEstimatedHeightCssPx(longKey)
        assertTrue(fullHeight * longKey.density > 262_143)
        assertEquals(REVIEW_MAX_VIEW_HEIGHT_PX.toFloat(), reviewViewportHeightDp(fullHeight, longKey.density) * longKey.density, 0.01f)
        assertTrue(reviewNeedsInnerScroll(fullHeight, longKey.density))
        assertFalse(reviewNeedsInnerScroll(500, longKey.density))
        assertEquals(500f, reviewViewportHeightDp(500, longKey.density), 0.01f)
        val body = ReviewRenderedBody(longKey.content, fullHeight)
        val cache = ReviewRenderCache()
        cache.put(longKey, body)
        assertEquals(150_000, cache[longKey]?.html?.length)
        assertEquals(fullHeight, cache[longKey]?.heightCssPx)
    }

    @Test fun freshContentKeepsSanitizerCspAndUntrustedTextEscaping() {
        val malicious = "</script><img src=x onerror=alert(1)>"
        val html = reviewContentHtml(key(malicious))
        assertFalse(html.contains(malicious))
        assertTrue(html.contains("DOMPurify.sanitize"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("trust:false"))
        assertTrue(html.contains("FORBID_ATTR:['href','src']"))
    }

    @Test fun cachedBodyKeepsMathAndTablesWithoutRerunningParserScripts() {
        val body = "<span class=\"katex\">formula</span><table><tr><td>cell</td></tr></table>"
        val html = reviewContentHtml(key("original"), body)
        assertTrue(html.contains(body))
        assertTrue(html.contains("script-src 'none'"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("marked.parse"))
        assertTrue(html.contains("katex.min.css"))
    }
}
