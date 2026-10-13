package com.terebibro.tv.mqtt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-JVM builder for the Home Assistant **MQTT Discovery** config payloads.
 *
 * Home Assistant only creates a device automatically from retained config
 * payloads published to
 * `<prefix>/<component>/<node_id>/<object_id>/config`, so this object owns that
 * fixed contract and nothing else: no I/O, no Android types and no secrets. It
 * is deliberately framework-free so the topic shape, the shared `device` block
 * and the button/text action wiring are directly unit-testable.
 *
 * The `<deviceId>` argument is the stable, sanitised device segment (the same
 * [MqttTopics.segment] value the MQTT client id and default base topic use), so
 * a restart never changes the node id and Home Assistant never sees a duplicate
 * device.
 */
object MqttDiscovery {

    /** The conventional Home Assistant discovery prefix. */
    const val DEFAULT_PREFIX = "homeassistant"

    /** Node-id / unique-id prefix; kept distinct from the topic namespace. */
    const val NODE_PREFIX = "terebibro_"

    const val MANUFACTURER = "terebibro"
    const val MODEL = "Terebi Bro"

    const val CATEGORY_DIAGNOSTIC = "diagnostic"

    /**
     * Maximum accepted length for a `text` entity value. Home Assistant's MQTT
     * `text` entity defaults to `max: 100`; our own parser accepts URLs up to
     * 2048 characters, so the default would silently reject a long URL on
     * `set-home`/`open`. Raised to HA's own 255-character state limit.
     */
    const val TEXT_MAX_LENGTH = 255

    /** The Home Assistant entity domains this device publishes. */
    enum class Component(val wire: String) {
        SENSOR("sensor"),
        BINARY_SENSOR("binary_sensor"),
        BUTTON("button"),
        TEXT("text")
    }

    /** One discovery entity: its domain, its topic object id and its config payload. */
    data class Entity(
        val component: Component,
        val objectId: String,
        val config: JSONObject
    )

    /** The discovery node id for a device: `terebibro_<deviceId>`. */
    fun nodeId(deviceId: String): String = NODE_PREFIX + deviceId

    /** A stable, per-entity unique id: `terebibro_<deviceId>_<objectId>`. */
    fun uniqueId(deviceId: String, objectId: String): String =
        NODE_PREFIX + deviceId + "_" + objectId

    /** The retained config topic for [entity]: `<prefix>/<component>/<node>/<obj>/config`. */
    fun topic(prefix: String, deviceId: String, entity: Entity): String =
        "$prefix/${entity.component.wire}/${nodeId(deviceId)}/${entity.objectId}/config"

    /**
     * The config topics that must be cleared when the discovery prefix changed:
     * every config topic built with [previousPrefix]. Empty when there was no
     * previous prefix or it is unchanged, so nothing is published unnecessarily.
     * Both this clear list and the publish list are derived from the same
     * [entities], so the two can never drift apart.
     */
    fun staleConfigTopics(
        previousPrefix: String?,
        currentPrefix: String,
        deviceId: String,
        entities: List<Entity>
    ): List<String> {
        val previous = previousPrefix?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (previous == currentPrefix) return emptyList()
        return entities.map { topic(previous, deviceId, it) }
    }

