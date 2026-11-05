package com.terebibro.tv.media

/**
 * A partial update to the media settings, as accepted by
 * `POST /api/settings/media`. A `null` field means "leave unchanged".
 *
 * Enum fields are carried as raw wire strings so the validator can report an
 * unknown value against the field that supplied it instead of a JSON type
 * error.
 */
data class MediaUpdate(
    val enabled: Boolean? = null,
    val qmsUrl: String? = null,
    val qmsStripSelector: String? = null,
    val qmsNumberSelector: String? = null,
    val qmsLocationSelector: String? = null,
    val qmsTimeSelector: String? = null,
    val callSource: String? = null,
    val dwellSec: Int? = null,
    val mediaSplitPercent: Int? = null,
    val headerBandPercent: Int? = null,
    val stripHeightPercent: Int? = null,
    val currentNumberPx: Int? = null,
    val historyNumberPx: Int? = null,
    val labelPx: Int? = null,
    val currentCardPx: Int? = null,
    val historyCardMinPx: Int? = null,
    val historyCardMaxPx: Int? = null,
    val uiLanguage: String? = null,
    val mediaLocalEnabled: Boolean? = null,
    val playlistEnabled: Boolean? = null,
    val playlistUrl: String? = null,
    val playlistRefreshSec: Int? = null,
    val mediaWebUrl: String? = null,
    val mediaWebEnabled: Boolean? = null,
    val mediaSourceDefault: String? = null,
    val imageDurationSec: Int? = null,
    val volumePercent: Int? = null,
    val mediaFillMode: String? = null,
    val newCallHighlight: Boolean? = null,
    val uiScale: Int? = null
)
