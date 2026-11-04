package com.terebibro.tv.media

import kotlin.math.roundToInt

/**
 * Pure band and letterbox geometry. The bands are percentages of the screen and
 * must sum to exactly the screen height: a 1 px overlap is a visible seam and a
 * 1 px gap is a black line across the TV.
 */
object MediaLayout {

    data class Bands(val mediaDp: Int, val headerDp: Int, val queueDp: Int)

    data class Fit(val w: Int, val h: Int)

    fun bands(screenHeightDp: Int, cfg: MediaSettings): Bands {
        val height = screenHeightDp.coerceAtLeast(0)
        val headerDp = percentOf(height, cfg.headerBandPercent)
        val queueDp = percentOf(height, cfg.stripHeightPercent)
        return Bands(mediaDp = height - headerDp - queueDp, headerDp = headerDp, queueDp = queueDp)
    }

    fun renderScale(screenHeightDp: Int): Float = screenHeightDp / 1080f

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
