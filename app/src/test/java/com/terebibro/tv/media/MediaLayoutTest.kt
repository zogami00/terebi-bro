package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `px bands sum exactly at every fractional density and tested height`() {
        val heights = listOf(720, 1080, 2160, 721, 1081, 1, 2, 0)
        for (density in listOf(1.0f, 1.5f, 2.0f, 2.75f)) {
            for (height in heights) {
                val bands = MediaLayout.bandsPx(height, density, cfg)
                assertEquals(
                    "height=$height density=$density",
                    height,
                    bands.mediaPx + bands.headerPx + bands.queuePx
                )
                assertEquals(height - bands.headerPx - bands.queuePx, bands.mediaPx)
                assertTrue("media must never go negative", bands.mediaPx >= 0)
                assertTrue("header must never go negative", bands.headerPx >= 0)
                assertTrue("queue must never go negative", bands.queuePx >= 0)
            }
        }
    }

    @Test
    fun `px remainder always goes to the media band`() {
        val bands = MediaLayout.bandsPx(1081, 1.5f, cfg)
        assertEquals(76, bands.headerPx)
        assertEquals(238, bands.queuePx)
        assertEquals(1081 - 76 - 238, bands.mediaPx)
    }

    @Test
    fun `every fill mode maps to its image treatment`() {
        assertEquals(MediaLayout.Fill.FIT_OVER_BACKDROP, MediaLayout.fillFor(MediaFillMode.LETTERBOX_BLUR))
        assertEquals(MediaLayout.Fill.FIT, MediaLayout.fillFor(MediaFillMode.LETTERBOX))
        assertEquals(MediaLayout.Fill.CROP, MediaLayout.fillFor(MediaFillMode.COVER))
    }

    @Test
    fun `renderScale is the reported dp height over 1080`() {
        assertEquals(1.0f, MediaLayout.renderScale(1080), 0.0001f)
        assertEquals(0.6667f, MediaLayout.renderScale(720), 0.0001f)
        assertEquals(2.0f, MediaLayout.renderScale(2160), 0.0001f)
    }

    @Test
    fun `safeRenderScale shrinks below 1080 dp instead of flooring at one`() {
        // A real phone surface: 1920x1080 px at 280 dpi is 617 dp tall, so the
        // authored interface has to come down to 57 % or the header band eats
        // the WebView and the cards clip.
        assertEquals(0.571f, MediaLayout.safeRenderScale(617), 0.0005f)
        assertEquals(1.0f, MediaLayout.safeRenderScale(1080), 0.0001f)
        assertEquals(2.0f, MediaLayout.safeRenderScale(2160), 0.0001f)
        assertEquals(0.5f, MediaLayout.safeRenderScale(540), 0.0001f)
        assertEquals(1.778f, MediaLayout.safeRenderScale(1920), 0.0005f)
    }

    @Test
    fun `safeRenderScale guards only against a zero or absurd height`() {
        assertEquals(1.0f, MediaLayout.safeRenderScale(0), 0.0001f)
        assertEquals(1.0f, MediaLayout.safeRenderScale(-720), 0.0001f)
        // A 100 dp surface is not a display; it is a bad metric report.
        assertEquals(0.25f, MediaLayout.safeRenderScale(100), 0.0001f)
        assertEquals(4.0f, MediaLayout.safeRenderScale(10000), 0.0001f)
    }

    @Test
    fun `a 16 by 9 video letterboxes inside a wide box`() {
        assertEquals(MediaLayout.Fit(1362, 766), MediaLayout.fit(1920, 1080, 1920, 766))
    }

    /**
     * The live bug: a 16:9 screen recording was stretched across a ~2.7:1
     * media band. The fit is height constrained there: 702 * 16 / 9 = 1248.
     */
    @Test
    fun `a 16 by 9 video letterboxes inside a 2 point 7 to 1 band`() {
        assertEquals(MediaLayout.Fit(1248, 702), MediaLayout.fit(1920, 1080, 1920, 702))
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
