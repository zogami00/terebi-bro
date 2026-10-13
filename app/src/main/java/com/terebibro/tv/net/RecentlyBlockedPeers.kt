package com.terebibro.tv.net

/**
 * A small, bounded, in-memory list of peers most recently rejected by the
 * subnet policy.
 *
 * This exists purely to bootstrap the "extra trusted networks" setting: a user
 * whose PC is on a different subnet cannot reach the controller to configure it,
 * so the TV overlay lists the address that was refused and offers a one-press
 * allow. The list is **never persisted** and **never logged** (peer addresses
 * are exactly the kind of detail `SafeLog` rules forbid): the on-screen TV list
 * is the only place an address is shown.
 *
 * Entries are de-duplicated by IP and the oldest is evicted past [maxEntries].
 */
class RecentlyBlockedPeers(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis
) {

    data class Blocked(val ip: String, val blockedAt: Long)

    private val lock = Any()

    /** Insertion order: the most recently blocked address is the last key. */
    private val entries = LinkedHashMap<String, Blocked>()

    fun record(ip: String) {
        if (ip.isEmpty()) return
        synchronized(lock) {
            // Re-inserting moves the address to the most-recent end of the map.
            entries.remove(ip)
            entries[ip] = Blocked(ip, now())
            while (entries.size > maxEntries) {
                val iterator = entries.keys.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    /** Most recently blocked first. */
    fun snapshot(): List<Blocked> = synchronized(lock) { entries.values.toList().asReversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5
    }
}
