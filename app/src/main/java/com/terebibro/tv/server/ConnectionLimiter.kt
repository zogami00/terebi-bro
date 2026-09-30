package com.terebibro.tv.server

/**
 * Per-peer concurrent connection accounting, kept out of [BoundedAsyncRunner]
 * so the cap rule is directly unit-testable (pure JVM, no Android framework).
 *
 * The global cap alone is exhaustible by a single LAN peer holding many idle
 * sockets, which would lock every legitimate controller out; this adds a
 * smaller cap keyed by the accepted socket's IP address.
 */
class ConnectionLimiter(private val maxPerPeer: Int) {

    private val counts = HashMap<String, Int>()

    /**
     * Reserves a slot for [peer].
     *
     * @return true when the peer is under its cap; false when it must be
     * rejected. A null/empty peer (no socket address) is always allowed here
     * and only governed by the global cap.
     */
    @Synchronized
    fun acquire(peer: String?): Boolean {
        if (peer.isNullOrEmpty()) return true
        val current = counts[peer] ?: 0
        if (current >= maxPerPeer) return false
        counts[peer] = current + 1
        return true
    }

    /** Releases a previously reserved slot; an unknown peer is ignored. */
    @Synchronized
    fun release(peer: String?) {
        if (peer.isNullOrEmpty()) return
        val current = counts[peer] ?: return
        if (current <= 1) counts.remove(peer) else counts[peer] = current - 1
    }

    /** Current reserved slots for [peer]; for tests and diagnostics. */
    @Synchronized
    fun countFor(peer: String): Int = counts[peer] ?: 0
}
