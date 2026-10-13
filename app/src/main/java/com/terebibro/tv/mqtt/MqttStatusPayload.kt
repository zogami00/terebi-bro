package com.terebibro.tv.mqtt

import com.terebibro.tv.server.StateJson
import com.terebibro.tv.web.BrowserState
import org.json.JSONObject

/**
 * Builds the retained `<base>/status` payload.
 *
 * It is deliberately **not** a second source of truth: the payload is the same
 * `BrowserState` object that `GET /api/state` and the WebSocket `state` message
 * serialise through [StateJson], which already carries everything the MQTT
 * contract asks for — `ip`, `port`, `webViewVersion`, `appVersion` and
 * `uptimeMs` (alongside the live URL/title/loading/progress fields). The MQTT
 * publisher is driven from the same state-change path, so the REST API, the
 * WebSocket and MQTT can never disagree.
 *
 * On top of the shared state it also carries the last discovery publish result
 * ([discoveryTopics] / [discoveryError]) and any retained `status`/`availability`
 * publish failure (`statusError`), so a partially published discovery pass or a
 * refused retained publish is observable from the broker without a logcat.
 */
object MqttStatusPayload {

    /**
     * The retained JSON status body for [state].
     *
     * [discoveryTopics] is how many discovery configs the last attempt wrote
     * successfully; [discoveryError] is the last failure reason (already
     * redacted), serialised as JSON `null` when there was none.
     * [statusError] records the last retained `status` or `availability` publish
     * failure so a publish that Paho refused (for example because the QoS-1
     * in-flight window was full, or a failed availability that leaves Home
     * Assistant showing the device offline) is visible rather than silently
     * dropped.
     */
    fun build(
        state: BrowserState,
        discoveryTopics: Int,
        discoveryError: String?,
        statusError: String? = null
    ): String {
        val obj = StateJson.toJson(state)
        obj.put("discoveryTopics", discoveryTopics)
        obj.put("discoveryError", discoveryError ?: JSONObject.NULL)
        obj.put("statusError", statusError ?: JSONObject.NULL)
        return obj.toString()
    }
}
