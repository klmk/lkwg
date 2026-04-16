package com.roco.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ruffleWebViewClient: RuffleWebViewClient

    companion object {
        private const val TARGET_URL = "https://17roco.qq.com/default.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Lock to portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        setContentView(R.layout.activity_main)

        urlText = findViewById(R.id.url_text)
        progressBar = findViewById(R.id.progress_bar)
        webView = findViewById(R.id.webview)

        // Initialize CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Configure WebView settings
        configureWebViewSettings(webView.settings)

        // Set up Ruffle WebView client
        ruffleWebViewClient = RuffleWebViewClient(this)
        webView.webViewClient = ruffleWebViewClient

        // Set up Chrome client for progress and console
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
        }

        // Load the target URL
        webView.loadUrl(TARGET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(settings: WebSettings) {
        // Enable JavaScript
        settings.javaScriptEnabled = true

        // Enable DOM storage
        settings.domStorageEnabled = true

        // Enable database storage
        settings.databaseEnabled = true

        // Mixed content mode - allow all for compatibility with HTTP resources
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Set desktop User-Agent
        settings.userAgentString = DESKTOP_USER_AGENT

        // Enable wide viewport and proper scaling
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Enable zoom
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Allow file access for local assets
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Cache settings
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Text encoding
        settings.defaultTextEncodingName = "gb2312"

        // Allow file access from file URLs (needed for local asset loading)
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // Media playback
        settings.mediaPlaybackRequiresUserGesture = false

        // Block network images initially (set to false to load them)
        settings.blockNetworkImage = false

        // Enable geolocation if needed
        settings.setGeolocationEnabled(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle back button for WebView navigation
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        webView.destroy()
        ruffleWebViewClient.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
