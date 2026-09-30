package com.terebibro.tv.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Pure-JVM tests for the PIN and token logic that [com.terebibro.tv.server.AuthManager]
 * delegates to. No Android framework classes are involved.
 */
class AuthManagerTest {

    /** In-memory stand-in for the SharedPreferences-backed storage. */
    private class InMemoryTokenStorage : TokenStorage {
        var tokens: List<PairedToken> = emptyList()
        override fun load(): List<PairedToken> = tokens
        override fun save(tokens: List<PairedToken>): Boolean {
            this.tokens = tokens
            return true
        }
    }

    /** Storage whose save() succeeds once, then can be made to fail. */
    private class FailableStorage : TokenStorage {
        var tokens: List<PairedToken> = emptyList()
        var fail = false
        override fun load(): List<PairedToken> = tokens
        override fun save(tokens: List<PairedToken>): Boolean {
            if (fail) return false
            this.tokens = tokens
            return true
        }
    }

    /** Storage whose blob is unreadable until forceSave() overwrites it. */
    private class CorruptStorage : TokenStorage {
        var corrupt = true
        override fun load(): List<PairedToken> = emptyList()
        override fun save(tokens: List<PairedToken>): Boolean = !corrupt
        override fun forceSave(tokens: List<PairedToken>): Boolean {
            corrupt = false
            return true
        }
    }

    private fun token(hex: String, name: String = "phone"): PairedToken =
        PairedToken(
            id = "id-$hex",
            sha256Hex = hex,
            clientName = name,
            createdAt = 1L,
            lastSeen = 1L
        )

    // ------------------------------------------------------------------ PIN

    @Test
    fun `pin is always six decimal digits`() {
        val pinManager = PinManager(random = SecureRandom())
        repeat(500) {
            val pin = pinManager.issuePin()
            assertTrue("PIN '$pin' must be 6 digits", pin.matches(Regex("^[0-9]{6}$")))
        }
    }

    @Test
    fun `pin is single use`() {
        val pinManager = PinManager()
        val pin = pinManager.issuePin()
        assertTrue(pinManager.submit(pin) is PinManager.Result.Success)
        assertNull(pinManager.currentPin())
        assertTrue(pinManager.submit(pin) is PinManager.Result.Expired)
    }

    @Test
    fun `pin expires after the ttl`() {
        var now = 0L
        val pinManager = PinManager(ttlMs = 120_000L, now = { now })
        val pin = pinManager.issuePin()
        now += 119_000L
        assertNotNull(pinManager.currentPin())
        assertTrue(pinManager.submit(pin) is PinManager.Result.Success)

        val second = pinManager.issuePin()
        now += 121_000L
        assertNull(pinManager.currentPin())
        assertTrue(pinManager.submit(second) is PinManager.Result.Expired)
    }

    @Test
    fun `lockout after five failures with a sixty second first lock`() {
        var now = 0L
        val pinManager = PinManager(now = { now })
        pinManager.issuePin()

        repeat(4) {
            assertTrue(pinManager.submit("000000") is PinManager.Result.BadPin)
        }

        val fifth = pinManager.submit("000000")
        assertTrue(fifth is PinManager.Result.Locked)
        assertEquals(60L, (fifth as PinManager.Result.Locked).retryAfterSec)
        assertTrue(pinManager.isLocked())
        // The PIN is invalidated and a new one is required.
        assertNull(pinManager.currentPin())

        now += 60_000L
        assertFalse(pinManager.isLocked())
        assertTrue(pinManager.submit("000000") is PinManager.Result.Expired)
    }

    @Test
    fun `subsequent lockouts double up to the fifteen minute cap`() {
        var now = 0L
        val pinManager = PinManager(now = { now })

        val expected = listOf(60L, 120L, 240L, 480L, 900L, 900L)
        for (lockSeconds in expected) {
            pinManager.issuePin()
            repeat(4) { pinManager.submit("000000") }
            val locked = pinManager.submit("000000")
            assertTrue(locked is PinManager.Result.Locked)
            assertEquals(lockSeconds, (locked as PinManager.Result.Locked).retryAfterSec)
            now += lockSeconds * 1000L
        }
    }

