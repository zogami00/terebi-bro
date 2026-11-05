package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileRulesTest {

    // ------------------------------------------------------------------
    // sanitize: accepted shapes
    // ------------------------------------------------------------------

    @Test
    fun `a normal name is accepted unchanged`() {
        assertEquals("promo.mp4", MediaFileRules.sanitize("promo.mp4"))
    }

    @Test
    fun `extensions are matched case-insensitively`() {
        assertEquals("Poster.JPEG", MediaFileRules.sanitize("Poster.JPEG"))
        assertEquals("logo.PNG", MediaFileRules.sanitize("logo.PNG"))
    }

    @Test
    fun `spaces dots underscores and dashes are allowed inside the stem`() {
        assertEquals("a b_c-d.e.mp4", MediaFileRules.sanitize("a b_c-d.e.mp4"))
    }

    @Test
    fun `trailing whitespace is trimmed`() {
        assertEquals("promo.mp4", MediaFileRules.sanitize("promo.mp4   "))
    }

    @Test
    fun `a 64 character stem is accepted`() {
        val stem = "a".repeat(64)
        assertEquals("$stem.mp4", MediaFileRules.sanitize("$stem.mp4"))
    }

    @Test
    fun `a 65 character stem is refused`() {
        assertNull(MediaFileRules.sanitize("a".repeat(65) + ".mp4"))
    }

    // ------------------------------------------------------------------
    // sanitize: every refusal
    // ------------------------------------------------------------------

    @Test
    fun `an empty or blank name is refused`() {
        assertNull(MediaFileRules.sanitize(""))
        assertNull(MediaFileRules.sanitize("   "))
    }

    @Test
    fun `a leading space is refused`() {
        assertNull(MediaFileRules.sanitize(" promo.mp4"))
    }

    @Test
    fun `a leading dot is refused`() {
        assertNull(MediaFileRules.sanitize(".mp4"))
        assertNull(MediaFileRules.sanitize(".hidden.mp4"))
    }

    @Test
    fun `a parent-directory sequence is refused`() {
        assertNull(MediaFileRules.sanitize(".."))
        assertNull(MediaFileRules.sanitize("a..b.mp4"))
    }

    @Test
    fun `path separators are refused`() {
        assertNull(MediaFileRules.sanitize("a/b.mp4"))
        assertNull(MediaFileRules.sanitize("a\\b.mp4"))
        assertNull(MediaFileRules.sanitize("../clip.mp4"))
    }

    @Test
    fun `control characters are refused`() {
        assertNull(MediaFileRules.sanitize("a\u0001b.mp4"))
        assertNull(MediaFileRules.sanitize("a\u007Fb.mp4"))
    }

    @Test
    fun `windows reserved device names are refused in any case`() {
        for (stem in listOf("CON", "con", "PRN", "AUX", "NUL", "COM1", "com9", "LPT1", "lpt9")) {
            assertNull("$stem.mp4", MediaFileRules.sanitize("$stem.mp4"))
        }
    }

    @Test
    fun `a reserved-looking but longer stem is accepted`() {
        assertEquals("CONSOLE.mp4", MediaFileRules.sanitize("CONSOLE.mp4"))
        assertEquals("COM0.mp4", MediaFileRules.sanitize("COM0.mp4"))
    }

    @Test
    fun `an unknown or missing extension is refused`() {
        assertNull(MediaFileRules.sanitize("notes.txt"))
        assertNull(MediaFileRules.sanitize("clip"))
        assertNull(MediaFileRules.sanitize("clip."))
    }

    @Test
    fun `every supported container extension is accepted`() {
        for (extension in listOf("mp4", "m4v", "mov", "mkv", "webm", "jpg", "jpeg", "png")) {
            assertEquals("clip.$extension", MediaFileRules.sanitize("clip.$extension"))
        }
    }

    @Test
    fun `only the last extension counts`() {
        assertNull(MediaFileRules.sanitize("mp4.txt"))
        assertEquals("mp4.txt.mp4", MediaFileRules.sanitize("mp4.txt.mp4"))
    }

    @Test
    fun `non-ascii characters are refused`() {
        assertNull(MediaFileRules.sanitize("caf\u00e9.mp4"))
        // An NFD form of the same name is normalised, then still fails the ASCII rule.
        assertNull(MediaFileRules.sanitize("cafe\u0301.mp4"))
    }

    @Test
    fun `a name over the hard 96 character bound is refused`() {
        assertNull(MediaFileRules.sanitize("a".repeat(97) + ".mp4"))
    }

    // ------------------------------------------------------------------
    // kindOf
    // ------------------------------------------------------------------

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ftyp at offset four is video`() {
        val magic = bytes(0, 0, 0, 0, 'f'.code, 't'.code, 'y'.code, 'p'.code)
        assertEquals(MediaItem.Kind.VIDEO, MediaFileRules.kindOf(magic))
    }

    @Test
    fun `ftyp at the wrong offset is not video`() {
        val magic = bytes('f'.code, 't'.code, 'y'.code, 'p'.code, 0, 0, 0, 0)
        assertNull(MediaFileRules.kindOf(magic))
    }

    @Test
    fun `the ebml header is video`() {
        val magic = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)
        assertEquals(MediaItem.Kind.VIDEO, MediaFileRules.kindOf(magic))
        assertEquals(MediaFileRules.Container.EBML, MediaFileRules.containerOf(magic))
    }

    @Test
    fun `a jpeg prefix is an image`() {
        assertEquals(
            MediaItem.Kind.IMAGE,
            MediaFileRules.kindOf(bytes(0xFF, 0xD8, 0xFF, 0xE0))
        )
    }

    @Test
    fun `a png signature is an image`() {
        assertEquals(
            MediaItem.Kind.IMAGE,
            MediaFileRules.kindOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        )
    }

    @Test
    fun `a truncated signature is refused`() {
        assertNull(MediaFileRules.kindOf(bytes(0xFF, 0xD8)))
        assertNull(MediaFileRules.kindOf(bytes(0x89, 0x50, 0x4E)))
        assertNull(MediaFileRules.kindOf(bytes(0, 0, 0, 0, 'f'.code, 't'.code, 'y'.code)))
    }

    @Test
    fun `an empty or random prefix is refused`() {
        assertNull(MediaFileRules.kindOf(ByteArray(0)))
        assertNull(MediaFileRules.kindOf(bytes(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun `a jpeg prefix on an mp4 name disagrees with the extension`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        assertEquals(MediaItem.Kind.IMAGE, MediaFileRules.kindOf(jpeg))
        assertEquals(MediaItem.Kind.VIDEO, MediaFileKinds.of("clip.mp4"))
    }

    // ------------------------------------------------------------------
    // contentMatchesExtension: the route's content gate
    // ------------------------------------------------------------------

    @Test
    fun `a jpeg renamed to mp4 is refused by the content gate`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mp4", jpeg))
        assertTrue(MediaFileRules.contentMatchesExtension("photo.jpg", jpeg))
    }

    @Test
    fun `a matching container is accepted`() {
        val mp4 = bytes(0, 0, 0, 0, 'f'.code, 't'.code, 'y'.code, 'p'.code)
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(MediaFileRules.contentMatchesExtension("clip.mp4", mp4))
        assertTrue(MediaFileRules.contentMatchesExtension("poster.png", png))
    }

    @Test
    fun `an unknown container or extension is refused by the content gate`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mp4", bytes(1, 2, 3, 4, 5, 6, 7, 8)))
        assertFalse(MediaFileRules.contentMatchesExtension("notes.txt", jpeg))
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mp4", ByteArray(0)))
    }

    @Test
    fun `mkv and webm require the ebml header`() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)
        assertTrue(MediaFileRules.contentMatchesExtension("clip.mkv", ebml))
        assertTrue(MediaFileRules.contentMatchesExtension("clip.webm", ebml))
    }

    @Test
    fun `an mkv whose magic is ftyp is refused`() {
        val mp4 = bytes(0, 0, 0, 0, 'f'.code, 't'.code, 'y'.code, 'p'.code)
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mkv", mp4))
        assertFalse(MediaFileRules.contentMatchesExtension("clip.webm", mp4))
    }

    @Test
    fun `an mp4 whose magic is ebml is refused`() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mp4", ebml))
        assertFalse(MediaFileRules.contentMatchesExtension("clip.m4v", ebml))
        assertFalse(MediaFileRules.contentMatchesExtension("clip.mov", ebml))
    }

    // ------------------------------------------------------------------
    // contentTypeOf
    // ------------------------------------------------------------------

    @Test
    fun `content types follow the extension`() {
        assertEquals("video/mp4", MediaFileRules.contentTypeOf("a.mp4"))
        assertEquals("video/mp4", MediaFileRules.contentTypeOf("a.M4V"))
        assertEquals("video/quicktime", MediaFileRules.contentTypeOf("a.mov"))
        assertEquals("video/x-matroska", MediaFileRules.contentTypeOf("a.mkv"))
        assertEquals("video/webm", MediaFileRules.contentTypeOf("a.webm"))
        assertEquals("image/jpeg", MediaFileRules.contentTypeOf("a.jpg"))
        assertEquals("image/jpeg", MediaFileRules.contentTypeOf("a.JPEG"))
        assertEquals("image/png", MediaFileRules.contentTypeOf("a.png"))
        assertNull(MediaFileRules.contentTypeOf("a.txt"))
    }

    // ------------------------------------------------------------------
    // accepts: every verdict and each cap boundary
    // ------------------------------------------------------------------

    @Test
    fun `caps are the documented constants`() {
        assertEquals(256L * 1024 * 1024, MediaFileRules.Caps.MAX_BYTES)
        assertEquals(200, MediaFileRules.Caps.MAX_FILES)
        assertEquals(2L * 1024 * 1024 * 1024, MediaFileRules.Caps.MAX_TOTAL_BYTES)
        assertEquals(256L * 1024 * 1024, MediaFileRules.Caps.RESERVE_BYTES)
        assertEquals(32L * 1024 * 1024, MediaFileRules.Caps.PREVIEW_MAX_BYTES)
    }

    @Test
    fun `a small upload that fits every cap is accepted`() {
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(0, 0L, 4L * 1024 * 1024 * 1024, 1024L)
        )
    }

    @Test
    fun `the per-file size cap is inclusive and one byte over is too large`() {
        val max = MediaFileRules.Caps.MAX_BYTES
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(0, 0L, 4L * 1024 * 1024 * 1024, max)
        )
        assertEquals(
            MediaFileRules.Verdict.TooLarge,
            MediaFileRules.accepts(0, 0L, 4L * 1024 * 1024 * 1024, max + 1)
        )
    }

    @Test
    fun `a negative incoming size is too large`() {
        assertEquals(
            MediaFileRules.Verdict.TooLarge,
            MediaFileRules.accepts(0, 0L, 4L * 1024 * 1024 * 1024, -1L)
        )
    }

    @Test
    fun `the file count cap is inclusive and one file over is too many`() {
        val max = MediaFileRules.Caps.MAX_FILES
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(max - 1, 0L, 4L * 1024 * 1024 * 1024, 1L)
        )
        assertEquals(
            MediaFileRules.Verdict.TooManyFiles,
            MediaFileRules.accepts(max, 0L, 4L * 1024 * 1024 * 1024, 1L)
        )
    }

    @Test
    fun `the total quota is inclusive and one byte over is over quota`() {
        val total = MediaFileRules.Caps.MAX_TOTAL_BYTES
        val incoming = 10L
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(1, total - incoming, 4L * 1024 * 1024 * 1024, incoming)
        )
        assertEquals(
            MediaFileRules.Verdict.OverQuota,
            MediaFileRules.accepts(1, total - incoming + 1, 4L * 1024 * 1024 * 1024, incoming)
        )
    }

    @Test
    fun `the free-space reserve is inclusive and one byte under is no space`() {
        val incoming = 1024L
        val reserve = MediaFileRules.Caps.RESERVE_BYTES
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(0, 0L, reserve + incoming, incoming)
        )
        assertEquals(
            MediaFileRules.Verdict.NoSpace,
            MediaFileRules.accepts(0, 0L, reserve + incoming - 1, incoming)
        )
        assertEquals(
            MediaFileRules.Verdict.NoSpace,
            MediaFileRules.accepts(0, 0L, 0L, incoming)
        )
    }

    @Test
    fun `a zero-byte upload at exactly the reserve is accepted`() {
        assertEquals(
            MediaFileRules.Verdict.Ok,
            MediaFileRules.accepts(0, 0L, MediaFileRules.Caps.RESERVE_BYTES, 0L)
        )
    }

    @Test
    fun `too large wins over every other violated cap`() {
        assertEquals(
            MediaFileRules.Verdict.TooLarge,
            MediaFileRules.accepts(
                MediaFileRules.Caps.MAX_FILES,
                MediaFileRules.Caps.MAX_TOTAL_BYTES,
                0L,
                MediaFileRules.Caps.MAX_BYTES + 1
            )
        )
    }
}
