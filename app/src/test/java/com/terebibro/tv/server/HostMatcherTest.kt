package com.terebibro.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMatcherTest {

    private val allowed = listOf("192.168.1.10:8765", "terebi-tv.local:8765")

    @Test
    fun `exact hosts are accepted case insensitively`() {
        assertTrue(HostMatcher.isHostAllowed("192.168.1.10:8765", allowed))
        assertTrue(HostMatcher.isHostAllowed("TEREBI-TV.LOCAL:8765", allowed))
        assertTrue(HostMatcher.isHostAllowed(" 192.168.1.10:8765 ", allowed))
    }

    @Test
    fun `lookalike and missing hosts are rejected`() {
        assertFalse(HostMatcher.isHostAllowed("192.168.1.10:9999", allowed))
        assertFalse(HostMatcher.isHostAllowed("evil.local:8765", allowed))
        assertFalse(HostMatcher.isHostAllowed(null, allowed))
        assertFalse(HostMatcher.isHostAllowed("", allowed))
    }

    @Test
    fun `origin must be http and match a host`() {
        assertTrue(HostMatcher.isOriginAllowed("http://terebi-tv.local:8765", allowed))
        assertTrue(HostMatcher.isOriginAllowed("HTTP://192.168.1.10:8765", allowed))
        assertFalse(HostMatcher.isOriginAllowed("https://terebi-tv.local:8765", allowed))
        assertFalse(HostMatcher.isOriginAllowed("http://evil.local:8765", allowed))
        assertFalse(HostMatcher.isOriginAllowed(null, allowed))
    }

    @Test
    fun `empty allowlist rejects everything`() {
        assertFalse(HostMatcher.isHostAllowed("x", emptyList()))
        assertFalse(HostMatcher.isOriginAllowed("http://x", emptyList()))
    }
}
