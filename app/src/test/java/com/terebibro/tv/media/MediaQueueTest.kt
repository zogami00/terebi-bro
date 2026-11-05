package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaQueueTest {

    @Test
    fun `advance wraps to the start`() {
        assertEquals(0, MediaQueue.advance(index = 2, count = 3, skip = 1))
        assertEquals(1, MediaQueue.advance(index = 0, count = 3, skip = 1))
    }

    @Test
    fun `advance with no skip stays put`() {
        assertEquals(2, MediaQueue.advance(index = 2, count = 3, skip = 0))
    }

    @Test
    fun `a single item always advances to itself`() {
        assertEquals(0, MediaQueue.advance(index = 0, count = 1, skip = 0))
        assertEquals(0, MediaQueue.advance(index = 0, count = 1, skip = 5))
    }

    @Test
    fun `an empty queue has no index`() {
        assertEquals(-1, MediaQueue.advance(index = 0, count = 0, skip = 1))
        assertEquals(-1, MediaQueue.advance(index = 3, count = 0, skip = 0))
    }

    @Test
    fun `a skip larger than the queue wraps cleanly`() {
        assertEquals(2, MediaQueue.advance(index = 2, count = 3, skip = 3))
        assertEquals(0, MediaQueue.advance(index = 2, count = 3, skip = 4))
        assertEquals(1, MediaQueue.advance(index = 2, count = 3, skip = 5))
    }

    @Test
    fun `a negative skip wraps backwards`() {
        assertEquals(2, MediaQueue.advance(index = 0, count = 3, skip = -1))
        assertEquals(0, MediaQueue.advance(index = 1, count = 3, skip = -1))
    }
}
