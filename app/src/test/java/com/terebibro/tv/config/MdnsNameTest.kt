package com.terebibro.tv.config

import org.junit.Assert.assertEquals
import org.junit.Test

class MdnsNameTest {

    @Test
    fun `lowercases and replaces invalid characters`() {
        assertEquals("living-room-tv", MdnsName.sanitize("Living Room TV"))
        assertEquals("tv-x", MdnsName.sanitize("tv.x"))
    }

    @Test
    fun `trims surrounding whitespace and hyphens`() {
        assertEquals("tv", MdnsName.sanitize("  --TV--  "))
    }

    @Test
    fun `allows lowercase digits and hyphens`() {
        assertEquals("tv-2-5g", MdnsName.sanitize("TV-2_5g"))
    }

    @Test
    fun `caps at sixty three characters`() {
        assertEquals(63, MdnsName.sanitize("a".repeat(100)).length)
    }

    @Test
    fun `falls back when nothing usable remains`() {
        assertEquals(MdnsName.FALLBACK, MdnsName.sanitize("###"))
        assertEquals(MdnsName.FALLBACK, MdnsName.sanitize(""))
    }
}
