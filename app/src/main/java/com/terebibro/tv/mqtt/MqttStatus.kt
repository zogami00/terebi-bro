package com.terebibro.tv.mqtt

/**
 * Read-only MQTT connection status surfaced to the controller and
 * `GET /api/device`. Never carries the password or the username.
 */
data class MqttStatus(
    val connected: Boolean,
    val state: State,
    /** The last connection error, already safe to display; null when there is none. */
    val lastError: String? = null,
    /**
     * How many Home Assistant discovery topics the last publish attempt wrote
     * successfully. Always equal to the number of entities when discovery is
     * healthy; a smaller value means the loop skipped entities.
     */
    val discoveryTopics: Int = 0,
    /**
     * A short, redacted reason for the last discovery publish failure, or null
     * when the last attempt published every entity. Never carries a credential.
     */
    val discoveryError: String? = null,
    /**
     * A short, redacted reason for the last retained publish failure, or null
     * when the last attempt succeeded. Covers both the retained `status` and the
     * retained `availability` publish: an availability failure leaves Home
     * Assistant showing the device offline, so — like [discoveryError] — it is
     * recorded rather than only logged.
     */
    val statusError: String? = null
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
