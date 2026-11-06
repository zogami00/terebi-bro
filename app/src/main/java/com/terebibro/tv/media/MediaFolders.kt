package com.terebibro.tv.media

import java.io.File

/**
 * Pure-JVM rules for playing media from a folder the user picked on the TV's
 * own storage or a USB/SD volume (`MediaSettings.mediaLocalFolder`). The
 * Android side supplies the volume roots; everything here works on plain
 * `File`s so it can be tested against a temporary directory.
 */
object MediaFolders {

    const val MAX_LENGTH = 1024

    /** Blank (app storage) or an absolute path with no `..` segment. */
    fun isValidSetting(path: String): Boolean =
        path.isEmpty() ||
            (path.length <= MAX_LENGTH &&
                path.startsWith("/") &&
                '\u0000' !in path &&
                path.split('/').none { it == ".." })

    /**
     * The canonical folder when [path] is inside one of [roots] (or is a root),
     * otherwise null. Browsing never leaves the storage volumes.
     */
    fun within(roots: List<File>, path: String): File? = try {
        val dir = File(path).canonicalFile
        val inside = roots.any { root ->
            val r = root.canonicalFile
            dir == r || dir.path.startsWith(r.path + File.separator)
        }
        if (inside && dir.isDirectory) dir else null
    } catch (e: Exception) {
        null
    }

    /** Visible subfolders of [dir], sorted by name. */
    fun subfolders(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }

    /** Number of playable files directly inside [dir]. */
    fun mediaCount(dir: File): Int =
        (dir.listFiles() ?: emptyArray()).count { it.isFile && MediaPaths.isListableName(it.name) }
}
