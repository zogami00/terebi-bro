package com.terebibro.tv.media

import com.terebibro.tv.server.Json
import com.terebibro.tv.server.StateJson
import com.terebibro.tv.web.BrowserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StateJsonMediaTest {

    private val state = BrowserState(
        url = "https://example.com/",
        title = "Example",
        loading = false,
        progress = 100,
        canGoBack = false,
        canGoForward = false,
        homeUrl = "https://example.com/",
        fullscreen = true,
        keepAwake = true,
        deviceName = "terebi-tv",
        ip = "10.0.0.5",
        port = 8765,
        network = "wifi",
        webViewVersion = "com.google.android.webview 138.0",
        webViewOutdated = false,
        appVersion = "0.3.3",
        androidVersion = "9",
        sdkInt = 28,
        uptimeMs = 0L,
        seq = 0L
    )

    private fun parsed(): Map<String, Json.Value> = Json.parseObject(
        ByteArrayInputStream(StateJson.toJson(state).toString().toByteArray(Charsets.UTF_8)),
        STATE_KEYS,
        arrayKeys = setOf("extraNetworks")
    )

    @Test
    fun `every new media key is present with its documented default`() {
        val values = parsed()

        assertEquals(false, (values["mediaEnabled"] as Json.Value.Bool).value)
        assertEquals("qms_only", (values["mediaMode"] as Json.Value.Str).value)
        assertEquals("", (values["mediaSource"] as Json.Value.Str).value)
        assertEquals("", (values["mediaItem"] as Json.Value.Str).value)
        assertEquals(0.0, (values["mediaIndex"] as Json.Value.Num).value, 0.0)
        assertEquals(0.0, (values["mediaCount"] as Json.Value.Num).value, 0.0)
        assertEquals(false, (values["mediaPaused"] as Json.Value.Bool).value)
        assertEquals("", (values["mediaError"] as Json.Value.Str).value)
        assertEquals("", (values["qmsNow"] as Json.Value.Str).value)
        assertEquals("", (values["qmsLocation"] as Json.Value.Str).value)
        assertEquals(false, (values["qmsDetectionHealthy"] as Json.Value.Bool).value)
        assertEquals(0.0, (values["qmsLastCallAtMs"] as Json.Value.Num).value, 0.0)
        assertEquals(false, (values["documentStartSupported"] as Json.Value.Bool).value)
        assertEquals(0.0, (values["mediaLocalFiles"] as Json.Value.Num).value, 0.0)
        assertEquals(0.0, (values["mediaLocalBytes"] as Json.Value.Num).value, 0.0)
        assertEquals(0.0, (values["mediaFreeBytes"] as Json.Value.Num).value, 0.0)
        assertEquals("", (values["playlistLastError"] as Json.Value.Str).value)
        assertEquals(0.0, (values["playlistLastFetchMs"] as Json.Value.Num).value, 0.0)
        assertEquals("ms", (values["uiLanguage"] as Json.Value.Str).value)
        assertEquals(0.0, (values["screenWidthDp"] as Json.Value.Num).value, 0.0)
        assertEquals(0.0, (values["screenHeightDp"] as Json.Value.Num).value, 0.0)
        assertEquals(0.0, (values["densityDpi"] as Json.Value.Num).value, 0.0)
        assertEquals(1.0, (values["renderScale"] as Json.Value.Num).value, 0.0001)
    }

    @Test
    fun `the media fields carry the values from the state`() {
        val filled = state.copy(
            mediaEnabled = true,
            mediaMode = "split",
            mediaSource = "local",
            mediaItem = "promo.mp4",
            mediaIndex = 1,
            mediaCount = 4,
            mediaPaused = true,
            mediaError = "unsupported codec",
            qmsNow = "A1001",
            qmsLocation = "Kaunter 1",
            qmsDetectionHealthy = true,
            qmsLastCallAtMs = 1_700_000_000_000L,
            documentStartSupported = true,
            mediaLocalFiles = 4,
            mediaLocalBytes = 1_048_576L,
            mediaFreeBytes = 2_147_483_648L,
            playlistLastError = "HTTP 503",
            playlistLastFetchMs = 1_234L,
            uiLanguage = "ta",
            screenWidthDp = 1920,
            screenHeightDp = 1080,
            densityDpi = 320,
            renderScale = 1.0f
        )
        val values = Json.parseObject(
            ByteArrayInputStream(StateJson.toJson(filled).toString().toByteArray(Charsets.UTF_8)),
            STATE_KEYS,
            arrayKeys = setOf("extraNetworks")
        )

        assertEquals("split", (values["mediaMode"] as Json.Value.Str).value)
        assertEquals("promo.mp4", (values["mediaItem"] as Json.Value.Str).value)
        assertEquals(1.0, (values["mediaIndex"] as Json.Value.Num).value, 0.0)
        assertEquals(4.0, (values["mediaCount"] as Json.Value.Num).value, 0.0)
        assertEquals(
            1_700_000_000_000.0,
            (values["qmsLastCallAtMs"] as Json.Value.Num).value,
            0.0
        )
        assertEquals(1_048_576.0, (values["mediaLocalBytes"] as Json.Value.Num).value, 0.0)
        assertEquals("HTTP 503", (values["playlistLastError"] as Json.Value.Str).value)
        assertEquals(1_234.0, (values["playlistLastFetchMs"] as Json.Value.Num).value, 0.0)
        assertEquals("ta", (values["uiLanguage"] as Json.Value.Str).value)
        assertEquals(1920.0, (values["screenWidthDp"] as Json.Value.Num).value, 0.0)
        assertEquals(320.0, (values["densityDpi"] as Json.Value.Num).value, 0.0)
    }

    @Test
    fun `there is no qmsNext in the payload`() {
        // The QMS column is a call history, not a queue; the strip never invents
        // a queue position, so the key must not exist.
        assertEquals(false, STATE_KEYS.contains("qmsNext"))
        assertTrue(parsed().isNotEmpty())
    }

    private companion object {
        /** Exactly the keys StateJson emits, so the strict reader accepts it. */
        val STATE_KEYS = setOf(
            "url", "title", "loading", "progress", "canGoBack", "canGoForward",
            "homeUrl", "fullscreen", "keepAwake", "deviceName", "ip", "port",
            "network", "webViewVersion", "webViewOutdated", "appVersion",
            "androidVersion", "sdkInt", "uptimeMs", "seq", "extraNetworks",
            "mediaEnabled", "mediaMode", "mediaSource", "mediaItem", "mediaIndex",
            "mediaCount", "mediaPaused", "mediaError", "qmsNow", "qmsLocation",
            "qmsDetectionHealthy", "qmsLastCallAtMs", "documentStartSupported",
            "mediaLocalFiles", "mediaLocalBytes", "mediaFreeBytes",
            "playlistLastError", "playlistLastFetchMs", "uiLanguage",
            "screenWidthDp", "screenHeightDp", "densityDpi", "renderScale"
        )
    }
}
