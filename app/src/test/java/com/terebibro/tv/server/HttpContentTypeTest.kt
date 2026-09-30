package com.terebibro.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpContentTypeTest {

    @Test
    fun `accepts json with and without charset`() {
        assertTrue(Http.isJsonContentType("application/json"))
        assertTrue(Http.isJsonContentType("application/json; charset=utf-8"))
        assertTrue(Http.isJsonContentType("  APPLICATION/JSON ; CHARSET=UTF-8  "))
    }

    @Test
    fun `rejects anything else`() {
        assertFalse(Http.isJsonContentType(null))
        assertFalse(Http.isJsonContentType(""))
        assertFalse(Http.isJsonContentType("text/plain"))
        assertFalse(Http.isJsonContentType("application/json; charset=iso-8859-1"))
        assertFalse(Http.isJsonContentType("application/jsonx"))
        assertFalse(Http.isJsonContentType("application/json; boundary=x"))
    }
}
