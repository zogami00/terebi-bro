package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerMotionTest {

    @Test
    fun speedAcceleratesWithRepeatAndCaps() {
        assertEquals(12f, PointerMotion.speed(0, 1920), 0.01f)
        assertEquals(24f, PointerMotion.speed(2, 1920), 0.01f)
        assertEquals(60f, PointerMotion.speed(100, 1920), 0.01f)
    }

    @Test
    fun moveInsideBoundsDoesNotScroll() {
        assertEquals(
            PointerMotion.Step(110f, 100f, 0, 0),
            PointerMotion.move(100f, 100f, 1, 0, 10f, 1920, 1080)
        )
    }

    @Test
    fun pushPastEdgePinsAndScrolls() {
        assertEquals(
            PointerMotion.Step(1919f, 1079f, 1, 1),
            PointerMotion.move(1915f, 1075f, 1, 1, 10f, 1920, 1080)
        )
        assertEquals(
            PointerMotion.Step(0f, 0f, -1, -1),
            PointerMotion.move(5f, 5f, -1, -1, 10f, 1920, 1080)
        )
    }
}
