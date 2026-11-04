package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyGuardTest {

    @Test
    fun `playback keys belong to the media layer in every mode but qms only`() {
        for (key in listOf(MediaKey.PLAY_PAUSE, MediaKey.RESUME, MediaKey.PAUSE, MediaKey.NEXT, MediaKey.PREVIOUS)) {
            assertFalse(MediaKeyGuard.shouldSwallow(MediaMode.QMS_ONLY, false, key))
            assertTrue(MediaKeyGuard.shouldSwallow(MediaMode.SPLIT, false, key))
            assertTrue(MediaKeyGuard.shouldSwallow(MediaMode.MEDIA_ONLY, false, key))
            assertTrue(MediaKeyGuard.shouldSwallow(MediaMode.QMS_FOCUS, false, key))
        }
    }

    @Test
    fun `playback keys stay swallowed while an overlay is up`() {
        for (mode in MediaMode.entries) {
            for (key in listOf(MediaKey.PLAY_PAUSE, MediaKey.RESUME, MediaKey.PAUSE, MediaKey.NEXT, MediaKey.PREVIOUS)) {
                val expected = mode != MediaMode.QMS_ONLY
                assertEquals("$mode/$key overlay=true", expected, MediaKeyGuard.shouldSwallow(mode, true, key))
            }
        }
    }

    @Test
    fun `direction and enter keys are swallowed only in media only without an overlay`() {
        for (mode in MediaMode.entries) {
            for (overlay in listOf(false, true)) {
                for (key in listOf(MediaKey.DPAD, MediaKey.ENTER)) {
                    val expected = mode == MediaMode.MEDIA_ONLY && !overlay
                    assertEquals(
                        "$mode/$key overlay=$overlay",
                        expected,
                        MediaKeyGuard.shouldSwallow(mode, overlay, key)
                    )
                }
            }
        }
    }

    @Test
    fun `menu and other keys are never swallowed`() {
        for (mode in MediaMode.entries) {
            for (overlay in listOf(false, true)) {
                assertFalse(MediaKeyGuard.shouldSwallow(mode, overlay, MediaKey.MENU))
                assertFalse(MediaKeyGuard.shouldSwallow(mode, overlay, MediaKey.OTHER))
            }
        }
    }
}