    /**
     * Every discovery entity for one device, in a stable order. [deviceName] is
     * the user-facing name shown in Home Assistant; [appVersion] becomes the
     * device `sw_version`; [base] is the effective MQTT base topic the state,
     * availability and command topics hang off.
     */
    fun entities(
        deviceId: String,
        deviceName: String,
        appVersion: String,
        base: String
    ): List<Entity> {
        val status = MqttTopics.status(base)
        val availability = MqttTopics.availability(base)
        val command = MqttTopics.command(base)
        val ctx = Context(deviceId, deviceName, appVersion, base)

        return buildList {
            // --- Sensors (state JSON) -------------------------------------
            // truncate(250, true) is length 250 with killwords=True. The second
            // positional argument is killwords, not leeway, so Jinja2's default
            // leeway of 5 still applies: anything up to 255 characters passes
            // through whole and longer strings are cut to 250, so the result never
            // exceeds Home Assistant's 255-character state limit.
            add(ctx.sensor("current_url", "Current URL", status, "{{ value_json.url | truncate(250, true) }}"))
            add(ctx.sensor("title", "Title", status, "{{ value_json.title | truncate(250, true) }}"))
            add(
                ctx.sensor(
                    "uptime", "Uptime", status,
                    "{{ (value_json.uptimeMs / 1000) | round(0) }}",
                    unit = "s", deviceClass = "duration"
                )
            )

            // --- Diagnostic sensors ---------------------------------------
            add(ctx.sensor("webview_version", "WebView version", status, "{{ value_json.webViewVersion }}", category = CATEGORY_DIAGNOSTIC))
            add(ctx.sensor("app_version", "App version", status, "{{ value_json.appVersion }}", category = CATEGORY_DIAGNOSTIC))
            add(ctx.sensor("ip_address", "IP address", status, "{{ value_json.ip }}", category = CATEGORY_DIAGNOSTIC))
            add(ctx.sensor("port", "Port", status, "{{ value_json.port }}", category = CATEGORY_DIAGNOSTIC))
            add(ctx.sensor("network", "Network", status, "{{ value_json.network }}", category = CATEGORY_DIAGNOSTIC))
            add(ctx.sensor("android_version", "Android version", status, "{{ value_json.androidVersion }}", category = CATEGORY_DIAGNOSTIC))

            // --- Binary sensors -------------------------------------------
            add(
                ctx.binarySensor(
                    "loading", "Loading", status,
                    valueTemplate = "{{ 'ON' if value_json.loading else 'OFF' }}",
                    payloadOn = "ON", payloadOff = "OFF"
                )
            )
            add(
                ctx.binarySensor(
                    "connectivity", "Connectivity", availability,
                    valueTemplate = null,
                    payloadOn = MqttManager.PAYLOAD_ONLINE,
                    payloadOff = MqttManager.PAYLOAD_OFFLINE,
                    deviceClass = "connectivity"
                )
            )

            // --- Buttons (command JSON) -----------------------------------
            add(ctx.button("reload", "Reload", command, "reload"))
            add(ctx.button("restart_webview", "Restart WebView", command, "restart-webview"))
            add(ctx.button("back", "Back", command, "back"))
            add(ctx.button("home", "Home", command, "home"))
            add(ctx.button("forward", "Forward", command, "forward"))
            add(ctx.button("stop", "Stop", command, "stop"))
            add(ctx.button("clear_cache", "Clear cache", command, "clear-cache"))
            add(ctx.button("clear_site_data", "Clear site data", command, "clear-site-data"))

            // --- Text (command JSON with a value) -------------------------
            add(
                ctx.text(
                    "home_url", "Home URL", command,
                    commandTemplate = template("set-home"),
                    stateTopic = status,
                    valueTemplate = "{{ value_json.homeUrl | truncate(250, true) }}"
                )
            )
            add(
                ctx.text(
                    "open_url", "Open URL", command,
                    commandTemplate = template("open")
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------

    private class Context(
        val deviceId: String,
        val deviceName: String,
        val appVersion: String,
        val base: String
    ) {
        fun sensor(
            objectId: String,
            name: String,
            stateTopic: String,
            valueTemplate: String,
            unit: String? = null,
            deviceClass: String? = null,
            category: String? = null
        ): Entity {
            val cfg = common(Component.SENSOR, objectId)
            cfg.put("name", name)
            cfg.put("state_topic", stateTopic)
            cfg.put("value_template", valueTemplate)
            unit?.let { cfg.put("unit_of_measurement", it) }
            deviceClass?.let { cfg.put("device_class", it) }
            category?.let { cfg.put("entity_category", it) }
            return Entity(Component.SENSOR, objectId, cfg)
        }

        fun binarySensor(
            objectId: String,
            name: String,
            stateTopic: String,
            valueTemplate: String?,
            payloadOn: String,
            payloadOff: String,
            deviceClass: String? = null
        ): Entity {
            val cfg = common(Component.BINARY_SENSOR, objectId)
            cfg.put("name", name)
            cfg.put("state_topic", stateTopic)
            valueTemplate?.let { cfg.put("value_template", it) }
            cfg.put("payload_on", payloadOn)
            cfg.put("payload_off", payloadOff)
            deviceClass?.let { cfg.put("device_class", it) }
            return Entity(Component.BINARY_SENSOR, objectId, cfg)
        }

        fun button(objectId: String, name: String, commandTopic: String, action: String): Entity {
            val cfg = common(Component.BUTTON, objectId)
            cfg.put("name", name)
            cfg.put("command_topic", commandTopic)
            cfg.put("payload_press", JSONObject().put("action", action).toString())
            return Entity(Component.BUTTON, objectId, cfg)
        }

        fun text(
            objectId: String,
            name: String,
            commandTopic: String,
            commandTemplate: String,
            stateTopic: String? = null,
            valueTemplate: String? = null
        ): Entity {
            val cfg = common(Component.TEXT, objectId)
            cfg.put("name", name)
            cfg.put("command_topic", commandTopic)
            cfg.put("command_template", commandTemplate)
            cfg.put("max", TEXT_MAX_LENGTH)
            stateTopic?.let { cfg.put("state_topic", it) }
            valueTemplate?.let { cfg.put("value_template", it) }
            return Entity(Component.TEXT, objectId, cfg)
        }

        /**
         * The fields every payload must carry: the discovery `platform`, the
         * unique id, availability and the device block.
         *
         * [component] is the single source of truth for the component: its
         * `wire` name is written as the required discovery `platform` key here
         * and is also the topic segment used by [topic], so the two cannot
         * drift. Home Assistant drops any auto-discovery config that omits
         * `platform` for the newer domains (button/binary_sensor/text), which
         * is why the key is mandatory here.
         */
        private fun common(component: Component, objectId: String): JSONObject {
            val device = JSONObject()
                .put("identifiers", JSONArray().put(nodeId(deviceId)))
                .put("name", deviceName)
                .put("manufacturer", MANUFACTURER)
                .put("model", MODEL)
                .put("sw_version", appVersion)
            return JSONObject()
                .put("platform", component.wire)
                .put("unique_id", uniqueId(deviceId, objectId))
                // Names entities relative to their device, so a second TV gets
                // readable ids instead of HA-generated suffixes like `_2`.
                .put("has_entity_name", true)
                .put("availability_topic", MqttTopics.availability(base))
                .put("payload_available", MqttManager.PAYLOAD_ONLINE)
                .put("payload_not_available", MqttManager.PAYLOAD_OFFLINE)
                .put("device", device)
        }
    }

    /**
     * The `command_template` body for a `text` entity. Home Assistant renders
     * the template with Jinja2, substituting the entity's entered value for
     * `value`; `tojson` serialises the whole dict, so a value containing a
     * double quote or a backslash is escaped and the published payload stays
     * valid JSON (string interpolation would produce invalid JSON and the
     * command would be dropped by the parser).
     */
    private fun template(action: String): String =
        "{{ {'action':'$action','url':value} | tojson }}"
}
