package com.terebibro.tv.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject

/** Response helpers shared by the control routes and the WebSocket upgrade. */
object Http {

    const val JSON_MIME = "application/json; charset=utf-8"

    /** The controller holds a bearer token in localStorage, so lock the document down. */
    const val HTML_CSP =
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; " +
            "media-src 'self' blob:; connect-src 'self' ws: wss:; base-uri 'none'; " +
            "form-action 'none'; frame-ancestors 'none'"

    private const val NON_HTML_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"

    /**
     * NanoHTTPD 2.3.1 has no 507 in its fixed [Response.Status] enum, so the
     * storage-refusal status is defined here. It is used when a quota or the
     * free-space reserve would be breached, and when external storage is
     * missing; a `409` would wrongly suggest the request could be retried
     * unchanged.
     */
    val INSUFFICIENT_STORAGE: Response.IStatus = object : Response.IStatus {
        override fun getDescription(): String = "Insufficient Storage"
        override fun getRequestStatus(): Int = 507
    }

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

    /** 507: a storage quota, reserve or missing volume refuses the operation. */
    fun insufficientStorage(code: String, message: String): Response =
        error(INSUFFICIENT_STORAGE, code, message)

    /**
     * A fixed-length binary response streamed from [stream]. The caller owns
     * the file and closes it through NanoHTTPD's stream handling; no range
     * support is advertised because preview is a whole-file read.
     */
    fun binaryResponse(
        status: Response.IStatus,
        contentType: String,
        length: Long,
        stream: () -> java.io.InputStream
    ): Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, contentType, stream(), length)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Content-Disposition", "inline")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Accept-Ranges", "none")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("Referrer-Policy", "no-referrer")
        return response
    }

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
