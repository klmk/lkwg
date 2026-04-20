package com.roco.shadow

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

        // 4. Intercept login3 HTML response to break out of iframe
        //    login3 returns a game page, but it runs inside an OAuth iframe.
        //    Its main005.js tries parent.document.getElementById("mainiframe") which
        //    fails due to cross-origin (web2.17roco.qq.com vs 17roco.qq.com).
        //    Fix: inject JS at the start to redirect to top window if in iframe.
        if (urlStr.contains("web2.17roco.qq.com/fcgi-bin/login3")) {
            return interceptLogin3Breakout(urlStr)
        }

        // 5. Proxy ALL cross-origin resource requests at network level
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

    /**
     * Intercept login3 response to break out of iframe.
     * login3 returns a game page HTML that runs inside an OAuth iframe.
     * Its main005.js needs to access parent.document.getElementById("mainiframe")
     * but this fails due to cross-origin (web2.17roco.qq.com vs 17roco.qq.com).
     *
     * Fix: inject a script at the very start that detects iframe and redirects
     * the top window to the same login3 URL (so it loads in the main window).
     */
    private fun interceptLogin3Breakout(urlStr: String): WebResourceResponse? {
        return try {
            debug("Intercepting login3 breakout: $urlStr")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Referer", "https://17roco.qq.com/logintarget.html")

            // Pass all cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = mutableSetOf<String>()
            cookieManager.getCookie(urlStr)?.let { cookies.add(it) }
            cookieManager.getCookie("https://qq.com")?.let { cookies.add(it) }
            cookieManager.getCookie("https://graph.qq.com")?.let { cookies.add(it) }
            cookieManager.getCookie("https://17roco.qq.com")?.let { cookies.add(it) }
            val cookieStr = cookies.filter { it.isNotEmpty() }.joinToString("; ")
            if (cookieStr.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookieStr)
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                debug("login3 breakout HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()

            // Collect Set-Cookie headers
            val setCookies = conn.headerFields?.get("Set-Cookie") ?: emptyList()
            for (cookie in setCookies) {
                val cookieValue = cookie.substringBefore(";")
                cookieManager.setCookie(urlStr, cookieValue)
            }
            cookieManager.flush()
            conn.disconnect()

            debug("login3 breakout response: ${body.length} chars, ${setCookies.size} cookies")

            // Instead of breaking out of iframe, patch the JS to work without mainiframe.
            // main005.js does: parent.document.getElementById("mainiframe").src = parent.frameurl;
            // Replace with: top.location.href = frameurl; (works even without mainiframe)
            var modifiedBody = body
                .replace(
                    "parent.document.getElementById(\"mainiframe\").src=parent.frameurl",
                    "top.location.href=frameurl"
                )
                .replace(
                    "parent.document.getElementById(\"mainiframe\").src=parent.frameurl;",
                    "top.location.href=frameurl;"
                )

            // Also inject Ruffle scripts since login3 returns a game page with SWF
            modifiedBody = injectRuffleScripts(modifiedBody)

            debug("login3 breakout: patched mainiframe refs and injected Ruffle")

            WebResourceResponse("text/html", "UTF-8",
                ByteArrayInputStream(modifiedBody.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            debug("❌ Error in login3 breakout: ${e.message}")
            null
        }
    }

    /**
     * Intercept login3 CGI response.
     * login3 runs inside an iframe (the OAuth login iframe in login.html).
     * Its response sets cookies (including angel_key) and does window.location.href.
     * But since it's in an iframe, the redirect only affects the iframe, not the parent.
     *
     * Strategy: Fetch login3 ourselves, extract the response, and return a modified
     * response that notifies the parent window to navigate to the correct URL.
     */
    private fun interceptLogin3(urlStr: String): WebResourceResponse? {
        return try {
            debug("Intercepting login3: $urlStr")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Referer", "https://17roco.qq.com/logintarget.html")

            // Pass all cookies from WebView to login3 request
            // login3 needs QQ login cookies to validate the OAuth code
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = mutableSetOf<String>()
            // Get cookies for the login3 domain
            cookieManager.getCookie(urlStr)?.let { cookies.add(it) }
            // Also get cookies for qq.com (QQ login state)
            cookieManager.getCookie("https://qq.com")?.let { cookies.add(it) }
            // And for graph.qq.com (OAuth domain)
            cookieManager.getCookie("https://graph.qq.com")?.let { cookies.add(it) }
            // And for 17roco.qq.com
            cookieManager.getCookie("https://17roco.qq.com")?.let { cookies.add(it) }
            val cookieStr = cookies.filter { it.isNotEmpty() }.joinToString("; ")
            if (cookieStr.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookieStr)
                debug("login3 cookies: ${cookieStr.take(300)}")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                debug("login3 HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            // Read response body
            val body = conn.inputStream.bufferedReader().readText()

            // Collect Set-Cookie headers and set them via CookieManager
            val setCookies = conn.headerFields?.get("Set-Cookie") ?: emptyList()
            for (cookie in setCookies) {
                // Set-Cookie format: "name=value; PATH=/; DOMAIN=..."
                val cookieValue = cookie.substringBefore(";")
                val url = urlStr
                cookieManager.setCookie(url, cookieValue)
                debug("login3 Set-Cookie: $cookieValue")
            }
            cookieManager.flush()
            conn.disconnect()

            debug("login3 response (${body.length} chars)")
            // Log response line by line for full visibility
            body.lines().forEachIndexed { i, line ->
                if (i < 50) debug("login3[$i]: $line")
            }
            if (body.lines().size > 50) debug("login3: ... (${body.lines().size} total lines)")
            debug("login3 Set-Cookie count: ${setCookies.size}")
            val hasAngelKey = setCookies.any { it.contains("angel_key") }
            debug("login3 has angel_key: $hasAngelKey")

            // Extract redirect URL from response: window.location.href="//17roco.qq.com/..."
            val hrefMatch = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""").find(body)
            val redirectUrl = hrefMatch?.groupValues?.get(1)
            debug("login3 redirect: ${redirectUrl ?: "none"}")

            if (hasAngelKey && redirectUrl != null) {
                // Login succeeded! Navigate the top-level window
                val fullRedirect = if (redirectUrl.startsWith("//")) "https:$redirectUrl" else redirectUrl
                debug("login3 SUCCESS! Redirecting top to: $fullRedirect")
                debug("login3 redirect detected: $fullRedirect")
                debug("login3 wants to redirect to: $fullRedirect")

                // Return JS that notifies parent to navigate
                // If we're in an iframe, use parent.location or top.location
                val jsResponse = """
                    <html><body><script>
                    try {
                        // Check if we have angel_key cookie (means login3 succeeded)
                        var hasAngelKey = document.cookie.indexOf('angel_key') >= 0;
                        console.log('[LOGIN3] hasAngelKey=' + hasAngelKey + ' redirect=' + '$fullRedirect');

                        if (hasAngelKey) {
                            // Login succeeded! Navigate the top-level window to default.html
                            top.location.href = '$fullRedirect';
                        } else {
                            // Login failed (system busy), stay on login page
                            top.location.href = '//17roco.qq.com/login.html';
                        }
                    } catch(e) {
                        console.log('[LOGIN3] Error: ' + e.message);
                        // If top access fails, try parent
                        try { parent.location.href = '$fullRedirect'; } catch(e2) {}
                    }
                    </script></body></html>
                """.trimIndent()

                WebResourceResponse("text/html", "UTF-8",
                    ByteArrayInputStream(jsResponse.toByteArray(Charsets.UTF_8)))
            } else {
                // No angel_key or no redirect - login3 failed
                // Return original response so the iframe can handle it normally
                debug("login3 FAILED (no angel_key or no redirect), returning original response")
                WebResourceResponse("text/html", "UTF-8",
                    ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            debug("❌ Error intercepting login3: ${e.message}")
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

            // CRITICAL FIX: login3's main005.js expects parent.document.getElementById("mainiframe")
            // to exist. It sets mainiframe.src = "default.html?..." to load the game.
            // Since login.html runs in the main window (not inside iframe.html),
            // we need to create a hidden mainiframe that will load the game.
            view?.evaluateJavascript("""
                (function(){
                  if (!document.getElementById('mainiframe')) {
                    var iframe = document.createElement('iframe');
                    iframe.id = 'mainiframe';
                    iframe.name = 'mainiframe';
                    iframe.style.width = '100%';
                    iframe.style.height = '100%';
                    iframe.style.border = 'none';
                    iframe.style.position = 'fixed';
                    iframe.style.top = '0';
                    iframe.style.left = '0';
                    iframe.style.zIndex = '9999';
                    document.body.appendChild(iframe);
                    console.log('[RUFFLE] Created mainiframe for login3 game loading');
                  }
                })();
            """.trimIndent(), null)
            debug("Injected mainiframe element for login3 compatibility")
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
        // CRITICAL: When login3 loads inside an iframe, its main005.js tries to access
        // parent.document.getElementById("mainiframe") which fails due to cross-origin.
        // Instead, intercept login3 navigation and load it in the main window.
        // login3 returns a full game page with SWF, so it needs Ruffle injection.
        if (url.contains("web2.17roco.qq.com/fcgi-bin/login3") && request.isForMainFrame) {
            debug("Intercepting login3 navigation, loading in main window: $url")
            view?.loadUrl(url)
            return true
        }
        return false
    }
}
