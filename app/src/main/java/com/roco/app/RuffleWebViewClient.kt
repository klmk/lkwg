package com.roco.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class RuffleWebViewClient(private val context: Context, private val debugLogger: DebugLogger? = null) : WebViewClient() {

    companion object {
        private const val TAG = "RuffleWebViewClient"
        private const val RUFFLE_ASSET_PATH = "ruffle/"
        private const val RUFFLE_URL_PREFIX = "/__ruffle/"
        private const val REFERER_FOR_RESOURCES = "https://web2.17roco.qq.com/"
    }

    private val injectedUrls = mutableSetOf<String>()

    private fun debug(msg: String) {
        Log.d(TAG, msg)
        debugLogger?.log(msg)
    }

    // Ruffle injection script
    // Uses urlRewriteRules to redirect SWF internal requests through /__proxy/
    // so shouldInterceptRequest can proxy them (bypasses CORS + keeps original URL for SWF)
    private val ruffleInjection: String
        get() {
            return "<script>\n" +
                "window.RufflePlayer = window.RufflePlayer || {};\n" +
                "window.RufflePlayer.config = {\n" +
                "  \"publicPath\": \"/__ruffle/\",\n" +
                "  \"polyfills\": true,\n" +
                "  \"allowScriptAccess\": true,\n" +
                "  \"maxExecutionDuration\": 999999,\n" +
                "  \"letterbox\": \"off\",\n" +
                "  \"backgroundColor\": null,\n" +
                "  \"warnOnUnsupportedContent\": false,\n" +
                "  \"upgradeToHttps\": true,\n" +
                "  \"logLevel\": \"Debug\",\n" +
                "  \"credentialAllowList\": [\"https://res.17roco.qq.com\", \"https://web2.17roco.qq.com\", \"https://17roco.qq.com\"],\n" +
                "  \"urlRewriteRules\": [\n" +
                "    [\"^//res\\\\.17roco\\\\.qq\\\\.com/\", \"/__proxy/https://res.17roco.qq.com/\"],\n" +
                "    [\"^https://res\\\\.17roco\\\\.qq\\\\.com/\", \"/__proxy/https://res.17roco.qq.com/\"]\n" +
                "  ],\n" +
                "  \"socketProxy\": [\n" +
                "    {\"host\": \"172.25.40.120\", \"port\": 9000, \"proxyUrl\": \"ws://127.0.0.1:9000\"},\n" +
                "    {\"host\": \"172.25.40.120\", \"port\": 9100, \"proxyUrl\": \"ws://127.0.0.1:9100\"},\n" +
                "    {\"host\": \"172.25.40.120\", \"port\": 9101, \"proxyUrl\": \"ws://127.0.0.1:9101\"},\n" +
                "    {\"host\": \"172.25.40.121\", \"port\": 9000, \"proxyUrl\": \"ws://127.0.0.1:19000\"},\n" +
                "    {\"host\": \"172.25.40.122\", \"port\": 9000, \"proxyUrl\": \"ws://127.0.0.1:19001\"}\n" +
                "  ]\n" +
                "};\n" +
                "console.log('[ROCO-CONFIG] maxExecutionDuration=' + window.RufflePlayer.config.maxExecutionDuration);\n" +
                // Hook WebAssembly.Memory to remove/increase maximum memory limit
                // This prevents OOM crashes in large Flash games like 洛克王国
                "(function() {\n" +
                "  var OrigMemory = WebAssembly.Memory;\n" +
                "  WebAssembly.Memory = function(descriptor) {\n" +
                "    if (descriptor && descriptor.maximum) {\n" +
                "      console.log('[WASM-MEMORY] Original max: ' + descriptor.maximum + ' pages (' + (descriptor.maximum * 64) + ' KB)');\n" +
                "      // Increase max to 4GB (65536 pages) - the WASM32 theoretical max\n" +
                "      descriptor.maximum = 65536;\n" +
                "      console.log('[WASM-MEMORY] Increased max to: ' + descriptor.maximum + ' pages (4 GB)');\n" +
                "    }\n" +
                "    return new OrigMemory(descriptor);\n" +
                "  };\n" +
                "  WebAssembly.Memory.prototype = OrigMemory.prototype;\n" +
                "  console.log('[WASM-MEMORY] Hook installed successfully');\n" +
                "})();\n" +
                // Capture detailed error info including WASM stack traces
                "window.addEventListener('error', function(e) {\n" +
                "  if (e.message && e.message.includes('unreachable')) {\n" +
                "    console.log('[CRASH-DETAIL] msg=' + e.message + ' file=' + e.filename + ' line=' + e.lineno + ' col=' + e.colno);\n" +
                "    if (e.error && e.error.stack) console.log('[CRASH-STACK] ' + e.error.stack);\n" +
                "  }\n" +
                "});\n" +
                "window.addEventListener('unhandledrejection', function(e) {\n" +
                "  console.log('[REJECTION] ' + (e.reason ? e.reason.message || e.reason : 'unknown'));\n" +
                "  if (e.reason && e.reason.stack) console.log('[REJECTION-STACK] ' + e.reason.stack);\n" +
                "});\n" +
                "</script>\n" +
                "<script src=\"/__ruffle/ruffle.js\"></script>\n"
        }

    @SuppressLint("DiscouragedApi")
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val urlStr = request.url.toString()
        val path = request.url.path ?: ""

        // 1. Serve Ruffle assets from local (any domain)
        if (path.contains("/__ruffle/")) {
            val rufflePath = path.substring(path.indexOf("/__ruffle/"))
            val requestOrigin = request.requestHeaders?.get("Origin")
                ?: request.requestHeaders?.get("origin")
                ?: request.url.scheme + "://" + request.url.host
            return serveRuffleAsset(rufflePath, requestOrigin)
        }

        // 2. Handle /__proxy/ requests (rewritten by Ruffle urlRewriteRules)
        if (path.startsWith("/__proxy/")) {
            val encodedUrl = path.substring("/__proxy/".length)
            val realUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            val requestOrigin = request.requestHeaders?.get("Origin")
                ?: request.requestHeaders?.get("origin")
                ?: request.url.scheme + "://" + request.url.host
            debug("Proxy request: $realUrl")
            return proxyRequest(realUrl, requestOrigin)
        }

        // 3. Intercept HTML pages from 17roco domains for Ruffle injection
        //    Only inject into pages that actually contain SWF content
        //    Skip login/iframe/utility pages that don't need Ruffle
        if (isRocoDomain(urlStr) && !injectedUrls.contains(urlStr) && isHtmlPage(urlStr)
            && !urlStr.contains("iframe.html")
            && !urlStr.contains("logintarget.html")
            && !urlStr.contains("login.html")
            && !urlStr.contains("fcgi-bin/login")) {
            val response = downloadAndInjectHtml(urlStr)
            if (response != null) return response
        }

        // 3b. For login.html: inject only rUri interceptor (no Ruffle)
        //     QQ login uses rUri:// scheme which WebView doesn't handle
        if (isRocoDomain(urlStr) && !injectedUrls.contains(urlStr) && isHtmlPage(urlStr)
            && (urlStr.contains("login.html") || urlStr.contains("logintarget.html"))) {
            val response = downloadAndInjectRuriOnly(urlStr)
            if (response != null) return response
        }

        // 4. Proxy ALL cross-origin resource requests at network level
        if (isResourceDomain(urlStr)) {
            val reqOrigin = request.requestHeaders?.get("Origin")
                ?: request.requestHeaders?.get("origin")
                ?: "https://17roco.qq.com"
            return proxyRequest(urlStr, reqOrigin)
        }

        return null
    }

    @SuppressLint("DiscouragedApi")
    private fun serveRuffleAsset(path: String, origin: String): WebResourceResponse? {
        val assetPath = RUFFLE_ASSET_PATH + path.substringAfter(RUFFLE_URL_PREFIX)
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val mimeType = guessMimeType(assetPath)
            val response = WebResourceResponse(mimeType, "UTF-8", inputStream)
            val headers = mutableMapOf<String, String>()
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Credentials"] = "true"
            response.responseHeaders = headers
            response
        } catch (e: Exception) {
            debug("❌ Failed to load Ruffle asset: $assetPath: ${e.message}")
            WebResourceResponse(
                "text/plain", "UTF-8",
                ByteArrayInputStream("Not found: $assetPath".toByteArray())
            )
        }
    }

    private fun downloadAndInjectHtml(urlStr: String): WebResourceResponse? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")

            if (urlStr.contains("17roco.qq.com")) {
                conn.setRequestProperty("Referer", "https://17roco.qq.com/")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return null
            }

            val contentType = conn.contentType ?: ""
            val mimeType = contentType.split(";")[0].trim().lowercase()

            // Only inject into HTML responses
            if (mimeType != "text/html" && mimeType != "application/xhtml+xml" &&
                !urlStr.contains(".html") && !urlStr.contains(".htm") && !urlStr.contains("fcgi-bin")) {
                conn.disconnect()
                return null
            }

            var charset = parseCharset(contentType) ?: "gb2312"
            val htmlBytes = conn.inputStream.readBytes()
            conn.disconnect()

            val htmlString = String(htmlBytes, Charset.forName(charset))
            val detectedCharset = detectCharsetFromMeta(htmlString)
            val finalHtml = if (detectedCharset != null && detectedCharset != charset) {
                String(htmlBytes, Charset.forName(detectedCharset))
            } else {
                htmlString
            }

            val modifiedHtml = injectRuffleScripts(finalHtml)
            injectedUrls.add(urlStr)
            debug("Injected Ruffle into $urlStr (${htmlBytes.size} bytes)")

            WebResourceResponse("text/html", "UTF-8",
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            debug("❌ Error injecting HTML for $urlStr: ${e.message}")
            null
        }
    }

    private fun downloadAndInjectRuriOnly(urlStr: String): WebResourceResponse? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (urlStr.contains("17roco.qq.com")) {
                conn.setRequestProperty("Referer", "https://17roco.qq.com/")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }

            var charset = parseCharset(conn.contentType ?: "") ?: "gb2312"
            val htmlBytes = conn.inputStream.readBytes()
            conn.disconnect()

            val htmlString = String(htmlBytes, Charset.forName(charset))
            val detectedCharset = detectCharsetFromMeta(htmlString)
            val finalHtml = if (detectedCharset != null && detectedCharset != charset) {
                String(htmlBytes, Charset.forName(detectedCharset))
            } else {
                htmlString
            }

            // Inject only rUri interceptor, no Ruffle
            val rUriScript = """<script>
(function(){
  function fixUri(u){return typeof u==='string'&&u.indexOf('rUri://')===0?u.replace('rUri://','https://'):u;}
  try{
    var d=Object.getOwnPropertyDescriptor(window.location,'href');
    if(d&&d.set)Object.defineProperty(window.location,'href',{set:function(v){d.set.call(this,fixUri(v));},get:d.get,configurable:true});
  }catch(e){}
  var oa=window.location.assign;window.location.assign=function(u){oa.call(this,fixUri(u));};
  var or=window.location.replace;window.location.replace=function(u){or.call(this,fixUri(u));};
  console.log('[RUFFLE] rUri interceptor ready');
})();
</script>
"""
            val headClose = finalHtml.lowercase().indexOf("</head>")
            val modifiedHtml = if (headClose != -1) {
                finalHtml.substring(0, headClose) + rUriScript + finalHtml.substring(headClose)
            } else {
                rUriScript + finalHtml
            }

            injectedUrls.add(urlStr)
            debug("Injected rUri-only into $urlStr (${htmlBytes.size} bytes)")

            WebResourceResponse("text/html", "UTF-8",
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            debug("❌ Error injecting rUri for $urlStr: ${e.message}")
            null
        }
    }

    private fun injectRuffleScripts(html: String): String {
        // Inject rUri:// interceptor BEFORE everything else (at start of <head>)
        // QQ login uses rUri:// custom scheme which WebView doesn't handle
        val rUriInterceptor = """<script>
(function(){
  function fixUri(u){return typeof u==='string'&&u.indexOf('rUri://')===0?u.replace('rUri://','https://'):u;}
  try{
    var d=Object.getOwnPropertyDescriptor(window.location,'href');
    if(d&&d.set)Object.defineProperty(window.location,'href',{set:function(v){d.set.call(this,fixUri(v));},get:d.get,configurable:true});
  }catch(e){}
  var oa=window.location.assign;window.location.assign=function(u){oa.call(this,fixUri(u));};
  var or=window.location.replace;window.location.replace=function(u){or.call(this,fixUri(u));};
  console.log('[RUFFLE] rUri interceptor ready');
})();
</script>
"""
        val headOpen = html.lowercase().indexOf("<head")
        val headClose = html.lowercase().indexOf("</head>")
        return if (headClose != -1) {
            val insertPoint = if (headOpen != -1) {
                // Find end of <head> tag
                val tagEnd = html.indexOf(">", headOpen)
                if (tagEnd != -1 && tagEnd < headClose) tagEnd + 1 else headClose
            } else {
                headClose
            }
            html.substring(0, insertPoint) + rUriInterceptor + ruffleInjection + html.substring(insertPoint)
        } else {
            rUriInterceptor + ruffleInjection + html
        }
    }

    /**
     * Check if URL is from a resource domain that needs proxying.
     * These are domains that serve SWF, images, etc. that Ruffle needs to load.
     */
    private fun isResourceDomain(url: String): Boolean {
        try {
            val host = Uri.parse(url).host ?: return false
            return host == "res.17roco.qq.com" ||
                   host.endsWith(".17roco.qq.com") && host != "17roco.qq.com" && host != "web2.17roco.qq.com"
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Check if URL is from a 17roco game domain (HTML pages).
     */
    private fun isRocoDomain(url: String): Boolean {
        try {
            val host = Uri.parse(url).host ?: return false
            return host == "17roco.qq.com" ||
                   host == "web2.17roco.qq.com" ||
                   host.endsWith(".17roco.qq.com")
        } catch (e: Exception) {
            return false
        }
    }

    // Only inject Ruffle into actual HTML pages, skip CGI/API/image/script requests
    private fun isHtmlPage(url: String): Boolean {
        val path = try { Uri.parse(url).path ?: "" } catch (e: Exception) { return false }
        if (path.contains("/cgi-bin/") || path.contains("/fcgi-bin/")) return false
        val nonHtml = listOf(".js", ".css", ".json", ".xml", ".png", ".jpg", ".jpeg", ".gif",
                             ".swf", ".ico", ".woff", ".woff2", ".ttf", ".svg", ".mp3", ".mp4")
        val lower = path.lowercase()
        if (nonHtml.any { lower.endsWith(it) }) return false
        return lower.endsWith(".html") || lower.endsWith(".htm") ||
               !path.contains(".") || path.endsWith("/")
    }

    /**
     * Proxy a request by downloading via Java HTTP and returning as WebResourceResponse.
     * The browser sees the original URL, so SWF security checks pass.
     * CORS is bypassed because the request never reaches the browser's network stack.
     */
    @SuppressLint("DiscouragedApi")
    private fun proxyRequest(urlStr: String, origin: String): WebResourceResponse? {
        return try {
            // Resolve protocol-relative URLs
            val resolvedUrl = if (urlStr.startsWith("//")) "https:$urlStr" else urlStr
            val url = URL(resolvedUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")

            if (resolvedUrl.contains("res.17roco.qq.com")) {
                conn.setRequestProperty("Referer", REFERER_FOR_RESOURCES)
            } else if (resolvedUrl.contains("17roco.qq.com")) {
                conn.setRequestProperty("Referer", "https://17roco.qq.com/")
            }

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Proxy HTTP $responseCode for $resolvedUrl")
                conn.disconnect()
                return null
            }

            // Read entire response into memory
            val data = conn.inputStream.readBytes()
            conn.disconnect()

            val contentType = conn.contentType ?: "application/octet-stream"
            val mimeType = contentType.split(";")[0].trim()

            debug("Proxied $resolvedUrl: ${data.size} bytes, type=$mimeType")

            val response = WebResourceResponse(mimeType, null, ByteArrayInputStream(data))
            val headers = mutableMapOf<String, String>()
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Credentials"] = "true"
            headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "*"
            headers["Content-Length"] = data.size.toString()
            response.responseHeaders = headers
            response
        } catch (e: Exception) {
            debug("❌ Proxy error for $urlStr: ${e.message}")
            null
        }
    }

    private fun parseCharset(contentType: String): String? {
        for (part in contentType.split(";")) {
            val trimmed = part.trim().lowercase()
            if (trimmed.startsWith("charset=")) {
                return trimmed.substring(8).trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }
        return null
    }

    private fun detectCharsetFromMeta(html: String): String? {
        val regex = Regex("""<meta[^>]+charset=["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun guessMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".wasm") -> "application/wasm"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".eot") -> "application/vnd.ms-fontobject"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".swf") -> "application/x-shockwave-flash"
            else -> "application/octet-stream"
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        debug("Page started: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        debug("Page finished: $url")
        // For login/logintarget pages: inject rUri interceptor via evaluateJavascript
        // (shouldInterceptRequest doesn't intercept main frame navigation)
        if (url != null && (url.contains("login.html") || url.contains("logintarget.html"))) {
            view?.evaluateJavascript("""
                (function(){
                  function fixUri(u){return typeof u==='string'&&u.indexOf('rUri://')===0?u.replace('rUri://','https://'):u;}
                  try{
                    var d=Object.getOwnPropertyDescriptor(window.location,'href');
                    if(d&&d.set)Object.defineProperty(window.location,'href',{set:function(v){d.set.call(this,fixUri(v));},get:d.get,configurable:true});
                  }catch(e){}
                  var oa=window.location.assign;window.location.assign=function(u){oa.call(this,fixUri(u));};
                  var or=window.location.replace;window.location.replace=function(u){or.call(this,fixUri(u));};
                  console.log('[RUFFLE] rUri interceptor ready (via evaluateJavascript)');
                })();
            """.trimIndent(), null)
            debug("Injected rUri interceptor via evaluateJavascript for $url")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // Block /__ruffle/ navigation
        if (url.contains("/__ruffle/")) return true
        // Handle rUri:// scheme - convert to https://
        // QQ login uses rUri:// as custom scheme for redirects
        // WebView doesn't handle it properly, causing redirectURI mismatch
        if (url.startsWith("rUri://")) {
            val httpsUrl = url.replace("rUri://", "https://")
            debug("Redirecting rUri:// -> $httpsUrl")
            view?.loadUrl(httpsUrl)
            return true
        }
        return false
    }
}
