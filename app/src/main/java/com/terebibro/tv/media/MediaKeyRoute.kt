package com.terebibro.tv.media

/** The view that must receive a synthetic direction/confirm key. */
enum class KeyTarget {
    /** The QMS page; every mode that is not `MEDIA_ONLY` keeps driving it. */
    QMS,

    /** The media band's own page, which owns the screen in `MEDIA_ONLY`. */
    MEDIA,

    /** Nobody: the key is swallowed and must not reach either WebView. */
    NONE
}

/**
 * The single place that decides where a direction/confirm key goes.
 *
 * The hardware remote, `POST /api/dpad` and any future synthetic-key source all
 * consult this predicate, so the three paths cannot drift apart again. Before
 * this existed the hardware path routed in `MainActivity.dispatchKeyEvent`
 * while `/api/dpad` went straight to the QMS WebView, and the controller's
 * on-screen D-pad kept driving the hidden QMS page in `MEDIA_ONLY`.
 *
 * Rules:
 * - Every mode except `MEDIA_ONLY` sends the key to the QMS page. In `SPLIT`
 *   that is the point: the band's page is inert and the remote navigates the
 *   QMS page.
 * - `MEDIA_ONLY` with a web page and no overlay gives the key to that page.
 * - `MEDIA_ONLY` with a native item has no D-pad target (the media layer
 *   swallows the key, see [MediaKeyGuard]).
 * - `MEDIA_ONLY` with an overlay up gives no key to either WebView: the
 *   on-device overlay owns focus, and the QMS view must never receive a
 *   synthetic key while the band owns the screen.
 *
 * Keys that are not direction/confirm keys are not routed at all:
 * `PLAY_PAUSE`, `MENU`, `OTHER`, … are handled elsewhere and return [KeyTarget.NONE].
 */
object MediaKeyRoute {

    fun route(
        key: MediaKey,
        mode: MediaMode,
        webActive: Boolean,
        overlayVisible: Boolean
    ): KeyTarget = when {
        key != MediaKey.DPAD && key != MediaKey.ENTER -> KeyTarget.NONE
        // Only MEDIA_ONLY ever takes the D-pad away from the QMS page.
        mode != MediaMode.MEDIA_ONLY -> KeyTarget.QMS
        // A native item has nothing to navigate; MediaKeyGuard swallows it.
        !webActive -> KeyTarget.NONE
        // An overlay owns the key; never leak it to the hidden QMS page.
        overlayVisible -> KeyTarget.NONE
        else -> KeyTarget.MEDIA
    }
}
