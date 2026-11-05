package com.terebibro.tv.media

/**
 * The immutable media-mode state. [mode], [manualUntilMs] and the playback
 * fields are the published shape; the trailing fields are reducer memory that
 * the effect layer does not display.
 *
 * [returnMode] is the mode that was active before a call promoted the display
 * to `QMS_FOCUS`, and [dwellUntilMs] is the re-armable dwell deadline; both are
 * needed because the reducer is total and memoryless across calls. [enabled]
 * cannot be inferred from [mode], since a manual hold can also be `QMS_ONLY`.
 *
 * Pausing has two independent sources: an overlay on top of the media band and
 * the activity lifecycle. They are stored separately and only combined by
 * [paused], so closing one never clears the other.
 */
data class MediaState(
    val mode: MediaMode = MediaMode.QMS_ONLY,
    val manualUntilMs: Long = 0L,
    val overlayVisible: Boolean = false,
    val lifecyclePaused: Boolean = false,
    val source: String = "",
    val index: Int = 0,
    val count: Int = 0,
    val lastError: String = "",
    val enabled: Boolean = false,
    val returnMode: MediaMode = MediaMode.SPLIT,
    val qmsAvailable: Boolean = true,
    val dwellUntilMs: Long = 0L,
    /**
     * Whether the strip selector has matched at least once since launch. It is
     * never persisted: a restart must not inherit a match from before the
     * reboot, and the daily activation form must keep the whole screen.
     */
    val qmsMatched: Boolean = false,
    /** Selector-level health, separate from page-level [qmsAvailable]. */
    val qmsDetectionHealthy: Boolean = false
) {
    /** Playback must be paused while either pause source is active. */
    val paused: Boolean get() = overlayVisible || lifecyclePaused
}
