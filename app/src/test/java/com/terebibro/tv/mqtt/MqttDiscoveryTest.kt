package com.terebibro.tv.mqtt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttDiscoveryTest {

    private val deviceName = "Living Room TV"
    private val deviceId = MqttTopics.segment(deviceName)
    private val base = "terebibro/$deviceId"
    private val prefix = MqttDiscovery.DEFAULT_PREFIX

    private fun entities() = MqttDiscovery.entities(deviceId, deviceName, APP_VERSION, base)

    private fun entity(objectId: String): MqttDiscovery.Entity =
        entities().first { it.objectId == objectId }

    // ---------------------------------------------------------------------
    // Topic shape and sanitisation
    // ---------------------------------------------------------------------

    @Test
    fun `the topic is prefix component node object config`() {
        assertEquals(
            "homeassistant/sensor/terebibro_living-room-tv/current_url/config",
            MqttDiscovery.topic(prefix, deviceId, entity("current_url"))
        )
        assertEquals(
            "homeassistant/binary_sensor/terebibro_living-room-tv/loading/config",
            MqttDiscovery.topic(prefix, deviceId, entity("loading"))
        )
        assertEquals(
            "homeassistant/button/terebibro_living-room-tv/reload/config",
            MqttDiscovery.topic(prefix, deviceId, entity("reload"))
        )
        assertEquals(
            "homeassistant/text/terebibro_living-room-tv/open_url/config",
            MqttDiscovery.topic(prefix, deviceId, entity("open_url"))
        )
    }

    @Test
    fun `a custom prefix is used verbatim`() {
        assertEquals(
            "my/ha/sensor/terebibro_living-room-tv/title/config",
            MqttDiscovery.topic("my/ha", deviceId, entity("title"))
        )
    }

    @Test
    fun `the device id is the sanitised segment so it is a single valid topic level`() {
        assertEquals("living-room-tv", deviceId)
        assertEquals("terebibro_living-room-tv", MqttDiscovery.nodeId(deviceId))
        // A hostile device name cannot inject a topic separator or a wildcard.
        val hostile = MqttTopics.segment("evil/+/#topic")
        val node = MqttDiscovery.nodeId(hostile)
        assertFalse(node.contains('/'))
        assertFalse(node.contains('+'))
        assertFalse(node.contains('#'))
    }

    @Test
    fun `every entity topic is unique`() {
        val topics = entities().map { MqttDiscovery.topic(prefix, deviceId, it) }
        assertEquals(topics.size, topics.toSet().size)
    }

    // ---------------------------------------------------------------------
    // Prefix change: stale configs are cleared
    // ---------------------------------------------------------------------

    @Test
    fun `a prefix change clears every config built with the previous prefix`() {
        val stale = MqttDiscovery.staleConfigTopics(
            previousPrefix = "homeassistant",
            currentPrefix = "my/ha",
            deviceId = deviceId,
            entities = entities()
        )
        assertEquals(
            entities().map { MqttDiscovery.topic("homeassistant", deviceId, it) },
            stale
        )
        // The clear list and the publish list come from the same entities, so
        // they cannot drift.
        assertEquals(entities().size, stale.size)
        assertTrue(stale.isNotEmpty())
    }

    @Test
    fun `an unchanged prefix clears nothing`() {
        assertEquals(
            emptyList<String>(),
            MqttDiscovery.staleConfigTopics("homeassistant", "homeassistant", deviceId, entities())
        )
    }

    @Test
    fun `a never-published or blank previous prefix clears nothing`() {
        assertEquals(
            emptyList<String>(),
            MqttDiscovery.staleConfigTopics(null, "my/ha", deviceId, entities())
        )
        assertEquals(
            emptyList<String>(),
            MqttDiscovery.staleConfigTopics("   ", "my/ha", deviceId, entities())
        )
    }

    // ---------------------------------------------------------------------
    // unique_id stability
    // ---------------------------------------------------------------------

    @Test
    fun `unique_id is stable for the same device and matches the entity`() {
        val first = entities()
        val second = entities()

        assertEquals(
            first.map { it.objectId to it.config.getString("unique_id") },
            second.map { it.objectId to it.config.getString("unique_id") }
        )
        assertEquals(
            "terebibro_living-room-tv_reload",
            entity("reload").config.getString("unique_id")
        )
        assertEquals(
            "terebibro_living-room-tv_current_url",
            entity("current_url").config.getString("unique_id")
        )
        // The unique id always equals the documented shape.
        for (e in first) {
            assertEquals(
                MqttDiscovery.uniqueId(deviceId, e.objectId),
                e.config.getString("unique_id")
            )
        }
    }

    @Test
    fun `the same physical device yields the same ids however the name is cased`() {
        assertEquals(
            MqttDiscovery.entities("living-room-tv", deviceName, APP_VERSION, base)
                .map { it.config.getString("unique_id") },
            MqttDiscovery.entities(MqttTopics.segment("living room tv"), deviceName, APP_VERSION, base)
                .map { it.config.getString("unique_id") }
        )
    }

    // ---------------------------------------------------------------------
    // Shared device block + availability wiring
    // ---------------------------------------------------------------------

    @Test
    fun `every payload carries the shared device block`() {
        for (e in entities()) {
            val device = e.config.getJSONObject("device")
            assertEquals(
                listOf("terebibro_living-room-tv"),
                device.getJSONArray("identifiers").let { list -> List(list.length()) { list.getString(it) } }
            )
            assertEquals(deviceName, device.getString("name"))
            assertEquals("terebibro", device.getString("manufacturer"))
            assertEquals("Terebi Bro", device.getString("model"))
            assertEquals(APP_VERSION, device.getString("sw_version"))
        }
    }

    @Test
    fun `every payload is wired to the availability topic`() {
        for (e in entities()) {
            assertEquals("$base/availability", e.config.getString("availability_topic"))
            assertEquals("online", e.config.getString("payload_available"))
            assertEquals("offline", e.config.getString("payload_not_available"))
        }
    }

    // ---------------------------------------------------------------------
    // Entity wiring
    // ---------------------------------------------------------------------

    @Test
    fun `state sensors read the status JSON with the required templates`() {
        val status = "$base/status"

        val url = entity("current_url")
        assertEquals(MqttDiscovery.Component.SENSOR, url.component)
        assertEquals(status, url.config.getString("state_topic"))
        // truncate(250, true) is length 250 with killwords=True. The second
        // positional argument is killwords, not leeway, so Jinja2's default leeway
        // of 5 still applies: anything up to 255 characters passes through whole
        // and longer strings are cut to 250, so the result never exceeds Home
        // Assistant's 255-character state limit.
        assertEquals("{{ value_json.url | truncate(250, true) }}", url.config.getString("value_template"))

        val title = entity("title")
        assertEquals(status, title.config.getString("state_topic"))
        assertEquals("{{ value_json.title | truncate(250, true) }}", title.config.getString("value_template"))

        val uptime = entity("uptime")
        assertEquals(status, uptime.config.getString("state_topic"))
        assertEquals("{{ (value_json.uptimeMs / 1000) | round(0) }}", uptime.config.getString("value_template"))
        assertEquals("s", uptime.config.getString("unit_of_measurement"))
        assertEquals("duration", uptime.config.getString("device_class"))
    }

    @Test
    fun `the diagnostic sensors are marked diagnostic`() {
        for (objectId in listOf(
            "webview_version", "app_version", "ip_address", "port", "network", "android_version"
        )) {
            assertEquals(
                "diagnostic",
                entity(objectId).config.getString("entity_category")
            )
        }
        // A normal sensor is not diagnostic.
        assertFalse(entity("current_url").config.has("entity_category"))
    }

    @Test
    fun `the loading binary sensor uses ON and OFF`() {
        val loading = entity("loading")
        assertEquals(MqttDiscovery.Component.BINARY_SENSOR, loading.component)
        assertEquals("$base/status", loading.config.getString("state_topic"))
        assertEquals("{{ 'ON' if value_json.loading else 'OFF' }}", loading.config.getString("value_template"))
        assertEquals("ON", loading.config.getString("payload_on"))
        assertEquals("OFF", loading.config.getString("payload_off"))
    }

    @Test
    fun `the connectivity binary sensor is driven by the availability topic`() {
        val connectivity = entity("connectivity")
        assertEquals(MqttDiscovery.Component.BINARY_SENSOR, connectivity.component)
        assertEquals("$base/availability", connectivity.config.getString("state_topic"))
        assertEquals("connectivity", connectivity.config.getString("device_class"))
        assertEquals("online", connectivity.config.getString("payload_on"))
        assertEquals("offline", connectivity.config.getString("payload_off"))
    }

    @Test
    fun `the home URL text entity mirrors the status homeUrl and writes it back`() {
        val homeUrl = entity("home_url")
        assertEquals(MqttDiscovery.Component.TEXT, homeUrl.component)
        assertEquals("$base/command", homeUrl.config.getString("command_topic"))
        assertEquals("$base/status", homeUrl.config.getString("state_topic"))
        assertEquals("{{ value_json.homeUrl | truncate(250, true) }}", homeUrl.config.getString("value_template"))
        assertEquals(
            "{{ {'action':'set-home','url':value} | tojson }}",
            homeUrl.config.getString("command_template")
        )
    }

    @Test
    fun `the open URL text entity has no state topic`() {
        val openUrl = entity("open_url")
        assertEquals("$base/command", openUrl.config.getString("command_topic"))
        assertEquals(
            "{{ {'action':'open','url':value} | tojson }}",
            openUrl.config.getString("command_template")
        )
        assertFalse(openUrl.config.has("state_topic"))
    }

    @Test
    fun `both text entities raise the HA text max to 255`() {
        // HA's text entity defaults to max 100, which would silently reject the
        // URLs up to 2048 the command parser accepts.
        for (objectId in listOf("home_url", "open_url")) {
            assertEquals(
                MqttDiscovery.TEXT_MAX_LENGTH,
                entity(objectId).config.getInt("max")
            )
            assertEquals(255, entity(objectId).config.getInt("max"))
        }
        // Non-text entities carry no action value bound.
        assertFalse(entity("reload").config.has("max"))
        assertFalse(entity("current_url").config.has("max"))
    }

    @Test
    fun `every entity is named relative to its device`() {
        // Without has_entity_name, a second TV would produce ids like
        // `button.reload_2`; with it, HA names each entity relative to the
        // device, and the entity `name` is the entity's own name.
        for (e in entities()) {
            assertTrue(e.objectId, e.config.getBoolean("has_entity_name"))
        }
        assertEquals("Current URL", entity("current_url").config.getString("name"))
        assertEquals("Reload", entity("reload").config.getString("name"))
        assertEquals("Home URL", entity("home_url").config.getString("name"))
    }

    // ---------------------------------------------------------------------
    // The button/text actions must be actions the real parser accepts.
    // ---------------------------------------------------------------------

    @Test
    fun `every button payload_press resolves to an action the real parser accepts`() {
        val expected = mapOf(
            "reload" to MqttAction.RELOAD,
            "restart_webview" to MqttAction.RESTART_WEBVIEW,
            "back" to MqttAction.BACK,
            "home" to MqttAction.HOME,
            "forward" to MqttAction.FORWARD,
            "stop" to MqttAction.STOP,
            "clear_cache" to MqttAction.CLEAR_CACHE,
            "clear_site_data" to MqttAction.CLEAR_SITE_DATA
        )
        for ((objectId, action) in expected) {
            val payload = entity(objectId).config.getString("payload_press")
            val command = parsed(payload, objectId)
            assertEquals(action, command.action)
            assertEquals("$base/command", entity(objectId).config.getString("command_topic"))
        }
        // Every button in the payload set is covered above, so a new button that
        // forgets its action cannot slip through silently.
        assertEquals(
            expected.keys,
            entities().filter { it.component == MqttDiscovery.Component.BUTTON }
                .map { it.objectId }.toSet()
        )
    }

    @Test
    fun `every text command_template resolves to an action the real parser accepts`() {
        val expected = mapOf(
            "home_url" to MqttAction.SET_HOME,
            "open_url" to MqttAction.OPEN
        )
        for ((objectId, action) in expected) {
            val template = entity(objectId).config.getString("command_template")
            // Home Assistant renders the dict through its tojson filter.
            val rendered = renderToJson(template, "https://example.com/")
            val command = parsed(rendered, objectId)
            assertEquals(action, command.action)
            assertEquals("https://example.com/", command.url)
        }
    }

    @Test
    fun `a value with a double quote and a backslash still decodes as valid JSON`() {
        // The regression: string interpolation (`{"url":"{{ value }}"}`) turns a
        // quote or backslash into invalid JSON, so the command is dropped as
        // `malformed_json`. `tojson` escapes it and the parser accepts the JSON.
        // A raw quote/backslash is not a legal URL, so the URL policy still
        // rejects it — but with `invalid_url`, proving the JSON decoded.
        val hostile = "https://example.com/a\"b\\c"
        for (objectId in listOf("home_url", "open_url")) {
            val template = entity(objectId).config.getString("command_template")
            val rendered = renderToJson(template, hostile)
            // The escaped form is present in the payload the parser receives.
            assertTrue(rendered, rendered.contains("\\\""))
            assertTrue(rendered, rendered.contains("\\\\"))

            val result = MqttCommandParser.parse(rendered)
            assertTrue("$objectId -> $rendered was $result", result is MqttCommandParser.Result.Failed)
            assertEquals(
                "the JSON must decode, only the URL policy may reject it",
                "invalid_url",
                (result as MqttCommandParser.Result.Failed).code
            )
        }
    }

    // ---------------------------------------------------------------------
    // No secrets
    // ---------------------------------------------------------------------

    @Test
    fun `no discovery payload carries a credential`() {
        for (e in entities()) {
            val text = e.config.toString().lowercase()
            assertFalse(e.objectId, text.contains("password"))
            assertFalse(e.objectId, text.contains("username"))
            assertFalse(e.objectId, text.contains("token"))
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun parsed(payload: String, label: String): MqttCommand {
        val result = MqttCommandParser.parse(payload)
        assertTrue("$label -> $payload was $result", result is MqttCommandParser.Result.Parsed)
        return (result as MqttCommandParser.Result.Parsed).command
    }

    /**
     * Mirrors Home Assistant rendering `{{ {'action':'X','url':value} | tojson }}`:
     * the Jinja dict is serialised as JSON with the entered value JSON-escaped.
     * The exact template string is asserted elsewhere, so this stays faithful to
     * the real filter rather than re-implementing the bug.
     */
    private fun renderToJson(template: String, value: String): String {
        val action = Regex("'action':'([^']+)'").find(template)?.groupValues?.get(1)
            ?: error("unexpected command_template: $template")
        return JSONObject().put("action", action).put("url", value).toString()
    }

    private companion object {
        const val APP_VERSION = "0.3.0"
    }
}