    @Test
    fun `successful pairing resets the failure counter`() {
        var now = 0L
        val pinManager = PinManager(now = { now })
        val pin = pinManager.issuePin()

        repeat(4) { pinManager.submit("000000") }
        assertTrue(pinManager.submit(pin) is PinManager.Result.Success)

        val next = pinManager.issuePin()
        repeat(4) { pinManager.submit("000000") }
        assertTrue(pinManager.submit(next) is PinManager.Result.Success)
    }

    // ---------------------------------------------------------------- tokens

    @Test
    fun `token is 32 base64url bytes without padding`() {
        val token = TokenCrypto.generateToken()
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]{43}$")))
    }

    @Test
    fun `constant time compare matches equal strings and rejects different ones`() {
        assertTrue(TokenCrypto.constantTimeEquals("abcdef", "abcdef"))
        assertFalse(TokenCrypto.constantTimeEquals("abcdef", "abcdeg"))
        assertFalse(TokenCrypto.constantTimeEquals("abcdef", "abcde"))
        assertFalse(TokenCrypto.constantTimeEquals("", "a"))
        assertTrue(TokenCrypto.constantTimeEquals("", ""))
    }

    @Test
    fun `token hash round trip`() {
        val token = TokenCrypto.generateToken()
        val hash = TokenCrypto.sha256Hex(token)
        assertEquals(64, hash.length)
        assertTrue(TokenCrypto.verifyToken(token, hash))
        assertFalse(TokenCrypto.verifyToken(TokenCrypto.generateToken(), hash))
        assertEquals(hash, TokenCrypto.sha256Hex(token))
    }

    @Test
    fun `registry find revoke and revokeAll`() {
        val storage = InMemoryTokenStorage()
        val registry = TokenRegistry(storage)

        val token = TokenCrypto.generateToken()
        val hash = TokenCrypto.sha256Hex(token)
        registry.add(token(hash, "phone"))

        assertEquals(1, registry.count())
        assertNotNull(registry.findByHash(hash))
        assertNull(registry.findByHash(TokenCrypto.sha256Hex("other")))

        registry.revokeByHash(hash)
        assertEquals(0, registry.count())
        assertNull(registry.findByHash(hash))
    }

    @Test
    fun `revokeAll clears every paired device`() {
        val storage = InMemoryTokenStorage()
        val registry = TokenRegistry(storage)
        val a = TokenCrypto.sha256Hex(TokenCrypto.generateToken())
        val b = TokenCrypto.sha256Hex(TokenCrypto.generateToken())
        registry.add(token(a, "one"))
        registry.add(token(b, "two"))
        assertEquals(2, registry.count())

        registry.revokeAll()
        assertEquals(0, registry.count())
        assertNull(registry.findByHash(a))
        assertNull(registry.findByHash(b))
    }

    @Test
    fun `revokeAll force-overwrites a corrupt blob`() {
        val storage = CorruptStorage()
        val registry = TokenRegistry(storage)
        // The corrupt blob refuses normal writes ...
        assertFalse(storage.save(emptyList()))
        // ... but an explicit revoke-all is always a way out.
        assertTrue(registry.revokeAll())
        assertFalse(storage.corrupt)
    }

    @Test
    fun `a failed revocation is reported but still applies in memory`() {
        val storage = FailableStorage()
        val registry = TokenRegistry(storage)
        val hash = TokenCrypto.sha256Hex(TokenCrypto.generateToken())
        assertTrue(registry.add(token(hash)))
        storage.fail = true

        assertFalse(registry.revokeByHash(hash))
        // The token must not stay usable just because the write failed.
        assertNull(registry.findByHash(hash))
    }

    @Test
    fun `touch updates lastSeen without changing other entries`() {
        val storage = InMemoryTokenStorage()
        val registry = TokenRegistry(storage)
        val a = TokenCrypto.sha256Hex(TokenCrypto.generateToken())
        val b = TokenCrypto.sha256Hex(TokenCrypto.generateToken())
        registry.add(token(a, "one"))
        registry.add(token(b, "two"))

        registry.touch("id-$a", 999L)
        assertEquals(999L, registry.findByHash(a)?.lastSeen)
        assertEquals(1L, registry.findByHash(b)?.lastSeen)
    }
}
