package com.terebibro.tv.media

/**
 * The single pure reducer that decides which mode is on screen.
 *
 * Total by construction: every event in every state returns a state, so a
 * signal arriving out of order cannot wedge the display. With media mode
 * disabled it is inert apart from [MediaEvent.Enabled] — the V1 path.
 */
object MediaStateMachine {

    /**
     * How long a manual `MEDIA_ONLY` selection holds. `MEDIA_ONLY` is a media
     * workspace: long enough to set media up at the TV, short enough that a
     * forgotten workspace never hides the queue for a whole day.
     */
    const val MANUAL_MEDIA_HOLD_MS = 30 * 60 * 1000L

    /**
     * The deadline stored for an indefinite manual hold (`QMS_ONLY`), which is
     * the safe state because the queue stays visible. Any real clock reading is
     * below this, so it never expires.
     */
    const val MANUAL_INDEFINITE_UNTIL_MS = Long.MAX_VALUE

    fun reduce(state: MediaState, event: MediaEvent, cfg: MediaSettings, nowMs: Long): MediaState {
        val current = expireManual(state, nowMs)
        return when (event) {
            is MediaEvent.Enabled -> onEnabled(current, event.enabled)
            else -> if (!current.enabled) current else onEnabledEvent(current, event, cfg, nowMs)
        }
    }

    private fun onEnabled(state: MediaState, enabled: Boolean): MediaState =
        if (enabled) {
            if (state.enabled) state else state.copy(enabled = true, mode = MediaMode.SPLIT)
        } else {
            // Disabling releases the display to V1 unconditionally, whatever
            // mode or hold was in force. The transient fields are cleared too:
            // a stale pause or error must not survive into the next enable.
            state.copy(
                enabled = false,
                mode = MediaMode.QMS_ONLY,
                manualUntilMs = 0L,
                returnMode = MediaMode.QMS_ONLY,
                dwellUntilMs = 0L,
                overlayVisible = false,
                lifecyclePaused = false,
                lastError = ""
            )
        }

    private fun onEnabledEvent(
        state: MediaState,
        event: MediaEvent,
        cfg: MediaSettings,
        nowMs: Long
    ): MediaState = when (event) {
        is MediaEvent.Manual -> onManual(state, event.mode, nowMs)
        MediaEvent.ManualExpired -> {
            // Same rule as a deadline passing: a live call keeps the screen
            // until its dwell ends, and then returns to the automatic mode.
            val automatic = automaticMode(state)
            if (state.mode == MediaMode.QMS_FOCUS) {
                state.copy(manualUntilMs = 0L, returnMode = automatic)
            } else {
                state.copy(manualUntilMs = 0L, mode = automatic)
            }
        }
        is MediaEvent.Call ->
            if (event.detected) onCall(state, cfg, nowMs) else state
        MediaEvent.DwellElapsed ->
            if (state.mode == MediaMode.QMS_FOCUS) {
                state.copy(mode = state.returnMode, dwellUntilMs = 0L)
            } else {
                state
            }
        MediaEvent.QmsUnavailable -> state.copy(mode = MediaMode.MEDIA_ONLY, qmsAvailable = false)
        // Only a real degradation may change the display: a repeated "available"
        // signal must not cut a live call short or override an operator's hold.
        MediaEvent.QmsAvailable ->
            if (state.qmsAvailable) state
            else state.copy(mode = MediaMode.SPLIT, qmsAvailable = true)
        is MediaEvent.Overlay -> state.copy(overlayVisible = event.visible)
        is MediaEvent.Lifecycle -> state.copy(lifecyclePaused = event.paused)
        is MediaEvent.PlaybackError -> state.copy(lastError = event.message)
        MediaEvent.PlaybackRecovered -> state.copy(lastError = "")
        is MediaEvent.SourceChanged -> state.copy(
            source = event.source,
            count = event.count,
            index = if (event.count <= 0) 0 else state.index.coerceIn(0, event.count - 1)
        )
        is MediaEvent.Enabled -> state
    }

    /**
     * Applies an operator selection. `MEDIA_ONLY` gets a real deadline,
     * `QMS_ONLY` is held indefinitely (it is the safe state) and `SPLIT` is the
     * automatic default, so choosing it simply drops any hold.
     */
    private fun onManual(state: MediaState, mode: MediaMode, nowMs: Long): MediaState = when (mode) {
        MediaMode.QMS_FOCUS -> state
        MediaMode.MEDIA_ONLY ->
            state.copy(mode = MediaMode.MEDIA_ONLY, manualUntilMs = nowMs + MANUAL_MEDIA_HOLD_MS)
        MediaMode.QMS_ONLY ->
            state.copy(mode = MediaMode.QMS_ONLY, manualUntilMs = MANUAL_INDEFINITE_UNTIL_MS)
        MediaMode.SPLIT ->
            state.copy(mode = MediaMode.SPLIT, manualUntilMs = 0L)
    }

    private fun onCall(state: MediaState, cfg: MediaSettings, nowMs: Long): MediaState {
        val dwellUntilMs = nowMs + cfg.dwellSec * 1000L
        return when (state.mode) {
            MediaMode.SPLIT ->
                state.copy(mode = MediaMode.QMS_FOCUS, returnMode = MediaMode.SPLIT, dwellUntilMs = dwellUntilMs)
            MediaMode.MEDIA_ONLY ->
                state.copy(mode = MediaMode.QMS_FOCUS, returnMode = MediaMode.MEDIA_ONLY, dwellUntilMs = dwellUntilMs)
            // A second call only re-arms the dwell: no mode change and no
            // re-layout, so the board does not flicker mid-announcement.
            MediaMode.QMS_FOCUS -> state.copy(dwellUntilMs = dwellUntilMs)
            MediaMode.QMS_ONLY -> state
        }
    }

    private fun expireManual(state: MediaState, nowMs: Long): MediaState {
        if (state.manualUntilMs == 0L || nowMs < state.manualUntilMs) return state
        val automatic = automaticMode(state)
        // A dwell recorded against the expired manual mode must not restore it:
        // returnMode follows the hold back to the automatic mode.
        val cleared = state.copy(manualUntilMs = 0L, returnMode = automatic)
        // Never cut a call short because a hold that was superseded expired.
        return if (state.mode == MediaMode.QMS_FOCUS) cleared else cleared.copy(mode = automatic)
    }

    private fun automaticMode(state: MediaState): MediaMode =
        if (state.qmsAvailable) MediaMode.SPLIT else MediaMode.MEDIA_ONLY
}
