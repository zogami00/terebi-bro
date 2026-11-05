package com.terebibro.tv.media

/** The remote keys whose handling depends on the media mode. */
enum class MediaKey { DPAD, ENTER, MENU, PLAY_PAUSE, RESUME, PAUSE, NEXT, PREVIOUS, OTHER }

/**
 * Pure rule for which key presses the activity must consume before the WebView
 * (or the platform) sees them.
 *
 * Playback keys belong to the media layer as soon as media mode is on, because
 * the WebView has no use for them. Direction keys only belong to it in
 * `MEDIA_ONLY` while the band shows a native item, which has nothing to
 * navigate; when the band shows a web page those keys must reach that page
 * instead. Otherwise D-pad navigation inside the vendor page must keep working,
 * and while an overlay is up the D-pad has to move focus inside that overlay.
 *
 * [webActive] is "the media band is currently showing a web page". It is only
 * consulted when the mode is `MEDIA_ONLY`, the one mode in which the media
 * surface owns the D-pad.
 */
object MediaKeyGuard {

    fun shouldSwallow(
        mode: MediaMode,
        overlayVisible: Boolean,
        webActive: Boolean,
        key: MediaKey
    ): Boolean = when (key) {
        MediaKey.PLAY_PAUSE, MediaKey.RESUME, MediaKey.PAUSE,
        MediaKey.NEXT, MediaKey.PREVIOUS -> mode != MediaMode.QMS_ONLY
        MediaKey.DPAD, MediaKey.ENTER ->
            mode == MediaMode.MEDIA_ONLY && !overlayVisible && !webActive
        MediaKey.MENU, MediaKey.OTHER -> false
    }
}
