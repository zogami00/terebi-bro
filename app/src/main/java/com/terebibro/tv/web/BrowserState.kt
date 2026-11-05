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
    val seq: Long,
    /** Extra trusted client networks beyond the device's own subnet. */
    val extraNetworks: List<String> = emptyList(),
    // Media-mode diagnostics. Published even while media mode is off, so a
    // controller can see the switch state and the device class without
    // inferring either from how the text looks.
    val mediaEnabled: Boolean = false,
    val mediaMode: String = "qms_only",
    val mediaSource: String = "",
    val mediaItem: String = "",
    val mediaIndex: Int = 0,
    val mediaCount: Int = 0,
    val mediaPaused: Boolean = false,
    val mediaError: String = "",
    val qmsNow: String = "",
    val qmsLocation: String = "",
    val qmsDetectionHealthy: Boolean = false,
    val qmsLastCallAtMs: Long = 0L,
    val documentStartSupported: Boolean = false,
    val mediaLocalFiles: Int = 0,
    val mediaLocalBytes: Long = 0L,
    val mediaFreeBytes: Long = 0L,
    /** Why the last playlist fetch failed; empty when the last one succeeded. */
    val playlistLastError: String = "",
    /** How long the last playlist fetch took, in ms; 0 when never fetched. */
    val playlistLastFetchMs: Long = 0L,
    val uiLanguage: String = "ms",
    val screenWidthDp: Int = 0,
    val screenHeightDp: Int = 0,
    val densityDpi: Int = 0,
    val renderScale: Float = 1.0f
)
