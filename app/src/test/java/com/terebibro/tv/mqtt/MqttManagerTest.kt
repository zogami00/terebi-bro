package com.terebibro.tv.mqtt

import com.terebibro.tv.mqtt.MqttManager.Companion.IncomingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttManagerTest {

    // ---------------------------------------------------------------------
    // Failure-isolated discovery publishing
    // ---------------------------------------------------------------------

    @Test
    fun `one throwing entity does not prevent the others from being published`() {
        val attempted = mutableListOf<String>()

        val result = MqttManager.runIsolated(
            listOf("sensor", "binary_sensor", "button", "text")
        ) { entity ->
            attempted += entity
            // The first non-sensor entity blows up, exactly as the live failure
            // looked: the loop must still reach the ones after it.
            if (entity == "binary_sensor") throw IllegalStateException("bad config")
        }

        // Every entity was attempted, in order, despite one throwing.
        assertEquals(listOf("sensor", "binary_sensor", "button", "text"), attempted)
        assertEquals(3, result.succeeded)
        assertEquals(1, result.failed)
        assertTrue(result.lastError!!.startsWith("IllegalStateException"))
    }

    @Test
    fun `the isolated run counts every success and failure`() {
        val ok = MqttManager.runIsolated(listOf(1, 2, 3, 4)) { /* publish */ }
        assertEquals(4, ok.succeeded)
        assertEquals(0, ok.failed)
        assertNull(ok.lastError)

        var n = 0
        val mixed = MqttManager.runIsolated(listOf(1, 2, 3)) {
            n++
            if (n == 2 || n == 3) throw RuntimeException("fail $n")
        }
        assertEquals(1, mixed.succeeded)
        assertEquals(2, mixed.failed)
        assertEquals("fail 3", mixed.lastError!!.substringAfter(": "))
    }

    @Test
    fun `an all-success run reports no failure reason`() {
        val result = MqttManager.runIsolated(listOf("a", "b", "c")) { }
        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        assertNull(result.lastError)
    }

    @Test
    fun `the failure reason names the class and redacts the message`() {
        // No message: just the class name.
        assertEquals(
            "IllegalStateException",
            MqttManager.discoveryFailureReason(IllegalStateException())
        )
        // A blank message is treated as no message.
        assertEquals(
            "RuntimeException",
            MqttManager.discoveryFailureReason(RuntimeException("  "))
        )
        // A user-info URL credential is stripped, and a token query is masked.
        val reason = MqttManager.discoveryFailureReason(
            IllegalArgumentException("connect tcp://user:secret@broker.local:1883?token=abc")
        )
        assertTrue(reason.startsWith("IllegalArgumentException: "))
        assertFalse(reason.contains("secret"))
        assertFalse(reason.contains("abc"))
        assertTrue(reason.contains("<redacted>"))
    }

    @Test
    fun `a payload within the bound is processed`() {
        assertEquals(
            IncomingDecision.PROCESS,
            MqttManager.classifyIncoming(isRetained = false, payloadSize = 0)
        )
        assertEquals(
            IncomingDecision.PROCESS,
            MqttManager.classifyIncoming(isRetained = false, payloadSize = MqttManager.MAX_INCOMING_PAYLOAD)
        )
    }

    @Test
    fun `a payload past the bound is rejected before decoding`() {
        assertEquals(
            IncomingDecision.TOO_LARGE,
            MqttManager.classifyIncoming(
                isRetained = false,
                payloadSize = MqttManager.MAX_INCOMING_PAYLOAD + 1
            )
        )
        assertEquals(
            IncomingDecision.TOO_LARGE,
            MqttManager.classifyIncoming(isRetained = false, payloadSize = 10_000_000)
        )
    }

    @Test
    fun `a retained message is ignored so it is not replayed on every connect`() {
        assertEquals(
            IncomingDecision.IGNORE_RETAINED,
            MqttManager.classifyIncoming(isRetained = true, payloadSize = 10)
        )
        assertEquals(
            IncomingDecision.IGNORE_RETAINED,
            MqttManager.classifyIncoming(
                isRetained = true,
                payloadSize = MqttManager.MAX_INCOMING_PAYLOAD + 1
            )
        )
    }
}
