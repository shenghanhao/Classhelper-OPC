package com.classmemory.app.ui

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.math.abs

private val LocalReviewRenderCache = staticCompositionLocalOf<ReviewRenderCache?> { null }

@Composable
internal fun ReviewRenderingScope(sessionId: String, content: @Composable () -> Unit) {
    val cache = remember(sessionId) { ReviewRenderCache() }
    CompositionLocalProvider(LocalReviewRenderCache provides cache, content = content)
}

/** Renders untrusted model text using bundled libraries; never exposes a JavaScript bridge. */
@Composable
internal fun ReviewContent(content: String) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val fallback = remember { ReviewRenderCache() }
    val cache = LocalReviewRenderCache.current ?: fallback
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val key = remember(content, dark, constraints.maxWidth, density.density, density.fontScale) {
            ReviewRenderKey(content, dark, constraints.maxWidth, density.density, density.fontScale)
        }
        val cached = remember(key) { cache[key] }
        var height by remember(key) { mutableIntStateOf(cached?.heightCssPx ?: reviewEstimatedHeightCssPx(key)) }
        val html = remember(key) { reviewContentHtml(key, cached?.html) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(reviewViewportHeightDp(height, density.density).dp),
            factory = { context -> ReviewWebView(context) },
            onReset = { it.resetDocument() },
            onRelease = { it.resetDocument(); it.destroy() },
            update = { view -> view.bind(key, html) { body ->
                cache.put(key, body)
                height = body.heightCssPx
            } },
        )
    }
}

