package com.terebibro.tv.media

/**
 * Pure, Android-free recovery of the persisted media settings.
 *
 * Stored preferences are untrusted: a downgrade, a hand-edited blob or a
 * partially written value can leave a single field at the wrong type or out of
 * range. Recovery is therefore **per field** — a bad value falls back to its own
 * default and no other field is touched — followed by a few deterministic
 * cross-field repairs. There is deliberately no "whole blob is invalid, return
 * defaults" path, because that would silently discard every other setting
 * (including the master switch).
 */
object MediaSettingsRecovery {

    // Stored preference keys. Kept here rather than in ConfigStore so the
    // recovery logic and its tests speak the exact names that are on disk.
    object Keys {
        const val ENABLED = "mediaEnabled"
        const val QMS_URL = "mediaQmsUrl"
        const val QMS_STRIP_SELECTOR = "mediaQmsStripSelector"
        const val QMS_NUMBER_SELECTOR = "mediaQmsNumberSelector"
        const val QMS_LOCATION_SELECTOR = "mediaQmsLocationSelector"
        const val QMS_TIME_SELECTOR = "mediaQmsTimeSelector"
        const val CALL_SOURCE = "mediaCallSource"
        const val DWELL_SEC = "mediaDwellSec"
        const val SPLIT_PERCENT = "mediaSplitPercent"
        const val HEADER_PERCENT = "mediaHeaderBandPercent"
        const val STRIP_PERCENT = "mediaStripHeightPercent"
        const val CURRENT_NUMBER_PX = "mediaCurrentNumberPx"
        const val HISTORY_NUMBER_PX = "mediaHistoryNumberPx"
        const val LABEL_PX = "mediaLabelPx"
        const val CURRENT_CARD_PX = "mediaCurrentCardPx"
        const val HISTORY_CARD_MIN_PX = "mediaHistoryCardMinPx"
        const val HISTORY_CARD_MAX_PX = "mediaHistoryCardMaxPx"
        const val UI_LANGUAGE = "mediaUiLanguage"
        const val LOCAL_ENABLED = "mediaLocalEnabled"
        const val PLAYLIST_ENABLED = "mediaPlaylistEnabled"
        const val PLAYLIST_URL = "mediaPlaylistUrl"
        const val PLAYLIST_REFRESH_SEC = "mediaPlaylistRefreshSec"
        const val WEB_URL = "mediaWebUrl"
        const val WEB_ENABLED = "mediaWebEnabled"
        const val SOURCE_DEFAULT = "mediaSourceDefault"
        const val IMAGE_DURATION_SEC = "mediaImageDurationSec"
        const val VOLUME_PERCENT = "mediaVolumePercent"
        const val FILL_MODE = "mediaFillMode"
        const val NEW_CALL_HIGHLIGHT = "mediaNewCallHighlight"
        const val UI_SCALE = "mediaUiScale"
    }

