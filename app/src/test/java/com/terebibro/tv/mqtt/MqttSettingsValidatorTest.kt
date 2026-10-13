package com.terebibro.tv.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttSettingsValidatorTest {

    private fun valid(update: MqttUpdate): MqttUpdate {
        val result = MqttSettingsValidator.validate(update)
        assertTrue("expected valid but was $result", result is MqttSettingsValidator.Result.Valid)
        return (result as MqttSettingsValidator.Result.Valid).update
    }

    private fun invalid(update: MqttUpdate): MqttSettingsValidator.Result.Invalid {
        val result = MqttSettingsValidator.validate(update)
        assertTrue("expected invalid but was $result", result is MqttSettingsValidator.Result.Invalid)
        return result as MqttSettingsValidator.Result.Invalid
    }

    @Test
    fun `an empty update is valid and changes nothing`() {
        assertEquals(MqttUpdate(), valid(MqttUpdate()))
    }

    @Test
    fun `a full update is normalised`() {
        val update = valid(
            MqttUpdate(
                enabled = true,
                host = "  192.168.1.10  ",
                port = 8883,
                tls = true,
                username = "user",
                password = "secret",
                baseTopic = "  /custom/base/  "
            )
        )
        assertEquals(true, update.enabled)
        assertEquals("192.168.1.10", update.host)
        assertEquals(8883, update.port)
        assertEquals(true, update.tls)
        assertEquals("custom/base", update.baseTopic)
    }

    @Test
    fun `a blank host is allowed so the setting can be cleared`() {
        assertEquals("", valid(MqttUpdate(host = "   ")).host)
    }

    @Test
    fun `a host with a scheme or path is rejected naming the field`() {
        assertEquals("host", invalid(MqttUpdate(host = "tcp://broker")).field)
        assertEquals("host", invalid(MqttUpdate(host = "broker/path")).field)
    }

    @Test
    fun `a host with whitespace is rejected naming the field`() {
        assertEquals("host", invalid(MqttUpdate(host = "bad host")).field)
    }

    @Test
    fun `a host carrying credentials is rejected naming the field`() {
        // `user:pw@host` would otherwise reach Paho and be echoed back in an
        // IllegalArgumentException message.
        assertEquals("host", invalid(MqttUpdate(host = "user:pw@bad^host")).field)
        assertEquals("host", invalid(MqttUpdate(host = "user@broker")).field)
    }

    @Test
    fun `a colon is only allowed inside a bracketed IPv6 literal`() {
        assertEquals("host", invalid(MqttUpdate(host = "broker:1883")).field)
        assertEquals("[::1]", valid(MqttUpdate(host = "[::1]")).host)
        assertEquals("[2001:db8::1]", valid(MqttUpdate(host = "[2001:db8::1]")).host)
    }

    @Test
    fun `an over-long host is rejected`() {
        assertEquals("host", invalid(MqttUpdate(host = "a".repeat(MqttSettingsValidator.MAX_HOST + 1))).field)
    }

    @Test
    fun `ports outside 1-65535 are rejected naming the field`() {
        assertEquals("port", invalid(MqttUpdate(port = 0)).field)
        assertEquals("port", invalid(MqttUpdate(port = 65536)).field)
        assertEquals("port", invalid(MqttUpdate(port = -1)).field)
        assertEquals(1883, valid(MqttUpdate(port = 1883)).port)
    }

    @Test
    fun `over-long credentials are rejected naming the field`() {
        assertEquals("username", invalid(MqttUpdate(username = "u".repeat(MqttSettingsValidator.MAX_CREDENTIAL + 1))).field)
        assertEquals("password", invalid(MqttUpdate(password = "p".repeat(MqttSettingsValidator.MAX_CREDENTIAL + 1))).field)
    }

    @Test
    fun `a base topic with wildcards is rejected naming the field`() {
        assertEquals("baseTopic", invalid(MqttUpdate(baseTopic = "a/+/b")).field)
        assertEquals("baseTopic", invalid(MqttUpdate(baseTopic = "a/#")).field)
    }

    @Test
    fun `an over-long base topic is rejected`() {
        assertEquals("baseTopic", invalid(MqttUpdate(baseTopic = "a".repeat(MqttSettingsValidator.MAX_TOPIC + 1))).field)
    }

    @Test
    fun `a bad value never yields a partially applied update`() {
        // A valid host plus an invalid port must fail the whole request: the
        // validator returns the field, not a half-normalised update.
        val result = MqttSettingsValidator.validate(
            MqttUpdate(host = "192.168.1.10", port = 70000)
        )
        assertTrue(result is MqttSettingsValidator.Result.Invalid)
        assertEquals("port", (result as MqttSettingsValidator.Result.Invalid).field)
    }
}
