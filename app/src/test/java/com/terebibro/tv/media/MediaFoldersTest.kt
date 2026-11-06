package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaFoldersTest {

    @Test
    fun settingMustBeBlankOrAbsoluteWithoutParentSegments() {
        assertTrue(MediaFolders.isValidSetting(""))
        assertTrue(MediaFolders.isValidSetting("/storage/ABCD-1234/Movies"))
        assertFalse(MediaFolders.isValidSetting("Movies"))
        assertFalse(MediaFolders.isValidSetting("/storage/emulated/0/../../data"))
    }

    @Test
    fun browsingStaysInsideTheRoots() {
        val root = Files.createTempDirectory("vol").toFile()
        val movies = File(root, "Movies").apply { mkdir() }
        File(root, ".hidden").mkdir()
        File(movies, "a.mp4").writeText("x")
        File(movies, "notes.txt").writeText("x")
        val outside = Files.createTempDirectory("other").toFile()

        assertNotNull(MediaFolders.within(listOf(root), movies.path))
        assertNotNull(MediaFolders.within(listOf(root), root.path))
        assertNull(MediaFolders.within(listOf(root), outside.path))
        assertNull(MediaFolders.within(listOf(root), movies.path + "/../../" + outside.name))
        assertEquals(listOf("Movies"), MediaFolders.subfolders(root).map { it.name })
        assertEquals(1, MediaFolders.mediaCount(movies))
    }
}
