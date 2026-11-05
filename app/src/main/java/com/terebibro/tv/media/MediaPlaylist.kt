package com.terebibro.tv.media

import com.terebibro.tv.web.UrlValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/**
 * The HTTP playlist: a strict, bounded JSON parser. Pure JVM (only `org.json`
 * and `java.net`), so the whole format is unit-testable without a device.
 *
 * The body is a top-level **array** of `{type, url, durationSec}` entries:
 *
 * - `type` is `image` or `video`;
 * - `url` is resolved against the playlist's own URL (so a relative entry is
 *   not silently dropped) and must then pass [UrlValidator] as an absolute
 *   `http`/`https` URL;
 * - `durationSec` is an integer in [MIN_DURATION_SEC]..[MAX_DURATION_SEC].
 *
 * A bad **entry** is skipped and parsing continues; the playlist is only
 * [Result.Invalid] when nothing survives. A bad **container** (not an array,
 * malformed JSON, too large, too many items) rejects the whole playlist.
 *
 * Strictness is deliberate: an entry with an unknown key, a missing key, a
 * non-string URL, a non-integer duration or a rejected scheme is not guessed
 * at. A typo in a playlist must be visible as a skipped item, never as an
 * item that plays something the operator did not write.
 */
object MediaPlaylist {

    /** Hard cap on the number of entries; a larger array rejects the playlist. */
    const val MAX_ITEMS = 200

    /** Hard cap on the body. The caller checks the byte count; [parse] re-checks the chars. */
    const val MAX_BYTES = 256 * 1024

    const val MIN_DURATION_SEC = 3
    const val MAX_DURATION_SEC = 120

    /** Longest accepted raw `url` value before resolution; the validator caps the result. */
    private const val MAX_RAW_URL_LENGTH = UrlValidator.MAX_LENGTH

    /** Chunk size for [readBounded]; the stream is never read further once the cap is passed. */
    internal const val READ_CHUNK_BYTES = 8 * 1024

    private val KNOWN_KEYS = setOf("type", "url", "durationSec")

    /** A parsed playlist: either usable items or the reason it could not be used. */
    sealed class Result {
        data class Valid(val items: List<MediaItem>) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Parses [body]. [base] is the playlist's own URL; relative entry URLs are
     * resolved against it. With an empty [base] only absolute `http`/`https`
     * entries survive.
     */
    fun parse(body: String, base: String = ""): Result {
        if (body.length > MAX_BYTES) return Result.Invalid("too_large")

        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            // org.json throws JSONException for a non-array, malformed or
            // non-JSON body; an empty body is malformed too.
            return Result.Invalid("malformed")
        }
        if (array.length() > MAX_ITEMS) return Result.Invalid("too_many_items")

        val items = ArrayList<MediaItem>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val item = itemOf(entry, base) ?: continue
            items += item
        }
        if (items.isEmpty()) return Result.Invalid("no_valid_items")
        return Result.Valid(items)
    }

    /**
     * Resolves [url] against [base] and returns the absolute form only when the
     * scheme is `http` or `https`. Returns null for a rejected scheme
     * (`javascript:`, `data:`, `file:`, ...), an unresolvable reference or a
     * malformed URL.
     */
    fun resolve(base: String, url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        // A protocol-relative reference ("//evil.com/a.mp4") would inherit the
        // playlist's scheme and land on another host; only an absolute URL or a
        // path relative to the playlist's own host is accepted.
        if (trimmed.startsWith("//")) return null
        val resolved = try {
            val ref = URI(trimmed)
            if (ref.isAbsolute) ref else URI(base).resolve(ref)
        } catch (e: Exception) {
            return null
        }
        val scheme = resolved.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return resolved.toASCIIString()
    }

    /**
     * Reads an HTTP body as UTF-8, returning null as soon as it grows past
     * [maxBytes]. The stream is never buffered whole beyond that cap, so a
     * hostile or misconfigured server cannot exhaust the heap.
     */
    fun readBounded(stream: InputStream, maxBytes: Int = MAX_BYTES): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = try {
                stream.read(buffer)
            } catch (e: Exception) {
                return null
            }
            if (read < 0) break
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun itemOf(entry: JSONObject, base: String): MediaItem? {
        // Unknown keys reject the entry: a misspelled field must not be
        // silently ignored while the default value plays something else.
        val keys = entry.keys()
        while (keys.hasNext()) {
            if (keys.next() !in KNOWN_KEYS) return null
        }

        val type = entry.opt("type")
        if (type !is String) return null
        val kind = when (type) {
            "image" -> MediaItem.Kind.IMAGE
            "video" -> MediaItem.Kind.VIDEO
            // Web is a deliberate single source, never a queue entry: a `web`
            // entry is skipped exactly like an unknown type, so the playlist
            // stays a file loop.
            "web" -> return null
            else -> return null
        }

        val rawUrl = entry.opt("url")
        if (rawUrl !is String || rawUrl.length > MAX_RAW_URL_LENGTH) return null
        val resolved = resolve(base, rawUrl) ?: return null
        val url = when (val valid = UrlValidator.validate(resolved)) {
            is UrlValidator.Result.Valid -> valid.url
            is UrlValidator.Result.Invalid -> return null
        }

        val durationSec = durationSeconds(entry.opt("durationSec")) ?: return null

        return MediaItem(
            id = url,
            name = url,
            kind = kind,
            durationMs = durationSec * 1000L,
            remote = true
        )
    }

    /**
     * A strict positive integer. `"10"` (a string) and `10.5` (a float) are
     * both rejected: the playlist schema says int, and guessing hides a
     * generation error in whatever produced the file.
     */
    private fun durationSeconds(value: Any?): Int? {
        val seconds = when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
            else -> null
        } ?: return null
        return seconds.takeIf { it in MIN_DURATION_SEC..MAX_DURATION_SEC }
    }
}
