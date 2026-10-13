package com.terebibro.tv.mqtt

/**
 * Pure-JVM MQTT topic builder.
 *
 * The base topic defaults to `terebibro/<device>` where `<device>` is the
 * device name sanitised into a single valid topic level, and every contract
 * topic is that base plus a fixed suffix:
 *
 * | Topic                | Direction |
 * | -------------------- | --------- |
 * | `<base>/availability`| publish   |
 * | `<base>/status`      | publish   |
 * | `<base>/command`     | subscribe |
 * | `<base>/command/result` | publish |
 *
 * A configured base topic is used verbatim once it has been validated (no
 * wildcards, whitespace or control characters); only the device-name derived
 * default is sanitised here. Keeping this framework-free makes the topic
 * contract directly unit-testable.
 */
object MqttTopics {

    const val AVAILABILITY = "availability"
    const val STATUS = "status"
    const val COMMAND = "command"
    const val COMMAND_RESULT = "command/result"

    /** Default leading level so the topics are namespaced on a shared broker. */
    const val BASE_PREFIX = "terebibro"

    /** Client-id prefix; the client id must be stable across reconnects. */
    const val CLIENT_ID_PREFIX = "terebibro-"

    const val FALLBACK_SEGMENT = "terebi-tv"

    /** A single MQTT topic level is bounded by the broker; keep it well under. */
    const val MAX_SEGMENT = 63

    /**
     * Sanitises an arbitrary device name into one valid MQTT topic level:
     * lowercased, reduced to `[a-z0-9_-]`, trimmed of hyphens and capped at
     * [MAX_SEGMENT]. Empty input (or input that sanitises to nothing) yields
     * [fallback].
     */
    fun segment(raw: String, fallback: String = FALLBACK_SEGMENT): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            sb.append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_') ch else '-')
        }
        var name = sb.toString().trim('-')
        if (name.length > MAX_SEGMENT) name = name.substring(0, MAX_SEGMENT).trim('-')
        return name.ifEmpty { fallback.ifEmpty { FALLBACK_SEGMENT } }
    }

    /** The default base topic for a device: `terebibro/<sanitised name>`. */
    fun defaultBaseTopic(mdnsName: String): String = "$BASE_PREFIX/${segment(mdnsName)}"

    /**
     * The effective base topic. [configured] is the stored setting; when blank
     * (after trimming slashes) the device-derived default is used. A configured
     * value is used as-is: the setting validator already guarantees it carries
     * no wildcards, whitespace or control characters, so it can never broaden a
     * subscription.
     */
    fun baseTopic(configured: String, mdnsName: String): String {
        val trimmed = configured.trim().trim('/')
        return if (trimmed.isEmpty()) defaultBaseTopic(mdnsName) else trimmed
    }

    fun availability(base: String): String = "$base/$AVAILABILITY"

    fun status(base: String): String = "$base/$STATUS"

    fun command(base: String): String = "$base/$COMMAND"

    fun commandResult(base: String): String = "$base/$COMMAND_RESULT"

    /**
     * A stable MQTT client id derived from the device. MQTT brokers key session
     * state on the client id, so a random id would make every reconnect look
     * like a brand-new device and would leak dangling sessions.
     */
    fun clientId(mdnsName: String): String = CLIENT_ID_PREFIX + segment(mdnsName)
}
