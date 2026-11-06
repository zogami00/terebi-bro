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

/**
 * Which source the media band selects at startup. `LOCAL` is the automatic
 * default (the local folder, with a configured HTTP playlist still winning, as
 * before); `PLAYLIST` prefers the playlist; `WEB` opens [MediaSettings.mediaWebUrl].
 */
enum class MediaSourceDefault(val wire: String) {
    LOCAL("local"),
    PLAYLIST("playlist"),
    WEB("web");

    companion object {
        fun fromWire(value: String): MediaSourceDefault? = entries.firstOrNull { it.wire == value }
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
    /** Folder the local source plays: blank = app storage (uploads), else an absolute path on the TV or a USB/SD volume. */
    val mediaLocalFolder: String = "",
    val playlistEnabled: Boolean = false,
    val playlistUrl: String = "",
    val playlistRefreshSec: Int = 300,
    /** The web page the media band can show. Stored in full (it may carry a token). */
    val mediaWebUrl: String = "",
    /** Open [mediaWebUrl] on startup instead of the folder/playlist. */
    val mediaWebEnabled: Boolean = false,
    /** Which source the controller selects at startup. */
    val mediaSourceDefault: MediaSourceDefault = MediaSourceDefault.LOCAL,
    val imageDurationSec: Int = 10,
    val volumePercent: Int = 100,
    val mediaFillMode: MediaFillMode = MediaFillMode.LETTERBOX_BLUR,
    val newCallHighlight: Boolean = true,
    val uiScale: Int = 100
)
