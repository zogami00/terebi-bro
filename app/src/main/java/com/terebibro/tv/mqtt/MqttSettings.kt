package com.terebibro.tv.mqtt

/**
 * Immutable, effective MQTT configuration. Pure-JVM (no Android types) so it
 * can be unit-tested and so the validator/merge logic never depends on
 * `ConfigStore`.
 *
 * [baseTopic] is the *effective* base topic: the stored value when set, or the
 * default derived from the device name (`terebibro/<mdnsName>`). The password is
 * carried here because the client needs it to connect; it is never logged and
 * never published in any payload.
 */
data class MqttSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val tls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val baseTopic: String = ""
) {

    /** True when the client has everything it needs to attempt a connection. */
    val connectable: Boolean get() = enabled && host.isNotBlank()

    companion object {
        /** The conventional MQTT port, and the default for `mqttPort`. */
        const val DEFAULT_PORT = 1883

        /** MQTT is off by default: broker access equals control (see the README). */
        const val DEFAULT_ENABLED = false

        /** TLS is off by default (plain `tcp://`). */
        const val DEFAULT_TLS = false
    }
}

/**
 * A partial update to the MQTT settings, as accepted by
 * `POST /api/settings/mqtt`. A `null` field means "leave unchanged"; the
 * validator returns a normalised `MqttUpdate` that the store merges in one
 * write. `password = ""` deliberately clears the stored password.
 */
data class MqttUpdate(
    val enabled: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val tls: Boolean? = null,
    val username: String? = null,
    val password: String? = null,
    val baseTopic: String? = null
)
