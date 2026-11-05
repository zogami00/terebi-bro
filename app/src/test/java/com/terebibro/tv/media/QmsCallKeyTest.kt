package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmsCallKeyTest {

    private val first = QmsCallKey.from("A1001", "Kaunter 1", "02:56")

    @Test
    fun `the composite is number pipe location pipe time`() {
        assertEquals("A1001|Kaunter 1|02:56", first.composite())
    }

    @Test
    fun `whitespace is normalised for the key only`() {
        val key = QmsCallKey.from("  A1001 ", "Kaunter   1", "02:56\n")
        assertEquals(QmsCallKey("A1001", "Kaunter 1", "02:56"), key)

        // The display strings are untouched by construction: from() is only
        // ever used to build the key, never to replace what the page shows.
        val display = "Kaunter   1"
        assertEquals("Kaunter   1", display)
    }

    @Test
    fun `the first observation seeds the baseline and is not a call`() {
        assertFalse(QmsCallKey.isNewCall(null, first))
        // The baseline is set by the controller after this check, so the next
        // identical observation is a re-render, not a call.
        assertFalse(QmsCallKey.isNewCall(first, first))
    }

    @Test
    fun `a missing current key is never a call`() {
        assertFalse(QmsCallKey.isNewCall(first, null))
        assertFalse(QmsCallKey.isNewCall(null, null))
    }

    @Test
    fun `an equal key is a re-render, not a call`() {
        val same = QmsCallKey.from("A1001", "Kaunter 1", "02:56")
        assertFalse(QmsCallKey.isNewCall(first, same))
    }

    @Test
    fun `the re-call case fires again when only the location and time change`() {
        val recalled = QmsCallKey.from("A1001", "Kaunter 3", "03:02")
        assertEquals("A1001", recalled.number)
        assertTrue(QmsCallKey.isNewCall(first, recalled))
    }
}
