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

class RuffleWebViewClient(private val context: Context) : WebViewClient() {

    companion object {
        private const val TAG = "RuffleWebViewClient"
        private const val RUFFLE_ASSET_PATH = "ruffle/"
        private const val RUFFLE_URL_PREFIX = "/__ruffle/"
        private const val REFERER_FOR_RESOURCES = "https://web2.17roco.qq.com/"
    }

    private val injectedUrls = mutableSetOf<String>()

    private val ruffleInjection: String
        get() {
            return "<script src=\"/__ruffle/ruffle.js\"></script>\n" +
                "<script>\n" +
                "window.addEventListener(\"DOMContentLoaded\", function() {\n" +
                "  if (window.RufflePlayer) {\n" +
                "    window.RufflePlayer.config = {\n" +
                "      \"publicPath\": \"/__ruffle/\",\n" +
                "      \"polyfills\": true,\n" +
                "      \"allowScriptAccess\": true\n" +
                "    };\n" +
                "    window.RufflePlayer.newest().autoEnable();\n" +
                "  }\n" +
                "});\n" +
                "</script>\n"
        }

    @SuppressLint("DiscouragedApi")
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val urlStr = request.url.toString()
        val path = request.url.path ?: ""

        // Serve Ruffle assets from local
        if (path.startsWith(RUFFLE_URL_PREFIX)) {
            return serveRuffleAsset(path)
        }

        // Intercept main frame HTML for Ruffle injection
        if (request.isForMainFrame && isHtmlUrl(urlStr) && !injectedUrls.contains(urlStr)) {
            return downloadAndInjectHtml(urlStr)
        }

        // Proxy all cross-origin requests to bypass CORS
        // This is critical for Ruffle to load SWF and other resources
        if (isCrossOrigin(urlStr)) {
            return proxyRequest(request)
        }

        return null
    }

    @SuppressLint("DiscouragedApi")
    private fun serveRuffleAsset(path: String): WebResourceResponse? {
        val assetPath = RUFFLE_ASSET_PATH + path.substringAfter(RUFFLE_URL_PREFIX)
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val mimeType = guessMimeType(assetPath)
            WebResourceResponse(mimeType, "UTF-8", inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Ruffle asset: $assetPath", e)
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
                conn.setRequestProperty("Referer", REFERER_FOR_RESOURCES)
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return null
            }

            val contentType = conn.contentType ?: "text/html"
            var charset = parseCharset(contentType) ?: "gb2312"

            val htmlBytes = conn.inputStream.readBytes()
            conn.inputStream.close()
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

            WebResourceResponse(
                "text/html", "UTF-8",
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting HTML for $urlStr", e)
            null
        }
    }

    private fun injectRuffleScripts(html: String): String {
        val headClose = html.lowercase().indexOf("</head>")
        if (headClose != -1) {
            return html.substring(0, headClose) + ruffleInjection + html.substring(headClose)
        }
        return ruffleInjection + html
    }

    private fun isCrossOrigin(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return false
            // Proxy requests to resource domains that Ruffle needs
            val resourceHosts = listOf(
                "res.17roco.qq.com",
                "web2.17roco.qq.com",
                "17roco.qq.com",
                "ossweb-img.qq.com",
                "qzs.qq.com",
                "pingjs.qq.com"
            )
            return resourceHosts.any { host == it || host.endsWith("." + it) }
        } catch (e: Exception) {
            return false
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun proxyRequest(request: WebResourceRequest): WebResourceResponse? {
        val urlStr = request.url.toString()
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")

            // Set proper Referer for resource requests
            if (urlStr.contains("res.17roco.qq.com")) {
                conn.setRequestProperty("Referer", REFERER_FOR_RESOURCES)
            } else if (urlStr.contains("17roco.qq.com")) {
                conn.setRequestProperty("Referer", "https://17roco.qq.com/")
            }

            // Forward request headers
            for ((key, value) in request.requestHeaders) {
                if (key.equals("Range", ignoreCase = true)) {
                    conn.setRequestProperty(key, value)
                }
            }

            val responseCode = conn.responseCode

            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location != null) {
                    // Follow redirect by making a new request
                    return try {
                        val redirectUrl = URL(location)
                        val redirectConn = redirectUrl.openConnection() as HttpURLConnection
                        redirectConn.connectTimeout = 15000
                        redirectConn.readTimeout = 15000
                        redirectConn.instanceFollowRedirects = true
                        redirectConn.setRequestProperty("User-Agent", Constants.DESKTOP_USER_AGENT)
                        redirectConn.setRequestProperty("Accept", "*/*")
                        redirectConn.setRequestProperty("Accept-Encoding", "identity")
                        if (location.contains("res.17roco.qq.com")) {
                            redirectConn.setRequestProperty("Referer", REFERER_FOR_RESOURCES)
                        }
                        val ct = redirectConn.contentType ?: "application/octet-stream"
                        val enc = redirectConn.contentEncoding
                        val mt = ct.split(";")[0].trim()
                        WebResourceResponse(mt, enc, redirectConn.inputStream)
                    } catch (e: Exception) {
                        Log.e(TAG, "Redirect proxy error for $location", e)
                        null
                    }
                }
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Proxy HTTP $responseCode for $urlStr")
                conn.disconnect()
                return null
            }

            val contentType = conn.contentType ?: "application/octet-stream"
            val encoding = conn.contentEncoding
            val mimeType = contentType.split(";")[0].trim()
            val inputStream: InputStream = conn.inputStream

            WebResourceResponse(mimeType, encoding, inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Proxy error for $urlStr", e)
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

    private fun isHtmlUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") ||
            lower.endsWith(".asp") || lower.endsWith(".jsp") || lower.endsWith("/")) {
            return true
        }
        val nonHtmlExts = listOf(
            ".swf", ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg",
            ".css", ".js", ".json", ".xml", ".txt", ".pdf", ".zip", ".mp3", ".mp4",
            ".woff", ".woff2", ".ttf", ".eot", ".ico", ".map", ".wasm"
        )
        for (ext in nonHtmlExts) {
            if (lower.contains(ext)) return false
        }
        return true
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
            else -> "application/octet-stream"
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Log.d(TAG, "Page started: $url")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        Log.d(TAG, "Page finished: $url")
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        if (request.url.toString().contains("/__ruffle/")) {
            return true
        }
        return false
    }
}
