package com.terebibro.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BindHost] is the pure-JVM seam for the control server's listener address:
 * release binds the advertised LAN address explicitly, debug binds the wildcard
 * address (NanoHTTPD 2.3.1 maps a null hostname to `new InetSocketAddress(port)`)
 * so a NAT'd emulator's loopback is reachable through `adb forward`.
 */
class BindHostTest {

    @Test
    fun `release binds the advertised LAN address explicitly`() {
        assertEquals("10.0.2.15", BindHost.of(allowLocalhost = false, ip = "10.0.2.15"))
    }

    @Test
    fun `debug binds the wildcard so loopback is reachable`() {
        assertNull(BindHost.of(allowLocalhost = true, ip = "10.0.2.15"))
    }
}
