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
     * Whether a fullscreen view that is already up survives the current mode.
     * Stricter than [allowsMediaFullscreen] for starting one, looser for
     * keeping one: a `QMS_FOCUS` takeover hides the whole media band (and the
     * fullscreen view inside it) for the call, then hands the screen back to
     * the previous mode. Exiting fullscreen there forced the viewer to press
     * the player's fullscreen button again after every called number.
     * Leaving for `QMS_ONLY`, turning media mode off, or the page no longer
     * being the source still closes it.
     */
    fun keepsMediaFullscreen(
        mediaEnabled: Boolean,
        webSource: Boolean,
        mode: MediaMode
    ): Boolean = mediaEnabled && webSource && mode != MediaMode.QMS_ONLY

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

    /** `PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID`, kept as a literal so this stays JVM-testable. */
    const val PROTECTED_MEDIA = "android.webkit.resource.PROTECTED_MEDIA_ID"

    /**
     * The only permission a media page gets: protected media, which WebView
     * requires before a page may use Widevine (DRM live TV such as RTMKlik
     * fails with DRM_NO_KEY_SYSTEM without it). Camera, microphone and
     * everything else stay denied. An empty result means deny.
     */
    fun grantedResources(requested: Array<String>): Array<String> =
        requested.filter { it == PROTECTED_MEDIA }.toTypedArray()
}
