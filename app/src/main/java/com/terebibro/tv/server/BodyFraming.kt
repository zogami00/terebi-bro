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
        val header = contentLengthHeader ?: return Decision.NoBody
        // An unreadable length means the body's extent is unknown: reject and
        // let the caller close, rather than leaving an unread tail on the socket.
        val length = header.toLongOrNull()
            ?: return Decision.Reject(400, "bad_request", "Invalid Content-Length")
        if (length != 0L) {
            return Decision.Reject(413, "payload_too_large", "Unexpected request body")
        }
        return Decision.NoBody
    }

    /**
     * Framing for the binary upload/preview routes. An upload is a POST whose
     * body is streamed straight to disk, so the JSON content-type rule of
     * [decide] does not apply; the length rules still do.
     *
     * Only a POST may carry a body. A non-POST route (the preview GET) is a
     * body-less read: a declared body or chunked encoding is refused exactly as
     * [decide] refuses it, so the binary path cannot smuggle a second request.
     *
     * Unlike [decide], a missing or unparseable length is `411 Length Required`
     * rather than `413`, because without a length the upload cannot be bounded
     * before the first byte is written.
     */
    fun decideUpload(
        method: String,
        contentLengthHeader: String?,
        transferEncodingHeader: String?,
        maxBytes: Long
    ): Decision {
        if (method == "POST") {
            if (transferEncodingHeader != null) {
                return Decision.Reject(413, "payload_too_large", "Chunked bodies are not accepted")
            }
            val header = contentLengthHeader
                ?: return Decision.Reject(411, "length_required", "Content-Length required")
            val length = header.toLongOrNull()
                ?: return Decision.Reject(411, "length_required", "Invalid Content-Length")
            if (length < 0) {
                return Decision.Reject(411, "length_required", "Invalid Content-Length")
            }
            if (length > maxBytes) {
                return Decision.Reject(413, "payload_too_large", "Body exceeds $maxBytes bytes")
            }
            return if (length == 0L) Decision.NoBody else Decision.Read(length.toInt())
        }

        if (transferEncodingHeader != null) {
            return Decision.Reject(413, "payload_too_large", "Chunked bodies are not accepted")
        }
        val header = contentLengthHeader ?: return Decision.NoBody
        val length = header.toLongOrNull()
            ?: return Decision.Reject(400, "bad_request", "Invalid Content-Length")
        if (length != 0L) {
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
        val header = contentLengthHeader ?: return false
        // Unparseable framing leaves an unknown amount unread: close.
        val length = header.toLongOrNull() ?: return true
        return length > 0
    }
}
