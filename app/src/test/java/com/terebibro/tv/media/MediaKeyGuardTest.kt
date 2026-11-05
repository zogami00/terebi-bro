package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyGuardTest {

    private val playbackKeys = listOf(
        MediaKey.PLAY_PAUSE,
        MediaKey.RESUME,
        MediaKey.PAUSE,
        MediaKey.NEXT,
        MediaKey.PREVIOUS
    )

    private val directionKeys = listOf(MediaKey.DPAD, MediaKey.ENTER)

    @Test
    fun `playback keys belong to the media layer in every mode but qms only`() {
        for (mode in MediaMode.entries) {
            for (overlay in listOf(false, true)) {
                for (webActive in listOf(false, true)) {
                    for (key in playbackKeys) {
                        val expected = mode != MediaMode.QMS_ONLY
                        assertEquals(
                            "$mode/$key overlay=$overlay webActive=$webActive",
                            expected,
                            MediaKeyGuard.shouldSwallow(mode, overlay, webActive, key)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `direction and enter keys are swallowed only for a native media only band`() {
        for (mode in MediaMode.entries) {
            for (overlay in listOf(false, true)) {
                for (webActive in listOf(false, true)) {
                    for (key in directionKeys) {
                        val expected =
                            mode == MediaMode.MEDIA_ONLY && !overlay && !webActive
                        assertEquals(
                            "$mode/$key overlay=$overlay webActive=$webActive",
                            expected,
                            MediaKeyGuard.shouldSwallow(mode, overlay, webActive, key)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `media only with a web page never swallows dpad or enter`() {
        for (overlay in listOf(false, true)) {
            for (key in directionKeys) {
                assertFalse(
                    "$key overlay=$overlay",
                    MediaKeyGuard.shouldSwallow(
                        MediaMode.MEDIA_ONLY,
                        overlay,
                        webActive = true,
                        key = key
                    )
                )
            }
        }
    }

    @Test
    fun `media only with a native item swallows dpad and enter while no overlay is up`() {
        for (key in directionKeys) {
            assertTrue(
                "$key with no overlay",
                MediaKeyGuard.shouldSwallow(
                    MediaMode.MEDIA_ONLY,
                    overlayVisible = false,
                    webActive = false,
                    key = key
                )
            )
            assertFalse(
                "$key with an overlay",
                MediaKeyGuard.shouldSwallow(
                    MediaMode.MEDIA_ONLY,
                    overlayVisible = true,
                    webActive = false,
                    key = key
                )
            )
        }
    }

    @Test
    fun `menu and other keys are never swallowed`() {
        for (mode in MediaMode.entries) {
            for (overlay in listOf(false, true)) {
                for (webActive in listOf(false, true)) {
                    for (key in listOf(MediaKey.MENU, MediaKey.OTHER)) {
                        assertFalse(
                            "$mode/$key overlay=$overlay webActive=$webActive",
                            MediaKeyGuard.shouldSwallow(mode, overlay, webActive, key)
                        )
                    }
                }
            }
        }
    }
}
