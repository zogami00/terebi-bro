package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val body = """[{"type":"video","url":"https://cdn.example.com/a.mp4","durationSec":10}]"""

    @Test
    fun `a written body reads back unchanged`() {
        val file = File(folder.root, "playlist-cache.json")

        assertTrue(PlaylistCache.write(file, body))
        assertEquals(body, PlaylistCache.read(file))
    }

    @Test
    fun `a missing file reads as null`() {
        val file = File(folder.root, "does-not-exist.json")

        assertNull(PlaylistCache.read(file))
    }

    @Test
    fun `a corrupt file reads as null`() {
        val file = File(folder.root, "playlist-cache.json")
        // A half-written body is not valid UTF-8; it must not be handed to the
        // parser as though it were a playlist.
        file.writeBytes(byteArrayOf(0x5b, 0xC3.toByte(), 0x28, 0x5d))

        assertNull(PlaylistCache.read(file))
    }

    @Test
    fun `a file larger than the cap reads as null`() {
        val file = File(folder.root, "playlist-cache.json")
        file.writeText("x".repeat(2048))

        assertNull(PlaylistCache.read(file, maxBytes = 1024))
        // Exactly the cap is still readable; the reader bounds, it does not
        // truncate.
        file.writeText("x".repeat(1024))
        assertEquals("x".repeat(1024), PlaylistCache.read(file, maxBytes = 1024))
    }

    @Test
    fun `write creates the parent directory`() {
        val file = File(File(folder.root, "nested"), "playlist-cache.json")

        assertTrue(PlaylistCache.write(file, body))
        assertEquals(body, PlaylistCache.read(file))
    }

    @Test
    fun `delete removes the cached body and tolerates a missing file`() {
        val file = File(folder.root, "playlist-cache.json")
        assertTrue(PlaylistCache.write(file, body))

        PlaylistCache.delete(file)
        assertFalse(file.exists())
        assertNull(PlaylistCache.read(file))
        // A second delete is a no-op.
        PlaylistCache.delete(file)
    }
}
