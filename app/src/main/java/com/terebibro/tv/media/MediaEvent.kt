package com.terebibro.tv.media

/** Every input the media reducer accepts. */
sealed class MediaEvent {
    data class Enabled(val enabled: Boolean) : MediaEvent()
    data class Manual(val mode: MediaMode) : MediaEvent()
    object ManualExpired : MediaEvent()
    data class Call(val detected: Boolean) : MediaEvent()
    object DwellElapsed : MediaEvent()
    object QmsUnavailable : MediaEvent()
    object QmsAvailable : MediaEvent()

    /**
     * Selector-level health from the injected probe. `matched` is true when the
     * list container resolved: either its first card carries the three field
     * selectors (`ok`), or the container exists with no cards yet (`empty`,
     * which is healthy — the board is just awaiting the first call). It is
     * false only when the structure is missing (`missing`).
     */
    data class QmsProbe(val matched: Boolean) : MediaEvent()

    /** The page itself could not load: there is nothing to restyle. */
    object QmsPageFailed : MediaEvent()

    /** The page finished loading; page-level availability is restored. */
    object QmsPageLoaded : MediaEvent()

    /** A rebuilt WebView or a manual restart: the per-launch match history is gone. */
    object QmsReset : MediaEvent()
    data class Overlay(val visible: Boolean) : MediaEvent()
    data class Lifecycle(val paused: Boolean) : MediaEvent()
    data class PlaybackError(val message: String) : MediaEvent()
    object PlaybackRecovered : MediaEvent()
    data class SourceChanged(val source: String, val count: Int) : MediaEvent()
}
