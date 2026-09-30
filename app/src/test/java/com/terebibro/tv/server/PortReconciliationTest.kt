package com.terebibro.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortReconciliationTest {

    @Test
    fun `a matching bound port needs no write-back`() {
        assertNull(PortReconciliation.sync(9001, 9001))
    }

    @Test
    fun `a fallback or restored port is written back`() {
        assertEquals(8765, PortReconciliation.sync(9001, 8765))
        assertEquals(9001, PortReconciliation.sync(8765, 9001))
    }

    @Test
    fun `invalid and unbound ports are ignored`() {
        assertNull(PortReconciliation.sync(9001, -1))
        assertNull(PortReconciliation.sync(9001, 0))
        assertNull(PortReconciliation.sync(9001, 70000))
    }
}
