package com.terebibro.tv.mqtt

import com.terebibro.tv.mqtt.MqttManager.Companion.IncomingDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class MqttManagerTest {

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
