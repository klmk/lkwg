package com.roco.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var debugPanel: ScrollView
    private lateinit var debugText: TextView
    private lateinit var debugToggle: Button
    private var socketProxies = mutableListOf<SocketProxyServer>()
    private val debugLines = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var reloadCount = 0
    private val MAX_RELOADS = 3

    companion object {
        private const val TAG = "RocoApp"
        private const val TARGET_URL = "https://17roco.qq.com/default.html"

        // Socket proxy mappings: listenPort -> targetHost:targetPort
        // Socket proxy routes: local port -> target game server
        private val PROXY_ROUTES = listOf(
            Triple(9000, "172.25.40.120", 9000),
            Triple(9100, "172.25.40.120", 9100),
            Triple(9101, "172.25.40.120", 9101),
            Triple(19000, "172.25.40.121", 9000),
            Triple(19001, "172.25.40.122", 9000),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== onCreate started ===")

        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        // Enable experimental WebView features for better WASM support
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        } catch (_: Exception) {}

        urlText = findViewById(R.id.url_text)
        progressBar = findViewById(R.id.progress_bar)
        webView = findViewById(R.id.webview)
        debugPanel = findViewById(R.id.debug_panel)
        debugText = findViewById(R.id.debug_text)
        debugToggle = findViewById(R.id.debug_toggle)

        // Debug toggle
        debugToggle.setOnClickListener {
            if (debugPanel.visibility == View.VISIBLE) {
                debugPanel.visibility = View.GONE
            } else {
                debugPanel.visibility = View.VISIBLE
                updateDebugText()
            }
        }

        // Cookie
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebView settings
        configureWebViewSettings(webView.settings)

        // Start socket proxy FIRST
        Log.d(TAG, "=== Starting SocketProxy servers ===")
        for ((listenPort, targetHost, targetPort) in PROXY_ROUTES) {
            try {
                Log.d(TAG, "Starting proxy :$listenPort -> $targetHost:$targetPort")
                val proxy = SocketProxyServer(listenPort, targetHost, targetPort)
                proxy.isReuseAddr = true
                proxy.start()
                socketProxies.add(proxy)
                addDebugLine("✅ Proxy :$listenPort -> $targetHost:$targetPort started")
                Log.d(TAG, "Proxy :$listenPort started OK")
            } catch (e: Exception) {
                addDebugLine("❌ Proxy :$listenPort failed: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Proxy :$listenPort FAILED", e)
            }
        }
        Log.d(TAG, "=== SocketProxy servers done ===")

        // Ruffle injection client with debug logging
        webView.webViewClient = RuffleWebViewClient(this, object : DebugLogger {
            override fun log(msg: String) {
                addDebugLine(msg)
            }
        })

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
                    val logLine = "[${message.messageLevel()}] $level"
                    Log.d(TAG, "CONSOLE: $logLine")
                    addDebugLine(logLine)
                    // Also write to file as backup
                    try {
                        val logFile = File(filesDir, "webconsole.log")
                        FileWriter(logFile, true).use { writer ->
                            writer.write("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $logLine\n")
                        }
                    } catch (_: Exception) {}

                    // Detect Ruffle crash - log it but don't auto-reload
                    // (reload loses game state and goes back to login page)
                    if (level.contains("unreachable") && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.w(TAG, "Ruffle unreachable error detected (known Ruffle bug)")
                        addDebugLine("⚠️ Ruffle crash: unreachable (known bug, see github.com/ruffle-rs/ruffle/issues/20990)")
                    }
                }
                return true
            }
        }

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
        socketProxies.forEach { it.shutdown() }
        webView.destroy()
        super.onDestroy()
    }

    // === Debug panel ===

    @Synchronized
    private fun addDebugLine(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        debugLines.add("[$time] $line")
        // Keep last 200 lines
        if (debugLines.size > 200) {
            debugLines.removeAt(0)
        }
        // Update UI if panel is visible
        if (debugPanel.visibility == View.VISIBLE) {
            handler.post { updateDebugText() }
        }
    }

    private fun updateDebugText() {
        val sb = StringBuilder()
        // Show last 50 lines
        val start = maxOf(0, debugLines.size - 50)
        for (i in start until debugLines.size) {
            sb.append(debugLines[i]).append("\n")
        }
        debugText.text = sb.toString()
        // Auto-scroll to bottom
        debugPanel.post { debugPanel.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

/**
 * Interface for RuffleWebViewClient to send debug logs to MainActivity
 */
interface DebugLogger {
    fun log(msg: String)
}
