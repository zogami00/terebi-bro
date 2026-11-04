package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsValidatorTest {

    private fun valid(
        update: MediaUpdate,
        current: MediaSettings = MediaSettings()
    ): MediaSettings {
        val result = MediaSettingsValidator.validate(update, current)
        assertTrue("expected valid but was $result", result is MediaSettingsValidator.Result.Valid)
        return (result as MediaSettingsValidator.Result.Valid).settings
    }

    private fun invalid(
        update: MediaUpdate,
        current: MediaSettings = MediaSettings()
    ): MediaSettingsValidator.Result.Invalid {
        val result = MediaSettingsValidator.validate(update, current)
        assertTrue("expected invalid but was $result", result is MediaSettingsValidator.Result.Invalid)
        return result as MediaSettingsValidator.Result.Invalid
    }

    /** Every field differs from its default, so an inverted merge is visible. */
    private fun nonDefault() = MediaSettings(
        enabled = true,
        qmsUrl = "https://qms.example/board",
        qmsStripSelector = "section.qms > div",
        qmsNumberSelector = "span.ticket",
        qmsLocationSelector = "span.room",
        qmsTimeSelector = "span.time",
        callSource = CallSource.AUDIO,
        dwellSec = 90,
        mediaSplitPercent = 60,
        headerBandPercent = 10,
        stripHeightPercent = 30,
        currentNumberPx = 120,
        historyNumberPx = 64,
        labelPx = 18,
        currentCardPx = 400,
        historyCardMinPx = 200,
        historyCardMaxPx = 280,
        uiLanguage = UiLanguage.TA,
        mediaLocalEnabled = false,
        playlistEnabled = true,
        playlistUrl = "https://example.com/list.json",
        playlistRefreshSec = 600,
        imageDurationSec = 20,
        volumePercent = 70,
        mediaFillMode = MediaFillMode.COVER,
        newCallHighlight = false,
        uiScale = 120
    )

    // ------------------------------------------------------------------ ranges

    @Test
    fun `an empty update is valid and changes nothing`() {
        val current = nonDefault()
        val settings = valid(MediaUpdate(), current)

        assertEquals(current.enabled, settings.enabled)
        assertEquals(current.qmsUrl, settings.qmsUrl)
        assertEquals(current.qmsStripSelector, settings.qmsStripSelector)
        assertEquals(current.qmsNumberSelector, settings.qmsNumberSelector)
        assertEquals(current.qmsLocationSelector, settings.qmsLocationSelector)
        assertEquals(current.qmsTimeSelector, settings.qmsTimeSelector)
        assertEquals(current.callSource, settings.callSource)
        assertEquals(current.dwellSec, settings.dwellSec)
        assertEquals(current.mediaSplitPercent, settings.mediaSplitPercent)
        assertEquals(current.headerBandPercent, settings.headerBandPercent)
        assertEquals(current.stripHeightPercent, settings.stripHeightPercent)
        assertEquals(current.currentNumberPx, settings.currentNumberPx)
        assertEquals(current.historyNumberPx, settings.historyNumberPx)
        assertEquals(current.labelPx, settings.labelPx)
        assertEquals(current.currentCardPx, settings.currentCardPx)
        assertEquals(current.historyCardMinPx, settings.historyCardMinPx)
        assertEquals(current.historyCardMaxPx, settings.historyCardMaxPx)
        assertEquals(current.uiLanguage, settings.uiLanguage)
        assertEquals(current.mediaLocalEnabled, settings.mediaLocalEnabled)
        assertEquals(current.playlistEnabled, settings.playlistEnabled)
        assertEquals(current.playlistUrl, settings.playlistUrl)
        assertEquals(current.playlistRefreshSec, settings.playlistRefreshSec)
        assertEquals(current.imageDurationSec, settings.imageDurationSec)
        assertEquals(current.volumePercent, settings.volumePercent)
        assertEquals(current.mediaFillMode, settings.mediaFillMode)
        assertEquals(current.newCallHighlight, settings.newCallHighlight)
        assertEquals(current.uiScale, settings.uiScale)
    }

    @Test
    fun `every numeric field rejects one below its minimum`() {
        val cases = listOf(
            "dwellSec" to MediaUpdate(dwellSec = 19),
            "mediaSplitPercent" to MediaUpdate(mediaSplitPercent = 54),
            "headerBandPercent" to MediaUpdate(headerBandPercent = 2),
            "stripHeightPercent" to MediaUpdate(stripHeightPercent = 17),
            "currentNumberPx" to MediaUpdate(currentNumberPx = 47),
            "historyNumberPx" to MediaUpdate(historyNumberPx = 31),
            "labelPx" to MediaUpdate(labelPx = 9),
            "currentCardPx" to MediaUpdate(currentCardPx = 239),
            "historyCardMinPx" to MediaUpdate(historyCardMinPx = 119),
            "historyCardMaxPx" to MediaUpdate(historyCardMaxPx = 189),
            "playlistRefreshSec" to MediaUpdate(playlistRefreshSec = 29),
            "imageDurationSec" to MediaUpdate(imageDurationSec = 2),
            "volumePercent" to MediaUpdate(volumePercent = -1),
            "uiScale" to MediaUpdate(uiScale = 79)
        )
        for ((field, update) in cases) {
            assertEquals(field, invalid(update).field)
        }
    }

    @Test
    fun `every numeric field rejects one above its maximum`() {
        val cases = listOf(
            "dwellSec" to MediaUpdate(dwellSec = 181),
            "mediaSplitPercent" to MediaUpdate(mediaSplitPercent = 86),
            "headerBandPercent" to MediaUpdate(headerBandPercent = 16),
            "stripHeightPercent" to MediaUpdate(stripHeightPercent = 41),
            "currentNumberPx" to MediaUpdate(currentNumberPx = 161),
            "historyNumberPx" to MediaUpdate(historyNumberPx = 97),
            "labelPx" to MediaUpdate(labelPx = 25),
            "currentCardPx" to MediaUpdate(currentCardPx = 481),
            "historyCardMinPx" to MediaUpdate(historyCardMinPx = 321),
            "historyCardMaxPx" to MediaUpdate(historyCardMaxPx = 481),
            "playlistRefreshSec" to MediaUpdate(playlistRefreshSec = 3601),
            "imageDurationSec" to MediaUpdate(imageDurationSec = 121),
            "volumePercent" to MediaUpdate(volumePercent = 101),
            "uiScale" to MediaUpdate(uiScale = 201)
        )
        for ((field, update) in cases) {
            assertEquals(field, invalid(update).field)
        }
    }

    @Test
    fun `every numeric boundary is accepted`() {
        // The band fields are omitted here when the compensating bands cannot
        // reach 100 inside their own ranges; see the band tests below.
        val cases = listOf(
            MediaUpdate(dwellSec = 20),
            MediaUpdate(dwellSec = 180),
            MediaUpdate(playlistRefreshSec = 30),
            MediaUpdate(playlistRefreshSec = 3600),
            MediaUpdate(imageDurationSec = 3),
            MediaUpdate(imageDurationSec = 120),
            MediaUpdate(volumePercent = 0),
            MediaUpdate(volumePercent = 100),
            MediaUpdate(uiScale = 80),
            MediaUpdate(uiScale = 200),
            MediaUpdate(currentNumberPx = 48),
            MediaUpdate(currentNumberPx = 160),
            MediaUpdate(historyNumberPx = 32),
            MediaUpdate(historyNumberPx = 96),
            MediaUpdate(labelPx = 10),
            MediaUpdate(labelPx = 24),
            MediaUpdate(currentCardPx = 240),
            MediaUpdate(currentCardPx = 480),
            MediaUpdate(historyCardMinPx = 120),
            MediaUpdate(historyCardMinPx = 320),
            MediaUpdate(historyCardMaxPx = 190),
            MediaUpdate(historyCardMaxPx = 480)
        )
        for (update in cases) valid(update)
    }

    // ------------------------------------------------------------------- bands

    @Test
    fun `the three bands must sum to exactly 100`() {
        val atHundred = valid(MediaUpdate(mediaSplitPercent = 71))
        assertEquals(71, atHundred.mediaSplitPercent)

        val low = invalid(MediaUpdate(mediaSplitPercent = 70))
        assertEquals("mediaSplitPercent", low.field)

        val high = invalid(MediaUpdate(mediaSplitPercent = 72))
        assertEquals("mediaSplitPercent", high.field)
    }

    @Test
    fun `the band sum error names the actual total`() {
        val low = invalid(MediaUpdate(mediaSplitPercent = 70))
        assertTrue("was ${low.message}", low.message.contains("99"))

        val high = invalid(MediaUpdate(mediaSplitPercent = 72))
        assertTrue("was ${high.message}", high.message.contains("101"))
    }

    @Test
    fun `the band sum error names the field the update actually carried`() {
        // 71 + 5 + 22 = 98: only headerBandPercent was sent.
        assertEquals("headerBandPercent", invalid(MediaUpdate(headerBandPercent = 5)).field)
        // 71 + 7 + 30 = 108: only stripHeightPercent was sent.
        assertEquals("stripHeightPercent", invalid(MediaUpdate(stripHeightPercent = 30)).field)
        // With more than one band present the last one in the order wins.
        assertEquals(
            "stripHeightPercent",
            invalid(MediaUpdate(headerBandPercent = 9, stripHeightPercent = 30)).field
        )
        // Nothing band-related present: fall back to the documented field.
        assertEquals(
            "mediaSplitPercent",
            invalid(MediaUpdate(dwellSec = 90), MediaSettings(enabled = true, stripHeightPercent = 30)).field
        )
    }

    @Test
    fun `each band accepts its minimum and maximum where the sum permits`() {
        // split 55 needs 45 from the other two bands.
        assertEquals(55, valid(MediaUpdate(mediaSplitPercent = 55, stripHeightPercent = 38)).mediaSplitPercent)
        // header 3 / 15, with the other bands compensated.
        assertEquals(
            3,
            valid(MediaUpdate(mediaSplitPercent = 71, headerBandPercent = 3, stripHeightPercent = 26))
                .headerBandPercent
        )
        assertEquals(
            15,
            valid(MediaUpdate(mediaSplitPercent = 65, headerBandPercent = 15, stripHeightPercent = 20))
                .headerBandPercent
        )
        // strip 18 / 40, with the other bands compensated.
        assertEquals(
            18,
            valid(MediaUpdate(mediaSplitPercent = 71, headerBandPercent = 11, stripHeightPercent = 18))
                .stripHeightPercent
        )
        assertEquals(
            40,
            valid(MediaUpdate(mediaSplitPercent = 55, headerBandPercent = 5, stripHeightPercent = 40))
                .stripHeightPercent
        )
    }

    @Test
    fun `split 85 cannot be reached because the other two bands would exceed 100`() {
        // 85 + minimum header 3 + minimum strip 18 = 106, so the value is in
        // range but the merged whole can never satisfy the sum rule.
        val result = invalid(MediaUpdate(mediaSplitPercent = 85))
        assertEquals("mediaSplitPercent", result.field)
        assertTrue("was ${result.message}", result.message.contains("114"))
    }

    // ------------------------------------------------------------- cross field

    @Test
    fun `history card minimum must not exceed its maximum`() {
        val result = invalid(MediaUpdate(historyCardMinPx = 300, historyCardMaxPx = 250))
        assertEquals("historyCardMinPx", result.field)
        // Equal is allowed.
        valid(MediaUpdate(historyCardMinPx = 300, historyCardMaxPx = 300))
    }

    @Test
    fun `enabling the playlist requires a url`() {
        assertEquals("playlistUrl", invalid(MediaUpdate(playlistEnabled = true)).field)
    }

    @Test
    fun `enabling the playlist rejects a bad url`() {
        val result = invalid(MediaUpdate(playlistEnabled = true, playlistUrl = "ftp://example.com/list.json"))
        assertEquals("playlistUrl", result.field)
    }

    @Test
    fun `a valid playlist url is accepted`() {
        val settings = valid(
            MediaUpdate(
                playlistEnabled = true,
                playlistUrl = "https://example.com/list.json"
            )
        )
        assertEquals(true, settings.playlistEnabled)
        assertEquals("https://example.com/list.json", settings.playlistUrl)
    }

    // -------------------------------------------------------------------- urls

    @Test
    fun `a blank url is allowed and stored blank`() {
        assertEquals("", valid(MediaUpdate(qmsUrl = "")).qmsUrl)
        assertEquals("", valid(MediaUpdate(qmsUrl = "   ")).qmsUrl)
        assertEquals("", valid(MediaUpdate(playlistUrl = "   ")).playlistUrl)
    }

    @Test
    fun `a non-http url is rejected naming the field`() {
        assertEquals("qmsUrl", invalid(MediaUpdate(qmsUrl = "javascript:alert(1)")).field)
        assertEquals("qmsUrl", invalid(MediaUpdate(qmsUrl = "ftp://qms.example")).field)
        assertEquals("qmsUrl", invalid(MediaUpdate(qmsUrl = "data:text/html,x")).field)
    }

    // --------------------------------------------------------------- selectors

    @Test
    fun `every selector rejects a blank value`() {
        assertEquals("qmsStripSelector", invalid(MediaUpdate(qmsStripSelector = "")).field)
        assertEquals("qmsNumberSelector", invalid(MediaUpdate(qmsNumberSelector = "   ")).field)
        assertEquals("qmsLocationSelector", invalid(MediaUpdate(qmsLocationSelector = "")).field)
        assertEquals("qmsTimeSelector", invalid(MediaUpdate(qmsTimeSelector = "  \t ")).field)
    }

    @Test
    fun `a selector is trimmed`() {
        assertEquals("div > span", valid(MediaUpdate(qmsNumberSelector = "  div > span  ")).qmsNumberSelector)
    }

    @Test
    fun `an over-long selector is rejected`() {
        val tooLong = "a".repeat(MediaSettingsValidator.MAX_SELECTOR_LENGTH + 1)
        assertEquals("qmsStripSelector", invalid(MediaUpdate(qmsStripSelector = tooLong)).field)
        valid(MediaUpdate(qmsStripSelector = "a".repeat(MediaSettingsValidator.MAX_SELECTOR_LENGTH)))
    }

    @Test
    fun `a selector with control characters or braces is rejected`() {
        assertEquals("qmsTimeSelector", invalid(MediaUpdate(qmsTimeSelector = "div\ttab")).field)
        assertEquals("qmsStripSelector", invalid(MediaUpdate(qmsStripSelector = "main > div{}")).field)
        assertEquals("qmsLocationSelector", invalid(MediaUpdate(qmsLocationSelector = "span{")).field)
    }

    @Test
    fun `a selector cannot break the stylesheet scope`() {
        // A comma ends the `html[data-terebi-strip] …` scope.
        val comma = invalid(MediaUpdate(qmsStripSelector = "main > div, body"))
        assertEquals("qmsStripSelector", comma.field)
        assertTrue(comma.message.contains("qmsStripSelector"))
        assertTrue(comma.message.contains("single, comment-free selector"))

        // These shapes are checked on every selector-bearing field.
        assertEquals("qmsNumberSelector", invalid(MediaUpdate(qmsNumberSelector = "span/*")).field)
        assertEquals("qmsLocationSelector", invalid(MediaUpdate(qmsLocationSelector = "span*/")).field)
        assertEquals("qmsTimeSelector", invalid(MediaUpdate(qmsTimeSelector = "span[title=\"x]")).field)

        // An even number of quotes is a well-formed attribute selector.
        valid(MediaUpdate(qmsStripSelector = "main > div[data-x=\"y\"]"))
    }

    @Test
    fun `the documented default selectors are accepted`() {
        val defaults = MediaSettings()
        valid(
            MediaUpdate(
                qmsStripSelector = defaults.qmsStripSelector,
                qmsNumberSelector = defaults.qmsNumberSelector,
                qmsLocationSelector = defaults.qmsLocationSelector,
                qmsTimeSelector = defaults.qmsTimeSelector
            )
        )
    }

    // ------------------------------------------------------------------- enums

    @Test
    fun `an unknown call source is rejected on its own field`() {
        assertEquals("callSource", invalid(MediaUpdate(callSource = "microphone")).field)
    }

    @Test
    fun `an unknown ui language is rejected on its own field`() {
        assertEquals("uiLanguage", invalid(MediaUpdate(uiLanguage = "fr")).field)
    }

    @Test
    fun `an unknown fill mode is rejected on its own field`() {
        assertEquals("mediaFillMode", invalid(MediaUpdate(mediaFillMode = "stretch")).field)
    }

    @Test
    fun `every documented enum wire value round-trips`() {
        assertEquals(CallSource.DOM_OR_AUDIO, valid(MediaUpdate(callSource = "dom_or_audio")).callSource)
        assertEquals(CallSource.AUDIO, valid(MediaUpdate(callSource = "audio")).callSource)
        assertEquals(UiLanguage.EN, valid(MediaUpdate(uiLanguage = "en")).uiLanguage)
        assertEquals(UiLanguage.ZH, valid(MediaUpdate(uiLanguage = "zh")).uiLanguage)
        assertEquals(UiLanguage.TA, valid(MediaUpdate(uiLanguage = "ta")).uiLanguage)
        assertEquals(MediaFillMode.LETTERBOX, valid(MediaUpdate(mediaFillMode = "letterbox")).mediaFillMode)
        assertEquals(MediaFillMode.COVER, valid(MediaUpdate(mediaFillMode = "cover")).mediaFillMode)
    }

    // ------------------------------------------------------------------ merging

    @Test
    fun `a partial update is merged over the current settings`() {
        val current = MediaSettings(
            dwellSec = 90,
            volumePercent = 30,
            uiLanguage = UiLanguage.EN,
            qmsUrl = "https://qms.example"
        )
        val settings = valid(MediaUpdate(dwellSec = 120), current)
        assertEquals(120, settings.dwellSec)
        assertEquals(30, settings.volumePercent)
        assertEquals(UiLanguage.EN, settings.uiLanguage)
        assertEquals("https://qms.example", settings.qmsUrl)
        assertEquals(current.mediaSplitPercent, settings.mediaSplitPercent)
    }

    @Test
    fun `a bad field leaves every other field unchanged`() {
        val current = MediaSettings(dwellSec = 90)
        val result = MediaSettingsValidator.validate(
            MediaUpdate(
                dwellSec = 10,
                volumePercent = 50,
                uiScale = 120,
                labelPx = 12
            ),
            current
        )
        assertTrue(result is MediaSettingsValidator.Result.Invalid)
        assertEquals("dwellSec", (result as MediaSettingsValidator.Result.Invalid).field)

        // The same good fields without the bad one apply cleanly, so the
        // failure above cannot have half-written anything.
        val settings = valid(MediaUpdate(volumePercent = 50, uiScale = 120, labelPx = 12), current)
        assertEquals(90, settings.dwellSec)
        assertEquals(50, settings.volumePercent)
        assertEquals(120, settings.uiScale)
        assertEquals(12, settings.labelPx)
    }

    @Test
    fun `booleans round-trip`() {
        val settings = valid(
            MediaUpdate(
                enabled = true,
                mediaLocalEnabled = false,
                newCallHighlight = false
            )
        )
        assertEquals(true, settings.enabled)
        assertEquals(false, settings.mediaLocalEnabled)
        assertEquals(false, settings.newCallHighlight)
    }
}
