package com.terebibro.tv.mqtt

import com.terebibro.tv.server.Json
import com.terebibro.tv.web.BrowserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MqttStatusPayloadTest {

    private val state = BrowserState(
        url = "https://example.com/",
        title = "Example",
        loading = false,
        progress = 100,
        canGoBack = true,
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
        appVersion = "0.3.0",
        androidVersion = "9",
        sdkInt = 28,
        uptimeMs = 123_456L,
        seq = 7L,
        extraNetworks = listOf("10.10.1.0/24")
    )

    /** Parses the payload with the repository's strict JSON reader. */
    private fun payload(
        state: BrowserState,
        discoveryTopics: Int = 0,
        discoveryError: String? = null
    ): Map<String, Json.Value> =
        Json.parseObject(
            ByteArrayInputStream(
                MqttStatusPayload.build(state, discoveryTopics, discoveryError)
                    .toByteArray(Charsets.UTF_8)
            ),
            STATE_KEYS,
            arrayKeys = setOf("extraNetworks")
        )

    @Test
    fun `the status payload carries the state object plus the required device fields`() {
        val values = payload(state)

        // The fields the MQTT contract requires on top of the browser state.
        assertEquals("10.0.0.5", (values["ip"] as Json.Value.Str).value)
        assertEquals(8765.0, (values["port"] as Json.Value.Num).value, 0.0)
        assertEquals(
            "com.google.android.webview 138.0",
            (values["webViewVersion"] as Json.Value.Str).value
        )
        assertEquals("0.3.0", (values["appVersion"] as Json.Value.Str).value)
        assertEquals(123_456.0, (values["uptimeMs"] as Json.Value.Num).value, 0.0)
    }

    @Test
    fun `the status payload reports the discovery result`() {
        val values = payload(state, discoveryTopics = 21, discoveryError = null)

        assertEquals(21.0, (values["discoveryTopics"] as Json.Value.Num).value, 0.0)
        // A healthy pass serialises the error as JSON null, not a missing key.
        assertEquals(Json.Value.Null, values["discoveryError"])
    }

    @Test
    fun `the status payload carries a discovery failure reason when present`() {
        val values = payload(state, discoveryTopics = 9, discoveryError = "MqttException: boom")

        assertEquals(9.0, (values["discoveryTopics"] as Json.Value.Num).value, 0.0)
        assertEquals(
            "MqttException: boom",
            (values["discoveryError"] as Json.Value.Str).value
        )
    }

    @Test
    fun `the status payload carries a status publish failure when present`() {
        val text = MqttStatusPayload.build(state, 21, null, "MqttException: max inflight")
        assertTrue(text.contains("\"statusError\":\"MqttException"))
    }

    @Test
    fun `the status payload is the shared browser state, not a second shape`() {
        val values = payload(state)

        assertEquals("https://example.com/", (values["url"] as Json.Value.Str).value)
        assertEquals("Example", (values["title"] as Json.Value.Str).value)
        assertEquals(false, (values["loading"] as Json.Value.Bool).value)
        assertEquals(7.0, (values["seq"] as Json.Value.Num).value, 0.0)
        assertEquals(
            listOf("10.10.1.0/24"),
            (values["extraNetworks"] as Json.Value.StrList).items
        )
    }

    @Test
    fun `no secret can appear in the payload`() {
        // The status payload is built only from BrowserState, which carries no
        // credentials; assert the serialised form has none of the secret keys.
        val text = MqttStatusPayload.build(state, 0, null)
        assertEquals(false, text.contains("password"))
        assertEquals(false, text.contains("token"))
        assertEquals(false, text.contains("pin"))
    }

    private companion object {
        /** Exactly the keys StateJson emits, so the strict reader accepts it. */
        val STATE_KEYS = setOf(
            "url", "title", "loading", "progress", "canGoBack", "canGoForward",
            "homeUrl", "fullscreen", "keepAwake", "deviceName", "ip", "port",
            "network", "webViewVersion", "webViewOutdated", "appVersion",
            "androidVersion", "sdkInt", "uptimeMs", "seq", "extraNetworks",
            "discoveryTopics", "discoveryError", "statusError"
        )
    }
}
