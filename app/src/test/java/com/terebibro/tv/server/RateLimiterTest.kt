package com.terebibro.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `allows up to the burst then rejects`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, now = { now })
        repeat(5) { assertTrue(limiter.allow("k")) }
        assertFalse(limiter.allow("k"))
    }

    @Test
    fun `refills over time`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 10.0, burst = 5, now = { now })
        repeat(5) { limiter.allow("k") }
        assertFalse(limiter.allow("k"))
        now += 100 // 10 tokens/s -> one token
        assertTrue(limiter.allow("k"))
    }

    @Test
    fun `keys are independent`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 1.0, burst = 1, now = { now })
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
    }

    @Test
    fun `idle buckets are pruned`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 1.0, burst = 2, now = { now })
        assertTrue(limiter.allow("a"))
        // Move past the sweep interval; "a" is idle longer than the idle window.
        now += RateLimiter.PRUNE_INTERVAL_MS + 1
        assertTrue(limiter.allow("b"))
        // "a" was evicted and starts from a full burst again.
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
    }
}
