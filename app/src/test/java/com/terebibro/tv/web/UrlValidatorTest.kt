package com.terebibro.tv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    private fun assertValid(raw: String, expected: String) {
        val result = UrlValidator.validate(raw)
        assertTrue("expected valid for '$raw' but was $result", result is UrlValidator.Result.Valid)
        assertEquals(expected, (result as UrlValidator.Result.Valid).url)
    }

    private fun assertInvalid(raw: String) {
        val result = UrlValidator.validate(raw)
        assertTrue("expected invalid for '$raw' but was $result", result is UrlValidator.Result.Invalid)
    }

    @Test
    fun `accepts http and https`() {
        assertValid("http://example.com/", "http://example.com/")
        assertValid("https://example.com/", "https://example.com/")
        assertValid("https://example.com:8443/path?q=1", "https://example.com:8443/path?q=1")
    }

    @Test
    fun `prepends https when the scheme is missing`() {
        assertValid("example.com", "https://example.com")
        assertValid("example.com/path", "https://example.com/path")
    }

    @Test
    fun `rejects dangerous schemes`() {
        assertInvalid("javascript:alert(1)")
        assertInvalid("JavaScript:alert(1)")
        assertInvalid("data:text/html,<script>alert(1)</script>")
        assertInvalid("file:///etc/passwd")
        assertInvalid("content://media/external/images/1")
        assertInvalid("intent://scan/#Intent;scheme=zxing;end")
        assertInvalid("about:blank")
        assertInvalid("blob:https://example.com/1234")
        assertInvalid("chrome://settings")
    }

    @Test
    fun `rejects userinfo`() {
        assertInvalid("https://user@example.com/")
        assertInvalid("https://user:pass@example.com/")
    }

    @Test
    fun `rejects empty host`() {
        assertInvalid("http://")
        assertInvalid("https:///path")
        assertInvalid("https://:8080/")
    }

    @Test
    fun `rejects over length input`() {
        assertInvalid("https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH))
    }

    @Test
    fun `rejects interior whitespace and control characters`() {
        assertInvalid("https://exa mple.com")
        assertInvalid("https://example.com/a\tb")
        assertInvalid("https://example.com/a\u0000b")
        assertInvalid("https://example.com/\u007fb")
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertValid("  https://example.com/  ", "https://example.com/")
        assertValid("\thttps://example.com/\n", "https://example.com/")
    }

    @Test
    fun `rejects empty input`() {
        assertInvalid("")
        assertInvalid("   ")
    }

    @Test
    fun `rejects out of range ports`() {
        assertInvalid("https://example.com:0/")
        assertInvalid("https://example.com:70000/")
    }
}
