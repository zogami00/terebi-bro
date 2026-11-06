package com.terebibro.tv.media

import android.content.Context
import android.os.storage.StorageManager
import android.os.StatFs
import java.io.File
import java.io.IOException

/** One stored library file, as the controller lists it. */
data class MediaFileInfo(
    val name: String,
    val bytes: Long,
    val kind: MediaItem.Kind,
    val modifiedMs: Long
)

/**
 * The local media folder (`getExternalFilesDir(null)/media`).
 *
 * Media lives on external app storage only. When that volume is unavailable
 * `getExternalFilesDir(null)` is null and [dir] returns null: the library then
 * degrades to empty/zero and writes are refused. It must never fall back to
 * `filesDir`, because filling the internal volume can make the token/config
 * `commit()` fail.
 *
 * Every method is plain file I/O with no UI or main-thread assumption. Files are
 * stored under their validated display name (`id = name`); see [MediaFileRules]
 * for why the scope's hash + manifest scheme is not used. The path policy itself
 * lives in [MediaPaths], so it can be unit-tested against a temporary folder.
 *
 * Nothing runs at construction: [sweepPartFiles] is called by the owner on its
 * IO executor so no disk I/O is added to the UI thread at startup.
 */
/**
 * [localFolder] is `MediaSettings.mediaLocalFolder`: blank plays the app's own
 * upload folder, anything else plays that folder on the TV or a USB/SD volume.
 * Uploads, deletes and previews always use the app folder.
 */
class MediaStore(
    private val context: Context,
    private val localFolder: () -> String = { "" }
) {

    /** Thrown by [upload] when the target name already exists; never an overwrite. */
    class DuplicateNameException(val name: String) : IOException("Duplicate media name")

    /**
     * The media folder, created on first use, or null when external app storage
     * is not available. Callers treat null as an empty library / refused write.
     */
    fun dir(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        val dir = File(base, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * The folder the local source plays, or null when it is unavailable (an
     * unplugged USB drive, or no media-read permission yet): an empty library.
     */
    fun playDir(): File? {
        val folder = localFolder()
        if (folder.isEmpty()) return dir()
        val dir = File(folder)
        return if (dir.isDirectory && dir.canRead()) dir else null
    }

    /**
     * The roots a folder may be picked from: internal shared storage and every
     * mounted USB/SD volume, labelled the way the system names them. Derived
     * from the app's external dirs (one per volume), which works on every API.
     */
    fun storageRoots(): List<Pair<String, File>> {
        val sm = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            val path = appDir.absolutePath
            val cut = path.indexOf("/Android/data/")
            if (cut <= 0) return@mapNotNull null
            val root = File(path.substring(0, cut))
            val label = try {
                sm?.getStorageVolume(root)?.getDescription(context)
            } catch (e: Exception) {
                null
            } ?: root.name
            label to root
        }
    }

    fun list(): List<MediaItem> {
        val dir = playDir() ?: return emptyList()
        val files = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .filter { MediaPaths.isListableName(it.name) }
            .mapNotNull { file ->
                val kind = MediaFileKinds.of(file.name) ?: return@mapNotNull null
                // The controller supplies the image duration; the store only
                // knows that a kind is playable.
                MediaItem(id = file.name, name = file.name, kind = kind, durationMs = 0L)
            }
            .sortedBy { it.name }
            .toList()
    }

    /** The library with the size and timestamp the controller list shows. */
    fun describe(): List<MediaFileInfo> {
        val dir = dir() ?: return emptyList()
        val files = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .filter { MediaPaths.isListableName(it.name) }
            .mapNotNull { file ->
                val kind = MediaFileKinds.of(file.name) ?: return@mapNotNull null
                MediaFileInfo(
                    name = file.name,
                    bytes = file.length(),
                    kind = kind,
                    modifiedMs = file.lastModified()
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    fun totalBytes(): Long = guarded {
        dir()?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    fun freeBytes(): Long = guarded {
        val dir = dir() ?: return@guarded 0L
        StatFs(dir.absolutePath).availableBytes
    }

    /**
     * Resolves a stored name to its file, or null when it is not a safe,
     * regular, non-symlink file inside the media folder.
     */
    fun file(name: String): File? {
        val base = dir() ?: return null
        return MediaPaths.resolve(base, name)
    }

    /**
     * Atomically moves a finished upload into the library. The temp file must
     * already live inside the media folder (it is created there by the route),
     * and the target is claimed with `createNewFile` before the rename so a
     * concurrent `adb push` cannot be overwritten. A duplicate is refused with
     * [DuplicateNameException]; a failed rename removes the claim.
     */
    fun upload(tempFile: File, name: String): MediaItem {
        val base = dir() ?: throw IOException("Media storage is unavailable")
        val target = MediaPaths.targetFor(base, name) ?: throw IOException("Invalid library name")
        if (!MediaPaths.isDirectChild(base, tempFile)) {
            throw IOException("Upload temp is outside the media folder")
        }
        if (!tempFile.isFile) throw IOException("Upload temp is missing")

        // Claim the name without touching any existing file. Two uploads that
        // race, or an upload racing an adb push, cannot both succeed.
        if (!target.createNewFile()) throw DuplicateNameException(name)

        if (!tempFile.renameTo(target)) {
            // Same directory, so this is an atomic rename on normal storage; a
            // failure means it did not land. Remove both the temp and the empty
            // claim so neither lingers in the listing.
            deleteQuietly(tempFile)
            deleteQuietly(target)
            throw IOException("Could not move the upload into the library")
        }
        val kind = MediaFileKinds.of(name) ?: throw IOException("Unplayable extension")
        return MediaItem(id = name, name = name, kind = kind, durationMs = 0L)
    }

    /** Deletes a library entry by name. Returns false when it was not a real entry. */
    fun delete(name: String): Boolean {
        val file = file(name) ?: return false
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Removes `*.part` leftovers from an interrupted upload. Safe to run at any
     * time: only the dedicated upload prefix and suffix are touched, and a live
     * upload holds its own temp file in this same folder. A missing volume is a
     * no-op.
     */
    fun sweepPartFiles() {
        val dir = dir() ?: return
        try {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && MediaPaths.isUploadTemp(file.name)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Storage unavailable: nothing to sweep.
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (ignored: Exception) {
            // ignore
        }
    }

    private inline fun guarded(block: () -> Long): Long =
        try {
            block()
        } catch (e: Exception) {
            0L
        }
}
