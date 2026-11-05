package com.terebibro.tv.media

/**
 * The published `mediaSource` values. The media WebView is a fourth source
 * beside the local folder, the HTTP playlist and a single ad-hoc URL: it is a
 * deliberate one-off, never a queue, so the published `mediaCount` is 0 while
 * it is active.
 *
 * Pure JVM: the reducer, the controller and the state serializer all speak
 * these strings.
 */
object MediaSource {
    const val LOCAL = "local"
    const val PLAYLIST = "playlist"
    const val URL = "url"
    const val WEB = "web"

    /** True while the media band is showing a web page rather than a queue item. */
    fun isWeb(source: String): Boolean = source == WEB
}
