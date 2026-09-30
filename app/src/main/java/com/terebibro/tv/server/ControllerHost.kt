package com.terebibro.tv.server

import com.terebibro.tv.web.BrowserState

/**
 * The application-side operations the control API needs, implemented by
 * [com.terebibro.tv.MainActivity]. Every method must be safe to call from a
 * server thread (the implementation hops to the UI thread itself).
 */
interface ControllerHost {

    fun buildState(): BrowserState?

    fun currentPin(): String?

    fun issuePin(): String?

    fun clearPin()

    fun pairedCount(): Int

    fun openUrl(url: String, setHome: Boolean): ApiResult

    fun setHomeUrl(url: String): ApiResult

    /** [action] is one of home/back/forward/reload/stop. */
    fun navAction(action: String): ApiResult

    fun restartWebView(): ApiResult

    fun clearCache(): ApiResult

    fun clearSiteData(): ApiResult

    fun setDisplay(fullscreen: Boolean?, keepAwake: Boolean?): ApiResult

    fun dpad(key: String): ApiResult

    fun setDeviceName(name: String): ApiResult

    fun setControllerPort(port: Int): ApiResult
}
