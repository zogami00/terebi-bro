package com.terebibro.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFramingTest {

    private fun reject(method: String, json: Boolean, length: String?, te: String?): BodyFraming.Decision.Reject {
        val decision = BodyFraming.decide(method, json, length, te)
        assertTrue("expected reject but was $decision", decision is BodyFraming.Decision.Reject)
        return decision as BodyFraming.Decision.Reject
    }

    @Test
    fun `post requires a json content type`() {
        assertEquals(415, reject("POST", json = false, length = "2", te = null).status)
    }

    @Test
    fun `chunked bodies are refused`() {
        assertEquals(413, reject("POST", json = true, length = null, te = "chunked").status)
    }

    @Test
    fun `post requires content length`() {
        assertEquals(413, reject("POST", json = true, length = null, te = null).status)
    }

    @Test
    fun `post rejects invalid and over length content length`() {
        assertEquals(413, reject("POST", json = true, length = "abc", te = null).status)
        assertEquals(413, reject("POST", json = true, length = "-1", te = null).status)
        assertEquals(413, reject("POST", json = true, length = "8193", te = null).status)
    }

    @Test
    fun `post with a valid length is read`() {
        val decision = BodyFraming.decide("POST", jsonContentType = true, contentLengthHeader = "10", transferEncodingHeader = null)
        assertTrue(decision is BodyFraming.Decision.Read)
        assertEquals(10, (decision as BodyFraming.Decision.Read).length)
    }

    @Test
    fun `post with zero length has no body`() {
        val decision = BodyFraming.decide("POST", jsonContentType = true, contentLengthHeader = "0", transferEncodingHeader = null)
        assertTrue(decision is BodyFraming.Decision.NoBody)
    }

    @Test
    fun `non post with a body is refused`() {
        assertEquals(413, reject("GET", json = false, length = "5", te = null).status)
        assertEquals(413, reject("GET", json = false, length = null, te = "chunked").status)
    }

    @Test
    fun `non post with zero or absent length has no body`() {
        assertTrue(
            BodyFraming.decide("GET", jsonContentType = false, contentLengthHeader = "0", transferEncodingHeader = null)
                is BodyFraming.Decision.NoBody
        )
        assertTrue(
            BodyFraming.decide("GET", jsonContentType = false, contentLengthHeader = null, transferEncodingHeader = null)
                is BodyFraming.Decision.NoBody
        )
    }

    @Test
    fun `unconsumed body detection`() {
        assertTrue(BodyFraming.hasUnconsumedBody("5", null))
        assertTrue(BodyFraming.hasUnconsumedBody(null, "chunked"))
        assertFalse(BodyFraming.hasUnconsumedBody("0", null))
        assertFalse(BodyFraming.hasUnconsumedBody(null, null))
    }

    // ------------------------------------------------------------------
    // Binary upload path (beside the untouched JSON path)
    // ------------------------------------------------------------------

    private fun uploadReject(
        method: String,
        length: String?,
        te: String?,
        maxBytes: Long = 1024L
    ): BodyFraming.Decision.Reject {
        val decision = BodyFraming.decideUpload(method, length, te, maxBytes)
        assertTrue("expected reject but was $decision", decision is BodyFraming.Decision.Reject)
        return decision as BodyFraming.Decision.Reject
    }

    @Test
    fun `upload of a valid length is read`() {
        val decision = BodyFraming.decideUpload("POST", "1024", null, maxBytes = 1024L)
        assertTrue(decision is BodyFraming.Decision.Read)
        assertEquals(1024, (decision as BodyFraming.Decision.Read).length)
    }

    @Test
    fun `upload with zero length has no body`() {
        assertTrue(
            BodyFraming.decideUpload("POST", "0", null, maxBytes = 1024L)
                is BodyFraming.Decision.NoBody
        )
    }

    @Test
    fun `upload requires a content length`() {
        assertEquals(411, uploadReject("POST", null, null).status)
        assertEquals(411, uploadReject("POST", "abc", null).status)
        assertEquals(411, uploadReject("POST", "-1", null).status)
    }

    @Test
    fun `upload over the cap is too large and the cap itself is accepted`() {
        assertEquals(413, uploadReject("POST", "1025", null).status)
        assertTrue(
            BodyFraming.decideUpload("POST", "1024", null, maxBytes = 1024L)
                is BodyFraming.Decision.Read
        )
    }

    @Test
    fun `chunked uploads are refused`() {
        assertEquals(413, uploadReject("POST", null, "chunked").status)
    }

    @Test
    fun `a non-post binary route is a body-less read`() {
        assertTrue(
            BodyFraming.decideUpload("GET", null, null, maxBytes = 1024L)
                is BodyFraming.Decision.NoBody
        )
        assertTrue(
            BodyFraming.decideUpload("GET", "0", null, maxBytes = 1024L)
                is BodyFraming.Decision.NoBody
        )
    }

    @Test
    fun `a non-post binary route refuses a body`() {
        assertEquals(413, uploadReject("GET", "5", null).status)
        assertEquals(413, uploadReject("GET", null, "chunked").status)
    }

    // ------------------------------------------------------------------
    // Unparseable framing (B7): refused on every method, and closed
    // ------------------------------------------------------------------

    @Test
    fun `a non-post route with an unparseable length is refused`() {
        assertEquals(400, reject("GET", json = false, length = "abc", te = null).status)
        assertEquals(400, uploadReject("GET", "abc", null).status)
    }

    @Test
    fun `an unparseable length always closes the connection`() {
        assertTrue(BodyFraming.hasUnconsumedBody("abc", null))
        assertTrue(BodyFraming.hasUnconsumedBody("", null))
        assertTrue(BodyFraming.hasUnconsumedBody(null, "chunked"))
    }
}
