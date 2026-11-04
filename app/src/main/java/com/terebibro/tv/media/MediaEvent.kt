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
    data class Overlay(val visible: Boolean) : MediaEvent()
    data class Lifecycle(val paused: Boolean) : MediaEvent()
    data class PlaybackError(val message: String) : MediaEvent()
    object PlaybackRecovered : MediaEvent()
    data class SourceChanged(val source: String, val count: Int) : MediaEvent()
}
