package com.terebibro.tv.media

import org.junit.Assert.assertEquals
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
    fun `the qms surface is hidden outright only in media only`() {
        assertTrue(MediaWebPolicy.hidesQmsSurface(MediaMode.MEDIA_ONLY))
        assertFalse(MediaWebPolicy.hidesQmsSurface(MediaMode.QMS_ONLY))
        assertFalse(MediaWebPolicy.hidesQmsSurface(MediaMode.SPLIT))
        assertFalse(MediaWebPolicy.hidesQmsSurface(MediaMode.QMS_FOCUS))
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

    @Test
    fun `media page gets protected media and nothing else`() {
        val camera = "android.webkit.resource.VIDEO_CAPTURE"
        val mic = "android.webkit.resource.AUDIO_CAPTURE"
        assertEquals(
            listOf(MediaWebPolicy.PROTECTED_MEDIA),
            MediaWebPolicy.grantedResources(arrayOf(camera, MediaWebPolicy.PROTECTED_MEDIA, mic)).toList()
        )
        assertTrue(MediaWebPolicy.grantedResources(arrayOf(camera, mic)).isEmpty())
    }
}
