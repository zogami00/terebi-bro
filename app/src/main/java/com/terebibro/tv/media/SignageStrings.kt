package com.terebibro.tv.media

/**
 * The patient-facing signage labels. Only our own five strings live here; the
 * vendor's text is never copied or rewritten.
 *
 * M2 ships Malay, the default. The other three languages are filled in M3 and
 * deliberately return Malay until then, so a missing translation is a visible
 * fallback rather than a blank board.
 */
object SignageStrings {

    enum class Key {
        CURRENT,
        HISTORY,
        NO_CALL,
        QMS_UNAVAILABLE,
        MEDIA_UNAVAILABLE
    }

    private val MALAY = mapOf(
        Key.CURRENT to "SEDANG DIPANGGIL",
        Key.HISTORY to "PANGGILAN TERDAHULU",
        Key.NO_CALL to "TIADA PANGGILAN",
        Key.QMS_UNAVAILABLE to "PAPAN GILIRAN TIDAK TERSEDIA",
        Key.MEDIA_UNAVAILABLE to "MEDIA TIDAK TERSEDIA"
    )

    fun label(language: UiLanguage, key: Key): String = when (language) {
        UiLanguage.MS, UiLanguage.EN, UiLanguage.ZH, UiLanguage.TA -> MALAY.getValue(key)
    }
}
