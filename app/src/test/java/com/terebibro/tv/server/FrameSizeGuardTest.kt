package com.terebibro.tv.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class FrameSizeGuardTest {

    private fun frame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        masked: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var first = opcode and 0x0F
        if (fin) first = first or 0x80
        out.write(first)
        val maskBit = if (masked) 0x80 else 0x00
        when {
            payload.size <= 125 -> out.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                var shift = 56
                while (shift >= 0) {
                    out.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
                    shift -= 8
                }
            }
        }
        if (masked) out.write(ByteArray(4))
        out.write(payload)
        return out.toByteArray()
    }

    /** Header-only frame with a declared 64-bit payload length. */
    private fun header64(opcode: Int, fin: Boolean, length: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var first = opcode and 0x0F
        if (fin) first = first or 0x80
        out.write(first)
        out.write(127)
        var shift = 56
        while (shift >= 0) {
            out.write(((length ushr shift) and 0xFF).toInt())
            shift -= 8
        }
        return out.toByteArray()
    }

    private fun drain(stream: InputStream) {
        while (stream.read() >= 0) {
            // consume
        }
    }

    @Test
    fun `payloads at the cap pass through unchanged`() {
        val data = frame(0x1, "hello".toByteArray(), masked = false)
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(data))
        assertArrayEquals(data, guard.readBytes())
    }

    @Test
    fun `masked payloads pass through unchanged`() {
        val data = frame(0x1, "hello".toByteArray(), masked = true)
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(data))
        assertArrayEquals(data, guard.readBytes())
    }

    @Test
    fun `declared 64 bit payload over the cap throws before allocation`() {
        val data = header64(0x2, fin = true, length = (FrameSizeGuardInputStream.MAX_PAYLOAD_BYTES + 1).toLong())
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(data))
        assertThrows(IOException::class.java) { drain(guard) }
    }

    @Test
    fun `declared payload exactly at the cap does not throw`() {
        val data = header64(0x2, fin = true, length = FrameSizeGuardInputStream.MAX_PAYLOAD_BYTES.toLong())
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(data))
        drain(guard)
    }

    @Test
    fun `oversized control frame throws`() {
        val out = ByteArrayOutputStream()
        out.write(0x89) // fin + ping
        out.write(126)
        out.write(0)
        out.write(200) // control frames may not exceed 125 bytes
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(out.toByteArray()))
        assertThrows(IOException::class.java) { drain(guard) }
    }

    @Test
    fun `fragmented total over the cap throws`() {
        val chunk = 40_000
        val out = ByteArrayOutputStream()
        out.write(frame(0x1, ByteArray(chunk), fin = false).inputStream().readBytes())
        out.write(frame(0x0, ByteArray(chunk), fin = true).inputStream().readBytes())
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(out.toByteArray()))
        assertThrows(IOException::class.java) { drain(guard) }
    }

    @Test
    fun `small fragmented message below the cap does not throw`() {
        val out = ByteArrayOutputStream()
        out.write(frame(0x1, ByteArray(100), fin = false).inputStream().readBytes())
        out.write(frame(0x0, ByteArray(100), fin = true).inputStream().readBytes())
        val guard = FrameSizeGuardInputStream(ByteArrayInputStream(out.toByteArray()))
        drain(guard)
        assertEquals(-1, guard.read())
    }
}
