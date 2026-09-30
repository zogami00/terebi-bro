package com.terebibro.tv.server

/**
 * Result of an application-level operation requested over the control API.
 *
 * [Ok.fields] is merged into the `{"ok":true, ...}` success envelope;
 * [Err] becomes `{"ok":false,"error":{"code":...,"message":...}}`.
 */
sealed class ApiResult {

    data class Ok(val fields: Map<String, Any?> = emptyMap()) : ApiResult()

    data class Err(
        val status: Int,
        val code: String,
        val message: String,
        val retryAfterSec: Long = 0
    ) : ApiResult()

    companion object {
        fun ok(vararg fields: Pair<String, Any?>): ApiResult = Ok(mapOf(*fields))

        fun badRequest(code: String, message: String): ApiResult = Err(400, code, message)

        fun invalidUrl(message: String = "Invalid URL"): ApiResult = Err(400, "invalid_url", message)

        fun conflict(code: String, message: String): ApiResult = Err(409, code, message)

        fun unavailable(code: String = "unavailable", message: String = "TV not ready"): ApiResult =
            Err(503, code, message)
    }
}
