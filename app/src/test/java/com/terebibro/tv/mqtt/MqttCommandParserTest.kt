package com.terebibro.tv.mqtt

import com.terebibro.tv.media.MediaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCommandParserTest {

    private fun parsed(text: String): MqttCommand {
        val result = MqttCommandParser.parse(text)
        assertTrue("expected parsed but was $result", result is MqttCommandParser.Result.Parsed)
        return (result as MqttCommandParser.Result.Parsed).command
    }

    private fun failed(text: String): MqttCommandParser.Result.Failed {
        val result = MqttCommandParser.parse(text)
        assertTrue("expected failure but was $result", result is MqttCommandParser.Result.Failed)
        return result as MqttCommandParser.Result.Failed
    }

    @Test
    fun `open parses and normalises the url`() {
        val command = parsed("""{"action":"open","url":"example.com","id":"abc"}""")
        assertEquals(MqttAction.OPEN, command.action)
        assertEquals("https://example.com", command.url)
        assertEquals("abc", command.id)
    }

    @Test
    fun `set-home parses`() {
        val command = parsed("""{"action":"set-home","url":"https://example.com/"}""")
        assertEquals(MqttAction.SET_HOME, command.action)
        assertEquals("https://example.com/", command.url)
        assertNull(command.id)
    }

    @Test
    fun `every simple action parses without a url`() {
        val expected = mapOf(
            "home" to MqttAction.HOME,
            "back" to MqttAction.BACK,
            "forward" to MqttAction.FORWARD,
            "reload" to MqttAction.RELOAD,
            "stop" to MqttAction.STOP,
            "restart-webview" to MqttAction.RESTART_WEBVIEW,
            "clear-cache" to MqttAction.CLEAR_CACHE,
            "clear-site-data" to MqttAction.CLEAR_SITE_DATA
        )
        for ((wire, action) in expected) {
            val command = parsed("""{"action":"$wire"}""")
            assertEquals(action, command.action)
            assertNull(command.url)
        }
    }

    @Test
    fun `a numeric id is accepted and stringified`() {
        assertEquals("42", parsed("""{"action":"home","id":42}""").id)
    }

    @Test
    fun `an unknown action is a clear failure`() {
        val failure = failed("""{"action":"launch-missiles"}""")
        assertEquals("unknown_action", failure.code)
    }

    @Test
    fun `an unknown action name is truncated in the echoed message`() {
        val long = "x".repeat(200)
        val failure = failed("""{"action":"$long"}""")
        assertEquals("unknown_action", failure.code)
        // The attacker-controlled name is bounded before it reaches command/result.
        assertEquals("Unknown action: " + "x".repeat(MqttCommandParser.MAX_ACTION_ECHO), failure.message)
    }

    @Test
    fun `a missing action is a clear failure`() {
        assertEquals("missing_action", failed("""{"url":"https://example.com"}""").code)
        assertEquals("missing_action", failed("""{"action":42}""").code)
    }

    @Test
    fun `open and set-home require a url`() {
        assertEquals("missing_url", failed("""{"action":"open"}""").code)
        assertEquals("missing_url", failed("""{"action":"set-home"}""").code)
        assertEquals("missing_url", failed("""{"action":"open","url":42}""").code)
    }

    @Test
    fun `a non-http scheme is rejected exactly like the REST path`() {
        assertEquals("invalid_url", failed("""{"action":"open","url":"javascript:alert(1)"}""").code)
        assertEquals("invalid_url", failed("""{"action":"open","url":"data:text/html,x"}""").code)
        assertEquals("invalid_url", failed("""{"action":"open","url":"file:///etc/passwd"}""").code)
    }

    @Test
    fun `malformed json never crashes`() {
        assertEquals("malformed_json", failed("").code)
        assertEquals("malformed_json", failed("{").code)
        assertEquals("malformed_json", failed("not json at all").code)
        assertEquals("malformed_json", failed("""["array"]""").code)
    }

    @Test
    fun `unknown keys are rejected as malformed`() {
        assertEquals("malformed_json", failed("""{"action":"home","surprise":true}""").code)
    }

    @Test
    fun `a wrong id type is a clear failure`() {
        assertEquals("bad_id", failed("""{"action":"home","id":true}""").code)
        assertEquals("bad_id", failed("""{"action":"home","id":1.5}""").code)
    }

    // ---------------------------------------------------------------------
    // Media actions
    // ---------------------------------------------------------------------

    @Test
    fun `every value-free media action parses without extra keys`() {
        val expected = mapOf(
            "media-next" to MqttAction.MEDIA_NEXT,
            "media-play" to MqttAction.MEDIA_PLAY,
            "media-pause" to MqttAction.MEDIA_PAUSE
        )
        for ((wire, action) in expected) {
            val command = parsed("""{"action":"$wire","id":"abc"}""")
            assertEquals(action, command.action)
            assertNull(command.url)
            assertNull(command.mode)
            assertEquals("abc", command.id)
        }
    }

    @Test
    fun `set-mode accepts exactly the three manual modes`() {
        val expected = mapOf(
            "qms_only" to MediaMode.QMS_ONLY,
            "media_only" to MediaMode.MEDIA_ONLY,
            "split" to MediaMode.SPLIT
        )
        for ((wire, mode) in expected) {
            val command = parsed("""{"action":"set-mode","mode":"$wire"}""")
            assertEquals(MqttAction.SET_MODE, command.action)
            assertEquals(mode, command.mode)
            assertNull(command.url)
        }
    }

    @Test
    fun `set-mode without a mode is a bad request naming the allowed values`() {
        val failure = failed("""{"action":"set-mode"}""")
        assertEquals("bad_request", failure.code)
        assertTrue(failure.message, failure.message.contains("qms_only"))
        assertTrue(failure.message, failure.message.contains("media_only"))
        assertTrue(failure.message, failure.message.contains("split"))
    }

    @Test
    fun `set-mode rejects qms_focus as a bad request`() {
        val failure = failed("""{"action":"set-mode","mode":"qms_focus"}""")
        assertEquals("bad_request", failure.code)
        assertTrue(failure.message, failure.message.contains("qms_focus"))
        assertTrue(failure.message, failure.message.contains("qms_only"))
    }

    @Test
    fun `set-mode rejects an unknown mode`() {
        val failure = failed("""{"action":"set-mode","mode":"fullscreen"}""")
        assertEquals("bad_request", failure.code)
        assertTrue(failure.message, failure.message.contains("fullscreen"))
        assertTrue(failure.message, failure.message.contains("split"))
    }

    @Test
    fun `set-mode rejects a non-string mode`() {
        assertEquals("bad_request", failed("""{"action":"set-mode","mode":1}""").code)
    }

    @Test
    fun `a value-free media action rejects a url`() {
        for (wire in listOf("media-next", "media-play", "media-pause")) {
            assertEquals(
                wire,
                "malformed_json",
                failed("""{"action":"$wire","url":"https://example.com/"}""").code
            )
        }
    }

    @Test
    fun `set-mode rejects a url and a media action rejects a mode`() {
        assertEquals(
            "malformed_json",
            failed("""{"action":"set-mode","mode":"split","url":"https://example.com/"}""").code
        )
        assertEquals(
            "malformed_json",
            failed("""{"action":"media-play","mode":"split"}""").code
        )
    }
}
