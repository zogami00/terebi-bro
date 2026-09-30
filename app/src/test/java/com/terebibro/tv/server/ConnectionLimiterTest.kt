package com.terebibro.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLimiterTest {

    @Test
    fun `allows up to the per-peer cap then rejects`() {
        val limiter = ConnectionLimiter(3)
        val peer = "192.168.1.50"
        repeat(3) { assertTrue(limiter.acquire(peer)) }
        assertFalse(limiter.acquire(peer))
        assertEquals(3, limiter.countFor(peer))
    }

    @Test
    fun `a rejected acquire does not consume a slot`() {
        val limiter = ConnectionLimiter(1)
        assertTrue(limiter.acquire("a"))
        assertFalse(limiter.acquire("a"))
        assertFalse(limiter.acquire("a"))
        assertEquals(1, limiter.countFor("a"))
    }

    @Test
    fun `peers have independent caps`() {
        val limiter = ConnectionLimiter(2)
        assertTrue(limiter.acquire("a"))
        assertTrue(limiter.acquire("a"))
        assertFalse(limiter.acquire("a"))
        assertTrue(limiter.acquire("b"))
        assertTrue(limiter.acquire("b"))
        assertFalse(limiter.acquire("b"))
    }

    @Test
    fun `release frees exactly one slot and is idempotent`() {
        val limiter = ConnectionLimiter(1)
        assertTrue(limiter.acquire("a"))
        assertFalse(limiter.acquire("a"))
        limiter.release("a")
        assertTrue(limiter.acquire("a"))
        limiter.release("b") // unknown peer: ignored
        assertEquals(1, limiter.countFor("a"))
        limiter.release("a")
        assertEquals(0, limiter.countFor("a"))
        limiter.release("a") // already released: still ignored
        assertEquals(0, limiter.countFor("a"))
    }

    @Test
    fun `an unknown peer is only governed by the global cap`() {
        val limiter = ConnectionLimiter(1)
        repeat(5) { assertTrue(limiter.acquire(null)) }
        repeat(5) { assertTrue(limiter.acquire("")) }
    }
}
