package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignageStringsTest {

    private val documentedMalay = mapOf(
        SignageStrings.Key.CURRENT to "SEDANG DIPANGGIL",
        SignageStrings.Key.HISTORY to "PANGGILAN TERDAHULU",
        SignageStrings.Key.NO_CALL to "TIADA PANGGILAN",
        SignageStrings.Key.QMS_UNAVAILABLE to "PAPAN GILIRAN TIDAK TERSEDIA",
        SignageStrings.Key.MEDIA_UNAVAILABLE to "MEDIA TIDAK TERSEDIA"
    )

    @Test
    fun `every key is present and non-blank in every language`() {
        for (language in UiLanguage.entries) {
            for (key in SignageStrings.Key.entries) {
                val value = SignageStrings.label(language, key)
                assertTrue("$language/$key is blank", value.isNotBlank())
            }
        }
    }

    @Test
    fun `malay returns the documented strings`() {
        for ((key, expected) in documentedMalay) {
            assertEquals(expected, SignageStrings.label(UiLanguage.MS, key))
        }
    }

    @Test
    fun `the untranslated languages fall back to malay until M3`() {
        for (language in listOf(UiLanguage.EN, UiLanguage.ZH, UiLanguage.TA)) {
            for ((key, expected) in documentedMalay) {
                assertEquals(expected, SignageStrings.label(language, key))
            }
        }
    }

    @Test
    fun `the dictionary covers exactly the enum`() {
        assertEquals(SignageStrings.Key.entries.toSet(), documentedMalay.keys)
    }
}
