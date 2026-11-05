package com.terebibro.tv.web

/**
 * Pure decision for installing the QMS script into a document that may already
 * exist.
 *
 * A document-start handler only reaches documents created **after** it is
 * installed. When the page has already started (or finished) loading, its
 * document cannot have run a handler installed now — independently of whether
 * the platform supports document-start injection — so the script must be
 * evaluated once against the current document. The script's own
 * `__terebiQmsInstalled` guard makes a second evaluation harmless on pages
 * that did run it.
 */
object QmsInjection {

    /**
     * True when an install must immediately evaluate the script against the
     * page. [documentStartSupported] does not change the answer: it only says
     * whether future documents will get the handler, never whether the
     * document already on screen did.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldEvaluateOnInstall(documentStartSupported: Boolean, pageStarted: Boolean): Boolean =
        pageStarted
}
