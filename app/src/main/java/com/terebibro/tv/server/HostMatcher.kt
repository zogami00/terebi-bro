package com.terebibro.tv.server

/**
 * Pure-JVM Host/Origin matching. Kept separate from [AuthManager] so the
 * normalization can be unit tested without the Android framework.
 */
object HostMatcher {

    fun isHostAllowed(host: String?, allowedHosts: List<String>): Boolean {
        if (host.isNullOrEmpty()) return false
        val normalized = host.trim().lowercase()
        return allowedHosts.any { it == normalized }
    }

    fun isOriginAllowed(origin: String?, allowedHosts: List<String>): Boolean {
        if (origin.isNullOrEmpty()) return false
        val normalized = origin.trim().lowercase()
        return allowedHosts.any { normalized == "http://$it" }
    }
}
