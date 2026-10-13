package com.terebibro.tv.net

/**
 * Pure-JVM IPv4 CIDR helpers for the "extra trusted client networks" setting.
 *
 * Framework-free on purpose: the server evaluates the setting without touching
 * Android networking APIs and the parser is directly unit-testable. Address
 * parsing reuses [SubnetMatcher.parseIpv4].
 *
 * A host address with host bits set is *not* an error: `10.10.1.10/24` is
 * normalised to the network it names, `10.10.1.0/24`. A prefix broader than
 * [/8][MIN_PREFIX] is refused (it would effectively disable the control), as is
 * anything that is not a dotted-quad IPv4 address with a `/len` suffix.
 */
object ClientNetworks {

    /**
     * The broadest prefix that may be trusted. A /7 or wider would cover a huge
     * slice of the public internet, so it is refused rather than accepted as a
     * foot-gun; callers report it as a validation error.
     */
    const val MIN_PREFIX = 8

    /** The narrowest single-host prefix. */
    const val MAX_PREFIX = 32

    /** Upper bound on stored entries, so a list cannot grow without limit. */
    const val MAX_ENTRIES = 16

    /** Prefix suggested by [containingNetworkFor] for a blocked client. */
    const val SUGGEST_PREFIX = 24

    /** A parsed, host-bit-normalised network: the masked network address and prefix. */
    data class Cidr(val network: Long, val prefix: Int) {
        /** The normalised `a.b.c.d/len` form. */
        override fun toString(): String = format(network, prefix)
    }

    /** Parses `a.b.c.d/len`, masking host bits. Null for anything invalid. */
    fun parseCidr(value: String): Cidr? = when (val parsed = parseDetailed(value)) {
        is Parsed.Ok -> parsed.cidr
        is Parsed.Bad -> null
    }

    /**
     * Masks [value] to its prefix and returns `a.b.c.d/len`, or null when it is
     * not a valid, in-bounds CIDR. `10.10.1.10/24` becomes `10.10.1.0/24`.
     */
    fun normalise(value: String): String? = parseCidr(value)?.toString()

    /** True when [ip] falls inside [network]; false when either argument is invalid. */
    fun contains(network: String, ip: String): Boolean {
        val cidr = parseCidr(network) ?: return false
        val address = SubnetMatcher.parseIpv4(ip) ?: return false
        return (address and mask(cidr.prefix)) == cidr.network
    }

    /**
     * The masked network of width [prefix] that [ip] falls in, as
     * `a.b.c.d/prefix` — used to suggest an entry for a blocked client without
     * making the user type a CIDR on a TV remote. Null for an invalid address or
     * an out-of-bounds prefix.
     */
    fun containingNetworkFor(ip: String, prefix: Int = SUGGEST_PREFIX): String? {
        if (prefix < MIN_PREFIX || prefix > MAX_PREFIX) return null
        val address = SubnetMatcher.parseIpv4(ip) ?: return null
        return format(address and mask(prefix), prefix)
    }

    /**
     * Normalises [values] and returns the accepted, de-duplicated list (in
     * input order, capped at [MAX_ENTRIES]) paired with a human-readable reason
     * for every entry that was refused. Duplicates of an already-accepted entry
     * are collapsed silently; invalid entries and entries past the cap are
     * reported so callers can return a precise `400` rather than dropping them.
     */
    fun validateList(values: List<String>): Pair<List<String>, List<String>> {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val seen = HashSet<String>()
        for (raw in values) {
            when (val parsed = parseDetailed(raw)) {
                is Parsed.Bad -> {
                    val label = raw.trim().ifBlank { "(empty)" }
                    rejected.add("$label: ${parsed.reason}")
                }
                is Parsed.Ok -> {
                    val text = parsed.cidr.toString()
                    if (!seen.add(text)) continue
                    if (accepted.size >= MAX_ENTRIES) {
                        rejected.add("$text: at most $MAX_ENTRIES networks are allowed")
                    } else {
                        accepted.add(text)
                    }
                }
            }
        }
        return accepted to rejected
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private sealed class Parsed {
        data class Ok(val cidr: Cidr) : Parsed()
        data class Bad(val reason: String) : Parsed()
    }

    private fun parseDetailed(value: String): Parsed {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return Parsed.Bad("empty entry")
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash != trimmed.lastIndexOf('/')) {
            return Parsed.Bad("not a valid IPv4 CIDR (expected a.b.c.d/len)")
        }
        val address = trimmed.substring(0, slash)
        val prefixText = trimmed.substring(slash + 1)
        val ip = SubnetMatcher.parseIpv4(address) ?: return Parsed.Bad("not a valid IPv4 address")
        if (prefixText.isEmpty() || prefixText.any { it !in '0'..'9' }) {
            return Parsed.Bad("not a valid prefix length")
        }
        val prefix = prefixText.toIntOrNull() ?: return Parsed.Bad("not a valid prefix length")
        if (prefix < MIN_PREFIX || prefix > MAX_PREFIX) {
            return Parsed.Bad("prefix must be between /$MIN_PREFIX and /$MAX_PREFIX")
        }
        return Parsed.Ok(Cidr(ip and mask(prefix), prefix))
    }

    private fun mask(prefix: Int): Long =
        if (prefix >= MAX_PREFIX) 0xFFFFFFFFL
        else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun format(network: Long, prefix: Int): String {
        val a = (network ushr 24) and 0xFF
        val b = (network ushr 16) and 0xFF
        val c = (network ushr 8) and 0xFF
        val d = network and 0xFF
        return "$a.$b.$c.$d/$prefix"
    }
}
