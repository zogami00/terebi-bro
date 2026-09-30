package com.terebibro.tv.device

/**
 * Advisory WebView-version check.
 *
 * The embedded browser uses the system WebView (the device's Chromium). On a
 * TV that has never been updated — or on an older emulator image — that
 * component can be several years behind. A WebView older than Chromium 100
 * (March 2022) misses cascade layers, `:has()`, `color-mix()` and container
 * queries, so modern CSS frameworks silently render as unstyled HTML.
 *
 * This is surfaced as a *warning only*: the app must keep running regardless,
 * so nothing here ever gates loading, navigation or any feature.
 */
object WebViewSupport {

    /**
     * Chromium 100 (Mar 2022). Below this, cascade layers, `:has()`,
     * `color-mix()` and container queries are missing, so modern CSS
     * frameworks render unstyled. Advisory only — never blocks.
     */
    const val MIN_RECOMMENDED_MAJOR = 100

    /**
     * The leading integer component of [versionName], or `null` when it cannot
     * be determined. Tolerates surrounding whitespace and a version with no dot
     * (`"90"`); `null`, blank and unparseable input yield `null`.
     */
    fun majorOf(versionName: String?): Int? {
        val trimmed = versionName?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        // A wildly long digit run overflows Int; treat that as unknown too.
        return digits.toIntOrNull()
    }

    /**
     * True only when [versionName] parses to a major below [floor].
     *
     * An unknown version is never outdated: a missing or garbage version string
     * is not evidence of an old WebView, and warning on it would be pure noise.
     */
    fun isOutdated(versionName: String?, floor: Int = MIN_RECOMMENDED_MAJOR): Boolean {
        val major = majorOf(versionName) ?: return false
        return major < floor
    }
}
