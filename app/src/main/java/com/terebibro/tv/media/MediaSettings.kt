package com.terebibro.tv.media

/** Which page signal promotes a call to `QMS_FOCUS`. */
enum class CallSource(val wire: String) {
    DOM("dom"),
    DOM_OR_AUDIO("dom_or_audio"),
    AUDIO("audio");

    companion object {
        fun fromWire(value: String): CallSource? = entries.firstOrNull { it.wire == value }
    }
}

/** The language of the patient-facing signage strings only, never the vendor's text. */
enum class UiLanguage(val wire: String) {
    MS("ms"),
    EN("en"),
    ZH("zh"),
    TA("ta");

    companion object {
        fun fromWire(value: String): UiLanguage? = entries.firstOrNull { it.wire == value }
    }
}

/** How the media band fills its box. Only `COVER` crops. */
enum class MediaFillMode(val wire: String) {
    LETTERBOX_BLUR("letterboxBlur"),
    LETTERBOX("letterbox"),
    COVER("cover");

    companion object {
        fun fromWire(value: String): MediaFillMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Where the two strip bands sit relative to the media band. */
enum class StripPosition(val wire: String) {
    BOTTOM("bottom"),
    RIGHT("right");

    companion object {
        fun fromWire(value: String): StripPosition? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Immutable, effective media-mode configuration. Pure-JVM (no Android types),
 * so the validator/merge logic can be unit-tested and never depends on
 * `ConfigStore`.
 *
 * Pixel fields are authored against a 1080p surface and scaled at runtime by
 * `renderScale`.
 */
data class MediaSettings(
    val enabled: Boolean = false,
    val qmsUrl: String = "",
    val qmsStripSelector: String = "main > div:nth-child(1)",
    val qmsNumberSelector: String = "div:first-child > span",
    val qmsLocationSelector: String = "div:nth-child(2) > span:first-child",
    val qmsTimeSelector: String = "div:nth-child(2) > span:nth-child(2)",
    val callSource: CallSource = CallSource.DOM,
    val dwellSec: Int = 50,
    val mediaSplitPercent: Int = 71,
    val headerBandPercent: Int = 7,
    val stripHeightPercent: Int = 22,
    val currentNumberPx: Int = 96,
    val historyNumberPx: Int = 56,
    val labelPx: Int = 15,
    val currentCardPx: Int = 360,
    val historyCardMinPx: Int = 190,
    val historyCardMaxPx: Int = 320,
    val uiLanguage: UiLanguage = UiLanguage.MS,
    val mediaLocalEnabled: Boolean = true,
    val playlistEnabled: Boolean = false,
    val playlistUrl: String = "",
    val playlistRefreshSec: Int = 300,
    val imageDurationSec: Int = 10,
    val volumePercent: Int = 100,
    val mediaFillMode: MediaFillMode = MediaFillMode.LETTERBOX_BLUR,
    val newCallHighlight: Boolean = true,
    val stripPosition: StripPosition = StripPosition.BOTTOM,
    val uiScale: Int = 100
)
