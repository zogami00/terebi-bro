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
    fun `at the root the ladder exits`() {
        assertEquals(Action.EXIT, BackOrder.decision(state(atHome = true)))
        assertFalse(BackOrder.canHandle(state(atHome = true)))
    }

    @Test
    fun `every non-root rung is handled`() {
        assertTrue(BackOrder.canHandle(state(info = true)))
        assertTrue(BackOrder.canHandle(state(error = true)))
        assertTrue(BackOrder.canHandle(state(fullscreen = true)))
        assertTrue(BackOrder.canHandle(state(canGoBack = true)))
        assertTrue(BackOrder.canHandle(state(atHome = false)))
    }

    @Test
    fun `canHandle mirrors the decision exactly`() {
        val states = listOf(
            state(),
            state(atHome = true),
            state(info = true),
            state(error = true),
            state(fullscreen = true),
            state(canGoBack = true),
            state(atHome = false),
            state(info = true, error = true, fullscreen = true, canGoBack = true, atHome = true)
        )
        for (s in states) {
            assertEquals(BackOrder.decision(s) != Action.EXIT, BackOrder.canHandle(s))
        }
    }
}
