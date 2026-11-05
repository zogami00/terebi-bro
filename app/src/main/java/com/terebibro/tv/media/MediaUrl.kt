package com.terebibro.tv.media

import java.net.URI

/**
 * URL masking for the values that leave the app.
 *
 * A web source URL can carry a credential in its query (`?token=…`) or its
 * fragment (YouTube ticks, Jellyfin session parameters). The state broadcast
 * and the controller card do not need it, so only `scheme://host[:port]/path`
 * is published. Pure JVM, so it is unit-testable.
 */
object MediaUrl {

    /**
     * The one transform every published URL goes through — the state broadcast,
     * the controller's settings card and the runtime item. It exists as a named
     * function rather than a bare [mask] call so the masking cannot be dropped
     * at one publication site while the others keep it, and so a test can fail
     * when it is.
     */
    fun forPublish(url: String): String = mask(url)

    /**
     * Returns [url] without its query, fragment or userinfo. A value that is
     * not an absolute URL (a library file name, for instance) is returned with
     * just a literal query/fragment tail removed, so a file called `a?b.mp4`
     * is not mangled into something else.
     */
    fun mask(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return stripQueryAndFragment(trimmed)
        }
        val scheme = uri.scheme ?: return stripQueryAndFragment(trimmed)
        val host = uri.host
        if (host.isNullOrEmpty()) return stripQueryAndFragment(trimmed)

        val masked = StringBuilder()
            .append(scheme)
            .append("://")
            .append(host)
        if (uri.port != -1) masked.append(':').append(uri.port)
        val path = uri.rawPath
        if (!path.isNullOrEmpty()) masked.append(path)
        return masked.toString()
    }

    private fun stripQueryAndFragment(value: String): String {
        var end = value.length
        val query = value.indexOf('?')
        if (query >= 0) end = minOf(end, query)
        val fragment = value.indexOf('#')
        if (fragment >= 0) end = minOf(end, fragment)
        return value.substring(0, end)
    }
}
