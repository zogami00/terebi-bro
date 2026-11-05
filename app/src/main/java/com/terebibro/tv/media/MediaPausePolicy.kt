package com.terebibro.tv.media

/**
 * Pure rule for whether the media layer must be paused.
 *
 * The media contract is explicit: while `QMS_FOCUS` (or `QMS_ONLY`) owns the
 * screen the media band is paused, not merely muted. Playback is also paused
 * when any independent pause source is active — an overlay above the band, the
 * activity lifecycle, the operator's own Play/Pause hold or a lost audio focus.
 *
 * This is the only pause predicate: the published state, the player effect, the
 * media band's web page and the focus-gain re-assertion all read it, so the
 * sources can never disagree.
 */
object MediaPausePolicy {

    fun shouldPause(
        mode: MediaMode,
        overlayVisible: Boolean,
        lifecyclePaused: Boolean,
        manualPaused: Boolean,
        focusPaused: Boolean = false
    ): Boolean = mode == MediaMode.QMS_ONLY ||
        mode == MediaMode.QMS_FOCUS ||
        overlayVisible ||
        lifecyclePaused ||
        manualPaused ||
        focusPaused
}
