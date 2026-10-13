package com.terebibro.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the bounded, in-memory list that bootstraps the
 * one-press allow on the TV overlay.
 */
class RecentlyBlockedPeersTest {

    @Test
    fun `keeps only the five most recent networks`() {
        val peers = RecentlyBlockedPeers()
        for (i in 0 until 7) peers.record("10.10.$i.10")

        val snapshot = peers.snapshot()
        assertEquals(5, snapshot.size)
        // The two oldest /24s were evicted; the newest is first.
        assertEquals("10.10.6.10", snapshot.first().ip)
        assertTrue(snapshot.none { it.ip == "10.10.0.10" || it.ip == "10.10.1.10" })
    }

    @Test
    fun `de-duplicates several addresses in one network`() {
        val peers = RecentlyBlockedPeers()
        peers.record("10.10.1.10")
        peers.record("10.10.1.20")
        peers.record("10.10.1.30")

        val snapshot = peers.snapshot()
        assertEquals(1, snapshot.size)
        // The most recently blocked address in the network is kept.
        assertEquals("10.10.1.30", snapshot[0].ip)
    }

    @Test
    fun `distinct networks are kept separately`() {
        val peers = RecentlyBlockedPeers()
        peers.record("10.10.1.10")
        peers.record("10.10.2.10")
        assertEquals(2, peers.snapshot().size)
    }

    @Test
    fun `snapshot is most recent first`() {
        var now = 0L
        val peers = RecentlyBlockedPeers(now = { now })
        peers.record("10.10.1.10")
        now = 10L
        peers.record("10.10.2.10")
        now = 20L
        peers.record("10.10.3.10")

        val snapshot = peers.snapshot()
        assertEquals(listOf("10.10.3.10", "10.10.2.10", "10.10.1.10"), snapshot.map { it.ip })
        assertEquals(20L, snapshot.first().blockedAt)
        assertEquals(0L, snapshot.last().blockedAt)
    }

    @Test
    fun `re-recording a network moves it to the front`() {
        val peers = RecentlyBlockedPeers()
        peers.record("10.10.1.10")
        peers.record("10.10.2.10")
        peers.record("10.10.1.40")

        val snapshot = peers.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("10.10.1.40", snapshot.first().ip)
    }

    @Test
    fun `clear empties the list`() {
        val peers = RecentlyBlockedPeers()
        peers.record("10.10.1.10")
        peers.record("10.10.2.10")
        peers.clear()
        assertTrue(peers.snapshot().isEmpty())
    }

    @Test
    fun `an empty ip is a no-op`() {
        val peers = RecentlyBlockedPeers()
        peers.record("")
        assertTrue(peers.snapshot().isEmpty())
    }

    @Test
    fun `peers without an admissible network are not recorded`() {
        val peers = RecentlyBlockedPeers()
        // 172.32/11 and 8.8.8.8 are outside the private/CGNAT/link-local ranges,
        // so no one-press allow can be offered for them.
        peers.record("172.32.0.5")
        peers.record("8.8.8.8")
        assertTrue(peers.snapshot().isEmpty())
    }

    @Test
    fun `a valid private peer still records and de-duplicates by network`() {
        val peers = RecentlyBlockedPeers()
        peers.record("10.10.1.10")
        peers.record("10.10.1.20")

        val snapshot = peers.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("10.10.1.20", snapshot[0].ip)
    }
}
