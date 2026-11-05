package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaModeTest {

    @Test
    fun `an unknown wire is not a manual mode`() {
        assertNull(MediaMode.manualOrNull("not_a_mode"))
        assertNull(MediaMode.manualOrNull(""))
    }

    @Test
    fun `qms focus is never a manual mode`() {
        assertNull(MediaMode.manualOrNull("qms_focus"))
    }

    @Test
    fun `every valid wire is parsed, except qms focus`() {
        assertEquals(MediaMode.QMS_ONLY, MediaMode.manualOrNull("qms_only"))
        assertEquals(MediaMode.MEDIA_ONLY, MediaMode.manualOrNull("media_only"))
        assertEquals(MediaMode.SPLIT, MediaMode.manualOrNull("split"))
        assertNull(MediaMode.manualOrNull(MediaMode.QMS_FOCUS.wire))
    }
}
