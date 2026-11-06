package com.terebibro.tv.media

import org.json.JSONObject

/**
 * Text typed on the phone controller, replacing the contents of the text box
 * that has focus on the media page. The system on-screen keyboard cannot be
 * driven from the controller (Android reserves key injection for system apps),
 * so the controller types straight into the page instead.
 */
object MediaText {

    /**
     * A script that replaces the focused field's text and evaluates to true,
     * or to false when no text field has focus. `execCommand` fires the page's
     * own input events, so search boxes react as if the text was typed.
     * [text] is embedded as a quoted JSON string, never as code.
     */
    fun script(text: String): String =
        "(function(){var e=document.activeElement;" +
            "if(!e||!(e.isContentEditable||/^(INPUT|TEXTAREA)$/.test(e.tagName)))return false;" +
            "document.execCommand('selectAll',false,null);" +
            "var t=${JSONObject.quote(text)};" +
            "document.execCommand(t?'insertText':'delete',false,t);" +
            "return true})()"
}
