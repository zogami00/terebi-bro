package com.terebibro.tv.web

import java.net.URI

/**
 * Strict allow-listing URL validator. Pure-JVM (only java.net.URI), so it is
 * directly unit-testable.
 *
 * Accepts only absolute http/https URLs, rejects userinfo, empty hosts,
 * over-long input, whitespace/control characters and every dangerous scheme.
 */
object UrlValidator {

    const val MAX_LENGTH = 2048

    /** Schemes we explicitly reject and report, beyond the "not http(s)" rule. */
    private val BLOCKED_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent",
        "about", "blob", "chrome"
    )

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    sealed class Result {
        data class Valid(val url: String) : Result()
        data class Invalid(val error: String) : Result()
    }

    /**
     * @param raw the user supplied value; a missing scheme is upgraded to https.
     */
    fun validate(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid("empty")
        if (trimmed.length > MAX_LENGTH) return Result.Invalid("too_long")

        for (ch in trimmed) {
            if (ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F) {
                return Result.Invalid("illegal_character")
            }
        }

        val candidate = if (SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

        // A blocked scheme is rejected before parsing so the message is precise.
        val schemePrefix = candidate.substringBefore(':').lowercase()
        if (schemePrefix in BLOCKED_SCHEMES) return Result.Invalid("blocked_scheme")

        val uri = try {
            URI(candidate)
        } catch (e: Exception) {
            return Result.Invalid("malformed")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("blocked_scheme")
        if (uri.userInfo != null) return Result.Invalid("userinfo_not_allowed")

        val host = uri.host
        if (host.isNullOrEmpty()) return Result.Invalid("empty_host")
        if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) return Result.Invalid("bad_port")

        return Result.Valid(uri.toASCIIString())
    }
}
