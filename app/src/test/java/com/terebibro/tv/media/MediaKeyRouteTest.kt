package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [MediaKeyRoute], the single routing predicate now shared by the
 * hardware remote and `POST /api/dpad`.
 *
 * The matrix covers all four modes × web/native source × overlay visible, which
 * is exactly the space in which the controller's on-screen D-pad used to drive
 * the hidden QMS page in `MEDIA_ONLY`.
 */
class MediaKeyRouteTest {

    private val directionKeys = listOf(MediaKey.DPAD, MediaKey.ENTER)

    private fun expected(
        mode: MediaMode,
        webActive: Boolean,
        overlayVisible: Boolean
    ): KeyTarget = when {
        mode != MediaMode.MEDIA_ONLY -> KeyTarget.QMS
        !webActive -> KeyTarget.NONE
        overlayVisible -> KeyTarget.NONE
        else -> KeyTarget.MEDIA
    }

    @Test
    fun `every mode source and overlay combination routes as specified`() {
        for (mode in MediaMode.entries) {
            for (webActive in listOf(false, true)) {
                for (overlayVisible in listOf(false, true)) {
                    for (key in directionKeys) {
                        assertEquals(
                            "$mode/$key web=$webActive overlay=$overlayVisible",
                            expected(mode, webActive, overlayVisible),
                            MediaKeyRoute.route(key, mode, webActive, overlayVisible)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `media only with a web page and no overlay sends direction keys to the media page`() {
        for (key in directionKeys) {
            assertEquals(
                "$key",
                KeyTarget.MEDIA,
                MediaKeyRoute.route(
                    key,
                    MediaMode.MEDIA_ONLY,
                    webActive = true,
                    overlayVisible = false
                )
            )
        }
    }

    @Test
    fun `media only with a web page never sends a direction key to the qms page`() {
        for (overlayVisible in listOf(false, true)) {
            for (key in directionKeys) {
                assertNotEquals(
                    "$key overlay=$overlayVisible",
                    KeyTarget.QMS,
                    MediaKeyRoute.route(
                        key,
                        MediaMode.MEDIA_ONLY,
                        webActive = true,
                        overlayVisible = overlayVisible
                    )
                )
            }
        }
    }

    @Test
    fun `media only with a native source gives direction keys to nobody`() {
        for (overlayVisible in listOf(false, true)) {
            for (key in directionKeys) {
                assertEquals(
                    "$key overlay=$overlayVisible",
                    KeyTarget.NONE,
                    MediaKeyRoute.route(
                        key,
                        MediaMode.MEDIA_ONLY,
                        webActive = false,
                        overlayVisible = overlayVisible
                    )
                )
            }
        }
    }

    @Test
    fun `qms only split and qms focus always drive the qms page`() {
        val qmsModes = listOf(
            MediaMode.QMS_ONLY,
            MediaMode.SPLIT,
            MediaMode.QMS_FOCUS
        )
        for (mode in qmsModes) {
            for (webActive in listOf(false, true)) {
                for (overlayVisible in listOf(false, true)) {
                    for (key in directionKeys) {
                        assertEquals(
                            "$mode/$key web=$webActive overlay=$overlayVisible",
                            KeyTarget.QMS,
                            MediaKeyRoute.route(key, mode, webActive, overlayVisible)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `non direction keys are not routed to either webview`() {
        val otherKeys = listOf(
            MediaKey.PLAY_PAUSE,
            MediaKey.RESUME,
            MediaKey.PAUSE,
            MediaKey.NEXT,
            MediaKey.PREVIOUS,
            MediaKey.MENU,
            MediaKey.OTHER
        )
        for (mode in MediaMode.entries) {
            for (webActive in listOf(false, true)) {
                for (overlayVisible in listOf(false, true)) {
                    for (key in otherKeys) {
                        assertEquals(
                            "$mode/$key web=$webActive overlay=$overlayVisible",
                            KeyTarget.NONE,
                            MediaKeyRoute.route(key, mode, webActive, overlayVisible)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `only media only with a web page and no overlay can reach the media page`() {
        for (mode in MediaMode.entries) {
            for (webActive in listOf(false, true)) {
                for (overlayVisible in listOf(false, true)) {
                    for (key in directionKeys) {
                        val target = MediaKeyRoute.route(
                            key,
                            mode,
                            webActive,
                            overlayVisible
                        )
                        if (target == KeyTarget.MEDIA) {
                            assertTrue(
                                "$mode/$key web=$webActive overlay=$overlayVisible",
                                mode == MediaMode.MEDIA_ONLY &&
                                    webActive &&
                                    !overlayVisible
                            )
                        }
                    }
                }
            }
        }
    }
}
