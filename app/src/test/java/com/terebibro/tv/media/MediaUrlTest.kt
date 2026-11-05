package com.terebibro.tv.media

import com.terebibro.tv.server.StateJson
import com.terebibro.tv.web.BrowserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlTest {

    @Test
    fun `mask drops the query`() {
        assertEquals(
            "https://jellyfin.example.com/web/index.html",
            MediaUrl.mask("https://jellyfin.example.com/web/index.html?api_key=secret")
        )
    }

    @Test
    fun `mask drops the fragment`() {
        assertEquals(
            "https://www.youtube.com/watch",
            MediaUrl.mask("https://www.youtube.com/watch#t=42")
        )
    }

    @Test
    fun `mask drops both the query and the fragment`() {
        assertEquals(
            "https://example.com/player",
            MediaUrl.mask("https://example.com/player?token=abc#frag")
        )
    }

    @Test
    fun `mask keeps a port`() {
        assertEquals(
            "http://192.168.1.10:8096/web",
            MediaUrl.mask("http://192.168.1.10:8096/web?token=abc")
        )
    }

    @Test
    fun `mask returns a url with no path unchanged apart from the tail`() {
        assertEquals("https://example.com", MediaUrl.mask("https://example.com?token=1"))
        assertEquals("https://example.com/", MediaUrl.mask("https://example.com/?token=1"))
    }

    @Test
    fun `mask removes userinfo`() {
        assertEquals(
            "https://example.com/path",
            MediaUrl.mask("https://user:pass@example.com/path?token=abc")
        )
    }

    @Test
    fun `a library file name is not a url and is left alone`() {
        assertEquals("clip.mp4", MediaUrl.mask("clip.mp4"))
        assertEquals("", MediaUrl.mask("   "))
    }

    /**
     * The whole publication path: a token-bearing page URL goes through
     * [MediaUrl.forPublish] and out through the state payload. Dropping the
     * mask from `forPublish` (or skipping it in the snapshot) fails here,
     * because the token would reach `mediaItem` verbatim.
     */
    @Test
    fun `a token bearing url reaches the state payload masked`() {
        val published = MediaUrl.forPublish(
            "https://jellyfin.example.com:8096/web/index.html?api_key=SECRET#token=SECRET2"
        )
        assertEquals("https://jellyfin.example.com:8096/web/index.html", published)

        val state = BrowserState(
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
            androidVersion = "14",
            sdkInt = 34,
            uptimeMs = 0L,
            seq = 0L,
            mediaSource = MediaSource.WEB,
            mediaItem = published
        )
        val json = StateJson.toJson(state).toString()

        assertFalse("token leaked into the payload: $json", json.contains("SECRET"))
        assertTrue(json.contains("https://jellyfin.example.com:8096/web/index.html"))
    }

    @Test
    fun `only the web source is a web source`() {
        assertTrue(MediaSource.isWeb(MediaSource.WEB))
        assertFalse(MediaSource.isWeb(MediaSource.LOCAL))
        assertFalse(MediaSource.isWeb(MediaSource.PLAYLIST))
        assertFalse(MediaSource.isWeb(MediaSource.URL))
        assertFalse(MediaSource.isWeb(""))
    }
}
