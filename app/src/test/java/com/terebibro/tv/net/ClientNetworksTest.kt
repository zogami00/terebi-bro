package com.terebibro.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the CIDR parser behind the extra trusted networks setting.
 */
class ClientNetworksTest {

    // ------------------------------------------------------------------ parsing

    @Test
    fun `parses a valid cidr into network and prefix`() {
        val cidr = ClientNetworks.parseCidr("10.10.1.0/24")
        assertNotNull(cidr)
        assertEquals(24, cidr!!.prefix)
        assertEquals("10.10.1.0/24", cidr.toString())
    }

    @Test
    fun `accepts prefix 8 and 32`() {
        assertNotNull(ClientNetworks.parseCidr("10.0.0.0/8"))
        assertNotNull(ClientNetworks.parseCidr("10.10.1.10/32"))
        assertEquals(8, ClientNetworks.parseCidr("10.0.0.0/8")!!.prefix)
        assertEquals(32, ClientNetworks.parseCidr("10.10.1.10/32")!!.prefix)
    }

    @Test
    fun `rejects prefix 7 and 33`() {
        assertNull(ClientNetworks.parseCidr("10.0.0.0/7"))
        assertNull(ClientNetworks.parseCidr("10.0.0.0/33"))
    }

    @Test
    fun `accepts private cgnat and link-local ranges`() {
        assertNotNull(ClientNetworks.parseCidr("10.0.0.0/8"))
        assertNotNull(ClientNetworks.parseCidr("172.16.0.0/12"))
        assertNotNull(ClientNetworks.parseCidr("192.168.1.0/24"))
        assertNotNull(ClientNetworks.parseCidr("100.64.0.0/10"))
        assertNotNull(ClientNetworks.parseCidr("169.254.0.0/16"))
    }

