package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFileKindsTest {

    @Test
    fun `mp4 is video`() {
        assertEquals(MediaItem.Kind.VIDEO, MediaFileKinds.of("promo.mp4"))
    }

    @Test
    fun `the extension is matched case-insensitively`() {
        assertEquals(MediaItem.Kind.VIDEO, MediaFileKinds.of("PROMO.MP4"))
        assertEquals(MediaItem.Kind.IMAGE, MediaFileKinds.of("Poster.JPEG"))
        assertEquals(MediaItem.Kind.IMAGE, MediaFileKinds.of("logo.PNG"))
    }

    @Test
    fun `jpeg and jpg and png are images`() {
        assertEquals(MediaItem.Kind.IMAGE, MediaFileKinds.of("a.jpg"))
        assertEquals(MediaItem.Kind.IMAGE, MediaFileKinds.of("a.jpeg"))
        assertEquals(MediaItem.Kind.IMAGE, MediaFileKinds.of("a.png"))
    }

    @Test
    fun `an unknown extension is not playable`() {
        assertNull(MediaFileKinds.of("notes.txt"))
        assertNull(MediaFileKinds.of("movie.mkv"))
        assertNull(MediaFileKinds.of("song.mp3"))
    }

    @Test
    fun `a name without an extension is not playable`() {
        assertNull(MediaFileKinds.of("clip"))
    }

    @Test
    fun `a dotfile has no extension`() {
        assertNull(MediaFileKinds.of(".mp4"))
        assertNull(MediaFileKinds.of(".hidden"))
    }

    @Test
    fun `a trailing dot has no extension`() {
        assertNull(MediaFileKinds.of("clip."))
    }

    @Test
    fun `only the last extension counts`() {
        assertNull(MediaFileKinds.of("mp4.txt"))
        assertEquals(MediaItem.Kind.VIDEO, MediaFileKinds.of("mp4.txt.mp4"))
    }

    @Test
    fun `an empty name is not playable`() {
        assertNull(MediaFileKinds.of(""))
    }
}
