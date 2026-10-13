package com.terebibro.tv.net

/**
 * Pure-JVM IPv4 CIDR helpers for the "extra trusted client networks" setting.
 *
 * Framework-free on purpose: the server evaluates the setting without touching
 * Android networking APIs and the parser is directly unit-testable. Address
 * parsing reuses [SubnetMatcher.parseIpv4].
 *
 * Only **non-globally-routable** networks are accepted: RFC1918 (`10/8`,
 * `172.16/12`, `192.168/16`), CGNAT (`100.64/10`) and link-local (`169.254/16`).
 * Anything else is refused with [NOT_ALLOWED_REASON], so a public /8 such as
 * `1.0.0.0/8` can never widen the control surface to the internet. A prefix
 * broader than [/8][MIN_PREFIX] is also refused, and so is non-canonical input:
 * host bits set (`10.10.1.10/24`) is a validation error naming the canonical
 * form rather than a silent widening to `10.10.1.0/24`.
 */
object ClientNetworks {

    /**
     * The broadest prefix that may be trusted. A /7 or wider would cover a huge
     * slice of the public internet, so it is refused rather than accepted as a
     * foot-gun; callers report it as a validation error.
     */
    const val MIN_PREFIX = 8

    /** Reason reported for a well-formed network that is not private/CGNAT/link-local. */
    const val NOT_ALLOWED_REASON = "only private, CGNAT or link-local ranges are allowed"

    /** The narrowest single-host prefix. */
    const val MAX_PREFIX = 32

    /** Upper bound on stored entries, so a list cannot grow without limit. */
    const val MAX_ENTRIES = 16

    /** Prefix suggested by [containingNetworkFor] for a blocked client. */
    const val SUGGEST_PREFIX = 24

    /** A parsed canonical network: the network address and prefix. */
    data class Cidr(val network: Long, val prefix: Int) {
        /** The canonical `a.b.c.d/len` form. */
        override fun toString(): String = format(network, prefix)
    }

    /**
     * Parses a canonical `a.b.c.d/len` CIDR. Null for anything invalid,
     * out-of-bounds, not private/CGNAT/link-local, or with host bits set.
     */
    fun parseCidr(value: String): Cidr? = when (val parsed = parseDetailed(value)) {
        is Parsed.Ok -> parsed.cidr
        is Parsed.Bad -> null
    }

    /**
     * Returns the canonical `a.b.c.d/len` form of [value], or null when it is
     * not a valid, in-bounds, non-globally-routable CIDR. Non-canonical input
     * (`10.10.1.10/24`) is refused rather than silently widened.
     */
    fun normalise(value: String): String? = parseCidr(value)?.toString()

    /** True when [ip] falls inside [network]; false when either argument is invalid. */
    fun contains(network: String, ip: String): Boolean {
        val cidr = parseCidr(network) ?: return false
        val address = SubnetMatcher.parseIpv4(ip) ?: return false
        return (address and mask(cidr.prefix)) == cidr.network
    }

    /**
     * The canonical network of width [prefix] that [ip] falls in, as
     * `a.b.c.d/prefix` — used to suggest an entry for a blocked client without
     * making the user type a CIDR on a TV remote. Null for an invalid address, an
     * out-of-bounds prefix, or a suggestion that is not a private/CGNAT/link-local
     * range (so a public peer never yields an un-addable button).
     */
    fun containingNetworkFor(ip: String, prefix: Int = SUGGEST_PREFIX): String? {
        if (prefix < MIN_PREFIX || prefix > MAX_PREFIX) return null
        val address = SubnetMatcher.parseIpv4(ip) ?: return null
        val network = address and mask(prefix)
        if (!isAllowedRange(network, prefix)) return null
        return format(network, prefix)
    }

    /**
     * Validates [values] and returns the accepted, de-duplicated list (in
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
        val network = ip and mask(prefix)
        if (network != ip) {
            return Parsed.Bad("host bits set; did you mean ${format(network, prefix)}?")
        }
        if (!isAllowedRange(network, prefix)) return Parsed.Bad(NOT_ALLOWED_REASON)
        return Parsed.Ok(Cidr(network, prefix))
    }

    /** A canonical range that may be trusted. */
    private data class Range(val network: Long, val prefix: Int)

    /**
     * The non-globally-routable ranges that may be trusted: RFC1918, CGNAT and
     * link-local. A candidate is admissible only when it is fully contained in
     * one of these (its prefix is at least as specific and its masked network
     * matches).
     */
    private val ALLOWED_RANGES = listOf(
        Range(SubnetMatcher.parseIpv4("10.0.0.0")!!, 8),
        Range(SubnetMatcher.parseIpv4("172.16.0.0")!!, 12),
        Range(SubnetMatcher.parseIpv4("192.168.0.0")!!, 16),
        Range(SubnetMatcher.parseIpv4("100.64.0.0")!!, 10),
        Range(SubnetMatcher.parseIpv4("169.254.0.0")!!, 16)
    )

    private fun isAllowedRange(network: Long, prefix: Int): Boolean =
        ALLOWED_RANGES.any { prefix >= it.prefix && (network and mask(it.prefix)) == it.network }

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
