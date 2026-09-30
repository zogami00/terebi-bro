package com.terebibro.tv.web

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.terebibro.tv.server.MainThreadBridge
import com.terebibro.tv.util.SafeLog

/**
 * Owns the WebView and performs every WebView operation on the UI thread.
 *
 * Public methods are safe to call from server threads: they hop to the main
 * thread through the listener's activity and return null on timeout.
 */
class WebViewController(
    private val activity: Activity,
    private val listener: Listener,
    private val homeUrlProvider: () -> String = { "" }
) {

    interface Listener {
        fun onWebStateChanged()
        fun onWebProgress(progress: Int)
        fun onWebPageStarted(url: String?)
        fun onWebPageFinished(url: String?)
        fun onWebPageError(description: String)
        fun onWebViewRestarted()
        fun onRemoteBack(): Boolean
        /** Must be called on the UI thread. */
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
    }

    data class WebSnapshot(
        val url: String,
        val title: String,
        val loading: Boolean,
        val progress: Int,
        val canGoBack: Boolean,
        val canGoForward: Boolean
    )

    sealed class Outcome {
        data class Ok(val url: String) : Outcome()
        data class Error(val code: String, val message: String) : Outcome()
    }

    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var homeUrlFallback: String = ""

    private var loading = false
    private var progress = 0
    private var title = ""

    /** Creates the WebView and adds it to [container]. Must run on the UI thread. */
    fun attach(container: FrameLayout, homeUrl: String) {
        this.container = container
        this.homeUrlFallback = homeUrl
        val view = createWebView()
        webView = view
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Must run on the UI thread. */
    fun detach() {
        listener.onHideCustomView()
        webView?.let { view ->
            view.onPause()
            container?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    /** Must run on the UI thread. */
    fun snapshot(): WebSnapshot {
        val view = webView
        return WebSnapshot(
            url = view?.url ?: homeUrlFallback,
            title = title,
            loading = loading,
            progress = progress,
            canGoBack = view?.canGoBack() ?: false,
            canGoForward = view?.canGoForward() ?: false
        )
    }

    /** Must run on the UI thread. */
    fun currentUrl(): String = webView?.url ?: homeUrlFallback

    /** Must run on the UI thread. */
    fun canGoBackOnMain(): Boolean = webView?.canGoBack() ?: false

    /** Restores WebView focus after an overlay is dismissed; any thread. */
    fun requestWebFocus() {
        dispatch { webView?.requestFocus() }
    }

    /** Pauses page timers/media when the Activity is backgrounded; any thread. */
    fun pause() {
        dispatch { webView?.onPause() }
    }

    /** Resumes page timers/media when the Activity returns; any thread. */
    fun resume() {
        dispatch { webView?.onResume() }
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    fun navigate(rawUrl: String): Outcome {
        return when (val validated = UrlValidator.validate(rawUrl)) {
            is UrlValidator.Result.Invalid ->
                Outcome.Error("invalid_url", "Rejected URL (${validated.error})")
            is UrlValidator.Result.Valid ->
                if (load(validated.url)) Outcome.Ok(validated.url)
                else Outcome.Error("webview_not_ready", "WebView is not ready")
        }
    }

    /** Must run on the UI thread. */
    fun loadOnMain(url: String): Boolean {
        val view = webView ?: return false
        view.loadUrl(url)
        listener.onWebStateChanged()
        return true
    }

    private fun load(url: String): Boolean = dispatch {
        webView?.loadUrl(url)
        listener.onWebStateChanged()
        true
    } ?: false

    fun goBack(): Boolean = dispatch {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
            listener.onWebStateChanged()
            true
        } else {
            false
        }
    } ?: false

    fun goForward(): Boolean = dispatch {
        val view = webView
        if (view != null && view.canGoForward()) {
            view.goForward()
            listener.onWebStateChanged()
            true
        } else {
            false
        }
    } ?: false

    fun reload(): Boolean = dispatch {
        webView?.reload()
        listener.onWebStateChanged()
        true
    } ?: false

    fun stop(): Boolean = dispatch {
        webView?.stopLoading()
        listener.onWebStateChanged()
        true
    } ?: false

    fun goHome(homeUrl: String): Boolean = dispatch {
        webView?.loadUrl(homeUrl)
        listener.onWebStateChanged()
        true
    } ?: false

    /** Must run on the UI thread. */
    fun restartOnMain(homeUrl: String): Boolean {
        homeUrlFallback = homeUrl
        val current = webView?.url ?: homeUrl
        rebuild(current)
        listener.onWebViewRestarted()
        listener.onWebStateChanged()
        return true
    }

    /** Must run on the UI thread. */
    fun clearCacheOnMain(): Boolean {
        webView?.clearCache(true)
        listener.onWebStateChanged()
        return true
    }

    /** Must run on the UI thread: cookies + WebStorage + cache. */
    @SuppressLint("SetJavaScriptEnabled")
    fun clearSiteDataOnMain(): Boolean {
        webView?.clearCache(true)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
        WebStorage.getInstance().deleteAllData()
        listener.onWebStateChanged()
        return true
    }

    // ---------------------------------------------------------------------
    // Remote control
    // ---------------------------------------------------------------------

    fun dispatchDpad(key: String): Outcome {
        val keyCode = when (key.lowercase()) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> {
                dispatch { listener.onRemoteBack() }
                return Outcome.Ok("")
            }
            else -> return Outcome.Error("bad_key", "Unknown D-pad key")
        }
        dispatch { sendKey(keyCode) }
        return Outcome.Ok("")
    }

    private fun sendKey(keyCode: Int) {
        val view = webView ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun <T> dispatch(block: () -> T): T? {
        if (MainThreadBridge.isMainThread()) return block()
        return MainThreadBridge.runOnMain { block() }
    }

    private fun createWebView(): WebView {
        val view = WebView(activity)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.webViewClient = TerebiWebViewClient()
        view.webChromeClient = TerebiWebChromeClient()
        view.requestFocus()
        return view
    }

    private fun rebuild(url: String) {
        val target = container ?: return
        // A fullscreen custom view would otherwise stay GONE underneath the new WebView.
        listener.onHideCustomView()
        webView?.let { old ->
            target.removeView(old)
            old.destroy()
        }
        val replacement = createWebView()
        webView = replacement
        target.addView(
            replacement,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        replacement.loadUrl(url)
        replacement.requestFocus()
    }

    private inner class TerebiWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            SafeLog.w(TAG, "Blocked navigation to scheme: $scheme")
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            SafeLog.e(TAG, "SSL error, cancelling")
            handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            SafeLog.e(TAG, "Render process gone; recreating WebView")
            val url = view.url ?: homeUrlProvider()
            rebuild(url)
            listener.onWebViewRestarted()
            listener.onWebStateChanged()
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            loading = true
            progress = 0
            listener.onWebPageStarted(url)
            listener.onWebStateChanged()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            progress = 100
            title = view.title ?: ""
            listener.onWebPageFinished(url)
            listener.onWebStateChanged()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val description = error.description?.toString() ?: "error"
            loading = false
            listener.onWebPageError(description)
            listener.onWebStateChanged()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            listener.onWebStateChanged()
        }
    }

    private inner class TerebiWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            listener.onWebProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            title = newTitle ?: ""
            listener.onWebStateChanged()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            listener.onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            listener.onHideCustomView()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            SafeLog.w(TAG, "Web permission request denied")
            request.deny()
        }
    }

    private companion object {
        const val TAG = "WebViewController"
    }
}
