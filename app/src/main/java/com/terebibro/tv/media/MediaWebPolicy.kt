package com.terebibro.tv.media

/**
 * Pure decisions for the media band's web page. The Android view work lives in
 * `MainActivity` and `MediaWebSurface`; everything here is a framework-free
 * predicate so the rules themselves can be unit-tested on the JVM.
 */
object MediaWebPolicy {

    /**
     * A page's HTML5 fullscreen request is honoured only while that page owns
     * the media band. In `MEDIA_ONLY` the band is the whole screen, so the
     * request is real fullscreen; in `SPLIT` the view fills only the media
     * band and the queue stays visible, which is intended.
     *
     * `QMS_ONLY` and `QMS_FOCUS` hide the media band, so there is nothing to
     * expand into and the request is refused.
     */
    fun allowsMediaFullscreen(
        mediaEnabled: Boolean,
        webSource: Boolean,
        mode: MediaMode
    ): Boolean = mediaEnabled &&
        webSource &&
        (mode == MediaMode.MEDIA_ONLY || mode == MediaMode.SPLIT)

    /**
     * Whether the QMS surface must be hidden outright rather than covered.
     *
     * `MEDIA_ONLY` gives the media band the whole screen; the QMS page is not
     * part of that layout at all. Leaving it merely behind the band is what the
     * device measured as a second, still focusable WebView at the old Split
     * height, so the container **and** its page are hidden in this mode (layout
     * re-assertion and the debug layout line both key off this predicate).
     */
    fun hidesQmsSurface(mode: MediaMode): Boolean = mode == MediaMode.MEDIA_ONLY

    /**
     * The kiosk re-asserts immersive mode whenever its window regains focus,
     * except while the soft keyboard is visible: hiding the system bars there
     * dismisses the IME the user is typing with. Once the keyboard is gone the
     * next focus change restores fullscreen as before.
     */
    fun shouldReapplyImmersive(fullscreenEnabled: Boolean, imeVisible: Boolean): Boolean =
        fullscreenEnabled && !imeVisible
}
