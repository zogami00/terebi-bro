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
            // A restart resets the per-launch history even while media mode is
            // off, so the next enable starts from the activation page again.
            MediaEvent.QmsReset -> onQmsReset(current)
            else -> if (!current.enabled) current else onEnabledEvent(current, event, cfg, nowMs)
        }
    }

    private fun onEnabled(state: MediaState, enabled: Boolean): MediaState =
        if (enabled) {
            if (state.enabled) {
                state
            } else {
                // Boot rule: media mode may engage only after the strip selector
                // has matched once since launch. Before that the page may be the
                // daily activation form, which needs the whole screen.
                val mode = if (state.qmsMatched) MediaMode.SPLIT else MediaMode.QMS_ONLY
                state.copy(enabled = true, mode = mode)
            }
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
        is MediaEvent.QmsProbe -> onQmsProbe(state, event.matched)
        MediaEvent.QmsPageFailed -> onQmsPageFailed(state)
        MediaEvent.QmsPageLoaded -> onQmsPageLoaded(state)
        MediaEvent.QmsReset -> state
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

    /**
     * Selector-level health. A failed probe never changes the mode: before the
     * first match the page may be the activation form (stay `QMS_ONLY`), and
     * after one match the strip stays in `SPLIT` with the unavailable card.
     * A passing probe is the activation signal: it is the first evidence that
     * the dashboard, not the form, is rendering. An empty call list counts as
     * passing — the container resolved and the board is simply awaiting a call —
     * so it neither blocks the boot rule nor hides the page.
     */
    private fun onQmsProbe(state: MediaState, matched: Boolean): MediaState {
        val wasUnavailable = !state.qmsAvailable
        val base = state.copy(
            qmsMatched = state.qmsMatched || matched,
            qmsDetectionHealthy = matched,
            qmsAvailable = if (matched) true else state.qmsAvailable
        )
        if (!matched) return base
        return when {
            base.mode == MediaMode.QMS_ONLY && base.manualUntilMs == 0L ->
                base.copy(mode = MediaMode.SPLIT)
            wasUnavailable && base.mode == MediaMode.MEDIA_ONLY && base.manualUntilMs == 0L ->
                base.copy(mode = MediaMode.SPLIT)
            else -> base
        }
    }

    /**
     * Page-level failure: the page will not load, so there is no strip to show.
     * A page that never loaded goes to `MEDIA_ONLY` at launch too, because
     * there is nothing to activate.
     */
    private fun onQmsPageFailed(state: MediaState): MediaState = state.copy(
        mode = MediaMode.MEDIA_ONLY,
        qmsAvailable = false,
        qmsDetectionHealthy = false,
        dwellUntilMs = 0L
    )

    /**
     * A page that loads proves the page-level failure is over. If no selector
     * has ever matched, the display returns to full-screen `QMS_ONLY` — the
     * page may be the activation form, which must not be crushed into a band.
     *
     * A live manual hold is restored rather than ignored: a page failure
     * overrode the hold with `MEDIA_ONLY`, so without this the display would
     * stay degraded after the page recovers. A live call is never cut short.
     */
    private fun onQmsPageLoaded(state: MediaState): MediaState {
        val base = state.copy(qmsAvailable = true)
        if (base.manualUntilMs != 0L) {
            if (base.mode == MediaMode.QMS_FOCUS) return base
            val held = if (base.manualUntilMs == MANUAL_INDEFINITE_UNTIL_MS) {
                MediaMode.QMS_ONLY
            } else {
                MediaMode.MEDIA_ONLY
            }
            return base.copy(mode = held, dwellUntilMs = 0L)
        }
        return when {
            base.mode == MediaMode.MEDIA_ONLY && base.qmsMatched ->
                base.copy(mode = MediaMode.SPLIT)
            base.mode == MediaMode.MEDIA_ONLY ->
                base.copy(mode = MediaMode.QMS_ONLY)
            else -> base
        }
    }

    /** A rebuilt WebView lands on the activation page; the match history resets. */
    private fun onQmsReset(state: MediaState): MediaState = state.copy(        mode = MediaMode.QMS_ONLY,
        manualUntilMs = 0L,
        returnMode = MediaMode.QMS_ONLY,
        dwellUntilMs = 0L,
        qmsMatched = false,
        qmsDetectionHealthy = false,
        qmsAvailable = true
    )

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
            // A call during a manual `QMS_ONLY` hold is still a real call: the
            // screen goes to the queue for the dwell and returns to the hold.
            // With no hold this is the boot path (the activation form has not
            // matched yet), and there is nothing to show yet.
            MediaMode.QMS_ONLY ->
                if (state.manualUntilMs != 0L) {
                    state.copy(
                        mode = MediaMode.QMS_FOCUS,
                        returnMode = MediaMode.QMS_ONLY,
                        dwellUntilMs = dwellUntilMs
                    )
                } else {
                    state
                }
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
