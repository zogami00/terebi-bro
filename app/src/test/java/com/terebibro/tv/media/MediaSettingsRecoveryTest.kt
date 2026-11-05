package com.terebibro.tv.media

import com.terebibro.tv.media.MediaSettingsRecovery.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsRecoveryTest {

    /** A settings object where none of the 30 fields holds its default. */
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
        mediaWebUrl = "https://jellyfin.example/web?token=abc",
        mediaWebEnabled = true,
        mediaSourceDefault = MediaSourceDefault.WEB,
        imageDurationSec = 20,
        volumePercent = 70,
        mediaFillMode = MediaFillMode.COVER,
        newCallHighlight = false,
        uiScale = 120
    )

    private fun rawOf(settings: MediaSettings): MutableMap<String, Any?> = mutableMapOf(
        Keys.ENABLED to settings.enabled,
        Keys.QMS_URL to settings.qmsUrl,
        Keys.QMS_STRIP_SELECTOR to settings.qmsStripSelector,
        Keys.QMS_NUMBER_SELECTOR to settings.qmsNumberSelector,
        Keys.QMS_LOCATION_SELECTOR to settings.qmsLocationSelector,
        Keys.QMS_TIME_SELECTOR to settings.qmsTimeSelector,
        Keys.CALL_SOURCE to settings.callSource.wire,
        Keys.DWELL_SEC to settings.dwellSec,
        Keys.SPLIT_PERCENT to settings.mediaSplitPercent,
        Keys.HEADER_PERCENT to settings.headerBandPercent,
        Keys.STRIP_PERCENT to settings.stripHeightPercent,
        Keys.CURRENT_NUMBER_PX to settings.currentNumberPx,
        Keys.HISTORY_NUMBER_PX to settings.historyNumberPx,
        Keys.LABEL_PX to settings.labelPx,
        Keys.CURRENT_CARD_PX to settings.currentCardPx,
        Keys.HISTORY_CARD_MIN_PX to settings.historyCardMinPx,
        Keys.HISTORY_CARD_MAX_PX to settings.historyCardMaxPx,
        Keys.UI_LANGUAGE to settings.uiLanguage.wire,
        Keys.LOCAL_ENABLED to settings.mediaLocalEnabled,
        Keys.PLAYLIST_ENABLED to settings.playlistEnabled,
        Keys.PLAYLIST_URL to settings.playlistUrl,
        Keys.PLAYLIST_REFRESH_SEC to settings.playlistRefreshSec,
        Keys.WEB_URL to settings.mediaWebUrl,
        Keys.WEB_ENABLED to settings.mediaWebEnabled,
        Keys.SOURCE_DEFAULT to settings.mediaSourceDefault.wire,
        Keys.IMAGE_DURATION_SEC to settings.imageDurationSec,
        Keys.VOLUME_PERCENT to settings.volumePercent,
        Keys.FILL_MODE to settings.mediaFillMode.wire,
        Keys.NEW_CALL_HIGHLIGHT to settings.newCallHighlight,
        Keys.UI_SCALE to settings.uiScale
    )

    // ------------------------------------------------------------ per field

    @Test
    fun `an empty blob recovers every default`() {
        assertEquals(MediaSettings(), MediaSettingsRecovery.recover(emptyMap()))
    }

    @Test
    fun `one corrupt field falls back to its own default only`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        raw[Keys.DWELL_SEC] = 5 // below the documented minimum of 20

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(MediaSettings().dwellSec, recovered.dwellSec)
        assertEquals(expected.copy(dwellSec = MediaSettings().dwellSec), recovered)
    }

    @Test
    fun `a wrong typed field does not discard the other twenty six`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        raw[Keys.VOLUME_PERCENT] = "not a number"

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(MediaSettings().volumePercent, recovered.volumePercent)
        assertEquals(expected.copy(volumePercent = MediaSettings().volumePercent), recovered)
    }

    @Test
    fun `a numeric string is accepted for an integer field`() {
        val raw = rawOf(nonDefault())
        raw[Keys.UI_SCALE] = "150"
        assertEquals(150, MediaSettingsRecovery.recover(raw).uiScale)
    }

    @Test
    fun `an unknown enum wire falls back to its own default`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        raw[Keys.UI_LANGUAGE] = "fr"
        raw[Keys.FILL_MODE] = "stretch"
        raw[Keys.CALL_SOURCE] = "microphone"

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(UiLanguage.MS, recovered.uiLanguage)
        assertEquals(MediaFillMode.LETTERBOX_BLUR, recovered.mediaFillMode)
        assertEquals(CallSource.DOM, recovered.callSource)
        assertEquals(expected.mediaSplitPercent, recovered.mediaSplitPercent)
    }

    @Test
    fun `the master switch survives a corrupt unrelated field`() {
        val raw = rawOf(nonDefault())
        raw[Keys.LABEL_PX] = -3

        val recovered = MediaSettingsRecovery.recover(raw)

        assertTrue(recovered.enabled)
        assertEquals(MediaSettings().labelPx, recovered.labelPx)
    }

    @Test
    fun `an unrelated stored key is ignored`() {
        val raw = rawOf(nonDefault())
        raw["mqttHost"] = "broker.local"

        assertEquals(nonDefault(), MediaSettingsRecovery.recover(raw))
    }

    // ----------------------------------------------------------- cross field

    @Test
    fun `a band sum violation derives the queue band from the other two`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        // Valid on its own, but 60 + 10 + 40 = 110.
        raw[Keys.STRIP_PERCENT] = 40

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(60, recovered.mediaSplitPercent)
        assertEquals(10, recovered.headerBandPercent)
        assertEquals(30, recovered.stripHeightPercent)
        // Nothing outside the trio moves.
        assertEquals(expected.copy(stripHeightPercent = 30), recovered)
    }

    @Test
    fun `an unrepairable band trio resets only the three band fields`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        raw[Keys.SPLIT_PERCENT] = 85
        raw[Keys.HEADER_PERCENT] = 15
        // 100 - 85 - 15 = 0 is below the 18 minimum, so the trio resets.
        raw[Keys.STRIP_PERCENT] = 22

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(MediaSettings().mediaSplitPercent, recovered.mediaSplitPercent)
        assertEquals(MediaSettings().headerBandPercent, recovered.headerBandPercent)
        assertEquals(MediaSettings().stripHeightPercent, recovered.stripHeightPercent)
        assertEquals(expected.copy(mediaSplitPercent = 71, headerBandPercent = 7, stripHeightPercent = 22), recovered)
    }

    @Test
    fun `an inverted card range is swapped and nothing else changes`() {
        val expected = nonDefault()
        val raw = rawOf(expected)
        raw[Keys.HISTORY_CARD_MIN_PX] = 280
        raw[Keys.HISTORY_CARD_MAX_PX] = 200

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(200, recovered.historyCardMinPx)
        assertEquals(280, recovered.historyCardMaxPx)
        assertEquals(expected.copy(historyCardMinPx = 200, historyCardMaxPx = 280), recovered)
    }

    // ------------------------------------------------------------------ url

    @Test
    fun `a blank qms url resolves to the home url`() {
        val raw = rawOf(nonDefault())
        raw[Keys.QMS_URL] = ""

        val recovered = MediaSettingsRecovery.recover(raw, homeUrl = "https://dashboard.example")

        assertEquals("https://dashboard.example", recovered.qmsUrl)
    }

    @Test
    fun `a configured qms url wins over the home url`() {
        val recovered = MediaSettingsRecovery.recover(
            rawOf(nonDefault()),
            homeUrl = "https://dashboard.example"
        )
        assertEquals("https://qms.example/board", recovered.qmsUrl)
    }

    @Test
    fun `a blank qms url stays blank when no home url is substituted`() {
        val raw = rawOf(nonDefault())
        raw[Keys.QMS_URL] = ""
        // This is the view used to merge and persist a settings update: the
        // Home URL substitution must not be written back.
        assertEquals("", MediaSettingsRecovery.recover(raw).qmsUrl)
    }

    @Test
    fun `recovery never writes the substituted url back into the blob`() {
        val raw = rawOf(nonDefault())
        raw[Keys.QMS_URL] = ""
        MediaSettingsRecovery.recover(raw, homeUrl = "https://dashboard.example")
        // The stored value stays blank, so it keeps tracking the Home URL.
        assertEquals("", raw[Keys.QMS_URL])
    }

    // ------------------------------------------------- persisted web source

    @Test
    fun `the web settings recover when stored`() {
        val raw = rawOf(nonDefault()).apply {
            put(Keys.WEB_URL, "https://jellyfin.example/web?token=x")
            put(Keys.WEB_ENABLED, true)
            put(Keys.SOURCE_DEFAULT, "web")
        }

        val recovered = MediaSettingsRecovery.recover(raw)

        // Stored in full: only the published state masks it.
        assertEquals("https://jellyfin.example/web?token=x", recovered.mediaWebUrl)
        assertTrue(recovered.mediaWebEnabled)
        assertEquals(MediaSourceDefault.WEB, recovered.mediaSourceDefault)
    }

    @Test
    fun `each web field falls back independently`() {
        val raw = rawOf(nonDefault()).apply {
            put(Keys.WEB_URL, 42)            // wrong type
            put(Keys.WEB_ENABLED, "yes")     // wrong type
            put(Keys.SOURCE_DEFAULT, "dvd")  // unknown wire
        }

        val recovered = MediaSettingsRecovery.recover(raw)

        val d = MediaSettings()
        assertEquals(d.mediaWebUrl, recovered.mediaWebUrl)
        assertEquals(d.mediaWebEnabled, recovered.mediaWebEnabled)
        assertEquals(d.mediaSourceDefault, recovered.mediaSourceDefault)
        // Nothing else was touched.
        assertEquals(nonDefault().volumePercent, recovered.volumePercent)
        assertTrue(recovered.enabled)
    }

    @Test
    fun `a stored web default without a url falls back to local only`() {
        val expected = nonDefault()
        val raw = rawOf(expected).apply {
            put(Keys.WEB_URL, "")
            put(Keys.SOURCE_DEFAULT, "web")
        }

        val recovered = MediaSettingsRecovery.recover(raw)

        assertEquals(MediaSourceDefault.LOCAL, recovered.mediaSourceDefault)
        // Only the source-selector field is repaired; the URL is blank because
        // that is what was stored, and nothing else moves.
        assertEquals(
            expected.copy(
                mediaWebUrl = "",
                mediaSourceDefault = MediaSourceDefault.LOCAL
            ),
            recovered
        )
    }
}
