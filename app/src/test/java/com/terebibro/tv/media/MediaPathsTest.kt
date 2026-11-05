package com.terebibro.tv.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [MediaPaths] over a real temporary directory: the traversal, symlink and
 * parent checks the store delegates to, without a `Context`.
 */
class MediaPathsTest {

    private lateinit var root: File
    private lateinit var base: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("terebi-media-paths").toFile()
        base = File(root, "media")
        assertTrue(base.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun touch(name: String, vararg magic: Int): File {
        val file = File(base, name)
        file.writeBytes(ByteArray(magic.size) { magic[it].toByte() })
        return file
    }

    @Test
    fun `a normal stored file resolves to its canonical path`() {
        val created = touch("clip.mp4", 0, 0, 0, 0)
        val resolved = MediaPaths.resolve(base, "clip.mp4")
        assertNotNull(resolved)
        assertEquals(created.canonicalFile, resolved)
    }

    @Test
    fun `a missing file resolves to null`() {
        assertNull(MediaPaths.resolve(base, "missing.mp4"))
    }

    @Test
    fun `a parent-directory sequence is refused`() {
        assertNull(MediaPaths.resolve(base, ".."))
        assertNull(MediaPaths.resolve(base, "../clip.mp4"))
        assertNull(MediaPaths.resolve(base, "a..b.mp4"))
    }

    @Test
    fun `a path separator is refused even when the file exists`() {
        touch("clip.mp4")
        assertNull(MediaPaths.resolve(base, "sub/clip.mp4"))
        assertNull(MediaPaths.resolve(base, "sub\\clip.mp4"))
    }

    @Test
    fun `a name that sanitises to something different is refused`() {
        val created = touch("clip.mp4")
        // Trailing whitespace would be trimmed by sanitise: the caller must not
        // get the file under a different name than it asked for.
        assertNull(MediaPaths.resolve(base, "clip.mp4   "))
        assertNotNull(MediaPaths.resolve(base, created.name))
    }

    @Test
    fun `a symlink pointing outside the folder is refused`() {
        val outsideDir = File(root, "outside")
        assertTrue(outsideDir.mkdirs())
        val outside = File(outsideDir, "secret.mp4")
        outside.writeBytes(byteArrayOf(1, 2, 3))
        val link = File(base, "link.mp4")

        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            // Creating symlinks needs a privilege on some platforms (notably
            // Windows without Developer Mode); skip rather than fail.
            Assume.assumeNoException("symlinks unavailable in this environment", e)
        }

        assertNull(MediaPaths.resolve(base, "link.mp4"))
        assertFalse(MediaPaths.isDirectChild(base, link))
    }

    @Test
    fun `targetFor accepts a name that does not exist yet and keeps it inside the folder`() {
        val target = MediaPaths.targetFor(base, "new.mp4")
        assertNotNull(target)
        assertEquals(File(base.canonicalFile, "new.mp4"), target)
    }

    @Test
    fun `targetFor refuses an unsafe name`() {
        assertNull(MediaPaths.targetFor(base, "../new.mp4"))
        assertNull(MediaPaths.targetFor(base, "new.mkv"))
        assertNull(MediaPaths.targetFor(base, ".new.mp4"))
    }

    @Test
    fun `upload temp names are recognised and excluded from the listing`() {
        assertTrue(MediaPaths.isUploadTemp(".upload-1234.part"))
        assertFalse(MediaPaths.isUploadTemp("clip.mp4"))
        // Only the dedicated prefix+suffix pair is a temp; a user file ending
        // in .part is not swept or hidden by this rule.
        assertFalse(MediaPaths.isUploadTemp("notes.part"))

        assertFalse(MediaPaths.isListableName(".upload-1234.part"))
        assertFalse(MediaPaths.isListableName("notes.txt"))
        assertTrue(MediaPaths.isListableName("clip.mp4"))
        assertTrue(MediaPaths.isListableName("poster.PNG"))
    }

    // ------------------------------------------------------------------
    // localPlaybackPath: the source string MediaPlayer.setDataSource gets
    // ------------------------------------------------------------------

    private fun item(id: String, remote: Boolean = false) =
        MediaItem(id = id, name = id, kind = MediaItem.Kind.VIDEO, durationMs = 0L, remote = remote)

    @Test
    fun `a local item yields the absolute path of the stored file`() {
        val created = touch("clip.mp4")
        val path = MediaPaths.localPlaybackPath(base, item("clip.mp4"))
        assertEquals(created.canonicalFile.absolutePath, path)
        assertTrue(File(path!!).isAbsolute)
    }

    @Test
    fun `a remote item yields null so the caller keeps the URL`() {
        assertNull(MediaPaths.localPlaybackPath(base, item("https://example.test/clip.mp4", remote = true)))
    }

    @Test
    fun `a local item that is missing yields null`() {
        assertNull(MediaPaths.localPlaybackPath(base, item("missing.mp4")))
    }
}
