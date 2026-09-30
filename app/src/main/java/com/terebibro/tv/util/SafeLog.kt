package com.terebibro.tv.util

import android.util.Log

/**
 * Minimal redacting logger.
 *
 * It must never emit secrets: tokens, PINs, cookies, auth headers or full URLs
 * that carry query strings / fragments. Everything passed to a log method is
 * passed through [redact] first, so callers cannot accidentally leak a secret
 * by logging it directly.
 */
object SafeLog {

    private const val TAG = "TerebiBro"

    private val URL_REGEX =
        Regex("""(?i)\bhttps?://[^\s"'<>\\)]+""")

    private val SENSITIVE_KV_REGEX =
        Regex(
            """(?i)\b(token|access[_-]?token|refresh[_-]?token|id[_-]?token|""" +
                """pin|pass(word|wd)?|pwd|secret|client[_-]?secret|""" +
                """api[_-]?key|apikey|auth|authorization|session[_-]?id|""" +
                """cookie|set-cookie)\b\s*[:=]\s*("[^"]*"|'[^']*'|\S+)"""
        )

    private val BEARER_REGEX = Regex("""(?i)\bbearer\s+\S+""")

    fun v(tag: String, message: String) = Log.v(tag(tag), redact(message))

    fun d(tag: String, message: String) = Log.d(tag(tag), redact(message))

    fun i(tag: String, message: String) = Log.i(tag(tag), redact(message))

    fun w(tag: String, message: String) = Log.w(tag(tag), redact(message))

    /**
     * The throwable is never passed to [Log] directly: its message and stack
     * trace could carry a URL with a query string or a token. Only its class is
     * appended (already redacted, so this cannot leak a secret).
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { " (${it.javaClass.simpleName})" } ?: ""
        Log.e(tag(tag), redact(message) + suffix)
    }

    private fun tag(tag: String): String = "$TAG/$tag"

    /**
     * Strips query strings and fragments from URLs and masks sensitive
     * key/value pairs and bearer credentials. Visible for testing.
     */
    fun redact(message: String): String {
        var out = message

        out = URL_REGEX.replace(out) { match ->
            val url = match.value
            val cut = url.indexOfFirst { it == '?' || it == '#' }
            if (cut >= 0) url.substring(0, cut) + "?<redacted>" else url
        }

        out = BEARER_REGEX.replace(out, "Bearer <redacted>")

        out = SENSITIVE_KV_REGEX.replace(out) { match ->
            "${match.groupValues[1]}=<redacted>"
        }

        return out
    }
}
