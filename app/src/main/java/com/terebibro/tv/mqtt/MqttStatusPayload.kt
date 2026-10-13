package com.terebibro.tv.mqtt

import com.terebibro.tv.server.StateJson
import com.terebibro.tv.web.BrowserState

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
 */
object MqttStatusPayload {

    /** The retained JSON status body for [state]. */
    fun build(state: BrowserState): String = StateJson.toJson(state).toString()
}
