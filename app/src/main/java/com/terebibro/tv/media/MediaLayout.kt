package com.terebibro.tv.media

import kotlin.math.roundToInt

/**
 * Pure band and letterbox geometry. The bands are percentages of the screen and
 * must sum to exactly the screen height: a 1 px overlap is a visible seam and a
 * 1 px gap is a black line across the TV.
 */
object MediaLayout {

    data class Bands(val mediaDp: Int, val headerDp: Int, val queueDp: Int)

    /** Screen-height bands in physical pixels; the three always sum to the surface. */
    data class BandsPx(val mediaPx: Int, val headerPx: Int, val queuePx: Int)

    data class Fit(val w: Int, val h: Int)

    /** How a still image fills the media band. */
    enum class Fill { FIT, CROP, FIT_OVER_BACKDROP }

    fun bands(screenHeightDp: Int, cfg: MediaSettings): Bands {
        val height = screenHeightDp.coerceAtLeast(0)
        val headerDp = percentOf(height, cfg.headerBandPercent)
        val queueDp = percentOf(height, cfg.stripHeightPercent)
        return Bands(mediaDp = height - headerDp - queueDp, headerDp = headerDp, queueDp = queueDp)
    }

    /**
     * Splits the physical surface height into px bands. Rounding each band to px
     * independently can make `mediaPx + lowerPx` differ from the surface by a
     * pixel (a seam or a clipped queue), so only the two fixed bands are rounded
     * here and media takes the exact remainder.
     */
    fun bandsPx(screenHeightPx: Int, density: Float, cfg: MediaSettings): BandsPx {
        val height = screenHeightPx.coerceAtLeast(0)
        // Density only matters if the authored heights were dp; these bands are
        // already percentages of the physical surface, so the value is kept in
        // the signature for callers that reason in dp and for the tests.
        val safeDensity = if (density > 0f) density else 1f
        val headerPx = ((height / safeDensity) * cfg.headerBandPercent / 100.0 * safeDensity)
            .roundToInt()
            .coerceIn(0, height)
        val queuePx = ((height / safeDensity) * cfg.stripHeightPercent / 100.0 * safeDensity)
            .roundToInt()
            .coerceIn(0, height - headerPx)
        return BandsPx(mediaPx = height - headerPx - queuePx, headerPx = headerPx, queuePx = queuePx)
    }

    /** The pure mapping from the operator's fill choice to the image treatment. */
    fun fillFor(mode: MediaFillMode): Fill = when (mode) {
        MediaFillMode.LETTERBOX_BLUR -> Fill.FIT_OVER_BACKDROP
        MediaFillMode.LETTERBOX -> Fill.FIT
        MediaFillMode.COVER -> Fill.CROP
    }

    fun renderScale(screenHeightDp: Int): Float = screenHeightDp / 1080f

    /**
     * The 1080p authoring surface mapped to this device's real dp height.
     *
     * A surface *shorter* than 1080 dp must render the whole interface smaller,
     * not unchanged: the authored bands are percentages of the 1080 dp design,
     * so flooring the factor at 1 leaves a phone with a header that fills most
     * of the WebView band and cards that overflow it. The bounds only guard
     * against a zero, negative or absurd height (a bogus metric report), never
     * against an honest small screen.
     */
    fun safeRenderScale(screenHeightDp: Int): Float =
        if (screenHeightDp <= 0) 1f else renderScale(screenHeightDp).coerceIn(0.25f, 4f)

    fun fit(videoW: Int, videoH: Int, boxW: Int, boxH: Int): Fit {
        if (videoW <= 0 || videoH <= 0 || boxW <= 0 || boxH <= 0) return Fit(0, 0)
        val videoAspect = videoW.toDouble() / videoH
        val boxAspect = boxW.toDouble() / boxH
        return if (videoAspect > boxAspect) {
            Fit(boxW, (boxW / videoAspect).roundToInt())
        } else {
            Fit((boxH * videoAspect).roundToInt(), boxH)
        }
    }

    private fun percentOf(total: Int, percent: Int): Int =
        (total.toDouble() * percent / 100.0).roundToInt()
}
