package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QmsSignalGuardTest {

    private var now = 0L
    private val guard = QmsSignalGuard { now }

    @Test
    fun `every wire name parses and anything else is null`() {
        assertEquals(QmsSignalKind.NOW, QmsSignalKind.fromWire("now"))
        assertEquals(QmsSignalKind.PROBE, QmsSignalKind.fromWire("probe"))
        assertEquals(QmsSignalKind.AUDIO, QmsSignalKind.fromWire("audio"))
        assertEquals(QmsSignalKind.READY, QmsSignalKind.fromWire("ready"))
        assertEquals(QmsSignalKind.VISIBILITY, QmsSignalKind.fromWire("visibility"))
        assertNull(QmsSignalKind.fromWire("bogus"))
        assertNull(QmsSignalKind.fromWire("NOW"))
        assertNull(QmsSignalKind.fromWire("now "))
    }

    @Test
    fun `a null value is rejected`() {
        assertNull(guard.accept(QmsSignalKind.NOW, null))
    }

    @Test
    fun `values are truncated to sixty four characters`() {
        val accepted = guard.accept(QmsSignalKind.NOW, "a".repeat(100))
        assertEquals(64, accepted?.length)
    }

    @Test
    fun `control characters are stripped`() {
        val accepted = guard.accept(QmsSignalKind.PROBE, "o\u0000k\r\n\t\u007Fok")
        assertEquals("okok", accepted)
    }

    @Test
    fun `the now field separator is preserved`() {
        val accepted = guard.accept(QmsSignalKind.NOW, "A1001\u001fKaunter 1\u001f02:56")
        assertEquals("A1001\u001fKaunter 1\u001f02:56", accepted)
    }

    @Test
    fun `a signal within five hundred milliseconds is dropped`() {
        now = 1_000L
        assertEquals("1", guard.accept(QmsSignalKind.NOW, "1"))

        now = 1_499L
        assertNull(guard.accept(QmsSignalKind.NOW, "2"))

        now = 1_500L
        assertEquals("3", guard.accept(QmsSignalKind.NOW, "3"))
    }

    @Test
    fun `the clamp is per kind, not global`() {
        now = 1_000L
        assertEquals("1", guard.accept(QmsSignalKind.NOW, "1"))
        assertEquals("ok", guard.accept(QmsSignalKind.PROBE, "ok"))
        assertEquals("1", guard.accept(QmsSignalKind.READY, "1"))
    }

    @Test
    fun `a backwards clock cannot win over the previous signal`() {
        now = 1_000L
        guard.accept(QmsSignalKind.NOW, "1")
        now = 900L
        assertNull(guard.accept(QmsSignalKind.NOW, "2"))
    }
}
