package com.terebibro.tv.media

/**
 * One playable item. [id] is opaque; [name] is display-only.
 *
 * [remote] says how [id] is read: a name inside the local media folder
 * (`false`) or an absolute `http`/`https` URL fetched by the player
 * (`true`). Playlist entries and ad-hoc URLs are remote; library files are not.
 * The distinction matters to [MediaPlayerHost], which must never look for a
 * remote id on disk.
 *
 * [Kind.WEB] is the media WebView: its [id] is the page URL and it is always
 * [remote]. It is never a queue item (the playlist parser refuses `web`
 * entries), so only the controller synthesises one, and the native player never
 * receives it.
 */
data class MediaItem(
    val id: String,
    val name: String,
    val kind: Kind,
    val durationMs: Long,
    val remote: Boolean = false
) {
    enum class Kind { VIDEO, IMAGE, WEB }
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

    /**
     * The index to resume at after the library was re-read.
     *
     * When [reset] is true the queue starts over at the first item. Otherwise the
     * item that was playing ([previousId]) keeps its place wherever it moved to.
     * If it is gone and [advanceOnMissing] is true, the item that shifted into
     * its old slot takes over — or the new tail, when the deleted item was last.
     */
    fun indexAfterReload(
        items: List<MediaItem>,
        previousId: String?,
        previousIndex: Int,
        reset: Boolean,
        advanceOnMissing: Boolean
    ): Int {
        if (items.isEmpty()) return 0
        if (reset) return 0
        val found = items.indexOfFirst { it.id == previousId }
        return when {
            found >= 0 -> found
            advanceOnMissing -> previousIndex.coerceIn(0, items.size - 1)
            else -> 0
        }
    }
}
