package com.terebibro.tv.media

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * The media band's web page, as seen by [MediaController]. The controller owns
 * the source state; the surface owns the single [WebView].
 *
 * A no-op implementation is the default so the controller stays testable
 * without an Activity.
 */
interface MediaWebHost {
    /** Shows the page, loading [url] only when it differs from the current one. */
    fun show(url: String)

    /** Hides the page without destroying it, so cookies and history survive. */
    fun hide()

    /** Destroys the WebView. Idempotent. */
    fun destroy()

    /**
     * Toggles D-pad reachability. Split mode leaves the page inert so the
     * remote keeps driving the QMS page; `MEDIA_ONLY` makes it navigable.
     */
    fun setFocusable(focusable: Boolean)

    /** Gives the page keyboard focus. A no-op before the first [show]. */
    fun requestFocus()

    /** Pauses the page's timers and media. Safe when absent or already paused. */
    fun pause()

    /**
     * Resumes the page. A no-op unless it is actually showing: a hidden page was
     * paused because another source owns the band and must stay paused.
     */
    fun resume()

    val isShowing: Boolean
}

/** Used when the controller is built without a media band (tests, headless). */
object NoopMediaWebHost : MediaWebHost {
    override fun show(url: String) = Unit
    override fun hide() = Unit
    override fun destroy() = Unit
    override fun setFocusable(focusable: Boolean) = Unit
    override fun requestFocus() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override val isShowing: Boolean = false
}

/**
 * The second renderer of the media band. Created lazily on the first web
 * source and reused afterwards, because a WebView is expensive to build and a
 * Jellyfin login must survive switching sources.
 *
 * Hardening mirrors [com.terebibro.tv.web.WebViewController] exactly: no
 * JavaScript interface, http(s) only, and SSL errors cancel. It is a separate
 * WebView from the QMS page, so neither document can reach the other's bridge
 * (and this one has none).
 *
 * Every method runs on the UI thread; the controller's doc contract says all of
 * its methods do, and the lifecycle calls come from `MainActivity`.
 */
class MediaWebSurface(
    private val activity: Activity,
    private val container: FrameLayout,
    /**
     * Reported when the page will not load (a main-frame error or a dead
     * renderer). The controller owns the source state and takes the band back.
     */
    private val onLoadFailed: () -> Unit = {}
) : MediaWebHost {

    private var view: WebView? = null
    private var currentUrl = ""
    private var focusable = false

    override val isShowing: Boolean
        get() = view?.visibility == View.VISIBLE

    override fun show(url: String) {
        val web = view ?: create().also { view = it }
        val wasHidden = web.visibility != View.VISIBLE
        web.visibility = View.VISIBLE
        applyFocusable(web)
        if (url.isNotEmpty() && url != currentUrl) {
            currentUrl = url
            web.loadUrl(url)
        }
        // A hidden page was paused in hide(); bringing it back — with a new URL
        // or the same one — resumes its timers and any media it was playing.
        if (wasHidden) {
            try {
                web.onResume()
            } catch (e: Exception) {
                // Nothing to resume.
            }
        }
    }

    override fun hide() {
        val web = view ?: return
        // Leaving the source is also leaving the page: stop its timers and any
        // playing media, but keep the instance (and its cookies) for next time.
        try {
            web.onPause()
        } catch (e: Exception) {
            // A destroyed renderer has nothing to pause.
        }
        web.visibility = View.GONE
    }

    override fun destroy() {
        val web = view ?: return
        view = null
        currentUrl = ""
        try {
            web.onPause()
        } catch (e: Exception) {
            // Nothing to pause.
        }
        container.removeView(web)
        try {
            web.destroy()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    override fun setFocusable(focusable: Boolean) {
        this.focusable = focusable
        view?.let { applyFocusable(it) }
    }

    override fun requestFocus() {
        view?.requestFocus()
    }

    override fun pause() {
        try {
            view?.onPause()
        } catch (e: Exception) {
            // Destroyed; nothing to pause.
        }
    }

    override fun resume() {
        // A hidden page must stay paused. [hide] stopped its timers and any
        // media it was playing because another source took the band; resuming
        // it on the next foregrounding would restart that audio behind the
        // new source (autoplay is explicitly enabled). Gated here as well as
        // at the call site, so no future caller can undo it by accident.
        if (!isShowing) return
        try {
            view?.onResume()
        } catch (e: Exception) {
            // Destroyed; nothing to resume.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val web = WebView(activity)
        web.settings.apply {
            javaScriptEnabled = true
            // A Jellyfin login lives in localStorage; it must survive.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        // No addJavascriptInterface anywhere in this class: there is nothing
        // for a page to call, and the QMS bridge is a different WebView.
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        // Cookies are accepted by default; the login persists for the process
        // lifetime without any extra configuration.
        web.webViewClient = HardenedWebViewClient()
        // Parity with the QMS view: a signage page gets no camera, microphone
        // or geolocation, and it cannot write arbitrary strings to logcat.
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
        }
        container.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return web
    }

    private fun applyFocusable(web: WebView) {
        web.isFocusable = focusable
        web.isFocusableInTouchMode = focusable
    }

    private inner class HardenedWebViewClient : WebViewClient() {

        /**
         * Non-http(s) navigation is refused. This is the same rule the URL
         * validator applies to the entry point, enforced again at every hop so
         * a redirect or an in-page link cannot leave the web.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase()
            return !(scheme == "http" || scheme == "https")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
        }

        /**
         * A main-frame load failure is reported so the controller can take the
         * dead page down and let the folder or playlist take the band. Without
         * it the surface would sit on the WebView's own error page forever and
         * every queue reload would be ignored because `source = web`.
         */
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            onLoadFailed()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The process is gone; drop the dead view so the next show builds a
            // fresh one instead of loading into a dead renderer.
            val wasCurrent = this@MediaWebSurface.view === view
            if (wasCurrent) {
                this@MediaWebSurface.view = null
                currentUrl = ""
            }
            container.removeView(view)
            try {
                view.destroy()
            } catch (e: Exception) {
                // Already gone.
            }
            // A dead renderer is a failed page, not a silent disappearance.
            if (wasCurrent) onLoadFailed()
            return true
        }
    }
}
