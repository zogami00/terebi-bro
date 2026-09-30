package com.terebibro.tv.web

import java.net.URI

/**
 * Normalised comparison between the current page and the configured Home URL,
 * used by Back handling. Pure JVM (only [java.net.URI]) so it is unit-testable.
 *
 * Back at the root falls back to the Home URL and consumes the event. If that
 * comparison were raw string equality, a Home URL that redirects (trailing
 * slash, a leading `www.`) would never match the loaded page: Back would reload
 * home, push a history entry, and the next Back would step back to the previous
 * entry, so Back oscillates instead of exiting. Comparing scheme + host + path
 * with a trailing slash removed and a leading `www.` ignored terminates that
 * cycle.
 */
object HomeUrlMatcher {

    /** True when [currentUrl] and [homeUrl] address the same scheme/host/path. */
    fun samePage(currentUrl: String?, homeUrl: String?): Boolean {
        val current = normalize(currentUrl) ?: return false
        val home = normalize(homeUrl) ?: return false
        return current == home
    }

    private fun normalize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        var host = uri.host?.lowercase() ?: return null
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        if (host.isEmpty()) return null

        val rawPath = uri.path ?: ""
        // "/" and "" denote the same document; other trailing slashes are noise.
        val path = if (rawPath.isEmpty() || rawPath == "/") "" else rawPath.trimEnd('/')

        val port = uri.port
        val portPart = when {
            port <= 0 -> ""
            scheme == "http" && port == 80 -> ""
            scheme == "https" && port == 443 -> ""
            else -> ":$port"
        }
        return "$scheme://$host$portPart$path"
    }
}
