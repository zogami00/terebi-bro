package com.terebibro.tv.server

import com.terebibro.tv.web.BrowserState
import org.json.JSONArray
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
        obj.put("webViewOutdated", state.webViewOutdated)
        obj.put("appVersion", state.appVersion)
        obj.put("androidVersion", state.androidVersion)
        obj.put("sdkInt", state.sdkInt)
        obj.put("uptimeMs", state.uptimeMs)
        obj.put("seq", state.seq)
        obj.put("extraNetworks", JSONArray(state.extraNetworks))
        obj.put("mediaEnabled", state.mediaEnabled)
        obj.put("mediaMode", state.mediaMode)
        obj.put("mediaSource", state.mediaSource)
        obj.put("mediaItem", state.mediaItem)
        obj.put("mediaIndex", state.mediaIndex)
        obj.put("mediaCount", state.mediaCount)
        obj.put("mediaPaused", state.mediaPaused)
        obj.put("mediaError", state.mediaError)
        obj.put("qmsNow", state.qmsNow)
        obj.put("qmsLocation", state.qmsLocation)
        obj.put("qmsDetectionHealthy", state.qmsDetectionHealthy)
        obj.put("qmsLastCallAtMs", state.qmsLastCallAtMs)
        obj.put("documentStartSupported", state.documentStartSupported)
        obj.put("mediaLocalFiles", state.mediaLocalFiles)
        obj.put("mediaLocalBytes", state.mediaLocalBytes)
        obj.put("mediaFreeBytes", state.mediaFreeBytes)
        obj.put("playlistLastError", state.playlistLastError)
        obj.put("playlistLastFetchMs", state.playlistLastFetchMs)
        obj.put("uiLanguage", state.uiLanguage)
        obj.put("screenWidthDp", state.screenWidthDp)
        obj.put("screenHeightDp", state.screenHeightDp)
        obj.put("densityDpi", state.densityDpi)
        obj.put("renderScale", state.renderScale.toDouble())
        return obj
    }
}
