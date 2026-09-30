package com.terebibro.tv.config

/**
 * Pure-JVM mDNS label sanitiser. Device names are user supplied ("Living Room
 * TV"), but a DNS label may only contain `[a-z0-9-]` and be at most 63 chars,
 * otherwise jmDNS registration fails and no browser can send a matching Host.
 */
object MdnsName {

    const val MAX_LABEL = 63
    const val FALLBACK = "terebi-tv"

    fun sanitize(raw: String, fallback: String = FALLBACK): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            sb.append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-') ch else '-')
        }
        var name = sb.toString().trim('-')
        if (name.length > MAX_LABEL) name = name.substring(0, MAX_LABEL).trim('-')
        return name.ifEmpty { fallback.ifEmpty { FALLBACK } }
    }
}
