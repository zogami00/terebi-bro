package com.terebibro.tv.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttTopicsTest {

    @Test
    fun `default base topic derives from the device name`() {
        assertEquals("terebibro/terebi-tv", MqttTopics.defaultBaseTopic("terebi-tv"))
        assertEquals("terebibro/living-room-tv", MqttTopics.defaultBaseTopic("Living Room TV"))
    }

    @Test
    fun `an empty device name falls back to the default segment`() {
        assertEquals("terebibro/$FALLBACK", MqttTopics.defaultBaseTopic(""))
        assertEquals("terebibro/$FALLBACK", MqttTopics.defaultBaseTopic("   "))
    }

    @Test
    fun `segment sanitises to a single valid topic level`() {
        assertEquals("living-room-tv", MqttTopics.segment("Living Room TV"))
        assertEquals("a-b-c", MqttTopics.segment("  a/b#c  "))
        assertEquals("under_score", MqttTopics.segment("under_score"))
        // Leading and trailing separators are trimmed so the level is never empty.
        assertEquals("x", MqttTopics.segment("--x--"))
    }

    @Test
    fun `segment caps its length`() {
        val long = "a".repeat(200)
        assertEquals(MqttTopics.MAX_SEGMENT, MqttTopics.segment(long).length)
    }

    @Test
    fun `a wildcard in the device name can never survive sanitising`() {
        val segment = MqttTopics.segment("evil+/#topic")
        assertEquals(false, segment.contains('+'))
        assertEquals(false, segment.contains('#'))
    }

    @Test
    fun `a blank configured base topic falls back to the derived default`() {
        assertEquals("terebibro/terebi-tv", MqttTopics.baseTopic("", "terebi-tv"))
        assertEquals("terebibro/terebi-tv", MqttTopics.baseTopic("   ", "terebi-tv"))
        assertEquals("terebibro/terebi-tv", MqttTopics.baseTopic("/", "terebi-tv"))
    }

    @Test
    fun `a configured base topic is used verbatim, minus surrounding slashes`() {
        assertEquals("custom/base", MqttTopics.baseTopic("/custom/base/", "terebi-tv"))
    }

    @Test
    fun `contract topics are the base plus the fixed suffixes`() {
        val base = MqttTopics.baseTopic("", "terebi-tv")
        assertEquals("terebibro/terebi-tv/availability", MqttTopics.availability(base))
        assertEquals("terebibro/terebi-tv/status", MqttTopics.status(base))
        assertEquals("terebibro/terebi-tv/command", MqttTopics.command(base))
        assertEquals("terebibro/terebi-tv/command/result", MqttTopics.commandResult(base))
    }

    @Test
    fun `the client id is stable for the same device and differs across devices`() {
        assertEquals("terebibro-terebi-tv", MqttTopics.clientId("terebi-tv"))
        assertNotEquals(MqttTopics.clientId("terebi-tv"), MqttTopics.clientId("other-tv"))
        // Derived from the sanitised name, so case/space differences collapse.
        assertEquals(MqttTopics.clientId("Living Room TV"), MqttTopics.clientId("living room tv"))
    }

    private companion object {
        const val FALLBACK = "terebi-tv"
    }
}
