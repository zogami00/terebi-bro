package com.terebibro.tv.media

/**
 * The patient-facing signage labels. Only our own five strings live here; the
 * vendor's text is never copied or rewritten.
 *
 * Four languages ship: Malay (the default), English, Simplified Chinese and
 * Tamil. The English, Chinese and Tamil values are **machine translations** and
 * are intended to be reviewed by a native speaker before they are relied on in
 * a waiting room; the Malay column is the original, human-authored copy.
 *
 * Every language must carry every [Key]: [label] never falls back, so a missing
 * entry is a blank board rather than a silent language switch, and
 * `SignageStringsTest` fails the build if one is missing.
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

    private val ENGLISH = mapOf(
        Key.CURRENT to "NOW CALLING",
        Key.HISTORY to "PREVIOUS CALLS",
        Key.NO_CALL to "NO CALLS YET",
        Key.QMS_UNAVAILABLE to "QUEUE BOARD UNAVAILABLE",
        Key.MEDIA_UNAVAILABLE to "MEDIA UNAVAILABLE"
    )

    private val CHINESE = mapOf(
        Key.CURRENT to "正在呼叫",
        Key.HISTORY to "之前的呼叫",
        Key.NO_CALL to "暂无呼叫",
        Key.QMS_UNAVAILABLE to "叫号板不可用",
        Key.MEDIA_UNAVAILABLE to "媒体不可用"
    )

    private val TAMIL = mapOf(
        Key.CURRENT to "அழைக்கப்படுகிறது",
        Key.HISTORY to "முந்தைய அழைப்புகள்",
        Key.NO_CALL to "அழைப்புகள் இல்லை",
        Key.QMS_UNAVAILABLE to "வரிசை பலகை கிடைக்கவில்லை",
        Key.MEDIA_UNAVAILABLE to "மீடியா கிடைக்கவில்லை"
    )

    /** The dictionary for one language; the test asserts each covers [Key]. */
    internal fun dictionary(language: UiLanguage): Map<Key, String> = when (language) {
        UiLanguage.MS -> MALAY
        UiLanguage.EN -> ENGLISH
        UiLanguage.ZH -> CHINESE
        UiLanguage.TA -> TAMIL
    }

    fun label(language: UiLanguage, key: Key): String = dictionary(language).getValue(key)
}
