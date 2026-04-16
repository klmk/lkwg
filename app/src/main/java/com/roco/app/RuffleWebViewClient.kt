package com.roco.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/**
 * Custom WebViewClient that intercepts HTML page loads and injects
 * Ruffle Flash emulator scripts into them.
 */
class RuffleWebViewClient(private val context: Context) : WebViewClient() {

    companion object {
        private const val TAG = "RuffleWebViewClient"
        private const val RUFFLE_ASSET_PATH = "ruffle/"
        private const val RUFFLE_URL_PREFIX = "/__ruffle/"
        private const val REFERER_FOR_RESOURCES = "https://web2.17roco.qq.com/"

        // Ruffle injection script - auto-detect and replace Flash content on the page
        private const val RUFFLE_INJECTION = """
<script src="/__ruffle/ruffle.js"></script>
<script>
  window.addEventListener("DOMContentLoaded", function() {
    if (window.RufflePlayer) {
      window.RufflePlayer.config = {
        "publicPath": "/__ruffle/",
        "polyfills": true,
        "allowScriptAccess": true
      };
      window.RufflePlayer.newest().autoEnable();
    }
  });
</script>
"""
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track which URLs we've already injected into to avoid infinite loops
    private val injectedUrls = mutableSetOf<String>()

    /**
     * Intercept requests: serve local Ruffle assets for /__ruffle/* paths,
     * and inject Ruffle scripts into HTML pages.
     */
    @SuppressLint("DiscouragedApi")
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val urlStr = request.url.toString()
        val path = request.url.path ?: ""

        // Handle /__ruffle/* requests - serve from local assets
        if (path.startsWith(RUFFLE_URL_PREFIX)) {
            return serveRuffleAsset(path)
        }

        // Only intercept main frame HTML navigation
        if (!request.isForMainFrame) {
            return null
        }

        // Check if this looks like an HTML page
        val lowerUrl = urlStr.lowercase()
        if (!isHtmlUrl(lowerUrl)) {
            return null
        }

        // Avoid re-injecting pages we already processed
        if (injectedUrls.contains(urlStr)) {
            return null
        }

        // Download the HTML, inject Ruffle, and return it
        return downloadAndInjectHtml(urlStr, request.url)
    }

    /**
     * Determine if a URL likely points to an HTML page.
     */
    private fun isHtmlUrl(url: String): Boolean {
        // URLs ending with known HTML extensions
        if (url.endsWith(".html") || url.endsWith(".htm") || url.endsWith(".shtml") ||
            url.endsWith(".php") || url.endsWith(".asp") || url.endsWith(".aspx") ||
            url.endsWith(".jsp") || url.endsWith(".cfm")
        ) {
            return true
        }
        // URLs ending with / (directory index)
        if (url.endsWith("/")) {
            return true
        }
        // URLs with no extension and no query string (likely HTML)
        val pathOnly = url.substringBefore("?").substringBefore("#")
        val lastSegment = pathOnly.substringAfterLast("/")
        if (lastSegment.contains(".") && !lastSegment.lowercase().endsWith(".html") &&
            !lastSegment.lowercase().endsWith(".htm")
        ) {
            // Has an extension that is not HTML - skip
            return false
        }
        // No extension at all - could be HTML (e.g., /default.html or /page)
        // But skip known non-HTML extensions
        val nonHtmlExts = listOf(
            ".swf", ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg",
            ".css", ".js", ".json", ".xml", ".txt", ".pdf", ".doc", ".zip",
            ".rar", ".7z", ".tar", ".gz", ".mp3", ".mp4", ".avi", ".mov",
            ".wmv", ".flv", ".wav", ".ogg", ".ttf", ".woff", ".woff2", ".eot",
            ".ico", ".cur", ".map", ".woff", ".otf"
        )
        for (ext in nonHtmlExts) {
            if (url.lowercase().contains(ext)) {
                return false
            }
        }
        return true
    }

    /**
     * Serve a Ruffle asset file from the local assets directory.
     */
    @SuppressLint("DiscouragedApi")
    private fun serveRuffleAsset(path: String): WebResourceResponse? {
        val assetPath = RUFFLE_ASSET_PATH + path.substringAfter(RUFFLE_URL_PREFIX)
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val mimeType = guessMimeType(assetPath)
            WebResourceResponse(mimeType, "UTF-8", inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Ruffle asset: $assetPath", e)
            WebResourceResponse("text/plain", "UTF-8",
                ByteArrayInputStream("Asset not found: $assetPath".toByteArray()))
        }
    }

