package com.terebibro.tv.mqtt

/**
 * Read-only MQTT connection status surfaced to the controller and
 * `GET /api/device`. Never carries the password or the username.
 */
data class MqttStatus(
    val connected: Boolean,
    val state: State,
    /** The last connection error, already safe to display; null when there is none. */
    val lastError: String? = null
) {

    enum class State {
        /** Feature disabled (or not configured): the client is not running. */
        DISABLED,
        CONNECTING,
        CONNECTED,
        /** Lost or never established, and a reconnect is scheduled. */
        DISCONNECTED,
        ERROR
    }

    companion object {
        val DISABLED = MqttStatus(connected = false, state = State.DISABLED, lastError = null)
    }
}
