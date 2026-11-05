package com.terebibro.tv.media

import com.terebibro.tv.web.UrlValidator

/**
 * Pure-JVM validation for `POST /api/settings/media`.
 *
 * The update is merged over the current settings and the **merged whole** is
 * validated, because the band-sum and playlist rules are cross-field: a partial
 * update must still yield a settings object that is valid as a whole. A single
 * bad field is `Invalid` naming the field, and nothing is applied.
 */
object MediaSettingsValidator {

    const val MIN_SPLIT_PERCENT = 55
    const val MAX_SPLIT_PERCENT = 85
    const val MIN_HEADER_PERCENT = 3
    const val MAX_HEADER_PERCENT = 15
    const val MIN_STRIP_PERCENT = 18
    const val MAX_STRIP_PERCENT = 40

    const val MIN_DWELL_SEC = 20
    const val MAX_DWELL_SEC = 180
    const val MIN_PLAYLIST_REFRESH_SEC = 30
    const val MAX_PLAYLIST_REFRESH_SEC = 3600
    const val MIN_IMAGE_DURATION_SEC = 3
    const val MAX_IMAGE_DURATION_SEC = 120
    const val MIN_VOLUME_PERCENT = 0
    const val MAX_VOLUME_PERCENT = 100
    const val MIN_UI_SCALE = 80
    const val MAX_UI_SCALE = 200

    const val MIN_CURRENT_NUMBER_PX = 48
    const val MAX_CURRENT_NUMBER_PX = 160
    const val MIN_HISTORY_NUMBER_PX = 32
    const val MAX_HISTORY_NUMBER_PX = 96
    const val MIN_LABEL_PX = 10
    const val MAX_LABEL_PX = 24
    const val MIN_CURRENT_CARD_PX = 240
    const val MAX_CURRENT_CARD_PX = 480
    const val MIN_HISTORY_CARD_MIN_PX = 120
    const val MAX_HISTORY_CARD_MIN_PX = 320
    const val MIN_HISTORY_CARD_MAX_PX = 190
    const val MAX_HISTORY_CARD_MAX_PX = 480

    const val MAX_SELECTOR_LENGTH = 200

    sealed class Result {
        data class Valid(val settings: MediaSettings) : Result()
        data class Invalid(val field: String, val message: String) : Result()
    }

