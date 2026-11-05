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

    // ------------------------------------------------------------------
    // indexAfterReload: the library-refresh advance
    // ------------------------------------------------------------------

    private fun item(id: String) =
        MediaItem(id = id, name = id, kind = MediaItem.Kind.VIDEO, durationMs = 0L)

    @Test
    fun `a playing item that still exists keeps its new position`() {
        val items = listOf(item("a.mp4"), item("b.mp4"), item("c.mp4"))
        assertEquals(
            2,
            MediaQueue.indexAfterReload(items, previousId = "c.mp4", previousIndex = 1, reset = false, advanceOnMissing = true)
        )
    }

    @Test
    fun `a deleted playing item advances to the item that took its slot`() {
        val items = listOf(item("a.mp4"), item("c.mp4"), item("d.mp4"))
        assertEquals(
            1,
            MediaQueue.indexAfterReload(items, previousId = "b.mp4", previousIndex = 1, reset = false, advanceOnMissing = true)
        )
    }

    @Test
    fun `a deleted tail falls back to the new last item`() {
        val items = listOf(item("a.mp4"), item("b.mp4"))
        assertEquals(
            1,
            MediaQueue.indexAfterReload(items, previousId = "c.mp4", previousIndex = 2, reset = false, advanceOnMissing = true)
        )
    }

    @Test
    fun `a reset starts at the first item even when the old item exists`() {
        val items = listOf(item("a.mp4"), item("b.mp4"))
        assertEquals(
            0,
            MediaQueue.indexAfterReload(items, previousId = "b.mp4", previousIndex = 1, reset = true, advanceOnMissing = true)
        )
    }

    @Test
    fun `a missing item without advance starts at the first item`() {
        val items = listOf(item("a.mp4"), item("b.mp4"))
        assertEquals(
            0,
            MediaQueue.indexAfterReload(items, previousId = "gone.mp4", previousIndex = 1, reset = false, advanceOnMissing = false)
        )
    }

    @Test
    fun `an empty library has index zero`() {
        assertEquals(
            0,
            MediaQueue.indexAfterReload(emptyList(), previousId = "a.mp4", previousIndex = 3, reset = false, advanceOnMissing = true)
        )
    }
}
