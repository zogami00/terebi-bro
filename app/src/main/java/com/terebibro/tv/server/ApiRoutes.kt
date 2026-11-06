package com.terebibro.tv.server

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.device.DeviceInfo
import com.terebibro.tv.media.MediaFileInfo
import com.terebibro.tv.media.MediaFileRules
import com.terebibro.tv.media.MediaLayout
import com.terebibro.tv.media.MediaMode
import com.terebibro.tv.media.MediaSettings
import com.terebibro.tv.media.MediaSettingsValidator
import com.terebibro.tv.media.MediaStore
import com.terebibro.tv.media.MediaUpdate
import com.terebibro.tv.media.MediaUrl
import com.terebibro.tv.mqtt.MqttSettingsValidator
import com.terebibro.tv.mqtt.MqttUpdate
import com.terebibro.tv.net.ClientNetworks
import com.terebibro.tv.security.PairedToken
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import com.terebibro.tv.web.UrlValidator
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The exact REST surface from the V1 spec.
 *
 * Security checks run in a fixed order: peer, route/method, Host, Origin, CSRF,
 * body framing, Bearer token, rate limit, then the body is read and parsed as
 * strict JSON. Every response sent before the request body has been fully
 * consumed closes the connection, so a rejected body can never be re-parsed as
 * a smuggled request.
 */
