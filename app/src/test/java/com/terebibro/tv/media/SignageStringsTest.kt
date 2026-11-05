package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignageStringsTest {

    private val documented = mapOf(
        UiLanguage.MS to mapOf(
            SignageStrings.Key.CURRENT to "SEDANG DIPANGGIL",
            SignageStrings.Key.HISTORY to "PANGGILAN TERDAHULU",
            SignageStrings.Key.NO_CALL to "TIADA PANGGILAN",
            SignageStrings.Key.QMS_UNAVAILABLE to "PAPAN GILIRAN TIDAK TERSEDIA",
            SignageStrings.Key.MEDIA_UNAVAILABLE to "MEDIA TIDAK TERSEDIA"
        ),
        UiLanguage.EN to mapOf(
            SignageStrings.Key.CURRENT to "NOW CALLING",
            SignageStrings.Key.HISTORY to "PREVIOUS CALLS",
            SignageStrings.Key.NO_CALL to "NO CALLS YET",
            SignageStrings.Key.QMS_UNAVAILABLE to "QUEUE BOARD UNAVAILABLE",
            SignageStrings.Key.MEDIA_UNAVAILABLE to "MEDIA UNAVAILABLE"
        ),
        UiLanguage.ZH to mapOf(
            SignageStrings.Key.CURRENT to "正在呼叫",
            SignageStrings.Key.HISTORY to "之前的呼叫",
            SignageStrings.Key.NO_CALL to "暂无呼叫",
            SignageStrings.Key.QMS_UNAVAILABLE to "叫号板不可用",
            SignageStrings.Key.MEDIA_UNAVAILABLE to "媒体不可用"
        ),
        UiLanguage.TA to mapOf(
            SignageStrings.Key.CURRENT to "அழைக்கப்படுகிறது",
            SignageStrings.Key.HISTORY to "முந்தைய அழைப்புகள்",
            SignageStrings.Key.NO_CALL to "அழைப்புகள் இல்லை",
            SignageStrings.Key.QMS_UNAVAILABLE to "வரிசை பலகை கிடைக்கவில்லை",
            SignageStrings.Key.MEDIA_UNAVAILABLE to "மீடியா கிடைக்கவில்லை"
        )
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
    fun `every shipped language covers exactly the enum`() {
        assertEquals(UiLanguage.entries.toSet(), documented.keys)
        for (language in UiLanguage.entries) {
            assertEquals(
                "$language is missing or has extra keys",
                SignageStrings.Key.entries.toSet(),
                SignageStrings.dictionary(language).keys
            )
        }
    }

    @Test
    fun `every language returns its documented strings`() {
        for (language in UiLanguage.entries) {
            val expected = documented.getValue(language)
            for ((key, text) in expected) {
                assertEquals("$language/$key", text, SignageStrings.label(language, key))
            }
        }
    }

    @Test
    fun `no language silently reuses another language`() {
        for (key in SignageStrings.Key.entries) {
            val values = UiLanguage.entries.map { SignageStrings.label(it, key) }
            assertEquals("$key has a duplicate across languages", values.size, values.toSet().size)
        }
        assertNotEquals(
            SignageStrings.label(UiLanguage.EN, SignageStrings.Key.CURRENT),
            SignageStrings.label(UiLanguage.MS, SignageStrings.Key.CURRENT)
        )
    }
}
