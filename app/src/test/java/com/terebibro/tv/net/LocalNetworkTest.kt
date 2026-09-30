package com.terebibro.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subnet membership is the rule the control server applies to every control
 * request; it is verified here without Android framework classes.
 */
class LocalNetworkTest {

    @Test
    fun `peers in the same twenty four bit subnet are accepted`() {
        assertTrue(SubnetMatcher.isSameSubnet("192.168.1.10", "192.168.1.84", 24))
        assertTrue(SubnetMatcher.isSameSubnet("10.0.0.2", "10.0.0.254", 24))
    }

    @Test
    fun `peers in a different subnet are rejected`() {
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.10", "192.168.2.10", 24))
        assertFalse(SubnetMatcher.isSameSubnet("10.0.0.2", "10.0.1.2", 24))
    }

    @Test
    fun `prefix length is honoured`() {
        // Same /16 but different /24
        assertTrue(SubnetMatcher.isSameSubnet("172.16.5.1", "172.16.9.1", 16))
        assertFalse(SubnetMatcher.isSameSubnet("172.16.5.1", "172.16.9.1", 24))
        // /32 means exact host match
        assertTrue(SubnetMatcher.isSameSubnet("192.168.1.5", "192.168.1.5", 32))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.5", "192.168.1.6", 32))
    }

    @Test
    fun `invalid input is rejected`() {
        assertFalse(SubnetMatcher.isSameSubnet(null, "192.168.1.1", 24))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.1", null, 24))
        assertFalse(SubnetMatcher.isSameSubnet("not-an-ip", "192.168.1.1", 24))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.1", "192.168.1", 24))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.300", "192.168.1.1", 24))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.1", "192.168.1.1", 0))
        assertFalse(SubnetMatcher.isSameSubnet("192.168.1.1", "192.168.1.1", 33))
    }

    @Test
    fun `loopback is detected`() {
        assertTrue(SubnetMatcher.isLoopback("127.0.0.1"))
        assertTrue(SubnetMatcher.isLoopback("127.10.20.30"))
        assertFalse(SubnetMatcher.isLoopback("192.168.1.1"))
        assertFalse(SubnetMatcher.isLoopback(null))
    }
}
