package com.terebibro.tv.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject

/** Response helpers shared by the control routes and the WebSocket upgrade. */
object Http {

    const val JSON_MIME = "application/json; charset=utf-8"

    /** The controller holds a bearer token in localStorage, so lock the document down. */
    private const val HTML_CSP =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "connect-src 'self' ws: wss:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private const val NON_HTML_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"

    fun jsonResponse(json: String, status: Response.IStatus): Response =
        NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, json).withSecurityHeaders("no-store")

    fun ok(fields: Map<String, Any?> = emptyMap()): Response {
        val obj = JSONObject()
        obj.put("ok", true)
        for ((key, value) in fields) obj.put(key, value)
        return jsonResponse(obj.toString(), Response.Status.OK)
    }

    fun error(
        status: Response.IStatus,
        code: String,
        message: String,
        retryAfterSec: Long = 0
    ): Response {
        val error = JSONObject()
        error.put("code", code)
        error.put("message", message)
        val obj = JSONObject()
        obj.put("ok", false)
        obj.put("error", error)
        val response = jsonResponse(obj.toString(), status)
        if (retryAfterSec > 0) response.addHeader("Retry-After", retryAfterSec.toString())
        return response
    }

    fun forbidden(reason: String): Response =
        error(Response.Status.FORBIDDEN, "forbidden", reason)

    fun serviceUnavailable(code: String = "unavailable", message: String = "TV not ready"): Response =
        error(Response.Status.SERVICE_UNAVAILABLE, code, message)

    fun staticResponse(status: Response.IStatus, mime: String, body: String): Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, mime, body)
            .withSecurityHeaders("no-cache")
        response.addHeader("Content-Security-Policy", if (mime.contains("text/html")) HTML_CSP else NON_HTML_CSP)
        return response
    }

    /**
     * Adds the hardening headers common to every response. The cache policy
     * differs per response kind and is passed in by the caller.
     */
    private fun Response.withSecurityHeaders(cacheControl: String): Response {
        addHeader("Cache-Control", cacheControl)
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        return this
    }

    /** Extracts a Bearer token, returning null when absent or malformed. */
    fun bearerToken(session: IHTTPSession): String? {
        val header = session.headers["authorization"] ?: return null
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return null
        val token = header.substring(prefix.length).trim()
        return token.ifEmpty { null }
    }

    /** True for `application/json` optionally followed by `;charset=utf-8`. */
    fun isJsonContentType(value: String?): Boolean {
        if (value == null) return false
        val lower = value.trim().lowercase()
        if (lower == "application/json") return true
        val parts = lower.split(';')
        if (parts[0].trim() != "application/json") return false
        if (parts.size == 1) return true
        val params = parts.drop(1).map { it.trim() }
        return params.all { it.startsWith("charset=") && it.removePrefix("charset=").trim() == "utf-8" }
    }
}
