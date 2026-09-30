package com.terebibro.tv.server

import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.net.LocalNetwork
import com.terebibro.tv.net.SubnetMatcher
import com.terebibro.tv.security.PairedToken
import com.terebibro.tv.security.PinManager
import com.terebibro.tv.security.TokenCrypto
import com.terebibro.tv.security.TokenRegistry
import java.util.UUID

/**
 * Pairing PIN, controller tokens, subnet/host/origin checks and rate limiting.
 *
 * The peer address is always taken from the socket session
 * ([NanoHTTPD.IHTTPSession.getRemoteIpAddress], which is derived from the
 * accepted socket's InetAddress) and never from a `remote-addr` /
 * `X-Forwarded-For` style header.
 */
class AuthManager(
    private val config: ConfigStore,
    private val localNetwork: LocalNetwork,
    val pinManager: PinManager = PinManager(),
    /**
     * The address/port the control server is actually bound to, or null before
     * it is listening. The bound port is authoritative: a fallback bind must
     * never leave the Host allowlist pointing at the configured port.
     */
    private val boundAddress: () -> Pair<String, Int>? = { null }
) {

    val tokens = TokenRegistry(config.tokenStorage)

    sealed class PairOutcome {
        data class Success(val token: String, val deviceName: String) : PairOutcome()
        data class Failure(
            val status: Int,
            val code: String,
            val message: String,
            val retryAfterSec: Long = 0
        ) : PairOutcome()
    }

    private val apiLimiter = RateLimiter(ratePerSecond = 20.0, burst = 20)
    private val dpadLimiter = RateLimiter(ratePerSecond = 100.0, burst = 100)

    // ---------------------------------------------------------------------
    // Host / peer / origin
    // ---------------------------------------------------------------------

    /**
     * `ip:port` and `<mdnsName>.local:port`, lowercase.
     *
     * Derived from the server's actual bound address when it is listening, so a
     * fallback bind (8765 while the configured port is 9001) does not answer
     * `403 host` to the address it advertises.
     */
    fun allowedHosts(): List<String> {
        val bound = boundAddress()
        val ip = bound?.first ?: localNetwork.current()?.ip ?: return emptyList()
        val port = bound?.second ?: config.controllerPort
        return listOf(
            "$ip:$port".lowercase(),
            "${config.mdnsName}.local:$port".lowercase()
        )
    }

    /** Loopback, own-address and off-subnet peers are rejected. */
    fun isPeerAllowed(peerIp: String?): Boolean {
        if (peerIp.isNullOrEmpty()) return false
        if (SubnetMatcher.isLoopback(peerIp)) return false
        val info = localNetwork.current() ?: return false
        if (peerIp == info.ip) return false
        return SubnetMatcher.isSameSubnet(peerIp, info.ip, info.prefixLength)
    }

    fun isHostAllowed(host: String?): Boolean = HostMatcher.isHostAllowed(host, allowedHosts())

    fun isOriginAllowed(origin: String?): Boolean = HostMatcher.isOriginAllowed(origin, allowedHosts())

    // ---------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------

    fun authenticate(bearer: String?): PairedToken? {
        if (bearer.isNullOrEmpty()) return null
        val hash = TokenCrypto.sha256Hex(bearer)
        val token = tokens.findByHash(hash) ?: return null
        tokens.touch(token.id, System.currentTimeMillis())
        return token
    }

    /** @return false when the revocation could not be persisted. */
    fun revokeAll(): Boolean = tokens.revokeAll()

    fun pairedCount(): Int = tokens.count()

    // ---------------------------------------------------------------------
    // Rate limiting
    // ---------------------------------------------------------------------

    fun rateLimit(token: PairedToken?, peerIp: String?, isDpad: Boolean): Boolean =
        rateLimit(token?.sha256Hex ?: peerIp ?: "anonymous", isDpad)

    /** Shared by REST routes and WebSocket messages. */
    fun rateLimit(key: String, isDpad: Boolean): Boolean =
        if (isDpad) dpadLimiter.allow(key) else apiLimiter.allow(key)

    // ---------------------------------------------------------------------
    // Pairing
    // ---------------------------------------------------------------------

    fun pair(pin: String, clientName: String): PairOutcome {
        // Validate and consume the PIN *first*: a rejected attempt must not
        // touch persistent storage, otherwise an unauthenticated LAN peer at
        // 20 req/s would cause a flash commit per request under the registry
        // lock. The PIN manager also enforces the lockout here.
        val result = pinManager.submit(pin)
        if (result !is PinManager.Result.Success) {
            return when (result) {
                is PinManager.Result.BadPin -> PairOutcome.Failure(401, "bad_pin", "Incorrect PIN")
                is PinManager.Result.Expired -> PairOutcome.Failure(410, "pin_expired", "PIN expired")
                is PinManager.Result.Locked ->
                    PairOutcome.Failure(429, "locked", "Too many attempts", result.retryAfterSec)
                else -> PairOutcome.Failure(500, "pair_failed", "Pairing failed")
            }
        }

        // The PIN was accepted; only now persist the token.
        val token = TokenCrypto.generateToken()
        val hash = TokenCrypto.sha256Hex(token)
        val now = System.currentTimeMillis()
        val record = PairedToken(
            id = UUID.randomUUID().toString(),
            sha256Hex = hash,
            clientName = clientName,
            createdAt = now,
            lastSeen = now
        )
        if (!tokens.add(record)) {
            // The PIN is consumed but no usable token exists: issue a fresh PIN
            // so pairing is not silently dead for the user.
            pinManager.issuePin()
            return PairOutcome.Failure(503, "storage_unavailable", "Could not persist pairing")
        }
        return PairOutcome.Success(token, config.deviceName)
    }

    fun issuePin(): String = pinManager.issuePin()

    fun currentPin(): String? = pinManager.currentPin()

    fun clearPin() = pinManager.clearPin()
}

/** Simple token-bucket limiter keyed by token hash or peer address. */
class RateLimiter(
    private val ratePerSecond: Double,
    private val burst: Int,
    private val now: () -> Long = System::currentTimeMillis
) {

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = HashMap<String, Bucket>()
    private var lastPruneMs = 0L

    @Synchronized
    fun allow(key: String): Boolean {
        val current = now()
        if (current - lastPruneMs >= PRUNE_INTERVAL_MS) {
            val iterator = buckets.entries.iterator()
            while (iterator.hasNext()) {
                if (current - iterator.next().value.lastRefillMs > IDLE_PRUNE_MS) iterator.remove()
            }
            lastPruneMs = current
        }
        val bucket = buckets.getOrPut(key) { Bucket(burst.toDouble(), current) }
        val elapsed = (current - bucket.lastRefillMs).coerceAtLeast(0L)
        bucket.tokens = minOf(burst.toDouble(), bucket.tokens + elapsed / 1000.0 * ratePerSecond)
        bucket.lastRefillMs = current
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    companion object {
        /** How often to sweep idle buckets. */
        const val PRUNE_INTERVAL_MS = 60_000L

        /** Buckets untouched for this long are evicted. */
        const val IDLE_PRUNE_MS = 60_000L
    }
}
