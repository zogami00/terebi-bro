package com.terebibro.tv.net

/**
 * IPv4 subnet membership. Pure-JVM so it is directly unit-testable and so the
 * server can reject peers without touching Android networking APIs.
 */
object SubnetMatcher {

    /**
     * True when [peerIp] and [localIp] share the first [prefixLength] bits of
     * their IPv4 addresses. Invalid input, a non-positive prefix or an
     * out-of-range prefix all yield false.
     */
    fun isSameSubnet(peerIp: String?, localIp: String?, prefixLength: Int): Boolean {
        if (peerIp == null || localIp == null) return false
        if (prefixLength <= 0 || prefixLength > 32) return false

        val peer = parseIpv4(peerIp) ?: return false
        val local = parseIpv4(localIp) ?: return false

        if (prefixLength == 32) return peer == local

        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return (peer and mask) == (local and mask)
    }

    /** Parses dotted-quad IPv4 into an unsigned 32-bit value, or null. */
    fun parseIpv4(value: String): Long? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet < 0 || octet > 255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    fun isLoopback(ip: String?): Boolean {
        val value = ip?.let { parseIpv4(it) } ?: return false
        return (value and 0xFF000000L) == 0x7F000000L
    }
}
