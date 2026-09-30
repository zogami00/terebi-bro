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
 * 6. at the true root    -> let the system exit the app
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

        /** Nothing left for the app to do: the platform should exit the activity. */
        EXIT
    }

    /** The single decision shared by [canHandle] and the activity's action. */
    fun decision(state: BackState): Action = when {
        state.infoOverlayVisible -> Action.CLOSE_INFO
        state.errorOverlayVisible -> Action.CLOSE_ERROR
        state.fullscreenVisible -> Action.HIDE_FULLSCREEN
        state.canGoBack -> Action.GO_BACK
        !state.atHome -> Action.GO_HOME
        else -> Action.EXIT
    }

    /** True when [decision] would consume Back; false only at the true root. */
    fun canHandle(state: BackState): Boolean = decision(state) != Action.EXIT
}
