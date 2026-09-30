package com.terebibro.tv.security

import java.security.SecureRandom

/**
 * Pairing PIN lifecycle. Pure-JVM: no Android framework classes, so the
 * brute-force protection is directly unit-testable.
 *
 * Rules:
 *  - 6 decimal digits from [SecureRandom]
 *  - valid for [ttlMs] (120 s) and single use
 *  - failed attempts are counted globally
 *  - after [maxFailures] failures the PIN is invalidated and pairing locked for
 *    [baseLockMs], doubling each subsequent lockout up to [maxLockMs]
 *  - a fresh PIN must be issued after a lockout
 */
class PinManager(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val baseLockMs: Long = DEFAULT_BASE_LOCK_MS,
    private val maxLockMs: Long = DEFAULT_MAX_LOCK_MS,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    sealed class Result {
        object Success : Result()
        object BadPin : Result()
        object Expired : Result()
        data class Locked(val retryAfterSec: Long) : Result()
    }

    private var pin: String? = null
    private var issuedAt: Long = 0L
    private var failures: Int = 0
    private var lockLevel: Int = 0
    private var lockedUntil: Long = 0L

    /** Generates and returns a fresh PIN, clearing the failure counter. */
    @Synchronized
    fun issuePin(): String {
        val fresh = generatePin()
        pin = fresh
        issuedAt = now()
        failures = 0
        return fresh
    }

    /** The current PIN if one is set and not expired, otherwise null. */
    @Synchronized
    fun currentPin(): String? {
        val current = pin ?: return null
        if (now() - issuedAt > ttlMs) {
            pin = null
            return null
        }
        return current
    }

    /** Invalidates the PIN (pairing screen closed) without touching lockout state. */
    @Synchronized
    fun clearPin() {
        pin = null
        failures = 0
    }

    @Synchronized
    fun isLocked(): Boolean = now() < lockedUntil

    @Synchronized
    fun retryAfterSec(): Long {
        val remaining = lockedUntil - now()
        if (remaining <= 0) return 0L
        return (remaining + 999L) / 1000L
    }

    @Synchronized
    fun submit(candidate: String): Result {
        if (now() < lockedUntil) return Result.Locked(retryAfterSec())

        val current = currentPin() ?: return Result.Expired

        if (TokenCrypto.constantTimeEquals(candidate, current)) {
            pin = null
            failures = 0
            lockLevel = 0
            return Result.Success
        }

        failures++
        if (failures >= maxFailures) {
            failures = 0
            lockLevel++
            lockedUntil = now() + lockDurationMs(lockLevel)
            pin = null
            return Result.Locked(retryAfterSec())
        }
        return Result.BadPin
    }

    private fun lockDurationMs(level: Int): Long {
        var duration = baseLockMs
        var i = 1
        while (i < level && duration < maxLockMs) {
            duration *= 2
            i++
        }
        return if (duration > maxLockMs) maxLockMs else duration
    }

    private fun generatePin(): String {
        val n = random.nextInt(1_000_000)
        return n.toString().padStart(6, '0')
    }

    companion object {
        const val DEFAULT_TTL_MS = 120_000L
        const val DEFAULT_BASE_LOCK_MS = 60_000L
        const val DEFAULT_MAX_LOCK_MS = 15 * 60_000L
        const val DEFAULT_MAX_FAILURES = 5
    }
}
