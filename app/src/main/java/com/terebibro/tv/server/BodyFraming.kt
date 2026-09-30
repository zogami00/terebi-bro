package com.terebibro.tv.server

/**
 * Pure-JVM request-body framing rules, kept out of [ApiRoutes] so they can be
 * unit tested. Chunked bodies are always refused and a non-POST request must
 * not carry a body: both are request-smuggling vectors.
 */
object BodyFraming {

    const val MAX_BODY_BYTES = 8192L

    sealed class Decision {
        /** Nothing to read. */
        object NoBody : Decision()

        /** Read exactly [length] bytes. */
        data class Read(val length: Int) : Decision()

        /** Reject with the given HTTP status/code/message. */
        data class Reject(val status: Int, val code: String, val message: String) : Decision()
    }

    fun decide(
        method: String,
        jsonContentType: Boolean,
        contentLengthHeader: String?,
        transferEncodingHeader: String?,
        maxBytes: Long = MAX_BODY_BYTES
    ): Decision {
        if (method == "POST") {
            if (!jsonContentType) {
                return Decision.Reject(415, "unsupported_media_type", "Expected application/json")
            }
            if (transferEncodingHeader != null) {
                return Decision.Reject(413, "payload_too_large", "Chunked bodies are not accepted")
            }
            val header = contentLengthHeader
                ?: return Decision.Reject(413, "payload_too_large", "Content-Length required")
            val length = header.toLongOrNull()
                ?: return Decision.Reject(413, "payload_too_large", "Invalid Content-Length")
            if (length < 0 || length > maxBytes) {
                return Decision.Reject(413, "payload_too_large", "Body exceeds $maxBytes bytes")
            }
            return if (length == 0L) Decision.NoBody else Decision.Read(length.toInt())
        }

        if (transferEncodingHeader != null) {
            return Decision.Reject(413, "payload_too_large", "Chunked bodies are not accepted")
        }
        val length = contentLengthHeader?.toLongOrNull()
        if (length != null && length != 0L) {
            return Decision.Reject(413, "payload_too_large", "Unexpected request body")
        }
        return Decision.NoBody
    }

    /** True when an early response must close the keep-alive connection. */
    fun hasUnconsumedBody(
        contentLengthHeader: String?,
        transferEncodingHeader: String?
    ): Boolean {
        if (transferEncodingHeader != null) return true
        val length = contentLengthHeader?.toLongOrNull() ?: return false
        return length > 0
    }
}