    /**
     * Recovers the effective settings from a raw preferences map (as returned
     * by `SharedPreferences.all`). Keys other than the media ones are ignored.
     *
     * A blank stored [MediaSettings.qmsUrl] resolves to [homeUrl] so enabling
     * media mode without configuring a QMS URL targets the dashboard. That is a
     * read-time substitute only: the stored value stays blank, so it keeps
     * tracking the Home URL.
     */
    fun recover(raw: Map<String, Any?>, homeUrl: String = ""): MediaSettings {
        val d = MediaSettings()
        val settings = MediaSettings(
            enabled = bool(raw, Keys.ENABLED, d.enabled),
            qmsUrl = string(raw, Keys.QMS_URL, d.qmsUrl).ifBlank { homeUrl },
            qmsStripSelector = string(raw, Keys.QMS_STRIP_SELECTOR, d.qmsStripSelector),
            qmsNumberSelector = string(raw, Keys.QMS_NUMBER_SELECTOR, d.qmsNumberSelector),
            qmsLocationSelector = string(raw, Keys.QMS_LOCATION_SELECTOR, d.qmsLocationSelector),
            qmsTimeSelector = string(raw, Keys.QMS_TIME_SELECTOR, d.qmsTimeSelector),
            callSource = CallSource.fromWire(string(raw, Keys.CALL_SOURCE, d.callSource.wire))
                ?: d.callSource,
            dwellSec = int(
                raw, Keys.DWELL_SEC, d.dwellSec,
                MediaSettingsValidator.MIN_DWELL_SEC, MediaSettingsValidator.MAX_DWELL_SEC
            ),
            mediaSplitPercent = int(
                raw, Keys.SPLIT_PERCENT, d.mediaSplitPercent,
                MediaSettingsValidator.MIN_SPLIT_PERCENT, MediaSettingsValidator.MAX_SPLIT_PERCENT
            ),
            headerBandPercent = int(
                raw, Keys.HEADER_PERCENT, d.headerBandPercent,
                MediaSettingsValidator.MIN_HEADER_PERCENT, MediaSettingsValidator.MAX_HEADER_PERCENT
            ),
            stripHeightPercent = int(
                raw, Keys.STRIP_PERCENT, d.stripHeightPercent,
                MediaSettingsValidator.MIN_STRIP_PERCENT, MediaSettingsValidator.MAX_STRIP_PERCENT
            ),
            currentNumberPx = int(
                raw, Keys.CURRENT_NUMBER_PX, d.currentNumberPx,
                MediaSettingsValidator.MIN_CURRENT_NUMBER_PX, MediaSettingsValidator.MAX_CURRENT_NUMBER_PX
            ),
            historyNumberPx = int(
                raw, Keys.HISTORY_NUMBER_PX, d.historyNumberPx,
                MediaSettingsValidator.MIN_HISTORY_NUMBER_PX, MediaSettingsValidator.MAX_HISTORY_NUMBER_PX
            ),
            labelPx = int(
                raw, Keys.LABEL_PX, d.labelPx,
                MediaSettingsValidator.MIN_LABEL_PX, MediaSettingsValidator.MAX_LABEL_PX
            ),
            currentCardPx = int(
                raw, Keys.CURRENT_CARD_PX, d.currentCardPx,
                MediaSettingsValidator.MIN_CURRENT_CARD_PX, MediaSettingsValidator.MAX_CURRENT_CARD_PX
            ),
            historyCardMinPx = int(
                raw, Keys.HISTORY_CARD_MIN_PX, d.historyCardMinPx,
                MediaSettingsValidator.MIN_HISTORY_CARD_MIN_PX, MediaSettingsValidator.MAX_HISTORY_CARD_MIN_PX
            ),
            historyCardMaxPx = int(
                raw, Keys.HISTORY_CARD_MAX_PX, d.historyCardMaxPx,
                MediaSettingsValidator.MIN_HISTORY_CARD_MAX_PX, MediaSettingsValidator.MAX_HISTORY_CARD_MAX_PX
            ),
            uiLanguage = UiLanguage.fromWire(string(raw, Keys.UI_LANGUAGE, d.uiLanguage.wire))
                ?: d.uiLanguage,
            mediaLocalEnabled = bool(raw, Keys.LOCAL_ENABLED, d.mediaLocalEnabled),
            playlistEnabled = bool(raw, Keys.PLAYLIST_ENABLED, d.playlistEnabled),
            playlistUrl = string(raw, Keys.PLAYLIST_URL, d.playlistUrl),
            playlistRefreshSec = int(
                raw, Keys.PLAYLIST_REFRESH_SEC, d.playlistRefreshSec,
                MediaSettingsValidator.MIN_PLAYLIST_REFRESH_SEC, MediaSettingsValidator.MAX_PLAYLIST_REFRESH_SEC
            ),
            mediaWebUrl = string(raw, Keys.WEB_URL, d.mediaWebUrl),
            mediaWebEnabled = bool(raw, Keys.WEB_ENABLED, d.mediaWebEnabled),
            mediaSourceDefault = MediaSourceDefault.fromWire(
                string(raw, Keys.SOURCE_DEFAULT, d.mediaSourceDefault.wire)
            ) ?: d.mediaSourceDefault,
            imageDurationSec = int(
                raw, Keys.IMAGE_DURATION_SEC, d.imageDurationSec,
                MediaSettingsValidator.MIN_IMAGE_DURATION_SEC, MediaSettingsValidator.MAX_IMAGE_DURATION_SEC
            ),
            volumePercent = int(
                raw, Keys.VOLUME_PERCENT, d.volumePercent,
                MediaSettingsValidator.MIN_VOLUME_PERCENT, MediaSettingsValidator.MAX_VOLUME_PERCENT
            ),
            mediaFillMode = MediaFillMode.fromWire(string(raw, Keys.FILL_MODE, d.mediaFillMode.wire))
                ?: d.mediaFillMode,
            newCallHighlight = bool(raw, Keys.NEW_CALL_HIGHLIGHT, d.newCallHighlight),
            uiScale = int(
                raw, Keys.UI_SCALE, d.uiScale,
                MediaSettingsValidator.MIN_UI_SCALE, MediaSettingsValidator.MAX_UI_SCALE
            )
        )
        return repair(settings)
    }

    /**
     * Deterministic cross-field repairs, applied only to the fields involved.
     * Every field has already been range-checked, so a repair can never push a
     * value out of its own bounds.
     */
    private fun repair(settings: MediaSettings): MediaSettings {
        var result = settings

        if (result.historyCardMinPx > result.historyCardMaxPx) {
            result = result.copy(
                historyCardMinPx = result.historyCardMaxPx,
                historyCardMaxPx = result.historyCardMinPx
            )
        }

        val bandTotal = result.mediaSplitPercent + result.headerBandPercent + result.stripHeightPercent
        if (bandTotal != 100) {
            val strip = 100 - result.mediaSplitPercent - result.headerBandPercent
            result = if (strip in MediaSettingsValidator.MIN_STRIP_PERCENT..MediaSettingsValidator.MAX_STRIP_PERCENT) {
                result.copy(stripHeightPercent = strip)
            } else {
                val defaults = MediaSettings()
                result.copy(
                    mediaSplitPercent = defaults.mediaSplitPercent,
                    headerBandPercent = defaults.headerBandPercent,
                    stripHeightPercent = defaults.stripHeightPercent
                )
            }
        }

        // A stored `web` default with no stored URL cannot be honoured; fall
        // back to this field's default only, leaving every other setting alone.
        if (result.mediaSourceDefault == MediaSourceDefault.WEB && result.mediaWebUrl.isBlank()) {
            result = result.copy(mediaSourceDefault = MediaSettings().mediaSourceDefault)
        }

        return result
    }

    // ----------------------------------------------------------------- reading

    /**
     * A stored value of the wrong type falls back to the default. `as?` makes
     * this a type guard rather than an exception, so one corrupt field cannot
     * abort the read of the others.
     */
    private fun string(raw: Map<String, Any?>, key: String, default: String): String =
        raw[key] as? String ?: default

    private fun bool(raw: Map<String, Any?>, key: String, default: Boolean): Boolean =
        raw[key] as? Boolean ?: default

    /** Accepts a stored number or a numeric string; null when it is neither. */
    private fun number(raw: Map<String, Any?>, key: String): Int? =
        try {
            when (val value = raw[key]) {
                is Int -> value
                is Number -> value.toInt()
                is String -> value.trim().toInt()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }

    private fun int(raw: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val value = number(raw, key) ?: return default
        return if (value in min..max) value else default
    }
}
