package com.terebibro.tv.server

import com.terebibro.tv.web.BrowserState
import org.json.JSONObject

/** Serializes the shared [BrowserState] snapshot; used by REST and WebSocket. */
object StateJson {

    fun toJson(state: BrowserState): JSONObject {
        val obj = JSONObject()
        obj.put("url", state.url)
        obj.put("title", state.title)
        obj.put("loading", state.loading)
        obj.put("progress", state.progress)
        obj.put("canGoBack", state.canGoBack)
        obj.put("canGoForward", state.canGoForward)
        obj.put("homeUrl", state.homeUrl)
        obj.put("fullscreen", state.fullscreen)
        obj.put("keepAwake", state.keepAwake)
        obj.put("deviceName", state.deviceName)
        obj.put("ip", state.ip)
        obj.put("port", state.port)
        obj.put("network", state.network)
        obj.put("webViewVersion", state.webViewVersion)
        obj.put("appVersion", state.appVersion)
        obj.put("androidVersion", state.androidVersion)
        obj.put("sdkInt", state.sdkInt)
        obj.put("uptimeMs", state.uptimeMs)
        obj.put("seq", state.seq)
        return obj
    }
}
