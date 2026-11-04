package com.terebibro.tv.web

import android.webkit.JavascriptInterface
import com.terebibro.tv.server.MainThreadBridge

/**
 * The content-free `@JavascriptInterface` sink the injected QMS script talks to.
 *
 * It deliberately holds nothing but [callback]: no token, no URL, no command.
 * A hostile page that reaches the WebView can post one of the five fixed signal
 * kinds and nothing else, so the worst case is a nuisance mode flip, never a
 * credential leak or navigation. Every failure is swallowed — an exception must
 * never escape back into JavaScript.
 *
 * [signal] takes a nullable `kind` on purpose: the parameter is annotated with
 * a null check in the generated bridge, so a page calling `signal()` with no
 * argument would otherwise throw before the body runs and crack the sink.
 *
 * The interface is registered per WebView, not per origin; the origin gate
 * therefore lives inside the injected script (see `QmsScript`).
 */
class QmsBridge(private val callback: (String, String?) -> Unit) {

    @JavascriptInterface
    fun signal(kind: String?, value: String?) {
        try {
            if (kind == null) return
            MainThreadBridge.post {
                try {
                    callback(kind, value)
                } catch (t: Throwable) {
                    // A controller failure is not the page's problem.
                }
            }
        } catch (t: Throwable) {
            // The sink must never throw into JavaScript.
        }
    }
}
