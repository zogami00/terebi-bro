package com.terebibro.tv.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmsInjectionTest {

    @Test
    fun `an already-started page is evaluated even with document-start support`() {
        // The startup/restart ordering bug: the handler is installed after the
        // document was created, so document-start never reaches it.
        assertTrue(QmsInjection.shouldEvaluateOnInstall(documentStartSupported = true, pageStarted = true))
    }

    @Test
    fun `an already-started page is evaluated without document-start support`() {
        assertTrue(QmsInjection.shouldEvaluateOnInstall(documentStartSupported = false, pageStarted = true))
    }

    @Test
    fun `a document that has not started is left to document-start`() {
        assertFalse(QmsInjection.shouldEvaluateOnInstall(documentStartSupported = true, pageStarted = false))
        assertFalse(QmsInjection.shouldEvaluateOnInstall(documentStartSupported = false, pageStarted = false))
    }
}