    /**
     * Download an HTML page, inject Ruffle scripts, and return as WebResourceResponse.
     * This is done synchronously in the WebView thread via shouldInterceptRequest.
     */
    private fun downloadAndInjectHtml(
        urlStr: String,
        uri: Uri
    ): WebResourceResponse? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")

            // Set Referer for 17roco resources
            if (urlStr.contains("17roco.qq.com")) {
                connection.setRequestProperty("Referer", REFERER_FOR_RESOURCES)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $responseCode for $urlStr")
                connection.disconnect()
                return null
            }

            // Detect encoding from Content-Type header or default to gb2312 for Chinese sites
            val contentType = connection.contentType ?: "text/html"
            var charset = parseCharsetFromContentType(contentType)
            if (charset == null) {
                // For Chinese sites, default to gb2312
                charset = "gb2312"
            }

            val inputStream = connection.inputStream
            val htmlBytes = inputStream.readBytes()
            inputStream.close()
            connection.disconnect()

            // Decode HTML with detected charset
            val htmlString = String(htmlBytes, Charset.forName(charset))

            // Try to detect actual encoding from meta tags
            val detectedCharset = detectCharsetFromMeta(htmlString)
            val finalHtml = if (detectedCharset != null && detectedCharset != charset) {
                // Re-decode with the charset from meta tag
                String(htmlBytes, Charset.forName(detectedCharset))
            } else {
                htmlString
            }

            // Inject Ruffle scripts
            val modifiedHtml = injectRuffleScripts(finalHtml)

            // Mark as injected
            injectedUrls.add(urlStr)

            // Return the modified HTML
            val mimeType = "text/html"
            val encoding = "UTF-8"
            WebResourceResponse(
                mimeType,
                encoding,
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/injecting HTML for $urlStr", e)
            null
        }
    }

    /**
     * Inject Ruffle script tags into the HTML.
     */
    private fun injectRuffleScripts(html: String): String {
        // Try to inject before </head>
        val headCloseIndex = html.lowercase().indexOf("</head>")
        return if (headCloseIndex != -1) {
            val before = html.substring(0, headCloseIndex)
            val after = html.substring(headCloseIndex)
            before + RUFFLE_INJECTION + after
        } else {
            // No </head> found, try to inject after <html> or at the beginning
            val htmlOpenIndex = html.lowercase().indexOf("<html")
            if (htmlOpenIndex != -1) {
                // Find the end of the <html...> tag
                val tagEndIndex = html.indexOf('>', htmlOpenIndex)
                if (tagEndIndex != -1) {
                    val before = html.substring(0, tagEndIndex + 1)
                    val after = html.substring(tagEndIndex + 1)
                    before + "<head>" + RUFFLE_INJECTION + "</head>" + after
                } else {
                    RUFFLE_INJECTION + html
                }
            } else {
                RUFFLE_INJECTION + html
            }
        }
    }

    /**
     * Parse charset from Content-Type header.
     */
    private fun parseCharsetFromContentType(contentType: String): String? {
        val parts = contentType.split(";")
        for (part in parts) {
            val trimmed = part.trim().lowercase()
            if (trimmed.startsWith("charset=")) {
                return trimmed.substring(8).trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }
        return null
    }

    /**
     * Detect charset from HTML meta tags.
     */
    private fun detectCharsetFromMeta(html: String): String? {
        // Match <meta charset="xxx"> or <meta http-equiv="Content-Type" content="...charset=xxx">
        val metaCharsetRegex = Regex("""<meta[^>]+charset=["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
        val match = metaCharsetRegex.find(html)
        return match?.groupValues?.get(1)
    }

    /**
     * Guess MIME type from file extension.
     */
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
            else -> "application/octet-stream"
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.d(TAG, "Page started: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "Page finished: $url")
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        val url = request.url.toString()

        // Handle /__ruffle/* URLs - prevent navigation, serve from assets
        if (url.contains("/__ruffle/")) {
            return true
        }

        return false
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        executor.shutdown()
    }
}

/** Desktop User-Agent string */
const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
