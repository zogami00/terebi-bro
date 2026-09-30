package com.terebibro.tv

import com.terebibro.tv.BackOrder.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackOrderTest {

    private fun state(
        info: Boolean = false,
        error: Boolean = false,
        fullscreen: Boolean = false,
        canGoBack: Boolean = false,
        atHome: Boolean = false
    ) = BackOrder.BackState(info, error, fullscreen, canGoBack, atHome)

    @Test
    fun `info overlay is closed first`() {
        assertEquals(
            Action.CLOSE_INFO,
            BackOrder.decision(state(info = true, error = true, fullscreen = true, canGoBack = true))
        )
    }

    @Test
    fun `error overlay is closed next`() {
        assertEquals(
            Action.CLOSE_ERROR,
            BackOrder.decision(state(error = true, fullscreen = true, canGoBack = true))
        )
    }

    @Test
    fun `fullscreen video is exited next`() {
        assertEquals(
            Action.HIDE_FULLSCREEN,
            BackOrder.decision(state(fullscreen = true, canGoBack = true))
        )
    }

    @Test
    fun `webview history navigates back`() {
        assertEquals(Action.GO_BACK, BackOrder.decision(state(canGoBack = true)))
    }

    @Test
    fun `off home loads home`() {
        assertEquals(Action.GO_HOME, BackOrder.decision(state(atHome = false)))
    }

    @Test
    fun `at the root the ladder opens the setup page`() {
        assertEquals(Action.SHOW_SETUP, BackOrder.decision(state(atHome = true)))
    }

    @Test
    fun `every rung of the ladder is a real action`() {
        val actions = listOf(
            state(),
            state(atHome = true),
            state(info = true),
            state(error = true),
            state(fullscreen = true),
            state(canGoBack = true),
            state(atHome = false)
        ).map { BackOrder.decision(it) }
        // The ladder is total: no rung can be "nothing to do".
        assertEquals(
            setOf(
                Action.CLOSE_INFO,
                Action.CLOSE_ERROR,
                Action.HIDE_FULLSCREEN,
                Action.GO_BACK,
                Action.GO_HOME,
                Action.SHOW_SETUP
            ),
            actions.toSet()
        )
    }

    // ---------------------------------------------------------------------
    // Long-press timing (pure helper backing the API < 33 detection)
    // ---------------------------------------------------------------------

    @Test
    fun `hold just under the threshold is not a long press`() {
        assertFalse(BackOrder.isLongPress(1_000L, 1_699L, 700L))
    }

    @Test
    fun `hold exactly at the threshold is a long press`() {
        assertTrue(BackOrder.isLongPress(1_000L, 1_700L, 700L))
    }

    @Test
    fun `hold just over the threshold is a long press`() {
        assertTrue(BackOrder.isLongPress(1_000L, 1_701L, 700L))
    }

    @Test
    fun `a press with no usable gesture start is never long`() {
        // downTime <= 0 is the "no usable ACTION_DOWN / gesture start"
        // sentinel; the elapsed value against it (here a huge uptime) must not
        // be read as a hold.
        assertFalse(BackOrder.isLongPress(0L, 100_000L, 700L))
        assertFalse(BackOrder.isLongPress(-1L, 100_000L, 700L))
    }

    @Test
    fun `an up before the gesture start is never long`() {
        // A malformed / cancelled event can report an UP earlier than the
        // gesture start; a negative delta must not read as a long press.
        assertFalse(BackOrder.isLongPress(5_000L, 4_000L, 700L))
    }

    @Test
    fun `zero-length hold is not a long press`() {
        assertFalse(BackOrder.isLongPress(1_000L, 1_000L, 700L))
    }
}
