package com.classmemory.app.ui

import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Renders untrusted model text using bundled libraries; never exposes a JavaScript bridge. */
@Composable
internal fun ReviewContent(content: String) {
    val dark = isSystemInDarkTheme()
    val encoded = JSONObject.quote(content).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    val foreground = if (dark) "#eeeeee" else "#202124"
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src file: 'unsafe-inline'; style-src file: 'unsafe-inline'; font-src file:;">
        <link rel="stylesheet" href="katex.min.css">
        <style>body{color:$foreground;background:transparent;font:16px sans-serif;line-height:1.5;margin:0;overflow-wrap:anywhere}p{margin:0 0 0.8em}pre,.katex-display{overflow-x:auto;overflow-y:hidden}img{display:none}a{color:inherit;pointer-events:none}</style>
        <script src="marked.js"></script><script src="purify.min.js"></script><script src="katex.min.js"></script><script src="contrib/auto-render.min.js"></script></head>
        <body><div id="content"></div><script>
        const raw = $encoded;
        const formulas = [];
        const protectedText = raw.replace(/\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\\\([\s\S]+?\\\)|\$[^\$\n]+?\$/g, m => { const key = 'FORMULATOKEN' + formulas.length + 'END'; formulas.push(m); return key; });
        const target = document.getElementById('content');
        target.innerHTML = DOMPurify.sanitize(marked.parse(protectedText), {FORBID_TAGS:['img','iframe','style','script','object','embed'], FORBID_ATTR:['href','src']});
        const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) { const node = walker.currentNode; node.nodeValue = node.nodeValue.replace(/FORMULATOKEN(\d+)END/g, (_, i) => formulas[Number(i)]); }
        renderMathInElement(target, {delimiters:[{left:'$$',right:'$$',display:true},{left:'\\[',right:'\\]',display:true},{left:'\\(',right:'\\)',display:false},{left:'$',right:'$',display:false}], throwOnError:false, trust:false});
        </script></body></html>
    """.trimIndent()
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context ->
        ReviewWebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            isVerticalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    return if (url.startsWith("file:///android_asset/review-katex/")) null
                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }
            }
        }
    }, onRelease = { it.destroy() }, update = {
        if (it.loadedHtml != html) {
            it.loadedHtml = html
            it.loadDataWithBaseURL("file:///android_asset/review-katex/", html, "text/html", "UTF-8", null)
        }
    })
}

private class ReviewWebView(context: android.content.Context) : WebView(context) {
    var loadedHtml: String? = null
}