    fun validate(update: MediaUpdate, current: MediaSettings): Result {
        val qmsUrl = (update.qmsUrl ?: current.qmsUrl).trim()
        val stripSelector = (update.qmsStripSelector ?: current.qmsStripSelector).trim()
        val numberSelector = (update.qmsNumberSelector ?: current.qmsNumberSelector).trim()
        val locationSelector = (update.qmsLocationSelector ?: current.qmsLocationSelector).trim()
        val timeSelector = (update.qmsTimeSelector ?: current.qmsTimeSelector).trim()
        val playlistUrl = (update.playlistUrl ?: current.playlistUrl).trim()
        // A blank web URL keeps the stored value, the same semantics the MQTT
        // password uses: `GET /api/settings/media` only ever returns the masked
        // URL, so an untouched field must not overwrite the real one. There is
        // no clearing path; switching the startup source away from `web` (and
        // `mediaWebEnabled` off) is how a page is turned off.
        val mediaWebUrl = update.mediaWebUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: current.mediaWebUrl.trim()

        val callSource = update.callSource?.let { wire ->
            CallSource.fromWire(wire.trim())
                ?: return Result.Invalid("callSource", "callSource must be dom, dom_or_audio or audio")
        } ?: current.callSource

        val uiLanguage = update.uiLanguage?.let { wire ->
            UiLanguage.fromWire(wire.trim())
                ?: return Result.Invalid("uiLanguage", "uiLanguage must be ms, en, zh or ta")
        } ?: current.uiLanguage

        val mediaFillMode = update.mediaFillMode?.let { wire ->
            MediaFillMode.fromWire(wire.trim())
                ?: return Result.Invalid(
                    "mediaFillMode",
                    "mediaFillMode must be letterboxBlur, letterbox or cover"
                )
        } ?: current.mediaFillMode

        val mediaSourceDefault = update.mediaSourceDefault?.let { wire ->
            MediaSourceDefault.fromWire(wire.trim())
                ?: return Result.Invalid(
                    "mediaSourceDefault",
                    "mediaSourceDefault must be local, playlist or web"
                )
        } ?: current.mediaSourceDefault

        selector(stripSelector, "qmsStripSelector")?.let { return it }
        selector(numberSelector, "qmsNumberSelector")?.let { return it }
        selector(locationSelector, "qmsLocationSelector")?.let { return it }
        selector(timeSelector, "qmsTimeSelector")?.let { return it }

        val normalisedQmsUrl = when (val parsed = urlOrNull(qmsUrl, "qmsUrl")) {
            is UrlCheck.Invalid -> return parsed.result
            is UrlCheck.Valid -> parsed.value
        }
        val normalisedPlaylistUrl = when (val parsed = urlOrNull(playlistUrl, "playlistUrl")) {
            is UrlCheck.Invalid -> return parsed.result
            is UrlCheck.Valid -> parsed.value
        }
        val normalisedWebUrl = when (val parsed = urlOrNull(mediaWebUrl, "mediaWebUrl")) {
            is UrlCheck.Invalid -> return parsed.result
            is UrlCheck.Valid -> parsed.value
        }
        // A web default is meaningless without a page to open; name the field
        // the operator chose so the error lands under the select.
        if (mediaSourceDefault == MediaSourceDefault.WEB && normalisedWebUrl.isEmpty()) {
            return Result.Invalid(
                "mediaSourceDefault",
                "mediaSourceDefault cannot be web without a mediaWebUrl"
            )
        }

        val dwellSec = update.dwellSec ?: current.dwellSec
        val splitPercent = update.mediaSplitPercent ?: current.mediaSplitPercent
        val headerPercent = update.headerBandPercent ?: current.headerBandPercent
        val stripPercent = update.stripHeightPercent ?: current.stripHeightPercent
        val currentNumberPx = update.currentNumberPx ?: current.currentNumberPx
        val historyNumberPx = update.historyNumberPx ?: current.historyNumberPx
        val labelPx = update.labelPx ?: current.labelPx
        val currentCardPx = update.currentCardPx ?: current.currentCardPx
        val historyCardMinPx = update.historyCardMinPx ?: current.historyCardMinPx
        val historyCardMaxPx = update.historyCardMaxPx ?: current.historyCardMaxPx
        val playlistRefreshSec = update.playlistRefreshSec ?: current.playlistRefreshSec
        val imageDurationSec = update.imageDurationSec ?: current.imageDurationSec
        val volumePercent = update.volumePercent ?: current.volumePercent
        val uiScale = update.uiScale ?: current.uiScale

        range("dwellSec", dwellSec, MIN_DWELL_SEC, MAX_DWELL_SEC)?.let { return it }
        range("mediaSplitPercent", splitPercent, MIN_SPLIT_PERCENT, MAX_SPLIT_PERCENT)?.let { return it }
        range("headerBandPercent", headerPercent, MIN_HEADER_PERCENT, MAX_HEADER_PERCENT)?.let { return it }
        range("stripHeightPercent", stripPercent, MIN_STRIP_PERCENT, MAX_STRIP_PERCENT)?.let { return it }
        range("currentNumberPx", currentNumberPx, MIN_CURRENT_NUMBER_PX, MAX_CURRENT_NUMBER_PX)?.let { return it }
        range("historyNumberPx", historyNumberPx, MIN_HISTORY_NUMBER_PX, MAX_HISTORY_NUMBER_PX)?.let { return it }
        range("labelPx", labelPx, MIN_LABEL_PX, MAX_LABEL_PX)?.let { return it }
        range("currentCardPx", currentCardPx, MIN_CURRENT_CARD_PX, MAX_CURRENT_CARD_PX)?.let { return it }
        range("historyCardMinPx", historyCardMinPx, MIN_HISTORY_CARD_MIN_PX, MAX_HISTORY_CARD_MIN_PX)?.let { return it }
        range("historyCardMaxPx", historyCardMaxPx, MIN_HISTORY_CARD_MAX_PX, MAX_HISTORY_CARD_MAX_PX)?.let { return it }
        range("playlistRefreshSec", playlistRefreshSec, MIN_PLAYLIST_REFRESH_SEC, MAX_PLAYLIST_REFRESH_SEC)?.let { return it }
        range("imageDurationSec", imageDurationSec, MIN_IMAGE_DURATION_SEC, MAX_IMAGE_DURATION_SEC)?.let { return it }
        range("volumePercent", volumePercent, MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT)?.let { return it }
        range("uiScale", uiScale, MIN_UI_SCALE, MAX_UI_SCALE)?.let { return it }

        val bandTotal = splitPercent + headerPercent + stripPercent
        if (bandTotal != 100) {
            // Name the field the update actually carried, so the controller
            // paints the error under a control the operator touched.
            val field = when {
                update.stripHeightPercent != null -> "stripHeightPercent"
                update.headerBandPercent != null -> "headerBandPercent"
                else -> "mediaSplitPercent"
            }
            return Result.Invalid(
                field,
                "mediaSplitPercent + headerBandPercent + stripHeightPercent must equal 100 (actual $bandTotal)"
            )
        }

        if (historyCardMinPx > historyCardMaxPx) {
            return Result.Invalid(
                "historyCardMinPx",
                "historyCardMinPx must not exceed historyCardMaxPx ($historyCardMinPx > $historyCardMaxPx)"
            )
        }

        if (playlistEnabled(update, current) && normalisedPlaylistUrl.isEmpty()) {
            return Result.Invalid("playlistUrl", "playlistUrl is required when playlistEnabled is true")
        }

        return Result.Valid(
            MediaSettings(
                enabled = update.enabled ?: current.enabled,
                qmsUrl = normalisedQmsUrl,
                qmsStripSelector = stripSelector,
                qmsNumberSelector = numberSelector,
                qmsLocationSelector = locationSelector,
                qmsTimeSelector = timeSelector,
                callSource = callSource,
                dwellSec = dwellSec,
                mediaSplitPercent = splitPercent,
                headerBandPercent = headerPercent,
                stripHeightPercent = stripPercent,
                currentNumberPx = currentNumberPx,
                historyNumberPx = historyNumberPx,
                labelPx = labelPx,
                currentCardPx = currentCardPx,
                historyCardMinPx = historyCardMinPx,
                historyCardMaxPx = historyCardMaxPx,
                uiLanguage = uiLanguage,
                mediaLocalEnabled = update.mediaLocalEnabled ?: current.mediaLocalEnabled,
                playlistEnabled = playlistEnabled(update, current),
                playlistUrl = normalisedPlaylistUrl,
                playlistRefreshSec = playlistRefreshSec,
                mediaWebUrl = normalisedWebUrl,
                mediaWebEnabled = update.mediaWebEnabled ?: current.mediaWebEnabled,
                mediaSourceDefault = mediaSourceDefault,
                imageDurationSec = imageDurationSec,
                volumePercent = volumePercent,
                mediaFillMode = mediaFillMode,
                newCallHighlight = update.newCallHighlight ?: current.newCallHighlight,
                uiScale = uiScale
            )
        )
    }

