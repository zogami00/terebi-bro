package com.terebibro.tv.web

/**
 * The single immutable state shape shared by `GET /api/state` and the
 * WebSocket `state` broadcast.
 */
data class BrowserState(
    val url: String,
    val title: String,
    val loading: Boolean,
    val progress: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val homeUrl: String,
    val fullscreen: Boolean,
    val keepAwake: Boolean,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val network: String,
    val webViewVersion: String,
    val webViewOutdated: Boolean,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int,
    val uptimeMs: Long,
    val seq: Long
)
