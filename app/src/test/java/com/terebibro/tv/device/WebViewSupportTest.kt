package com.terebibro.tv.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebViewSupport] is the pure-JVM seam for the advisory WebView warning. The
 * critical property is that an unknown version must never be reported as
 * outdated: the app warns only on evidence of a genuinely old Chromium.
 */
class WebViewSupportTest {

    // --------------------------------------------------------------- majorOf

    @Test
    fun `majorOf reads the leading component`() {
        assertEquals(90, WebViewSupport.majorOf("90.0.4430.91"))
        assertEquals(138, WebViewSupport.majorOf("138.0.7204.157"))
    }

    @Test
    fun `majorOf tolerates whitespace and a dotless version`() {
        assertEquals(90, WebViewSupport.majorOf("  90.0.4430.91  "))
        assertEquals(90, WebViewSupport.majorOf("90"))
        assertEquals(100, WebViewSupport.majorOf("\t100.0.0.0"))
    }

    @Test
    fun `majorOf returns null for unknown input`() {
        assertNull(WebViewSupport.majorOf(null))
        assertNull(WebViewSupport.majorOf(""))
        assertNull(WebViewSupport.majorOf("   "))
        assertNull(WebViewSupport.majorOf("not-a-version"))
    }

    // ------------------------------------------------------------ isOutdated

    @Test
    fun `a current WebView is not outdated`() {
        assertFalse(WebViewSupport.isOutdated("138.0.7204.157"))
    }

    @Test
    fun `the reported emulator WebView is outdated`() {
        assertTrue(WebViewSupport.isOutdated("90.0.4430.91"))
    }

    @Test
    fun `the floor itself is not outdated`() {
        assertFalse(WebViewSupport.isOutdated("100.0.0.0"))
    }

    @Test
    fun `one below the floor is outdated`() {
        assertTrue(WebViewSupport.isOutdated("99.9.9.9"))
    }

    @Test
    fun `a dotless old version is outdated`() {
        assertTrue(WebViewSupport.isOutdated("90"))
    }

    @Test
    fun `unknown versions never warn`() {
        assertFalse(WebViewSupport.isOutdated(null))
        assertFalse(WebViewSupport.isOutdated(""))
        assertFalse(WebViewSupport.isOutdated("  "))
        assertFalse(WebViewSupport.isOutdated("not-a-version"))
    }

    @Test
    fun `the floor is overridable`() {
        assertTrue(WebViewSupport.isOutdated("90.0.4430.91", floor = 91))
        assertFalse(WebViewSupport.isOutdated("90.0.4430.91", floor = 90))
    }
}