    @Test
    fun `rejects globally routable ranges with a clear reason`() {
        assertNull(ClientNetworks.parseCidr("1.0.0.0/8"))
        assertNull(ClientNetworks.parseCidr("8.8.8.0/24"))
        assertNull(ClientNetworks.parseCidr("172.15.0.0/12"))
        assertNull(ClientNetworks.parseCidr("172.32.0.0/12"))
        assertNull(ClientNetworks.parseCidr("100.0.0.0/10"))
        assertNull(ClientNetworks.parseCidr("169.255.0.0/16"))
        // A network broader than the range it would name is rejected too.
        assertNull(ClientNetworks.parseCidr("169.254.0.0/15"))
        assertNull(ClientNetworks.parseCidr("100.0.0.0/9"))

        val (accepted, rejected) = ClientNetworks.validateList(listOf("1.0.0.0/8"))
        assertTrue(accepted.isEmpty())
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contains(ClientNetworks.NOT_ALLOWED_REASON))
    }

    @Test
    fun `rejects malformed and non ipv4 input`() {
        assertNull(ClientNetworks.parseCidr(""))
        assertNull(ClientNetworks.parseCidr("10.10.1.0"))
        assertNull(ClientNetworks.parseCidr("10.10.1.0/"))
        assertNull(ClientNetworks.parseCidr("/24"))
        assertNull(ClientNetworks.parseCidr("10.10.1/24"))
        assertNull(ClientNetworks.parseCidr("10.10.1.256/24"))
        assertNull(ClientNetworks.parseCidr("10.10.1.0/2x"))
        assertNull(ClientNetworks.parseCidr("10.10.1.0/24/12"))
        assertNull(ClientNetworks.parseCidr("not-a-network"))
    }

    @Test
    fun `an overflowing prefix is invalid rather than throwing`() {
        // All digits, but far past Int.MAX_VALUE: toInt() would throw and break
        // the documented "null / Bad for anything invalid" contract.
        assertNull(ClientNetworks.parseCidr("10.0.0.0/99999999999"))
        assertNull(ClientNetworks.normalise("10.0.0.0/99999999999"))

        val (accepted, rejected) = ClientNetworks.validateList(listOf("10.0.0.0/99999999999"))
        assertTrue(accepted.isEmpty())
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contains("not a valid prefix length"))
    }

    // ------------------------------------------------------------- normalisation

    @Test
    fun `host bits set is rejected with the canonical form suggested`() {
        assertNull(ClientNetworks.normalise("10.10.1.10/24"))
        assertNull(ClientNetworks.parseCidr("10.10.1.10/24"))
        assertNull(ClientNetworks.parseCidr("10.10.1.10/8"))

        val (accepted, rejected) = ClientNetworks.validateList(listOf("10.10.1.10/24"))
        assertTrue(accepted.isEmpty())
        assertEquals(1, rejected.size)
        assertTrue(rejected[0].contains("host bits set"))
        assertTrue(rejected[0].contains("did you mean 10.10.1.0/24?"))
    }

    @Test
    fun `a canonical network is accepted and round trips`() {
        assertEquals("10.10.1.0/24", ClientNetworks.normalise("10.10.1.0/24"))
        assertEquals("10.10.1.10/32", ClientNetworks.normalise("10.10.1.10/32"))
        assertNull(ClientNetworks.normalise("10.10.1.10/7"))
        assertNull(ClientNetworks.normalise("garbage"))
    }

    // ----------------------------------------------------------------- membership

    @Test
    fun `contains includes the boundary addresses and rejects a non match`() {
        assertTrue(ClientNetworks.contains("10.10.1.0/24", "10.10.1.10"))
        assertTrue(ClientNetworks.contains("10.10.1.0/24", "10.10.1.0"))
        assertTrue(ClientNetworks.contains("10.10.1.0/24", "10.10.1.255"))
        assertFalse(ClientNetworks.contains("10.10.1.0/24", "10.10.2.1"))
        assertFalse(ClientNetworks.contains("10.10.1.0/24", "10.10.0.255"))
        assertFalse(ClientNetworks.contains("nonsense", "10.10.1.10"))
        assertFalse(ClientNetworks.contains("10.10.1.0/24", "nonsense"))
    }

    @Test
    fun `containing network suggests the masked network for an address`() {
        assertEquals("10.10.30.0/24", ClientNetworks.containingNetworkFor("10.10.30.10"))
        assertEquals("10.10.1.0/24", ClientNetworks.containingNetworkFor("10.10.1.10", 24))
        assertEquals("10.0.0.0/8", ClientNetworks.containingNetworkFor("10.10.1.10", 8))
        assertEquals("10.10.1.10/32", ClientNetworks.containingNetworkFor("10.10.1.10", 32))
        assertNull(ClientNetworks.containingNetworkFor("not-an-ip"))
        assertNull(ClientNetworks.containingNetworkFor("10.0.0.1", 7))
        assertNull(ClientNetworks.containingNetworkFor("10.0.0.1", 33))
        // A public peer yields no suggestion: the network could not be admitted.
        assertNull(ClientNetworks.containingNetworkFor("8.8.8.8"))
    }

    // --------------------------------------------------------------- list validation

    @Test
    fun `validate list de-duplicates canonical entries in order`() {
        val (accepted, rejected) = ClientNetworks.validateList(
            listOf("10.10.1.0/24", "10.10.1.0/24", "192.168.0.0/16")
        )
        assertEquals(listOf("10.10.1.0/24", "192.168.0.0/16"), accepted)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `validate list caps at sixteen and reports the extras`() {
        val inputs = (0 until 18).map { "10.0.$it.0/24" }
        val (accepted, rejected) = ClientNetworks.validateList(inputs)
        assertEquals(16, accepted.size)
        assertEquals(2, rejected.size)
        assertTrue(rejected.all { it.contains("at most 16") })
    }

    @Test
    fun `validate list reports the reason for each rejected entry`() {
        val (accepted, rejected) = ClientNetworks.validateList(
            listOf("10.0.0.0/7", "nope", "", "10.10.1.0/24")
        )
        assertEquals(listOf("10.10.1.0/24"), accepted)
        assertEquals(3, rejected.size)
        assertTrue(rejected[0].contains("10.0.0.0/7"))
        assertTrue(rejected[0].contains("between"))
        assertTrue(rejected[1].contains("nope"))
        assertTrue(rejected[2].contains("empty"))
    }
}
