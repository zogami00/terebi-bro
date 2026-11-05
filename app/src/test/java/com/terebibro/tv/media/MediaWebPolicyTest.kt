package com.terebibro.tv.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWebPolicyTest {

    @Test
    fun `fullscreen is allowed in media only and split`() {
        assertTrue(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = true,
                webSource = true,
                mode = MediaMode.MEDIA_ONLY
            )
        )
        assertTrue(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = true,
                webSource = true,
                mode = MediaMode.SPLIT
            )
        )
    }

    @Test
    fun `fullscreen is refused when the band is hidden`() {
        assertFalse(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = true,
                webSource = true,
                mode = MediaMode.QMS_ONLY
            )
        )
        assertFalse(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = true,
                webSource = true,
                mode = MediaMode.QMS_FOCUS
            )
        )
    }

    @Test
    fun `fullscreen needs a web source and media mode`() {
        assertFalse(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = true,
                webSource = false,
                mode = MediaMode.MEDIA_ONLY
            )
        )
        assertFalse(
            MediaWebPolicy.allowsMediaFullscreen(
                mediaEnabled = false,
                webSource = true,
                mode = MediaMode.MEDIA_ONLY
            )
        )
    }

    @Test
    fun `immersive mode is not re-applied while the keyboard is up`() {
        assertTrue(MediaWebPolicy.shouldReapplyImmersive(fullscreenEnabled = true, imeVisible = false))
        assertFalse(MediaWebPolicy.shouldReapplyImmersive(fullscreenEnabled = true, imeVisible = true))
    }

    @Test
    fun `immersive mode stays off when fullscreen is disabled`() {
        assertFalse(MediaWebPolicy.shouldReapplyImmersive(fullscreenEnabled = false, imeVisible = false))
        assertFalse(MediaWebPolicy.shouldReapplyImmersive(fullscreenEnabled = false, imeVisible = true))
    }
}
