package com.betterwebtv.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class WebTvViewHelper(
    private val context: Context,
    private val webView: WebView,
    private val fullscreenContainer: FrameLayout,
    private val listener: Listener
) {
    interface Listener {
        fun onPageStarted(url: String)
        fun onVideoPlaying()
        fun onPageFinished(url: String)
        fun onNetworkError(description: String)
        fun onPlaybackStalled(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fullscreenScript: String by lazy {
        context.assets.open("webtv_fullscreen.js").bufferedReader().use { it.readText() }
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingLoadRunnable: Runnable? = null
    private var lastStallSignalMs: Long = 0L

    @Volatile
    var isAutoFullscreenEnabled: Boolean = true
        private set

    @Volatile
    var currentUrl: String = ""
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_CHROME_UA
        }

        webView.setBackgroundColor(0xFF000000.toInt())
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        setWebViewFocusable(false)

        webView.addJavascriptInterface(Bridge(), "BetterWebTvBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val protectedMedia = request.resources.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                }.toTypedArray()
                if (protectedMedia.isNotEmpty()) {
                    request.grant(protectedMedia)
                } else {
                    request.deny()
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message().orEmpty()
                if (isAutoFullscreenEnabled && (
                        msg.contains("An unrecoverable error occurred", ignoreCase = true) ||
                            msg.contains("Buffer append error", ignoreCase = true)
                        )
                ) {
                    notifyStallDebounced(msg)
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url == "about:blank") return false
                val scheme = request.url?.scheme?.lowercase() ?: ""
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url.isNullOrBlank() || url == "about:blank") return
                currentUrl = url
                listener.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank() || url == "about:blank") return
                currentUrl = url
                flushCookies()
                injectFullscreenScript()
                applyAutoFullscreenState()
                listener.onPageFinished(currentUrl)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && request.url?.toString() != "about:blank") {
                    listener.onNetworkError(error?.description?.toString() ?: "網頁連線失敗")
                }
            }
        }
    }

    fun loadUrl(url: String) {
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        hideCustomView()
        currentUrl = url
        lastStallSignalMs = 0L

        // Release active MediaCodec/MediaSource decoder before navigating to next live stream
        runCatching {
            webView.evaluateJavascript(
                "try{document.querySelectorAll('video').forEach(function(v){v.pause();v.removeAttribute('src');v.load();});}catch(e){}",
                null
            )
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }

        val runnable = Runnable {
            pendingLoadRunnable = null
            webView.loadUrl(url)
        }
        pendingLoadRunnable = runnable
        mainHandler.postDelayed(runnable, 200L)
    }

    fun reload() {
        val urlToLoad = currentUrl.ifBlank { webView.url }.orEmpty()
        if (urlToLoad.isNotBlank() && urlToLoad != "about:blank") {
            loadUrl(urlToLoad)
        } else {
            hideCustomView()
            webView.reload()
        }
    }

    fun setAutoFullscreenMode(enabled: Boolean) {
        isAutoFullscreenEnabled = enabled
        setWebViewFocusable(!enabled)
        if (!enabled) {
            hideCustomView()
        }
        applyAutoFullscreenState()
    }

    fun setWebViewFocusable(focusable: Boolean) {
        webView.isFocusable = focusable
        webView.isFocusableInTouchMode = focusable
        if (!focusable) {
            webView.clearFocus()
        }
    }

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun hideCustomView() {
        customView?.let { view ->
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            customView = null
            runCatching { customViewCallback?.onCustomViewHidden() }
            customViewCallback = null
        }
    }

    fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    fun onPause() {
        flushCookies()
        webView.onPause()
    }

    fun destroy() {
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLoadRunnable = null
        flushCookies()
        hideCustomView()
        webView.stopLoading()
        webView.destroy()
    }

    private fun notifyStallDebounced(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastStallSignalMs < 5_000L) return
        lastStallSignalMs = now
        mainHandler.post { listener.onPlaybackStalled(reason) }
    }

    private fun injectFullscreenScript() {
        webView.evaluateJavascript(fullscreenScript, null)
    }

    private fun applyAutoFullscreenState() {
        val flag = if (isAutoFullscreenEnabled) "true" else "false"
        webView.evaluateJavascript(
            "if (typeof window.__betterWebTvSetEnabled === 'function') { window.__betterWebTvSetEnabled($flag); }",
            null
        )
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onVideoPlaying() {
            mainHandler.post { listener.onVideoPlaying() }
        }

        @JavascriptInterface
        fun onPlaybackStalled(reason: String) {
            notifyStallDebounced(reason)
        }
    }

    companion object {
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
