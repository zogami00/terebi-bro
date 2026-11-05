package com.terebibro.tv.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The last-good playlist kept on internal storage, so a cold boot with no
 * network still has a queue.
 *
 * The file holds a playlist **body** (`[{type,url,durationSec}]`), not the
 * parsed items: a cache read re-enters exactly the same strict parse path as a
 * network body, so a corrupt or outdated cache can never inject an item the
 * network would have rejected. Plain `java.io` only, so it is unit-testable
 * without a device.
 *
 * Every operation is best effort: a cache miss only costs one offline boot.
 */
object PlaylistCache {

    /**
     * Reads [file] as UTF-8, or null when it is missing, larger than
     * [maxBytes] or not valid UTF-8 (a truncated or half-written file is
     * corrupt and must not be parsed as if it were a playlist).
     */
    fun read(file: File, maxBytes: Int = MediaPlaylist.MAX_BYTES): String? {
        return try {
            if (!file.isFile) return null
            if (file.length() > maxBytes.toLong()) return null
            val bytes = file.readBytes()
            if (bytes.size > maxBytes) return null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Writes [body] through a temporary file so a crash mid-write cannot leave
     * a half-serialised playlist behind. Returns whether the file is in place.
     */
    fun write(file: File, body: String): Boolean {
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(body)
            if (tmp.renameTo(file)) return true
            // A stale cache file blocks the rename on some filesystems.
            file.delete()
            if (tmp.renameTo(file)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Removes the cached body; a missing or undeletable file is not an error. */
    fun delete(file: File) {
        try {
            if (file.isFile) file.delete()
        } catch (e: Exception) {
            // Best effort.
        }
    }
}
