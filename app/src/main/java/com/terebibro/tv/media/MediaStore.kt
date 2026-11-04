package com.terebibro.tv.media

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * The local media folder (`getExternalFilesDir(null)/media`).
 *
 * Every method is plain file I/O with no UI or main-thread assumption. Storage
 * may be missing or unmounted, so the accessors degrade to an empty library or
 * zero instead of throwing.
 */
class MediaStore(private val context: Context) {

    fun dir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun list(): List<MediaItem> {
        val files = try {
            dir().listFiles()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val kind = MediaFileKinds.of(file.name) ?: return@mapNotNull null
                // The controller supplies the image duration; the store only
                // knows that a kind is playable.
                MediaItem(id = file.name, name = file.name, kind = kind, durationMs = 0L)
            }
            .sortedBy { it.name }
            .toList()
    }

    fun totalBytes(): Long = guarded {
        dir().listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    fun freeBytes(): Long = guarded {
        StatFs(dir().absolutePath).availableBytes
    }

    private inline fun guarded(block: () -> Long): Long =
        try {
            block()
        } catch (e: Exception) {
            0L
        }
}
