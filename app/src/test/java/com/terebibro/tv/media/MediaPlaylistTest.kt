package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaPlaylistTest {

    private val base = "https://example.com/media/playlist.json"

    private fun entry(
        type: String = "video",
        url: String = "https://cdn.example.com/clip.mp4",
        durationSec: String = "10"
    ) = """{"type":"$type","url":"$url","durationSec":$durationSec}"""

    private fun list(vararg entries: String) = entries.joinToString(",", "[", "]")

    private fun valid(body: String, base: String = ""): List<MediaItem> {
        val result = MediaPlaylist.parse(body, base)
        assertTrue("expected a valid playlist, got $result", result is MediaPlaylist.Result.Valid)
        return (result as MediaPlaylist.Result.Valid).items
    }

    private fun invalid(body: String, base: String = ""): String {
        val result = MediaPlaylist.parse(body, base)
        assertTrue("expected an invalid playlist, got $result", result is MediaPlaylist.Result.Invalid)
        return (result as MediaPlaylist.Result.Invalid).reason
    }

    @Test
    fun `a valid list becomes remote items with the documented fields`() {
        val items = valid(
            list(
                entry(type = "video", url = "https://cdn.example.com/a.mp4", durationSec = "30"),
                entry(type = "image", url = "https://cdn.example.com/b.jpg", durationSec = "5")
            )
        )

        assertEquals(2, items.size)
        assertEquals("https://cdn.example.com/a.mp4", items[0].id)
        assertEquals("https://cdn.example.com/a.mp4", items[0].name)
        assertEquals(MediaItem.Kind.VIDEO, items[0].kind)
        assertEquals(30_000L, items[0].durationMs)
        assertTrue(items[0].remote)
        assertEquals(MediaItem.Kind.IMAGE, items[1].kind)
        assertEquals(5_000L, items[1].durationMs)
        assertTrue(items[1].remote)
    }

    @Test
    fun `a relative entry is resolved against the playlist url`() {
        val items = valid(
            list(entry(url = "clips/promo.mp4"), entry(type = "image", url = "/stills/room.jpg")),
            base = base
        )

        assertEquals("https://example.com/media/clips/promo.mp4", items[0].id)
        assertEquals("https://example.com/stills/room.jpg", items[1].id)
    }

    @Test
    fun `resolve rejects every non http scheme`() {
        assertNull(MediaPlaylist.resolve(base, "javascript:alert(1)"))
        assertNull(MediaPlaylist.resolve(base, "data:text/html;base64,AAAA"))
        assertNull(MediaPlaylist.resolve(base, "file:///etc/passwd"))
        assertNull(MediaPlaylist.resolve(base, ""))
        assertNull(MediaPlaylist.resolve(base, "ftp://example.com/a.mp4"))
        assertEquals("https://example.com/a.mp4", MediaPlaylist.resolve(base, "https://example.com/a.mp4"))
    }

    @Test
    fun `resolve rejects a protocol-relative reference`() {
        // "//evil.com/a.mp4" would inherit the playlist's scheme and land on
        // another host, so it must never validate.
        assertNull(MediaPlaylist.resolve(base, "//evil.com/a.mp4"))
        assertNull(MediaPlaylist.resolve("", "//evil.com/a.mp4"))
        // A single slash stays a path on the playlist's own host.
        assertEquals("https://example.com/a.mp4", MediaPlaylist.resolve(base, "/a.mp4"))
    }

    @Test
    fun `a protocol-relative entry is skipped while the rest of the playlist survives`() {
        val items = valid(
            list(
                entry(url = "//evil.com/a.mp4"),
                entry(url = "https://cdn.example.com/kept.mp4")
            ),
            base = base
        )

        assertEquals(listOf("https://cdn.example.com/kept.mp4"), items.map { it.id })
    }

    @Test
    fun `a rejected scheme rejects the entry, not the playlist`() {
        val items = valid(
            list(
                entry(url = "javascript:alert(1)"),
                entry(url = "data:text/plain,hi"),
                entry(url = "file:///sdcard/Android/data/com.terebibro.tv/files/media/a.mp4"),
                entry(url = "https://cdn.example.com/kept.mp4")
            )
        )

        assertEquals(1, items.size)
        assertEquals("https://cdn.example.com/kept.mp4", items[0].id)
    }

    @Test
    fun `one bad entry is skipped while the rest survive`() {
        val items = valid(
            list(
                entry(url = "https://cdn.example.com/a.mp4"),
                """{"type":"audio","url":"https://cdn.example.com/b.mp3","durationSec":10}""",
                entry(url = "https://cdn.example.com/c.jpg", type = "image"),
                """{"type":"video","url":"https://cdn.example.com/d.mp4"}""",
                """{"type":"video","url":"https://cdn.example.com/e.mp4","durationSec":10,"extra":1}""",
                entry(url = "https://cdn.example.com/f.mp4")
            )
        )

        assertEquals(
            listOf("https://cdn.example.com/a.mp4", "https://cdn.example.com/c.jpg", "https://cdn.example.com/f.mp4"),
            items.map { it.id }
        )
    }

    @Test
    fun `every entry bad is an invalid playlist`() {
        invalid(list(entry(type = "audio"), entry(url = "javascript:alert(1)")))
        invalid("[]")
    }

    @Test
    fun `more than two hundred items is invalid`() {
        val entries = (1..MediaPlaylist.MAX_ITEMS + 1)
            .map { entry(url = "https://cdn.example.com/$it.mp4") }
        invalid(list(*entries.toTypedArray()))
    }

    @Test
    fun `exactly two hundred items is accepted`() {
        val entries = (1..MediaPlaylist.MAX_ITEMS)
            .map { entry(url = "https://cdn.example.com/$it.mp4") }
        assertEquals(MediaPlaylist.MAX_ITEMS, valid(list(*entries.toTypedArray())).size)
    }

    @Test
    fun `a body larger than 256 KiB is invalid`() {
        val oversized = "[" + " ".repeat(MediaPlaylist.MAX_BYTES) + "]"
        assertEquals("too_large", invalid(oversized))
    }

    @Test
    fun `malformed json is invalid`() {
        assertEquals("malformed", invalid(""))
        assertEquals("malformed", invalid("not json"))
        assertEquals("malformed", invalid("""{"type":"video"}"""))
        assertEquals("malformed", invalid("""[{"type":"video""""))
    }

    @Test
    fun `durationSec is bounded to 3 to 120`() {
        // 2 is out of range, and a one-entry playlist with no valid entry is
        // an invalid playlist rather than an empty queue.
        assertTrue(MediaPlaylist.parse(list(entry(durationSec = "2"))) is MediaPlaylist.Result.Invalid)

        assertEquals(3_000L, valid(list(entry(durationSec = "3")))[0].durationMs)
        assertEquals(120_000L, valid(list(entry(durationSec = "120")))[0].durationMs)
        assertTrue(MediaPlaylist.parse(list(entry(durationSec = "121"))) is MediaPlaylist.Result.Invalid)
    }

    @Test
    fun `an out-of-range duration skips the entry, not the playlist`() {
        // Entry-level boundaries: 2 and 121 are skipped while a valid
        // neighbour still forms a usable playlist (skip, never reject).
        for (duration in listOf("2", "121")) {
            val items = valid(
                list(
                    entry(url = "https://cdn.example.com/short.mp4", durationSec = duration),
                    entry(url = "https://cdn.example.com/kept.mp4", durationSec = "10")
                )
            )
            assertEquals(
                "durationSec=$duration must skip exactly one entry",
                listOf("https://cdn.example.com/kept.mp4"),
                items.map { it.id }
            )
        }
    }

    @Test
    fun `the type is case-sensitive`() {
        val items = valid(
            list(
                entry(type = "Video"),
                entry(type = "IMAGE"),
                entry(type = "video", url = "https://cdn.example.com/kept.mp4")
            )
        )

        assertEquals(listOf("https://cdn.example.com/kept.mp4"), items.map { it.id })
    }

    @Test
    fun `a non-integer duration rejects the entry`() {
        val items = valid(
            list(
                entry(durationSec = "10.5"),
                entry(durationSec = "\"10\""),
                entry(url = "https://cdn.example.com/ok.mp4", durationSec = "10")
            )
        )

        assertEquals(1, items.size)
        assertEquals(10_000L, items[0].durationMs)
    }

    @Test
    fun `an unknown key rejects the entry`() {
        val items = valid(
            list(
                """{"type":"video","url":"https://cdn.example.com/a.mp4","durationSec":10,"schedule":"night"}""",
                entry(url = "https://cdn.example.com/b.mp4")
            )
        )

        assertEquals(listOf("https://cdn.example.com/b.mp4"), items.map { it.id })
    }

    @Test
    fun `a missing key rejects the entry`() {
        val items = valid(
            list(
                """{"url":"https://cdn.example.com/a.mp4","durationSec":10}""",
                """{"type":"video","durationSec":10}""",
                """{"type":"video","url":"https://cdn.example.com/b.mp4"}""",
                entry(url = "https://cdn.example.com/c.mp4")
            )
        )

        assertEquals(listOf("https://cdn.example.com/c.mp4"), items.map { it.id })
    }

    @Test
    fun `duplicate urls are preserved in order`() {
        val items = valid(
            list(
                entry(url = "https://cdn.example.com/a.mp4"),
                entry(url = "https://cdn.example.com/a.mp4")
            )
        )

        assertEquals(listOf("https://cdn.example.com/a.mp4", "https://cdn.example.com/a.mp4"), items.map { it.id })
    }

    @Test
    fun `readBounded stops at the cap instead of buffering the whole body`() {
        val payload = "x".repeat(64)
        val stream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        assertEquals(payload, MediaPlaylist.readBounded(stream, 64))

        val tooLong = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        assertNull(MediaPlaylist.readBounded(tooLong, 63))
    }

    @Test
    fun `readBounded stops consuming the stream once the cap is passed`() {
        val payload = ByteArray(256 * 1024) { 'x'.code.toByte() }
        val counting = CountingStream(payload)
        val cap = 1024

        assertNull(MediaPlaylist.readBounded(counting, cap))
        // The reader may overshoot by at most one chunk; it must never drain
        // an unbounded body into memory.
        assertTrue(
            "consumed ${counting.consumed} bytes for a $cap byte cap",
            counting.consumed <= cap + MediaPlaylist.READ_CHUNK_BYTES
        )
        assertTrue(counting.consumed < payload.size)
    }

    @Test
    fun `readBounded returns null when the stream throws`() {
        val throwing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("boom")
        }

        assertNull(MediaPlaylist.readBounded(throwing, 1024))
    }

    /** Counts the bytes the reader actually pulled out of the payload. */
    private class CountingStream(private val payload: ByteArray) : java.io.InputStream() {
        var consumed = 0
        private var position = 0

        override fun read(): Int {
            if (position >= payload.size) return -1
            consumed++
            return payload[position++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= payload.size) return -1
            val count = minOf(len, payload.size - position)
            System.arraycopy(payload, position, b, off, count)
            position += count
            consumed += count
            return count
        }
    }

    @Test
    fun `a cached body round-trips through the parser`() {
        // The controller writes the cache as a playlist body, so the cache is
        // parsed by exactly the same strict path.
        val body = list(entry(url = "https://cdn.example.com/a.mp4", durationSec = "15"))
        val first = valid(body)
        val again = valid(body)

        assertEquals(first, again)
    }
}
