package com.terebibro.tv.media

import java.text.Normalizer

/**
 * Pure-JVM rules for the media library: filename validation, content sniffing
 * and the upload quota arithmetic. Kept free of Android and file-system types
 * so every refusal and every cap boundary can be unit-tested.
 *
 * **Deviation from the scope document.** The scope stores uploads under a hash
 * with the display name in a manifest. This implementation stores a validated
 * filename verbatim (`id = name`) and has no manifest. The folder therefore
 * stays human-manageable for `adb push` and USB transfers, and a validated name
 * plus a canonical-path check in `MediaStore` is already traversal-proof. A
 * duplicate name is refused (`409 duplicate_name`), never overwritten.
 *
 * Percent-decoding is *not* done here: the HTTP header arrives already decoded
 * by the caller, which is the only layer that knows it came from a header.
 */
object MediaFileRules {

    /** Upper bound on the sanitised name, in characters. */
    const val MAX_NAME_CHARS = 96

    /** The permitted shape: an ASCII stem of at most 64 chars, then a known extension. */
    private val NAME_REGEX =
        Regex(
            "^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}\\.(mp4|m4v|mov|mkv|webm|jpg|jpeg|png)$",
            RegexOption.IGNORE_CASE
        )

    /** Windows reserved device names, refused so the folder stays portable. */
    private val RESERVED: Set<String> = buildSet {
        add("CON")
        add("PRN")
        add("AUX")
        add("NUL")
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /** Upload caps, hardcoded constants rather than settings. */
    object Caps {
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 200
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val RESERVE_BYTES = 256L * 1024 * 1024
        const val PREVIEW_MAX_BYTES = 32L * 1024 * 1024
    }

    /** Outcome of the pre-write quota and reserve check. */
    enum class Verdict {
        Ok,
        TooManyFiles,
        OverQuota,
        NoSpace,
        TooLarge
    }

    /**
     * Returns the safe filename, or null for anything a library entry must not
     * be. The result is NFC-normalised and trimmed; a leading space in the raw
     * value is still refused rather than silently changed.
     */
    fun sanitize(raw: String): String? {
        // Control characters and path separators are refused before any
        // normalisation or trimming could disguise them.
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (raw.contains('/') || raw.contains('\\')) return null
        if (raw.isNotEmpty() && raw.first().isWhitespace()) return null

        val name = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return null
        if (name.startsWith(".")) return null
        if (name.contains("..")) return null
        if (!NAME_REGEX.matches(name)) return null

        val stem = name.substringBeforeLast('.')
        if (RESERVED.contains(stem.uppercase())) return null
        return name
    }

    /** The container families the content gate distinguishes. */
    enum class Container { MP4, EBML, JPEG, PNG }

    /**
     * Sniffs the container from the first bytes of the body: MP4-family
     * (`ftyp` at offset 4), Matroska/WebM (the EBML header `1A 45 DF A3`), JPEG
     * (`FF D8 FF`) or PNG (`89 50 4E 47`). Anything else — including a
     * truncated prefix — is null.
     */
    fun containerOf(magic: ByteArray): Container? {
        if (magic.size >= 8 &&
            magic[4] == 'f'.code.toByte() &&
            magic[5] == 't'.code.toByte() &&
            magic[6] == 'y'.code.toByte() &&
            magic[7] == 'p'.code.toByte()
        ) {
            return Container.MP4
        }
        if (magic.size >= 4 && u(magic[0]) == 0x1A && u(magic[1]) == 0x45 &&
            u(magic[2]) == 0xDF && u(magic[3]) == 0xA3
        ) {
            // EBML is shared by Matroska and WebM; the platform treats both as
            // video containers.
            return Container.EBML
        }
        if (magic.size >= 3 && u(magic[0]) == 0xFF && u(magic[1]) == 0xD8 && u(magic[2]) == 0xFF) {
            return Container.JPEG
        }
        if (magic.size >= 4 && u(magic[0]) == 0x89 && u(magic[1]) == 0x50 &&
            u(magic[2]) == 0x4E && u(magic[3]) == 0x47
        ) {
            return Container.PNG
        }
        return null
    }

    /** The playable kind a sniffed container maps to. */
    fun kindOf(magic: ByteArray): MediaItem.Kind? = when (containerOf(magic)) {
        Container.MP4, Container.EBML -> MediaItem.Kind.VIDEO
        Container.JPEG, Container.PNG -> MediaItem.Kind.IMAGE
        null -> null
    }

    /**
     * The upload content gate: the container sniffed from [magic] must agree
     * with the extension of [name]. A JPEG renamed `.mp4` is refused, and so is
     * an MP4 renamed `.mkv`: matching only the playable *kind* would let the
     * latter through, so the container family is compared instead.
     */
    fun contentMatchesExtension(name: String, magic: ByteArray): Boolean {
        val container = containerOf(magic) ?: return false
        val expected = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v", "mov" -> Container.MP4
            "mkv", "webm" -> Container.EBML
            "jpg", "jpeg" -> Container.JPEG
            "png" -> Container.PNG
            else -> return false
        }
        return container == expected
    }

    /** The MIME type for a validated library name, or null for an unknown extension. */
    fun contentTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }

    /**
     * Decides whether one more [incoming]-byte file may be written to a library
     * holding [count] files and [totalBytes], with [freeBytes] left on the
     * volume. Quota and reserve are checked before the first byte is written.
     */
    fun accepts(count: Int, totalBytes: Long, freeBytes: Long, incoming: Long): Verdict {
        if (incoming < 0 || incoming > Caps.MAX_BYTES) return Verdict.TooLarge
        // count is an int from a directory listing; guard the addition rather
        // than trusting it cannot overflow.
        if (count >= Caps.MAX_FILES) return Verdict.TooManyFiles
        if (totalBytes > Caps.MAX_TOTAL_BYTES || incoming > Caps.MAX_TOTAL_BYTES - totalBytes) {
            return Verdict.OverQuota
        }
        if (freeBytes < Caps.RESERVE_BYTES + incoming) return Verdict.NoSpace
        return Verdict.Ok
    }

    private fun u(byte: Byte): Int = byte.toInt() and 0xFF
}
