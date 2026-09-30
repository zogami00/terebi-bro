package com.terebibro.tv.server

import android.os.Handler
import android.os.Looper
import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.device.DeviceInfo
import com.terebibro.tv.security.PairedToken
import com.terebibro.tv.util.SafeLog
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream

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
    private val canBindPort: (Int) -> Boolean,
    private val onPortChanged: () -> Unit
) {

    private class Route(
        val method: Method,
        val authRequired: Boolean,
        val allowedKeys: Set<String>,
        val isDpad: Boolean = false,
        val handler: (IHTTPSession, PairedToken?, Map<String, Json.Value>) -> Response
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private val routes: Map<String, Route> = buildMap {
        put("/", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("index.html") }))
        put("/app.js", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("app.js") }))
        put("/app.css", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> staticFile("app.css") }))

        put("/api/info", Route(Method.GET, false, emptySet(), handler = { _, _, _ -> info() }))
        put("/api/pair", Route(Method.POST, false, setOf("pin", "clientName"), handler = { _, _, v -> pair(v) }))

        put("/api/unpair", Route(Method.POST, true, emptySet(), handler = { _, t, _ -> unpair(t) }))
        put("/api/state", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> state() }))
        put("/api/device", Route(Method.GET, true, emptySet(), handler = { _, _, _ -> device() }))

        put("/api/nav/open", Route(Method.POST, true, setOf("url", "setHome"), handler = { _, _, v -> navOpen(v) }))
        put("/api/home", Route(Method.POST, true, setOf("url"), handler = { _, _, v -> setHome(v) }))
        put("/api/nav/home", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("home") }))
        put("/api/nav/back", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("back") }))
        put("/api/nav/forward", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("forward") }))
        put("/api/nav/reload", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("reload") }))
        put("/api/nav/stop", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> nav("stop") }))

        put("/api/webview/restart", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> restart() }))
        put("/api/webview/clear-cache", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> clearCache() }))
        put("/api/webview/clear-site-data", Route(Method.POST, true, emptySet(), handler = { _, _, _ -> clearSiteData() }))

        put("/api/display", Route(Method.POST, true, setOf("fullscreen", "keepAwake"), handler = { _, _, v -> display(v) }))
        put("/api/dpad", Route(Method.POST, true, setOf("key"), isDpad = true, handler = { _, _, v -> dpad(v) }))

        put("/api/settings/device-name", Route(Method.POST, true, setOf("name"), handler = { _, _, v -> setDeviceName(v) }))
        put("/api/settings/port", Route(Method.POST, true, setOf("port"), handler = { _, _, v -> setPort(v) }))

        put("/ws", Route(Method.GET, false, emptySet(), handler = { _, _, _ ->
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
        val route = routes[path]
            ?: return closeIfNeeded(session, Http.error(Response.Status.NOT_FOUND, "not_found", "Unknown route"))
        if (method != route.method) {
            return closeIfNeeded(
                session,
                Http.error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed")
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
        val decision = BodyFraming.decide(
            method = method.name,
            jsonContentType = Http.isJsonContentType(session.headers["content-type"]),
            contentLengthHeader = session.headers["content-length"],
            transferEncodingHeader = session.headers["transfer-encoding"]
        )
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

        // (7) rate limit
        if (!auth.rateLimit(token, peerIp, route.isDpad)) {
            return closeIfNeeded(
                session,
                Http.error(Response.Status.TOO_MANY_REQUESTS, "rate_limited", "Too many requests", retryAfterSec = 1)
            )
        }

        // (8) read the body (only now that the peer is authenticated), then strict JSON
        val body: ByteArray? = if (declaredLength > 0) readBody(session, declaredLength) else null
        val values: Map<String, Json.Value> = if (body != null) {
            try {
                Json.parseObject(ByteArrayInputStream(body), route.allowedKeys)
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
        val obj = mapOf(
            "deviceName" to snapshot.deviceName,
            "appVersion" to snapshot.appVersion,
            "androidVersion" to snapshot.androidVersion,
            "sdkInt" to snapshot.sdkInt,
            "webViewVersion" to snapshot.webViewVersion,
            "ip" to snapshot.ip,
            "port" to snapshot.port,
            "pairedCount" to auth.pairedCount()
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
        413 -> Response.Status.PAYLOAD_TOO_LARGE
        415 -> Response.Status.UNSUPPORTED_MEDIA_TYPE
        429 -> Response.Status.TOO_MANY_REQUESTS
        503 -> Response.Status.SERVICE_UNAVAILABLE
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
    }
}
