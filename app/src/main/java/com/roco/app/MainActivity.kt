package com.roco.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private lateinit var progressBar: ProgressBar
    private var socketProxy: SocketProxyServer? = null

    companion object {
        private const val TAG = "RocoApp"
        private const val TARGET_URL = "https://17roco.qq.com/default.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        urlText = findViewById(R.id.url_text)
        progressBar = findViewById(R.id.progress_bar)
        webView = findViewById(R.id.webview)

        // Cookie
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebView settings
        configureWebViewSettings(webView.settings)

        // Ruffle injection client
        webView.webViewClient = RuffleWebViewClient(this)

        // Start local WebSocket-to-TCP proxy for game socket connections
        startSocketProxy()

        // Progress bar + Console logging
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
                urlText.text = view?.url ?: TARGET_URL
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message != null) {
                    val level = message.message()
                    val source = message.sourceId()
                    val lineNum = message.lineNumber()
                    val logLine = "[$source:$lineNum] $level"
                    Log.d(TAG, "CONSOLE: $logLine")
                    try {
                        val logFile = File(filesDir, "webconsole.log")
                        FileWriter(logFile, true).use { writer ->
                            writer.write(logLine + "\n")
                        }
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        // Write proxy status to webconsole.log so we can read it via ADB
        val proxyStatus = if (socketProxy != null) "SocketProxy OK: ws://127.0.0.1:8765" else "SocketProxy FAILED"
        try {
            val logFile = File(filesDir, "webconsole.log")
            FileWriter(logFile, true).use { writer ->
                writer.write("[MainActivity] $proxyStatus\n")
            }
        } catch (_: Exception) {}

        webView.loadUrl(TARGET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = Constants.DESKTOP_USER_AGENT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.defaultTextEncodingName = "gb2312"
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.blockNetworkImage = false
        settings.setGeolocationEnabled(false)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        socketProxy?.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Start local WebSocket server that bridges Ruffle's WebSocket connections
     * to real TCP game servers (e.g., 172.25.*:9000).
     * This bypasses the browser sandbox limitation where WASM cannot create raw TCP sockets.
     */
    private fun startSocketProxy() {
        try {
            socketProxy = SocketProxyServer(8765)
            socketProxy?.isReuseAddr = true
            socketProxy?.start()
            val msg = "Socket proxy started on ws://127.0.0.1:8765"
            Log.d(TAG, msg)
            writeDebugLog(msg)
        } catch (e: Exception) {
            val msg = "Failed to start socket proxy: ${e.message}"
            Log.e(TAG, msg, e)
            writeDebugLog(msg)
        }
    }

    private fun writeDebugLog(msg: String) {
        try {
            val logFile = File(filesDir, "debug.log")
            FileWriter(logFile, true).use { writer ->
                writer.write("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
            }
        } catch (_: Exception) {}
    }
}
