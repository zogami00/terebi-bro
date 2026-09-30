package com.terebibro.tv

/**
 * The Back behaviour ladder, extracted as a pure function so the decision and
 * the action can never drift and so the ordering is unit-testable on the JVM
 * (no Android framework types here).
 *
 * The first matching rule wins, in this order:
 * 1. info overlay open   -> close it
 * 2. error overlay open  -> close it
 * 3. HTML5 fullscreen    -> hide the video view
 * 4. WebView can go back -> navigate back
 * 5. not at the home page -> load home
 * 6. at the true root    -> open the setup / pairing page
 *
 * There is no "exit" rung: Back always has something to do, so the ladder is
 * always handled. Exiting the app is deliberate and happens elsewhere (a
 * long-press of Back on API < 33, or the Exit App button on the setup page).
 */
object BackOrder {

    /** Immutable snapshot of everything the Back decision depends on. */
    data class BackState(
        val infoOverlayVisible: Boolean,
        val errorOverlayVisible: Boolean,
        val fullscreenVisible: Boolean,
        val canGoBack: Boolean,
        val atHome: Boolean
    )

    enum class Action {
        CLOSE_INFO,
        CLOSE_ERROR,
        HIDE_FULLSCREEN,
        GO_BACK,
        GO_HOME,

        /** At the root: open the setup / pairing overlay instead of exiting. */
        SHOW_SETUP
    }

    /** The single decision shared by the activity's handler and its tests. */
    fun decision(state: BackState): Action = when {
        state.infoOverlayVisible -> Action.CLOSE_INFO
        state.errorOverlayVisible -> Action.CLOSE_ERROR
        state.fullscreenVisible -> Action.HIDE_FULLSCREEN
        state.canGoBack -> Action.GO_BACK
        !state.atHome -> Action.GO_HOME
        else -> Action.SHOW_SETUP
    }

    /**
     * True when a Back press lasted at least [thresholdMs]. Pure and
     * framework-free so the long-press decision is unit-testable.
     *
     * [downTimeMs] is the event's gesture start (`KeyEvent.downTime`, repeated
     * unchanged across auto-repeat events) and [upTimeMs] is the event time at
     * `ACTION_UP`. Using the gesture start rather than the DOWN event's own
     * `eventTime` keeps the measurement correct on repeating inputs (a keyboard
     * key held down, a repeating TV remote): `eventTime` advances with every
     * repeat, so it would shrink the apparent hold.
     *
     * A non-positive [downTimeMs] (no usable DOWN), or an [upTimeMs] before
     * [downTimeMs] (a malformed / cancelled event), is never read as a long
     * press.
     */
    fun isLongPress(downTimeMs: Long, upTimeMs: Long, thresholdMs: Long): Boolean =
        downTimeMs > 0L && upTimeMs >= downTimeMs && (upTimeMs - downTimeMs) >= thresholdMs
}