class ApiRoutes(
    private val auth: AuthManager,
    private val config: ConfigStore,
    private val deviceInfo: DeviceInfo,
    private val host: ControllerHost,
    private val wsHub: WsHub,
    private val assets: AssetSource,
    private val mediaStore: MediaStore,
    private val canBindPort: (Int) -> Boolean,
    private val onPortChanged: () -> Unit
) {

    private class Route(
        val method: Method,
        val authRequired: Boolean,
        val allowedKeys: Set<String>,
        /** Keys whose value is a string array rather than a scalar. */
        val arrayKeys: Set<String> = emptySet(),
        val isDpad: Boolean = false,
        /**
         * A binary body: framed by [BodyFraming.decideUpload] and streamed by the
         * handler instead of being read as JSON.
         */
        val binary: Boolean = false,
        /** Uses the dedicated 6/min upload bucket instead of the shared API one. */
        val uploadLimit: Boolean = false,
        val handler: (IHTTPSession, PairedToken?, Map<String, Json.Value>) -> Response
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Keyed by `method to path` so one path may serve more than one method
     * (`/api/settings/networks` is both GET and POST). An unknown path is 404;
     * a known path with the wrong method is 405.
     */
    private val routes: Map<Pair<Method, String>, Route> = buildMap {
        put(Method.GET to "/", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("index.html") }))
        put(Method.GET to "/app.js", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("app.js") }))
        put(Method.GET to "/app.css", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("app.css") }))

        put(Method.GET to "/api/info", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> info() }))
        put(Method.POST to "/api/pair", Route(Method.POST, false, setOf("pin", "clientName"), handler = { _, _, v -> pair(v) }))

        put(Method.POST to "/api/unpair", Route(Method.POST, true, emptySet(), handler = { _, t, _ -> unpair(t) }))
        put(Method.GET to "/api/state", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> state() }))
        put(Method.GET to "/api/device", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> device() }))

        put(Method.POST to "/api/nav/open", Route(Method.POST, true, setOf("url", "setHome"), handler = { _, _, v -> navOpen(v) }))
        put(Method.POST to "/api/home", Route(Method.POST, true, setOf("url"), handler = { _, _, v -> setHome(v) }))
        put(Method.POST to "/api/nav/home", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("home") }))
        put(Method.POST to "/api/nav/back", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("back") }))
        put(Method.POST to "/api/nav/forward", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("forward") }))
        put(Method.POST to "/api/nav/reload", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("reload") }))
        put(Method.POST to "/api/nav/stop", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("stop") }))

        put(Method.POST to "/api/webview/restart", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> restart() }))
        put(Method.POST to "/api/webview/clear-cache", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> clearCache() }))
        put(Method.POST to "/api/webview/clear-site-data", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> clearSiteData() }))

        put(Method.POST to "/api/display", Route(Method.POST, true, setOf("fullscreen", "keepAwake"), handler = { _, _, v -> display(v) }))
        put(Method.POST to "/api/dpad", Route(Method.POST, true, setOf("key"), isDpad = true, handler = { _, _, v -> dpad(v) }))
        put(Method.POST to "/api/text", Route(Method.POST, true, setOf("text", "submit"), handler = { _, _, v -> typeText(v) }))

        put(Method.POST to "/api/settings/device-name", Route(Method.POST, true, setOf("name"), handler = { _, _, v -> setDeviceName(v) }))
        put(Method.POST to "/api/settings/port", Route(Method.POST, true, setOf("port"), handler = { _, _, v -> setPort(v) }))
        put(Method.GET to "/api/settings/networks", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> networks() }))
        put(
            Method.POST to "/api/settings/networks",
            Route(
                Method.POST,
                true,
                setOf("networks"),
                arrayKeys = setOf("networks"),
                handler = { _, _, v -> setNetworks(v) }
            )
        )

        put(Method.GET to "/api/settings/mqtt", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> mqttSettings() }))
        put(
            Method.POST to "/api/settings/mqtt",
            Route(
                Method.POST,
                true,
                setOf("enabled", "host", "port", "tls", "username", "password", "baseTopic", "discoveryEnabled", "discoveryPrefix"),
                handler = { _, _, v -> setMqttSettings(v) }
            )
        )

        put(Method.GET to "/api/settings/media", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> mediaSettings() }))
        put(
            Method.POST to "/api/settings/media",
            Route(Method.POST, true, MEDIA_SETTING_KEYS, handler = { _, _, v -> setMediaSettings(v) })
        )
        put(Method.POST to "/api/media/mode", Route(Method.POST, true, setOf("mode"), handler = { _, _, v -> setMediaMode(v) }))
        put(Method.POST to "/api/media/open", Route(Method.POST, true, setOf("url", "web"), handler = { _, _, v -> mediaOpen(v) }))
        // Playback control: the same ControllerHost methods the MQTT actions and
        // the on-device menu use, so the controller needs no broker to pause or
        // skip a clip. Each is a no-op reporting `applied:false` while media
        // mode is off.
        put(Method.POST to "/api/media/next", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> respond(host.mediaNext()) }))
        put(Method.POST to "/api/media/play", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> respond(host.mediaPlay()) }))
        put(Method.POST to "/api/media/pause", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> respond(host.mediaPause()) }))
        // Forces a library re-read while the app is in the foreground. A push
        // that leaves the folder's mtime unchanged is otherwise invisible until
        // the next resume.
        put(Method.POST to "/api/media/rescan", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> mediaRescan() }))

        // Library. The upload is the one binary request; the JSON framing rule
        // would reject its content type, so it gets the upload framing instead.
        put(Method.GET to "/api/media/files", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> mediaFiles() }))
        put(
            Method.POST to "/api/media/upload",
            Route(
                Method.POST,
                true,
                emptySet(),
                binary = true,
                uploadLimit = true,
                handler = { s, _, _ -> mediaUpload(s) }
            )
        )
        put(
            Method.POST to "/api/media/delete",
            Route(Method.POST, true, setOf("name"), handler = { _, _, v -> mediaDelete(v) })
        )
        // Preview is a whole-file GET: binary framing, no JSON, no Range.
        put(
            Method.GET to "/api/media/file",
            Route(Method.GET, true, emptySet(), binary = true, handler = { s, _, _ -> mediaFile(s) })
        )

        put(Method.GET to "/ws", Route(Method.GET, false, emptySet(), handler = { _, _, _ ->
            Http.error(Response.Status.BAD_REQUEST, "websocket_required", "Use a WebSocket upgrade")
        }))
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    fun handle(session: IHTTPSession, peerIp: String?): Response {
        val path = session.uri ?: "/"
        val method = session.method

        // (1) exact method + route allowlist
        val route = routes[method to path]
        if (route == null) {
            val knownPath = routes.keys.any { it.second == path }
            return closeIfNeeded(
                session,
                if (knownPath) {
                    Http.error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed")
                } else {
                    Http.error(Response.Status.NOT_FOUND, "not_found", "Unknown route")
                }
            )
        }

        // (2) Host
        if (!auth.isHostAllowed(session.headers["host"])) return closeIfNeeded(session, Http.forbidden("host"))

        // (3) Origin: required on POST, optional but validated on GET
        val origin = session.headers["origin"]
        if (method == Method.POST) {
            if (!auth.isOriginAllowed(origin)) return closeIfNeeded(session, Http.forbidden("origin"))
        } else if (origin != null && !auth.isOriginAllowed(origin)) {
            return closeIfNeeded(session, Http.forbidden("origin"))
        }

        // (4) CSRF header on every /api/* request
        if (path.startsWith("/api/") && session.headers["x-terebi-csrf"] != "1") {
            return closeIfNeeded(
                session,
                Http.error(Response.Status.FORBIDDEN, "csrf", "Missing X-Terebi-CSRF header")
            )
        }

        // (5) body framing rules; the body itself is not read until authenticated
        val decision = if (route.binary) {
            BodyFraming.decideUpload(
                method = method.name,
                contentLengthHeader = session.headers["content-length"],
                transferEncodingHeader = session.headers["transfer-encoding"],
                maxBytes = MediaFileRules.Caps.MAX_BYTES
            )
        } else {
            BodyFraming.decide(
                method = method.name,
                jsonContentType = Http.isJsonContentType(session.headers["content-type"]),
                contentLengthHeader = session.headers["content-length"],
                transferEncodingHeader = session.headers["transfer-encoding"]
            )
        }
        if (decision is BodyFraming.Decision.Reject) {
            return closeIfNeeded(session, Http.error(statusFor(decision.status), decision.code, decision.message))
        }
        val declaredLength = if (decision is BodyFraming.Decision.Read) decision.length else -1

        // (6) Bearer token — before any body read
        val bearer = Http.bearerToken(session)
        val token = auth.authenticate(bearer)
        if (route.authRequired && token == null) {
            return closeIfNeeded(
                session,
                Http.error(Response.Status.UNAUTHORIZED, "unauthorized", "Missing or invalid token")
            )
        }

        // (7) rate limit — the upload route has its own 6/min bucket
        val allowed = if (route.uploadLimit) {
            auth.rateLimitUpload(token, peerIp)
        } else {
            auth.rateLimit(token, peerIp, route.isDpad)
        }
        if (!allowed) {
            return closeIfNeeded(
                session,
                Http.error(
                    Response.Status.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many requests",
                    retryAfterSec = if (route.uploadLimit) 10 else 1
                )
            )
        }

        // (8) read the body (only now that the peer is authenticated), then strict JSON
        val body: ByteArray? = if (!route.binary && declaredLength > 0) readBody(session, declaredLength) else null
        val values: Map<String, Json.Value> = if (body != null) {
            try {
                Json.parseObject(ByteArrayInputStream(body), route.allowedKeys, route.arrayKeys)
            } catch (e: Json.BadJson) {
                return Http.error(Response.Status.BAD_REQUEST, "bad_request", "Invalid JSON body")
            }
        } else {
            emptyMap()
        }

        return try {
            route.handler(session, token, values)
        } catch (e: Json.BadJson) {
            Http.error(Response.Status.BAD_REQUEST, "bad_request", "Invalid request body")
        } catch (e: Exception) {
            SafeLog.e(TAG, "Route handler failed: ${e.javaClass.simpleName}")
            Http.error(Response.Status.INTERNAL_ERROR, "internal_error", "Internal error")
        }
    }

    /**
     * A response returned before the request body is drained must terminate the
     * keep-alive connection; otherwise NanoHTTPD re-parses the leftover body as
     * a new request.
     */
    private fun closeIfNeeded(session: IHTTPSession, response: Response): Response {
        if (hasUnconsumedBody(session)) response.closeConnection(true)
        return response
    }

    private fun hasUnconsumedBody(session: IHTTPSession): Boolean =
        BodyFraming.hasUnconsumedBody(
            session.headers["content-length"],
            session.headers["transfer-encoding"]
        )

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    private fun staticFile(fileName: String): Response {
        val content = assets.read(fileName)
            ?: return Http.error(Response.Status.NOT_FOUND, "not_found", "Asset missing")
        return Http.staticResponse(Response.Status.OK, assets.mimeType(fileName), content)
    }

    private fun info(): Response {
        val obj = mapOf(
            "deviceName" to config.deviceName,
            "apiVersion" to 1,
            "appVersion" to deviceInfo.appVersion
        )
        return Http.ok(obj)
    }

    private fun pair(values: Map<String, Json.Value>): Response {
        val pin = Json.requireString(values, "pin", 16).trim()
        if (!pin.matches(PIN_REGEX)) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", "PIN must be 6 digits")
        }
        val clientName = Json.requireString(values, "clientName", 40).trim()
        if (clientName.isEmpty()) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", "clientName is required")
        }
        return when (val outcome = auth.pair(pin, clientName)) {
            is AuthManager.PairOutcome.Success -> {
                val obj = mapOf(
                    "token" to outcome.token,
                    "deviceName" to outcome.deviceName,
                    "apiVersion" to 1
                )
                Http.ok(obj)
            }
            is AuthManager.PairOutcome.Failure -> {
                Http.error(statusFor(outcome.status), outcome.code, outcome.message, outcome.retryAfterSec)
            }
        }
    }

    private fun unpair(token: PairedToken?): Response {
        if (token != null) {
            val persisted = auth.tokens.revokeByHash(token.sha256Hex)
            wsHub.closeForTokenHash(token.sha256Hex, "revoked")
            if (!persisted) {
                // The socket is still closed, but the revocation did not reach
                // durable storage; say so rather than pretending it landed.
                return Http.serviceUnavailable("storage_unavailable", "Could not persist revocation")
            }
        }
        return Http.ok()
    }

    private fun state(): Response {
        val snapshot = host.buildState() ?: return Http.serviceUnavailable()
        return Http.ok(mapOf("state" to StateJson.toJson(snapshot)))
    }

    private fun device(): Response {
        val snapshot = host.buildState() ?: return Http.serviceUnavailable()
        val mqtt = host.mqttStatus()
        val obj = mapOf(
            "deviceName" to snapshot.deviceName,
            "appVersion" to snapshot.appVersion,
            "androidVersion" to snapshot.androidVersion,
            "sdkInt" to snapshot.sdkInt,
            "webViewVersion" to snapshot.webViewVersion,
            "webViewOutdated" to snapshot.webViewOutdated,
            "ip" to snapshot.ip,
            "port" to snapshot.port,
            "pairedCount" to auth.pairedCount(),
            "mqttConnected" to mqtt.connected,
            "mqttDiscoveryTopics" to mqtt.discoveryTopics,
            "mqttDiscoveryError" to (mqtt.discoveryError ?: JSONObject.NULL),
            "mqttStatusError" to (mqtt.statusError ?: JSONObject.NULL),
            "extraNetworks" to JSONArray(snapshot.extraNetworks)
        )
        return Http.ok(obj)
    }

    private fun navOpen(values: Map<String, Json.Value>): Response {
        val url = Json.requireString(values, "url", 2048)
        val setHome = Json.optionalBool(values, "setHome") ?: false
        return respond(host.openUrl(url, setHome))
    }

    private fun setHome(values: Map<String, Json.Value>): Response {
        val url = Json.requireString(values, "url", 2048)
        return respond(host.setHomeUrl(url))
    }

    private fun nav(action: String): Response = respond(host.navAction(action))

    private fun restart(): Response {
        val result = respond(host.restartWebView())
        wsHub.broadcastState()
        return result
    }

    private fun clearCache(): Response {
        val result = respond(host.clearCache())
        wsHub.broadcastEvent("webview_restarted", "clear-cache")
        return result
    }

    private fun clearSiteData(): Response = respond(host.clearSiteData())

    private fun display(values: Map<String, Json.Value>): Response {
        val fullscreen = Json.optionalBool(values, "fullscreen")
        val keepAwake = Json.optionalBool(values, "keepAwake")
        if (fullscreen == null && keepAwake == null) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", "fullscreen or keepAwake required")
        }
        return respond(host.setDisplay(fullscreen, keepAwake))
    }

    private fun dpad(values: Map<String, Json.Value>): Response {
        val key = Json.requireString(values, "key", 16).lowercase()
        if (key !in DPAD_KEYS) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_key", "Unknown D-pad key")
        }
        return respond(host.dpad(key))
    }

    private fun typeText(values: Map<String, Json.Value>): Response {
        val text = Json.requireString(values, "text", 500)
        val submit = Json.optionalBool(values, "submit") ?: false
        return respond(host.typeText(text, submit))
    }

    private fun setDeviceName(values: Map<String, Json.Value>): Response {
        val raw = Json.requireString(values, "name", 40)
        val name = raw.trim()
        if (name.isEmpty() || name.length > 40 || name.any { it.code < 0x20 || it.code == 0x7F }) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", "Invalid device name")
        }
        return respond(host.setDeviceName(name))
    }

    private fun setPort(values: Map<String, Json.Value>): Response {
        val port = Json.requireInt(values, "port")
        if (port < 1024 || port > 65535) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", "Port must be 1024-65535")
        }
        // Probe the bind before persisting or telling the client it worked.
        if (port != config.controllerPort && !canBindPort(port)) {
            return Http.error(Response.Status.CONFLICT, "port_unavailable", "Port $port is not available")
        }
        val result = respond(host.setControllerPort(port))
        if (result.status == Response.Status.OK) {
            wsHub.broadcastEvent("port_changing", port.toString())
            mainHandler.postDelayed({ onPortChanged() }, 500L)
        }
        return result
    }

    private fun networks(): Response =
        Http.ok(mapOf("networks" to JSONArray(config.extraClientNetworks)))

    /**
     * Replaces the extra trusted networks. Every rejected entry is named with a
     * reason and the whole request is refused as `400 bad_request` — nothing is
     * silently dropped.
     */
    private fun setNetworks(values: Map<String, Json.Value>): Response {
        val raw = Json.requireStringList(values, "networks", MAX_NETWORK_ENTRIES, MAX_NETWORK_CHARS)
        val (accepted, rejected) = ClientNetworks.validateList(raw)
        if (rejected.isNotEmpty()) {
            return Http.error(
                Response.Status.BAD_REQUEST,
                "bad_request",
                "Rejected networks: ${rejected.joinToString("; ")}"
            )
        }
        config.extraClientNetworks = accepted
        wsHub.broadcastState()
        return Http.ok(mapOf("networks" to JSONArray(accepted)))
    }

    // ---------------------------------------------------------------------
    // MQTT settings
    // ---------------------------------------------------------------------

    /** The stored MQTT settings plus read-only status. Never returns the password. */
    private fun mqttSettings(): Response {
        val settings = config.mqttSettings
        val status = host.mqttStatus()
        return Http.ok(
            mapOf(
                "enabled" to settings.enabled,
                "host" to settings.host,
                "port" to settings.port,
                "tls" to settings.tls,
                "username" to settings.username,
                "baseTopic" to settings.baseTopic,
                "discoveryEnabled" to settings.discoveryEnabled,
                "discoveryPrefix" to settings.discoveryPrefix,
                "mqttPasswordSet" to settings.password.isNotEmpty(),
                "mqttConnected" to status.connected,
                "mqttState" to status.state.name.lowercase(),
                "mqttLastError" to status.lastError,
                "mqttDiscoveryTopics" to status.discoveryTopics,
                "mqttDiscoveryError" to status.discoveryError,
                "mqttStatusError" to status.statusError
            )
        )
    }

    /**
     * Updates the MQTT settings. Every supplied field is validated before
     * anything is written: a bad value is `400 bad_request` naming the field and
     * the stored settings are left untouched (never a partial write). The
     * password is write-only — send an empty string to clear it.
     */
    private fun setMqttSettings(values: Map<String, Json.Value>): Response {
        val update = try {
            MqttUpdate(
                enabled = Json.optionalBool(values, "enabled"),
                host = Json.optionalString(values, "host", MqttSettingsValidator.MAX_HOST),
                port = Json.optionalInt(values, "port"),
                tls = Json.optionalBool(values, "tls"),
                username = Json.optionalString(values, "username", MqttSettingsValidator.MAX_CREDENTIAL),
                password = Json.optionalString(values, "password", MqttSettingsValidator.MAX_CREDENTIAL),
                baseTopic = Json.optionalString(values, "baseTopic", MqttSettingsValidator.MAX_TOPIC),
                discoveryEnabled = Json.optionalBool(values, "discoveryEnabled"),
                discoveryPrefix = Json.optionalString(values, "discoveryPrefix", MqttSettingsValidator.MAX_TOPIC)
            )
        } catch (e: Json.BadJson) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", e.message ?: "Invalid request body")
        }

        return when (val validated = MqttSettingsValidator.validate(update)) {
            is MqttSettingsValidator.Result.Invalid ->
                Http.error(
                    Response.Status.BAD_REQUEST,
                    "bad_request",
                    "${validated.field}: ${validated.message}"
                )
            is MqttSettingsValidator.Result.Valid -> {
                config.applyMqttUpdate(validated.update)
                host.onMqttSettingsChanged()
                mqttSettings()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Media settings
    // ---------------------------------------------------------------------

    /** Every media setting plus the read-only runtime bits, never a secret. */
    private fun mediaSettings(): Response {
        val settings = host.mediaSettingsSnapshot()
        val state = host.buildState()
        val mode = state?.mediaMode ?: MediaMode.QMS_ONLY.wire
        val metrics = host.screenMetrics()
        val renderScale = MediaLayout.safeRenderScale(metrics.heightDp)
        return Http.ok(mediaFields(settings, mode, renderScale, state))
    }

    /**
     * Updates the media settings as a whole. The supplied subset is merged over
     * the stored settings and the merged result is validated, so the cross-field
     * rules (band sum, playlist URL) hold for a partial update too. One bad
     * field is `400` naming the field and nothing is written.
     */
    private fun setMediaSettings(values: Map<String, Json.Value>): Response {
        val update = try {
            MediaUpdate(
                enabled = Json.optionalBool(values, "mediaModeEnabled"),
                qmsUrl = Json.optionalString(values, "qmsUrl", UrlValidator.MAX_LENGTH),
                qmsStripSelector = Json.optionalString(
                    values, "qmsStripSelector", MediaSettingsValidator.MAX_SELECTOR_LENGTH
                ),
                qmsNumberSelector = Json.optionalString(
                    values, "qmsNumberSelector", MediaSettingsValidator.MAX_SELECTOR_LENGTH
                ),
                qmsLocationSelector = Json.optionalString(
                    values, "qmsLocationSelector", MediaSettingsValidator.MAX_SELECTOR_LENGTH
                ),
                qmsTimeSelector = Json.optionalString(
                    values, "qmsTimeSelector", MediaSettingsValidator.MAX_SELECTOR_LENGTH
                ),
                callSource = Json.optionalString(values, "callSource", MAX_ENUM_WIRE),
                dwellSec = Json.optionalInt(values, "dwellSec"),
                mediaSplitPercent = Json.optionalInt(values, "mediaSplitPercent"),
                headerBandPercent = Json.optionalInt(values, "headerBandPercent"),
                stripHeightPercent = Json.optionalInt(values, "stripHeightPercent"),
                currentNumberPx = Json.optionalInt(values, "currentNumberPx"),
                historyNumberPx = Json.optionalInt(values, "historyNumberPx"),
                labelPx = Json.optionalInt(values, "labelPx"),
                currentCardPx = Json.optionalInt(values, "currentCardPx"),
                historyCardMinPx = Json.optionalInt(values, "historyCardMinPx"),
                historyCardMaxPx = Json.optionalInt(values, "historyCardMaxPx"),
                uiLanguage = Json.optionalString(values, "uiLanguage", MAX_ENUM_WIRE),
                mediaLocalEnabled = Json.optionalBool(values, "mediaLocalEnabled"),
                playlistEnabled = Json.optionalBool(values, "playlistEnabled"),
                playlistUrl = Json.optionalString(values, "playlistUrl", UrlValidator.MAX_LENGTH),
                playlistRefreshSec = Json.optionalInt(values, "playlistRefreshSec"),
                mediaWebUrl = Json.optionalString(values, "mediaWebUrl", UrlValidator.MAX_LENGTH),
                mediaWebEnabled = Json.optionalBool(values, "mediaWebEnabled"),
                mediaSourceDefault = Json.optionalString(values, "mediaSourceDefault", MAX_ENUM_WIRE),
                imageDurationSec = Json.optionalInt(values, "imageDurationSec"),
                volumePercent = Json.optionalInt(values, "volumePercent"),
                mediaFillMode = Json.optionalString(values, "mediaFillMode", MAX_ENUM_WIRE),
                newCallHighlight = Json.optionalBool(values, "newCallHighlight"),
                uiScale = Json.optionalInt(values, "uiScale")
            )
        } catch (e: Json.BadJson) {
            return Http.error(Response.Status.BAD_REQUEST, "bad_request", e.message ?: "Invalid request body")
        }

        return when (val validated = MediaSettingsValidator.validate(update, config.mediaSettingsForUpdate)) {
            is MediaSettingsValidator.Result.Invalid ->
                Http.error(
                    Response.Status.BAD_REQUEST,
                    "bad_request",
                    "${validated.field}: ${validated.message}"
                )
            is MediaSettingsValidator.Result.Valid -> {
                config.applyMediaUpdate(validated.settings)
                host.onMediaSettingsChanged()
                mediaSettings()
            }
        }
    }

    /** A manual mode; `qms_focus` is the reducer's, not the operator's. */
    private fun setMediaMode(values: Map<String, Json.Value>): Response {
        val wire = Json.requireString(values, "mode", MAX_ENUM_WIRE).trim()
        val mode = MediaMode.manualOrNull(wire)
            ?: return Http.error(
                Response.Status.BAD_REQUEST,
                "bad_request",
                "mode must be qms_only, media_only or split"
            )
        return respond(host.setMediaMode(mode))
    }

    /** Plays an ad-hoc URL now; the same `UrlValidator` as `/api/nav/open`. */
    private fun mediaOpen(values: Map<String, Json.Value>): Response {
        val url = Json.requireString(values, "url", UrlValidator.MAX_LENGTH)
        // Omitted means today's behaviour: the native player.
        val web = Json.optionalBool(values, "web") ?: false
        return respond(host.openMediaUrl(url, web))
    }

    /**
     * The writable media settings plus the read-only `runtime` object. Read-only
     * values live in their own object so a client echoing what it read cannot
     * trip `POST`'s unknown-key rejection.
     */
    private fun mediaFields(
        settings: MediaSettings,
        mode: String,
        renderScale: Float,
        state: BrowserState? = null
    ): Map<String, Any?> =
        mapOf(
            "mediaModeEnabled" to settings.enabled,
            "qmsUrl" to settings.qmsUrl,
            "qmsStripSelector" to settings.qmsStripSelector,
            "qmsNumberSelector" to settings.qmsNumberSelector,
            "qmsLocationSelector" to settings.qmsLocationSelector,
            "qmsTimeSelector" to settings.qmsTimeSelector,
            "callSource" to settings.callSource.wire,
            "dwellSec" to settings.dwellSec,
            "mediaSplitPercent" to settings.mediaSplitPercent,
            "headerBandPercent" to settings.headerBandPercent,
            "stripHeightPercent" to settings.stripHeightPercent,
            "currentNumberPx" to settings.currentNumberPx,
            "historyNumberPx" to settings.historyNumberPx,
            "labelPx" to settings.labelPx,
            "currentCardPx" to settings.currentCardPx,
            "historyCardMinPx" to settings.historyCardMinPx,
            "historyCardMaxPx" to settings.historyCardMaxPx,
            "uiLanguage" to settings.uiLanguage.wire,
            "mediaLocalEnabled" to settings.mediaLocalEnabled,
            "playlistEnabled" to settings.playlistEnabled,
            "playlistUrl" to settings.playlistUrl,
            "playlistRefreshSec" to settings.playlistRefreshSec,
            // Masked like every other published URL, and never the stored
            // value itself: this is the convention `/api/settings/mqtt` follows
            // for `mqttPassword` (`mqttPasswordSet`, never the password). A
            // Jellyfin URL may carry a session token in its query.
            "mediaWebUrl" to MediaUrl.forPublish(settings.mediaWebUrl),
            "mediaWebUrlSet" to settings.mediaWebUrl.isNotEmpty(),
            "mediaWebEnabled" to settings.mediaWebEnabled,
            "mediaSourceDefault" to settings.mediaSourceDefault.wire,
            "imageDurationSec" to settings.imageDurationSec,
            "volumePercent" to settings.volumePercent,
            "mediaFillMode" to settings.mediaFillMode.wire,
            "newCallHighlight" to settings.newCallHighlight,
            "uiScale" to settings.uiScale,
            "runtime" to mapOf(
                "mode" to mode,
                "renderScale" to renderScale,
                // Read-only source diagnostics, mirrored from the state
                // broadcast so a fresh Media card shows them immediately.
                "mediaSource" to (state?.mediaSource ?: ""),
                // The queue size makes an empty library visible at a glance.
                "mediaCount" to (state?.mediaCount ?: 0),
                "playlistLastError" to (state?.playlistLastError ?: ""),
                "playlistLastFetchMs" to (state?.playlistLastFetchMs ?: 0L)
            )
        )

    // ---------------------------------------------------------------------
    // Media library
    // ---------------------------------------------------------------------

    /**
     * The library listing. Sizes are read from the files themselves; the
     * totals and the free-space figure are the same values the state publishes.
     */
    private fun mediaFiles(): Response = Http.ok(
        mapOf(
            "files" to mediaFilesJson(mediaStore.describe()),
            "totalBytes" to mediaStore.totalBytes(),
            "freeBytes" to mediaStore.freeBytes(),
            "maxFiles" to MediaFileRules.Caps.MAX_FILES,
            "maxTotalBytes" to MediaFileRules.Caps.MAX_TOTAL_BYTES
        )
    )

    private fun mediaFilesJson(files: List<MediaFileInfo>): JSONArray {
        val arr = JSONArray()
        for (file in files) {
            val obj = JSONObject()
            obj.put("name", file.name)
            obj.put("bytes", file.bytes)
            obj.put("kind", file.kind.name.lowercase())
            obj.put("modifiedMs", file.modifiedMs)
            arr.put(obj)
        }
        return arr
    }

    /**
     * Forces the media layer to re-read the library, then reports the queue it
     * now holds. The re-read itself is asynchronous: the controller hops to the
     * UI thread and walks the folder on its IO executor, so `count`/`source` are
     * the values published at the moment the request returns. The controller's
     * next state broadcast carries the settled figures, and the caller refreshes
     * the listing from `/api/media/files` independently.
     */
    private fun mediaRescan(): Response {
        host.onMediaLibraryChanged()
        val state = host.buildState()
        return Http.ok(
            mapOf(
                "ok" to true,
                "count" to (state?.mediaCount ?: 0),
                "source" to (state?.mediaSource ?: "")
            )
        )
    }

    /**
     * Streams one uploaded file to a `.part` file in the media folder. The
     * body is never buffered whole: only the first 4 KiB are kept for the magic
     * byte check. Quota and reserve are checked before the first byte.
     */
    private fun mediaUpload(session: IHTTPSession): Response {
        val name = requestedName(session)
            ?: return closeIfNeeded(
                session,
                Http.error(Response.Status.BAD_REQUEST, "bad_filename", "Invalid filename")
            )
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return closeIfNeeded(
                session,
                Http.error(Response.Status.LENGTH_REQUIRED, "length_required", "Content-Length required")
            )

        // One upload at a time: a second concurrent request would race the
        // quota arithmetic and the free-space reserve.
        if (!uploadInProgress.compareAndSet(false, true)) {
            return closeIfNeeded(
                session,
                Http.error(Response.Status.CONFLICT, "upload_in_progress", "Another upload is in progress")
            )
        }

        var temp: File? = null
        // The decode probe is advisory (see probeMediaFile); it is recorded
        // here so the success response can report what the device thought.
        var probe = ProbeOutcome.Failed
        try {
            // Media is only ever written to external app storage. A missing
            // volume is a 507, never a write beside SharedPreferences.
            val dir = mediaStore.dir()
                ?: return closeIfNeeded(
                    session,
                    Http.insufficientStorage("storage_unavailable", "Media storage is unavailable")
                )

            if (mediaStore.file(name) != null) {
                return closeIfNeeded(
                    session,
                    Http.error(Response.Status.CONFLICT, "duplicate_name", "A file with that name already exists")
                )
            }

            val verdict = MediaFileRules.accepts(
                count = mediaStore.describe().size,
                totalBytes = mediaStore.totalBytes(),
                freeBytes = mediaStore.freeBytes(),
                incoming = length
            )
            when (verdict) {
                MediaFileRules.Verdict.TooManyFiles -> return closeIfNeeded(
                    session,
                    Http.insufficientStorage("too_many_files", "The library is full")
                )
                MediaFileRules.Verdict.OverQuota -> return closeIfNeeded(
                    session,
                    Http.insufficientStorage("quota_exceeded", "Library quota exceeded")
                )
                MediaFileRules.Verdict.NoSpace -> return closeIfNeeded(
                    session,
                    Http.insufficientStorage("disk_full", "Not enough free space")
                )
                MediaFileRules.Verdict.TooLarge -> return closeIfNeeded(
                    session,
                    Http.error(Response.Status.PAYLOAD_TOO_LARGE, "payload_too_large", "File too large")
                )
                MediaFileRules.Verdict.Ok -> Unit
            }

            val upload = File(dir, ".upload-${UUID.randomUUID()}.part")
            temp = upload
            when (val streamed = streamToFile(session, upload, length)) {
                is StreamOutcome.Mismatch -> {
                    // Bytes may remain on the connection: it must not be
                    // reused, or the tail could be parsed as a new request.
                    val mismatch = Http.error(
                        Response.Status.BAD_REQUEST,
                        "bad_request",
                        "Upload ended early or exceeded its declared length"
                    )
                    mismatch.closeConnection(true)
                    return mismatch
                }
                is StreamOutcome.Complete -> {
                    // The extension and the actual container must agree; this
                    // is the real content gate, not the extension alone.
                    if (!MediaFileRules.contentMatchesExtension(name, streamed.magic)) {
                        return Http.error(
                            Response.Status.UNSUPPORTED_MEDIA_TYPE,
                            "unsupported_media_type",
                            "File content does not match its extension"
                        )
                    }
                    // Advisory only: the retriever is unreliable on some
                    // devices (notably the API 28 emulator) and once refused a
                    // verified-good MP4. Refusing a valid file is worse than
                    // admitting one that later fails to play: a play failure is
                    // visible, recoverable and advances the queue, while a
                    // silent rejection looks like the feature is broken.
                    probe = probeMediaFile(upload)
                }
            }

            val item = try {
                mediaStore.upload(upload, name)
            } catch (e: MediaStore.DuplicateNameException) {
                return Http.error(
                    Response.Status.CONFLICT,
                    "duplicate_name",
                    "A file with that name already exists"
                )
            } catch (e: IOException) {
                return Http.error(
                    Response.Status.INTERNAL_ERROR,
                    "upload_failed",
                    "Could not store the upload"
                )
            }

            host.onMediaLibraryChanged()
            return Http.ok(
                mapOf(
                    "name" to item.name,
                    "bytes" to length,
                    "kind" to item.kind.name.lowercase(),
                    "probe" to probe.wire,
                    "totalBytes" to mediaStore.totalBytes(),
                    "freeBytes" to mediaStore.freeBytes()
                )
            )
        } finally {
            uploadInProgress.set(false)
            // On success the rename moved the file and this is a no-op; on
            // every failure path the partial file is removed here.
            temp?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }
    }

    /** Deletes by name; an unknown or unsafe name is a uniform 404. */
    private fun mediaDelete(values: Map<String, Json.Value>): Response {
        val raw = Json.requireString(values, "name", MAX_FILENAME_HEADER)
        val name = MediaFileRules.sanitize(raw)
            ?: return Http.error(Response.Status.NOT_FOUND, "not_found", "File not found")
        if (!mediaStore.delete(name)) {
            return Http.error(Response.Status.NOT_FOUND, "not_found", "File not found")
        }
        host.onMediaLibraryChanged()
        return Http.ok(mapOf("files" to mediaFilesJson(mediaStore.describe())))
    }

    /** Serves one stored file whole; preview is capped and never ranged. */
    private fun mediaFile(session: IHTTPSession): Response {
        val name = requestedName(session)
            ?: return Http.error(Response.Status.NOT_FOUND, "not_found", "File not found")
        val file = mediaStore.file(name)
            ?: return Http.error(Response.Status.NOT_FOUND, "not_found", "File not found")
        val length = file.length()
        if (length > MediaFileRules.Caps.PREVIEW_MAX_BYTES) {
            return Http.error(
                Response.Status.PAYLOAD_TOO_LARGE,
                "payload_too_large",
                "File is too large to preview"
            )
        }
        val contentType = MediaFileRules.contentTypeOf(name)
            ?: return Http.error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Unsupported type")
        return Http.binaryResponse(Response.Status.OK, contentType, length) { FileInputStream(file) }
    }

    /**
     * The percent-encoded filename header, decoded once and validated. The
     * 160-character bound applies to the encoded header; the sanitised name is
     * bounded again by [MediaFileRules.sanitize].
     */
    private fun requestedName(session: IHTTPSession): String? {
        val raw = session.headers["x-terebi-filename"] ?: return null
        if (raw.isEmpty() || raw.length > MAX_FILENAME_HEADER) return null
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            return null
        }
        return MediaFileRules.sanitize(decoded)
    }

    /** The result of streaming an upload body to its temporary file. */
    private sealed class StreamOutcome {
        /** Exactly the declared body was written; [magic] holds the sniff prefix. */
        data class Complete(val magic: ByteArray, val written: Long) : StreamOutcome()

        /** The body ended early, ran past its declared length, or hit the cap. */
        object Mismatch : StreamOutcome()
    }

    /**
     * Copies the request body to [target], retaining the first 4 KiB for
     * sniffing. The copy is bounded by `MAX_BYTES` and by the declared length;
     * the number of bytes actually written is counted and must equal
     * [declared]. A short body is a mismatch, and bytes that were already
     * buffered past the declared end also fail, because the caller must close
     * the connection rather than let the tail be parsed as a new request.
     */
    private fun streamToFile(session: IHTTPSession, target: File, declared: Long): StreamOutcome {
        val magic = ByteArray(SNIFF_BYTES)
        var magicLength = 0
        var written = 0L
        val cap = MediaFileRules.Caps.MAX_BYTES
        val limit = minOf(declared, cap)
        try {
            BufferedOutputStream(FileOutputStream(target)).use { out ->
                val buffer = ByteArray(64 * 1024)
                val input = session.inputStream
                while (written < limit) {
                    val want = minOf(buffer.size.toLong(), limit - written).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (magicLength < magic.size) {
                        val copy = minOf(read, magic.size - magicLength)
                        System.arraycopy(buffer, 0, magic, magicLength, copy)
                        magicLength += copy
                    }
                    written += read
                }
                if (written == declared && input.available() > 0) {
                    // The client declared less than it sent. The extra bytes are
                    // still buffered, so this connection cannot be kept alive.
                    return StreamOutcome.Mismatch
                }
            }
        } catch (e: IOException) {
            return StreamOutcome.Mismatch
        }
        return if (written == declared) {
            StreamOutcome.Complete(magic.copyOf(magicLength), written)
        } else {
            StreamOutcome.Mismatch
        }
    }

    /**
     * Advisory `MediaMetadataRetriever` check on the finished `.part` file.
     *
     * It is **not a gate**: [mediaUpload] accepts any file whose magic bytes
     * match its extension, whatever this returns. The retriever cannot
     * introspect every valid container on every device (the API 28 emulator
     * refuses a verified-good MP4 with `avc1`/`mp4a`), so an unreadable result
     * is reported to the operator as a warning rather than a rejection. It
     * runs on the server's request thread, never the UI thread.
     */
    private fun probeMediaFile(file: File): ProbeOutcome = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH))
                ?.toIntOrNull() ?: 0
            val height = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT))
                ?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if ((width > 0 && height > 0) || duration > 0L) {
                ProbeOutcome.Readable
            } else {
                ProbeOutcome.NotReadable
            }
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        ProbeOutcome.Failed
    }

    /** Advisory decode-probe result, reported as `probe` in the upload response. */
    internal enum class ProbeOutcome(val wire: String) {
        Readable("readable"),
        NotReadable("unreadable"),
        Failed("error")
    }

    /** One upload at a time, so a second request is a clean 409. */
    private val uploadInProgress = AtomicBoolean(false)

    private fun respond(result: ApiResult): Response = when (result) {
        is ApiResult.Ok -> Http.ok(result.fields)
        is ApiResult.Err -> Http.error(statusFor(result.status), result.code, result.message, result.retryAfterSec)
    }

    private fun statusFor(status: Int): Response.IStatus = when (status) {
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        405 -> Response.Status.METHOD_NOT_ALLOWED
        409 -> Response.Status.CONFLICT
        410 -> Response.Status.GONE
        411 -> Response.Status.LENGTH_REQUIRED
        413 -> Response.Status.PAYLOAD_TOO_LARGE
        415 -> Response.Status.UNSUPPORTED_MEDIA_TYPE
        429 -> Response.Status.TOO_MANY_REQUESTS
        503 -> Response.Status.SERVICE_UNAVAILABLE
        507 -> Http.INSUFFICIENT_STORAGE
        else -> Response.Status.INTERNAL_ERROR
    }

    private fun readBody(session: IHTTPSession, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        val stream = session.inputStream
        while (offset < length) {
            val read = stream.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }

    private companion object {
        const val TAG = "ApiRoutes"
        val PIN_REGEX = Regex("^[0-9]{6}$")
        val DPAD_KEYS = setOf("up", "down", "left", "right", "ok", "back")

        /** Generous per-request bounds; [ClientNetworks.validateList] owns the 16-entry cap. */
        const val MAX_NETWORK_ENTRIES = 64
        const val MAX_NETWORK_CHARS = 64

        const val MAX_ENUM_WIRE = 32

        /** Encoded `X-Terebi-Filename` bound, and the magic-byte prefix kept. */
        const val MAX_FILENAME_HEADER = 160
        const val SNIFF_BYTES = 4096

        val MEDIA_SETTING_KEYS = setOf(
            "mediaModeEnabled", "qmsUrl", "qmsStripSelector", "qmsNumberSelector",
            "qmsLocationSelector", "qmsTimeSelector", "callSource", "dwellSec",
            "mediaSplitPercent", "headerBandPercent", "stripHeightPercent",
            "currentNumberPx", "historyNumberPx", "labelPx", "currentCardPx",
            "historyCardMinPx", "historyCardMaxPx", "uiLanguage", "mediaLocalEnabled",
            "playlistEnabled", "playlistUrl", "playlistRefreshSec", "imageDurationSec",
            "volumePercent", "mediaFillMode", "newCallHighlight", "uiScale",
            "mediaWebUrl", "mediaWebEnabled", "mediaSourceDefault"
        )
    }
}
