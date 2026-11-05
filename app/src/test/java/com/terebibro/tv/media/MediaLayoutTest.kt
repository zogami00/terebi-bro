package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLayoutTest {

    private val cfg = MediaSettings()

    private fun assertBandsSum(screenHeightDp: Int, cfg: MediaSettings = this.cfg) {
        val bands = MediaLayout.bands(screenHeightDp, cfg)
        assertEquals(
            screenHeightDp,
            bands.mediaDp + bands.headerDp + bands.queueDp
        )
    }

    @Test
    fun `1080p splits into 71 7 22 with the remainder on the media band`() {
        val bands = MediaLayout.bands(1080, cfg)
        assertEquals(76, bands.headerDp)
        assertEquals(238, bands.queueDp)
        assertEquals(766, bands.mediaDp)
        assertBandsSum(1080)
    }

    @Test
    fun `720p splits without a seam or a gap`() {
        val bands = MediaLayout.bands(720, cfg)
        assertEquals(50, bands.headerDp)
        assertEquals(158, bands.queueDp)
        assertEquals(512, bands.mediaDp)
        assertBandsSum(720)
    }

    @Test
    fun `a native dp 4K surface doubles the 1080p design`() {
        val bands = MediaLayout.bands(2160, cfg)
        assertEquals(151, bands.headerDp)
        assertEquals(475, bands.queueDp)
        assertEquals(1534, bands.mediaDp)
        assertBandsSum(2160)
    }

    @Test
    fun `a non-16-by-9 height still sums exactly`() {
        val bands = MediaLayout.bands(768, cfg)
        assertEquals(54, bands.headerDp)
        assertEquals(169, bands.queueDp)
        assertEquals(545, bands.mediaDp)
        assertBandsSum(768)
    }

    @Test
    fun `the bands always sum exactly for awkward heights`() {
        for (height in listOf(1, 7, 99, 333, 540, 721, 1079, 1081, 1440)) {
            assertBandsSum(height)
        }
    }

    @Test
    fun `the strip position does not change the band arithmetic`() {
        val right = MediaSettings(stripPosition = StripPosition.RIGHT)
        assertEquals(MediaLayout.bands(1080, cfg), MediaLayout.bands(1080, right))
    }

    @Test
    fun `renderScale is the reported dp height over 1080`() {
        assertEquals(1.0f, MediaLayout.renderScale(1080), 0.0001f)
        assertEquals(0.6667f, MediaLayout.renderScale(720), 0.0001f)
        assertEquals(2.0f, MediaLayout.renderScale(2160), 0.0001f)
    }

    @Test
    fun `a 16 by 9 video letterboxes inside a wide box`() {
        assertEquals(MediaLayout.Fit(1362, 766), MediaLayout.fit(1920, 1080, 1920, 766))
    }

    @Test
    fun `a 4 by 3 video is height constrained`() {
        assertEquals(MediaLayout.Fit(1440, 1080), MediaLayout.fit(640, 480, 1920, 1080))
    }

    @Test
    fun `a portrait video is height constrained`() {
        assertEquals(MediaLayout.Fit(608, 1080), MediaLayout.fit(1080, 1920, 1920, 1080))
    }

    @Test
    fun `a square box constrains by width when the video is wider`() {
        assertEquals(MediaLayout.Fit(1000, 750), MediaLayout.fit(1600, 1200, 1000, 1000))
    }

    @Test
    fun `any non-positive input yields an empty fit`() {
        assertEquals(MediaLayout.Fit(0, 0), MediaLayout.fit(0, 1080, 1920, 1080))
        assertEquals(MediaLayout.Fit(0, 0), MediaLayout.fit(1920, 0, 1920, 1080))
        assertEquals(MediaLayout.Fit(0, 0), MediaLayout.fit(1920, 1080, 0, 1080))
        assertEquals(MediaLayout.Fit(0, 0), MediaLayout.fit(1920, 1080, 1920, 0))
        assertEquals(MediaLayout.Fit(0, 0), MediaLayout.fit(-1, -1, -1, -1))
    }
}
