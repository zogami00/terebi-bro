package com.terebibro.tv.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

/**
 * Bounds the declared size of incoming WebSocket frames before NanoWSD 2.3.1
 * allocates the payload.
 *
 * `WebSocketFrame.readPayload` performs `new byte[payloadLength]` straight from
 * the wire and only rejects lengths above `Int.MAX_VALUE`, so a ~2 GiB frame
 * kills the process with `OutOfMemoryError` on the read thread before the peer
 * has authenticated. This stream parses each frame header as it flows past and
 * throws [IOException] once the declared payload (or the accumulated fragment
 * total) exceeds [maxPayloadBytes], which NanoWSD handles like any other
 * malformed frame. All other bytes pass through unchanged.
 */
class FrameSizeGuardInputStream(
    private val delegate: InputStream,
    private val maxPayloadBytes: Int = MAX_PAYLOAD_BYTES
) : InputStream() {

    private enum class Phase { FIRST, LEN, EXT, MASK, PAYLOAD }

    private var phase = Phase.FIRST
    private var fin = false
    private var opcode = 0
    private var masked = false
    private var lengthBytes = 0
    private var extRead = 0
    private var payloadLength = 0L
    private var payloadRemaining = 0L
    private var maskRemaining = 0
    private var fragmentTotal = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b < 0) return -1
        process(b and 0xFF)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (phase == Phase.MASK) {
            val n = delegate.read(b, off, minOf(len, maskRemaining))
            if (n > 0) {
                maskRemaining -= n
                if (maskRemaining == 0) startPayload()
            }
            return n
        }
        if (phase == Phase.PAYLOAD) {
            val allowed = minOf(len.toLong(), payloadRemaining).toInt().coerceAtLeast(1)
            val n = delegate.read(b, off, allowed)
            if (n > 0) {
                payloadRemaining -= n
                if (payloadRemaining <= 0L) resetForNextFrame()
            }
            return n
        }
        // Header and extended-length bytes must be inspected one at a time.
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        return 1
    }

    private fun process(value: Int) {
        when (phase) {
            Phase.FIRST -> {
                fin = (value and 0x80) != 0
                opcode = value and 0x0F
                phase = Phase.LEN
            }
            Phase.LEN -> {
                masked = (value and 0x80) != 0
                val len = value and 0x7F
                when {
                    len <= 125 -> {
                        payloadLength = len.toLong()
                        afterLength()
                    }
                    len == 126 -> {
                        lengthBytes = 2
                        extRead = 0
                        payloadLength = 0L
                        phase = Phase.EXT
                    }
                    else -> {
                        lengthBytes = 8
                        extRead = 0
                        payloadLength = 0L
                        phase = Phase.EXT
                    }
                }
            }
            Phase.EXT -> {
                payloadLength = (payloadLength shl 8) or value.toLong()
                extRead++
                if (extRead == lengthBytes) afterLength()
            }
            Phase.MASK -> {
                maskRemaining--
                if (maskRemaining == 0) startPayload()
            }
            Phase.PAYLOAD -> {
                payloadRemaining--
                if (payloadRemaining <= 0L) resetForNextFrame()
            }
        }
    }

    private fun afterLength() {
        val isControl = (opcode and 0x08) != 0
        if (isControl && payloadLength > 125L) {
            throw IOException("WebSocket control frame payload too large")
        }
        if (payloadLength > maxPayloadBytes || payloadLength < 0L) {
            throw IOException("WebSocket frame payload exceeds $maxPayloadBytes bytes")
        }
        if (!isControl) {
            fragmentTotal += payloadLength
            if (fragmentTotal > maxPayloadBytes) {
                throw IOException("WebSocket fragmented message exceeds $maxPayloadBytes bytes")
            }
            if (fin) fragmentTotal = 0L
        }
        if (masked) {
            maskRemaining = 4
            phase = Phase.MASK
        } else {
            startPayload()
        }
    }

    private fun startPayload() {
        if (payloadLength <= 0L) {
            resetForNextFrame()
            return
        }
        payloadRemaining = payloadLength
        phase = Phase.PAYLOAD
    }

    private fun resetForNextFrame() {
        phase = Phase.FIRST
        fin = false
        opcode = 0
        masked = false
        lengthBytes = 0
        extRead = 0
        payloadLength = 0L
        payloadRemaining = 0L
        maskRemaining = 0
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    companion object {
        /** 64 KiB: far larger than any legitimate controller message. */
        const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}

/**
 * Delegating handshake session whose [getInputStream] returns a single
 * [FrameSizeGuardInputStream]. NanoWSD captures the stream once in its
 * `WebSocket` constructor, so the instance must be stable.
 */
internal class WebSocketGuardSession(
    private val delegate: NanoHTTPD.IHTTPSession
) : NanoHTTPD.IHTTPSession by delegate {

    private val guardedInput: InputStream by lazy { FrameSizeGuardInputStream(delegate.inputStream) }

    override fun getInputStream(): InputStream = guardedInput
}
