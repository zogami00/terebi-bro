package com.terebibro.tv.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUrlMatcherTest {

    @Test
    fun `an empty path and a trailing slash are equivalent`() {
        assertTrue(HomeUrlMatcher.samePage("https://example.com", "https://example.com/"))
        assertTrue(HomeUrlMatcher.samePage("https://example.com/", "https://example.com"))
        assertTrue(HomeUrlMatcher.samePage("https://example.com/home/", "https://example.com/home"))
    }

    @Test
    fun `a leading www prefix is ignored`() {
        assertTrue(HomeUrlMatcher.samePage("https://www.example.com/", "https://example.com"))
        assertTrue(HomeUrlMatcher.samePage("https://example.com/home", "https://www.example.com/home"))
    }

    @Test
    fun `scheme and host are compared case insensitively`() {
        assertTrue(HomeUrlMatcher.samePage("HTTPS://Example.COM/", "https://example.com"))
    }

    @Test
    fun `default ports are equivalent to no port`() {
        assertTrue(HomeUrlMatcher.samePage("http://example.com:80/", "http://example.com"))
        assertTrue(HomeUrlMatcher.samePage("https://example.com:443/", "https://example.com"))
    }

    @Test
    fun `non-default ports still distinguish pages`() {
        assertFalse(HomeUrlMatcher.samePage("https://example.com:8443", "https://example.com"))
        assertFalse(HomeUrlMatcher.samePage("https://example.com:8443", "https://example.com:9443"))
    }

    @Test
    fun `different paths are different pages`() {
        assertFalse(HomeUrlMatcher.samePage("https://example.com/status", "https://example.com/"))
        assertFalse(HomeUrlMatcher.samePage("https://example.com/a", "https://example.com/b"))
    }

    @Test
    fun `different hosts or schemes are different pages`() {
        assertFalse(HomeUrlMatcher.samePage("https://other.example.com", "https://example.com"))
        assertFalse(HomeUrlMatcher.samePage("http://example.com", "https://example.com"))
    }

    @Test
    fun `blank and non http values never match`() {
        assertFalse(HomeUrlMatcher.samePage(null, "https://example.com"))
        assertFalse(HomeUrlMatcher.samePage("https://example.com", null))
        assertFalse(HomeUrlMatcher.samePage("", ""))
        assertFalse(HomeUrlMatcher.samePage("about:blank", "about:blank"))
    }
}
