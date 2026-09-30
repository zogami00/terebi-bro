package com.terebibro.tv.security

/** A paired controller. Only the SHA-256 hash of the token is ever stored. */
data class PairedToken(
    val id: String,
    val sha256Hex: String,
    val clientName: String,
    val createdAt: Long,
    val lastSeen: Long
)

/** Persistence boundary for [TokenRegistry]; the Android side uses SharedPreferences. */
interface TokenStorage {
    fun load(): List<PairedToken>

    /** @return false when the write did not reach durable storage. */
    fun save(tokens: List<PairedToken>): Boolean

    /**
     * Overwrites the stored blob even when it could not be parsed, so an
     * explicit TV-side "revoke all" can recover from a corrupt blob instead of
     * being refused forever. Defaults to [save].
     */
    fun forceSave(tokens: List<PairedToken>): Boolean = save(tokens)
}

/**
 * In-memory registry of paired controllers, backed by [TokenStorage].
 *
 * The list is cached so that the high-frequency `touch` on every authenticated
 * request does not perform a JSON load and a synchronous flash commit per call.
 * Mutations (`add`/`revokeByHash`/`revokeAll`) persist immediately; `lastSeen`
 * updates are flushed at most once a minute. Pure-JVM so it is directly
 * unit-testable.
 */
class TokenRegistry(
    private val storage: TokenStorage,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()
    private var cache: MutableList<PairedToken>? = null
    private var lastFlushAt = 0L

    private fun loaded(): MutableList<PairedToken> =
        cache ?: storage.load().toMutableList().also { cache = it }

    fun all(): List<PairedToken> = synchronized(lock) { loaded().toList() }

    fun count(): Int = synchronized(lock) { loaded().size }

    /** @return false when the token could not be persisted; the cache is rolled back. */
    fun add(token: PairedToken): Boolean = synchronized(lock) {
        val list = loaded()
        list.add(token)
        val ok = storage.save(list.toList())
        if (!ok) list.removeAll { it.id == token.id }
        ok
    }

    /** Compares every stored hash in constant time, never short-circuiting. */
    fun findByHash(hash: String): PairedToken? = synchronized(lock) {
        var match: PairedToken? = null
        for (token in loaded()) {
            if (TokenCrypto.constantTimeEquals(token.sha256Hex, hash)) match = token
        }
        match
    }

    /** Updates `lastSeen` in memory; persistence is throttled to once a minute. */
    fun touch(id: String, timestamp: Long) = synchronized(lock) {
        val list = loaded()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = list[index].copy(lastSeen = timestamp)
        val nowMs = now()
        if (nowMs - lastFlushAt >= FLUSH_INTERVAL_MS) {
            lastFlushAt = nowMs
            storage.save(list.toList())
        }
    }

    /**
     * Revokes [hash] in memory and in storage.
     *
     * @return false when the revocation could not be persisted. The in-memory
     * revocation still applies (revocation must never silently fail), but the
     * caller is told the write did not land.
     */
    fun revokeByHash(hash: String): Boolean = synchronized(lock) {
        val remaining = loaded().filterNot { TokenCrypto.constantTimeEquals(it.sha256Hex, hash) }
        cache = remaining.toMutableList()
        storage.save(remaining)
    }

    /**
     * Revokes every token, force-overwriting a blob that could not be parsed so
     * this is always a way out of a corrupt token store.
     *
     * @return false when the revocation could not be persisted.
     */
    fun revokeAll(): Boolean = synchronized(lock) {
        cache = mutableListOf()
        storage.forceSave(emptyList())
    }

    companion object {
        const val FLUSH_INTERVAL_MS = 60_000L
    }
}
