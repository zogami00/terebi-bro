package com.terebibro.tv.media

/** One playable item. [id] is opaque; [name] is display-only. */
data class MediaItem(
    val id: String,
    val name: String,
    val kind: Kind,
    val durationMs: Long
) {
    enum class Kind { VIDEO, IMAGE }
}

/** Queue arithmetic only; the queue itself is owned by the media layer. */
object MediaQueue {

    /**
     * The index [skip] items after [index], wrapping in both directions.
     * `-1` means "no item" and is the only value returned for an empty queue.
     */
    fun advance(index: Int, count: Int, skip: Int): Int {
        if (count <= 0) return -1
        val start = ((index % count) + count) % count
        val step = (skip % count + count) % count
        return (start + step) % count
    }
}