internal fun reviewContentHtml(key: ReviewRenderKey, renderedBody: String? = null): String {
    val foreground = if (key.dark) "#f1effb" else "#1b1b28"
    val accent = if (key.dark) "#bcb0ff" else "#4e36e9"
    val tint = if (key.dark) "#302a4c" else "#eeebff"
    val border = if (key.dark) "#454156" else "#dcdcf0"
    val muted = if (key.dark) "#b8b4ca" else "#686a80"
    val scripts = if (renderedBody == null) "file: 'unsafe-inline'" else "'none'"
    val head = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $scripts; style-src file: 'unsafe-inline'; font-src file:;">
        <link rel="stylesheet" href="katex.min.css">
        <style>
        html,body{margin:0;padding:0}body{color:$foreground;background:transparent;font:${16f * key.fontScale}px sans-serif;line-height:1.75;overflow-wrap:anywhere}
        #content{display:flow-root;min-height:1px}p{margin:0 0 .85em}#content>:last-child{margin-bottom:0}
        h1,h2,h3,h4,h5,h6{font-weight:700;line-height:1.45;margin:1.1em 0 .55em;color:$foreground}
        h1{font-size:1.4em}h2{font-size:1.25em}h3{font-size:1.12em}h4,h5,h6{font-size:1em}
        h2,h3{border-left:4px solid $accent;padding-left:.6em}#content>:first-child{margin-top:0}
        strong{font-weight:700}ul,ol{padding-left:1.55em;margin:.4em 0 1em}li{padding-left:.15em;margin:.3em 0}li::marker{color:$accent;font-weight:600}
        blockquote{margin:.8em 0;padding:.65em .85em;border-left:3px solid $accent;background:$tint;border-radius:0 10px 10px 0}blockquote p:last-child{margin:0}
        pre,.katex-display{overflow-x:auto;overflow-y:hidden}pre{padding:.85em;border:1px solid $border;border-radius:10px;background:$tint;line-height:1.6}
        code{font-size:.9em;background:$tint;padding:.1em .3em;border-radius:4px}pre code{padding:0;background:transparent}
        .katex-display{padding:.65em .5em;background:$tint;border-radius:10px;margin:.85em 0}.katex{font-size:1.08em}
        table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;margin:.85em 0;font-size:.94em}
        th,td{border:1px solid $border;padding:.5em .65em;min-width:3em}th{background:$tint;text-align:left;font-weight:700}tbody tr:nth-child(even){background:${if (key.dark) "#252237" else "#f6f4ff"}}
        hr{border:0;border-top:1px solid $border;margin:1.2em 0}small{color:$muted}img{display:none}a{color:$accent;pointer-events:none}
        </style>
    """.trimIndent()
    if (renderedBody != null) return "$head</head><body><div id=\"content\" data-review-render=\"REVIEW_RENDER_ID\">$renderedBody</div></body></html>"
    val encoded = JSONObject.quote(key.content).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return head + """
        <script src="marked.js"></script><script src="purify.min.js"></script><script src="katex.min.js"></script><script src="contrib/auto-render.min.js"></script></head>
        <body><div id="content" data-review-render="REVIEW_RENDER_ID"></div><script>
        window.__reviewReady = false;
        const raw = $encoded;
        const formulas = [];
        const protectedText = raw.replace(/\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\\\([\s\S]+?\\\)|\$[^\$\n]+?\$/g, m => { const key = 'FORMULATOKEN' + formulas.length + 'END'; formulas.push(m); return key; });
        const target = document.getElementById('content');
        target.innerHTML = DOMPurify.sanitize(marked.parse(protectedText), {FORBID_TAGS:['img','iframe','style','script','object','embed'], FORBID_ATTR:['href','src']});
        const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) { const node = walker.currentNode; node.nodeValue = node.nodeValue.replace(/FORMULATOKEN(\d+)END/g, (_, i) => formulas[Number(i)]); }
        renderMathInElement(target, {delimiters:[{left:'$$',right:'$$',display:true},{left:'\\[',right:'\\]',display:true},{left:'\\(',right:'\\)',display:false},{left:'$',right:'$',display:false}], throwOnError:false, trust:false});
        document.fonts.ready.then(() => { requestAnimationFrame(() => { window.__reviewReady = true; }); });
        </script></body></html>
    """.trimIndent()
}

private class ReviewWebView(context: Context) : WebView(context) {
    private val binding = ReviewRenderBinding()
    private var onRendered: ((ReviewRenderedBody) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var innerVerticalScroll = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.textZoom = 100
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                return if (url.startsWith("file:///android_asset/review-katex/")) null
                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                val key = binding.key ?: return
                measureDocument(binding.generation, key, 0)
            }
        }
    }

    fun bind(key: ReviewRenderKey, html: String, onRendered: (ReviewRenderedBody) -> Unit) {
        this.onRendered = onRendered
        if (binding.key == key) return
        stopLoading()
        alpha = 0f
        innerVerticalScroll = false
        val generation = binding.bind(key)
        scrollTo(0, 0)
        loadDataWithBaseURL("file:///android_asset/review-katex/",
            html.replaceFirst("data-review-render=\"REVIEW_RENDER_ID\"", "data-review-render=\"$generation\""), "text/html", "UTF-8", null)
    }

    fun resetDocument() {
        binding.reset()
        onRendered = null
        stopLoading()
        alpha = 0f
        innerVerticalScroll = false
        scrollTo(0, 0)
    }

    private fun measureDocument(generation: Long, key: ReviewRenderKey, attempt: Int) {
        if (!binding.accepts(generation, key)) return
        // Native evaluation only; no JS bridge. Check the document nonce as well as the view binding.
        evaluateJavascript("""
            (() => { const target = document.getElementById('content');
            const waitingForFonts = document.fonts && document.fonts.status !== 'loaded';
            if (!target || target.dataset.reviewRender !== '$generation' || ((window.__reviewReady === false || waitingForFonts) && $attempt < 40)) return null;
            return JSON.stringify({html:target.innerHTML,height:Math.max(1,Math.ceil(target.getBoundingClientRect().height))}); })()
        """.trimIndent()) { result ->
            if (!binding.accepts(generation, key)) return@evaluateJavascript
            val body = runCatching {
                val json = JSONTokener(result).nextValue() as? String ?: return@runCatching null
                val value = JSONObject(json)
                ReviewRenderedBody(value.getString("html"), value.getInt("height").coerceAtLeast(1))
            }.getOrNull()
            if (body != null) {
                innerVerticalScroll = reviewNeedsInnerScroll(body.heightCssPx, key.density)
                onRendered?.invoke(body)
                alpha = 1f
            }
            else if (attempt < 40) postDelayed({ measureDocument(generation, key, attempt + 1) }, 50L)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(innerVerticalScroll)
            }
            MotionEvent.ACTION_MOVE -> {
                val horizontal = abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)
                val direction = if (event.y < lastY) 1 else -1
                val scrollInside = innerVerticalScroll && canScrollVertically(direction)
                // Only oversized documents scroll inside; their edges hand control back to the list.
                parent?.requestDisallowInterceptTouchEvent(horizontal || scrollInside)
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
