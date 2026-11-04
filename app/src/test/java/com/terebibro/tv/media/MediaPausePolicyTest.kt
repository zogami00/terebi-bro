package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPausePolicyTest {

    @Test
    fun `only split and media only play without a pause source`() {
        for (mode in MediaMode.entries) {
            val expected = mode == MediaMode.QMS_ONLY || mode == MediaMode.QMS_FOCUS
            assertEquals(
                "$mode without a pause source",
                expected,
                MediaPausePolicy.shouldPause(mode, false, false, false)
            )
        }
    }

    @Test
    fun `each pause source pauses every mode`() {
        val sources = listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
            Triple(true, true, true)
        )
        for (mode in MediaMode.entries) {
            for ((overlay, lifecycle, manual) in sources) {
                assertTrue(
                    "$mode overlay=$overlay lifecycle=$lifecycle manual=$manual",
                    MediaPausePolicy.shouldPause(mode, overlay, lifecycle, manual)
                )
            }
        }
    }

    @Test
    fun `the manual pause is an independent OR term`() {
        assertTrue(MediaPausePolicy.shouldPause(MediaMode.SPLIT, false, false, manualPaused = true))
        assertTrue(MediaPausePolicy.shouldPause(MediaMode.MEDIA_ONLY, false, false, manualPaused = true))
        assertEquals(
            false,
            MediaPausePolicy.shouldPause(MediaMode.SPLIT, false, false, manualPaused = false)
        )
    }

    @Test
    fun `lost audio focus pauses every mode`() {
        for (mode in MediaMode.entries) {
            assertTrue(
                "$mode with focus lost",
                MediaPausePolicy.shouldPause(mode, false, false, false, focusPaused = true)
            )
        }
    }

    /**
     * The focus-gain race: a pause source added while focus was lost has to keep
     * the pause after the focus term is dropped, so the controller re-asserts it
     * against the host instead of letting the host auto-resume.
     */
    @Test
    fun `a surviving source keeps the pause after the focus term clears`() {
        assertTrue(
            MediaPausePolicy.shouldPause(
                MediaMode.QMS_ONLY,
                overlayVisible = false,
                lifecyclePaused = false,
                manualPaused = false,
                focusPaused = false
            )
        )
        assertTrue(
            MediaPausePolicy.shouldPause(
                MediaMode.SPLIT,
                overlayVisible = true,
                lifecyclePaused = false,
                manualPaused = false,
                focusPaused = false
            )
        )
        // Focus was the only hold: the gain does clear the pause.
        assertFalse(
            MediaPausePolicy.shouldPause(
                MediaMode.MEDIA_ONLY,
                overlayVisible = false,
                lifecyclePaused = false,
                manualPaused = false,
                focusPaused = false
            )
        )
    }
}