    private fun playlistEnabled(update: MediaUpdate, current: MediaSettings): Boolean =
        update.playlistEnabled ?: current.playlistEnabled

    private fun range(field: String, value: Int, min: Int, max: Int): Result.Invalid? =
        if (value < min || value > max) {
            Result.Invalid(field, "$field must be between $min and $max")
        } else {
            null
        }

    private fun selector(value: String, field: String): Result.Invalid? {
        if (value.isEmpty()) return Result.Invalid(field, "$field must not be blank")
        if (value.length > MAX_SELECTOR_LENGTH) {
            return Result.Invalid(field, "$field is too long (max $MAX_SELECTOR_LENGTH)")
        }
        if (value.any { it.code < 0x20 || it.code == 0x7F }) {
            return Result.Invalid(field, "$field must not contain control characters")
        }
        if (value.contains('{') || value.contains('}')) {
            return Result.Invalid(field, "$field must not contain '{' or '}'")
        }
        if (QmsStripCss.selectorBreaksScope(value)) {
            return Result.Invalid(field, "$field must be a single, comment-free selector")
        }
        return null
    }

    private sealed class UrlCheck {
        data class Valid(val value: String) : UrlCheck()
        data class Invalid(val result: Result.Invalid) : UrlCheck()
    }

    /** A blank URL is allowed (the setting is simply not configured). */
    private fun urlOrNull(value: String, field: String): UrlCheck {
        if (value.isEmpty()) return UrlCheck.Valid("")
        return when (val parsed = UrlValidator.validate(value)) {
            is UrlValidator.Result.Invalid ->
                UrlCheck.Invalid(
                    Result.Invalid(field, "$field must be a valid http or https URL (${parsed.error})")
                )
            is UrlValidator.Result.Valid -> UrlCheck.Valid(parsed.url)
        }
    }
}
