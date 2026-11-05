package com.terebibro.tv.media

import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM path policy for the media folder.
 *
 * The store owns the Android storage lookup (`Context.getExternalFilesDir`),
 * but every name-to-file decision lives here, so traversal, symlink and parent
 * checks can be exercised against an ordinary temporary directory. No
 * `Context`, no `StatFs`, no Android types.
 *
 * A name is never trusted. It must sanitise to exactly itself, and the resolved
 * path must stay a direct child of the canonical media folder. A symlink is
 * refused even when it points back inside the folder, because the library
 * should consist of regular files only.
 */
object MediaPaths {

    /** The canonical media folder, or null when the path cannot be resolved. */
    fun canonicalBase(base: File): File? = try {
        base.canonicalFile
    } catch (e: Exception) {
        null
    }

    /**
     * Resolves [name] to an existing regular, non-symlink file that is a direct
     * child of [base]. Returns null for anything else, including a missing file.
     */
    fun resolve(base: File, name: String): File? = try {
        val canonicalBase = canonicalBase(base) ?: return null
        val candidate = targetIn(canonicalBase, name) ?: return null
        if (Files.isSymbolicLink(candidate.toPath())) {
            null
        } else {
            val canonical = candidate.canonicalFile
            if (canonical.parentFile != canonicalBase || !canonical.isFile) null else canonical
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The canonical path an upload named [name] would occupy, whether or not it
     * exists yet. Null for an unsafe name or a path that would leave [base].
     */
    fun targetFor(base: File, name: String): File? = try {
        val canonicalBase = canonicalBase(base) ?: return null
        targetIn(canonicalBase, name)
    } catch (e: Exception) {
        null
    }

    /**
     * The filesystem path a video player should open for [item], or null when
     * there is nothing it can open.
     *
     * A remote item is a URL, not a path, so it yields null and the caller keeps
     * the URL as its source. A local item only resolves to an existing regular
     * file directly inside [base], exactly like [resolve] — `MediaPlayer` must
     * receive a plain path because `setDataSource(Context, Uri)` cannot read a
     * `file://` Uri.
     */
    fun localPlaybackPath(base: File, item: MediaItem): String? {
        if (item.remote) return null
        return resolve(base, item.id)?.absolutePath
    }

    /** True when [file] (canonically) is a direct child of the canonical [base]. */
    fun isDirectChild(base: File, file: File): Boolean = try {
        val canonicalBase = canonicalBase(base) ?: return false
        file.canonicalFile.parentFile == canonicalBase
    } catch (e: Exception) {
        false
    }

    /**
     * True for the dedicated temporary files an upload creates. Such a file is
     * never a library entry, even before the sweep removes it.
     */
    fun isUploadTemp(name: String): Boolean =
        name.startsWith(".upload-") && name.endsWith(".part")

    /** True when the listing may show [name]: not an upload temp and a known kind. */
    fun isListableName(name: String): Boolean =
        !isUploadTemp(name) && MediaFileKinds.of(name) != null

    private fun targetIn(canonicalBase: File, name: String): File? {
        val safe = MediaFileRules.sanitize(name) ?: return null
        // A value that sanitising would change (trim, normalise) is refused
        // rather than silently treated as a different name.
        if (safe != name) return null
        val canonical = File(canonicalBase, safe).canonicalFile
        return if (canonical.parentFile == canonicalBase) canonical else null
    }
}
