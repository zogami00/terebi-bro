package com.terebibro.tv.server

import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The controller document is served twice: as an HTTP header and as a `<meta>`
 * tag in `index.html`. The browser enforces the intersection, so a drift
 * silently narrows (or widens) the policy. This test pins them together.
 */
class CspAgreementTest {

    private fun indexHtml(): File {
        val candidates = listOf(
            File("src/main/assets/controller/index.html"),
            File("app/src/main/assets/controller/index.html")
        )
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue("index.html not found from ${File(".").absolutePath}", found != null)
        return found!!
    }

    @Test
    fun `the served header and the meta tag are identical`() {
        val html = indexHtml().readText()
        val match = Regex(
            """http-equiv="Content-Security-Policy"\s+content="([^"]*)""""
        ).find(html)
        assumeTrue("no CSP meta tag in index.html", match != null)
        val meta = match!!.groupValues[1]

        assertEquals(Http.HTML_CSP, meta)

        val served = Http.staticResponse(Response.Status.OK, "text/html; charset=utf-8", "")
            .getHeader("Content-Security-Policy")
        assertEquals("served header must equal the meta tag", meta, served)
    }
}
