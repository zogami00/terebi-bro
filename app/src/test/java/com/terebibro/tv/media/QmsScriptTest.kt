package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmsScriptTest {

    private val settings = MediaSettings()
    private val origin = "https://qms.example.com"
    private val script = QmsScript.build(settings, origin)

    @Test
    fun `the three field selectors and the strip selector appear verbatim`() {
        assertTrue(script.contains("var STRIP = \"${settings.qmsStripSelector}\";"))
        assertTrue(script.contains("var NUMBER = \"${settings.qmsNumberSelector}\";"))
        assertTrue(script.contains("var LOCATION = \"${settings.qmsLocationSelector}\";"))
        assertTrue(script.contains("var TIME = \"${settings.qmsTimeSelector}\";"))
    }

    @Test
    fun `the origin gate returns before installing`() {
        assertTrue(script.contains("location.origin !== \"$origin\""))
        val gate = script.indexOf("location.origin !==")
        val install = script.indexOf("window.__terebiQmsInstalled = true")
        assertTrue(gate in 0 until install)
    }

    @Test
    fun `installing twice is a no-op`() {
        assertTrue(script.contains("window.__terebiQmsInstalled === true"))
        assertTrue(script.contains("window.__terebiQmsInstalled = true"))
    }

    @Test
    fun `a probe transition inside the app clamp window is re-posted on the next tick`() {
        // Two state changes inside 500 ms: the app's clamp may drop the second,
        // but forceProbe posts the current state unconditionally every second,
        // so the dropped state is delivered on the next tick rather than lost.
        assertTrue(script.contains("function forceProbe()"))
        assertTrue(script.contains(
            "setInterval(function () { observe(); report(); forceProbe(); }, 1000);"
        ))
        // The dedupe key advances only when the post actually happened.
        assertTrue(script.contains("if (post(\"probe\", state)) { lastProbe = state; }"))
    }

    @Test
    fun `the observer and the one second fallback are both installed`() {
        assertTrue(script.contains("new MutationObserver"))
        assertTrue(script.contains("}, 1000);"))
    }

    @Test
    fun `the ready and probe signals are posted`() {
        assertTrue(script.contains("post(\"ready\", \"1\")"))
        assertTrue(script.contains("post(\"probe\""))
        assertTrue(script.contains("\"ok\"") && script.contains("\"missing\""))
    }

    @Test
    fun `an empty list is a third, healthy probe state`() {
        // The container resolving with no cards is not a selector failure: the
        // board is simply awaiting the first call of the day.
        assertTrue(script.contains("\"empty\""))
        assertTrue(script.contains("function probeState()"))
        assertTrue(script.contains("if (!list) { return \"missing\"; }"))
        assertTrue(script.contains("if (!list.children || !list.children.length) { return \"empty\"; }"))
        // The probe reports the state it found, not a boolean.
        assertTrue(script.contains("var state = probeState();"))
    }

    @Test
    fun `now is re-posted every tick with no client-side dedupe`() {
        // The app clamps repeats; a dedupe here would lose a call that the
        // 500 ms clamp dropped, because the payload never changes again.
        assertFalse(script.contains("lastNow"))
        assertFalse(script.contains("payload === "))
        assertTrue(script.contains(
            "post(\"now\", number + \"\\u001f\" + location + \"\\u001f\" + time);"
        ))
    }

    @Test
    fun `the audio hook is present but separately guarded`() {
        assertTrue(script.contains("signalAudio"))
        assertTrue(script.contains("HTMLMediaElement.prototype.play"))
        assertTrue(script.contains("createBufferSource"))
        // Three independent try blocks: one failed patch must not stop the DOM
        // watch.
        assertTrue(script.contains("window.Audio"))
    }

    @Test
    fun `the installed script exposes a re-probe hook`() {
        assertTrue(script.contains("window.__terebiQmsProbe = forceProbe;"))
        // The hook lives inside the origin-guarded install block: a page the
        // script never installed on cannot be probed.
        val install = script.indexOf("window.__terebiQmsInstalled = true")
        assertTrue(script.indexOf("window.__terebiQmsProbe = forceProbe") > install)
        // A second evaluation of the whole script exits on the guard first, so
        // re-running it cannot duplicate the hook, the observer or the timer.
        assertTrue(script.indexOf("window.__terebiQmsInstalled === true") < install)
    }

    @Test
    fun `probeCall re-probes only an installed script and is otherwise a no-op`() {
        val call = QmsScript.probeCall()
        assertTrue(call.contains("typeof window.__terebiQmsProbe === \"function\""))
        assertTrue(call.contains("window.__terebiQmsProbe();"))
        // Self-contained and inert: no markup and no origin assumption, so it
        // is safe to evaluate on any page the operator navigated to.
        assertFalse(call.contains("<"))
        assertFalse(call.contains("location.origin"))
    }

    @Test
    fun `a hostile selector cannot break out of its literal`() {
        val hostile = settings.copy(qmsStripSelector = "main > div[data-x=\"a\\b\"]</script>")
        val built = QmsScript.build(hostile, origin)

        assertTrue(built.contains("var STRIP = \"main > div[data-x=\\\"a\\\\b\\\"]\\u003C/script>\";"))
        assertFalse(built.contains("</script>"))
        assertFalse(built.contains("data-x=\"a\\b\""))
    }

    @Test
    fun `a hostile origin cannot break out of its literal`() {
        val built = QmsScript.build(settings, "https://x\"><script>")
        assertTrue(built.contains("location.origin !== \"https://x\\\">\\u003Cscript>\""))
        assertFalse(built.contains("</script>"))
    }

    @Test
    fun `jsString escapes quotes backslashes and line separators`() {
        assertEquals("\"a\\\"b\"", QmsScript.jsString("a\"b"))
        assertEquals("\"a\\\\b\"", QmsScript.jsString("a\\b"))
        assertEquals("\"a\\nb\"", QmsScript.jsString("a\nb"))
        assertEquals("\"a\\u2028b\"", QmsScript.jsString("a\u2028b"))
        assertEquals("\"a\\u0001b\"", QmsScript.jsString("a\u0001b"))
    }

    @Test
    fun `originOf keeps the origin and omits a default port`() {
        assertEquals("https://qms.example.com", QmsScript.originOf("https://qms.example.com/a/b"))
        assertEquals("http://qms.example.com", QmsScript.originOf("http://QMS.Example.com:80/"))
        assertEquals("https://qms.example.com:8443", QmsScript.originOf("https://qms.example.com:8443"))
        assertEquals("", QmsScript.originOf("ftp://qms.example.com"))
        assertEquals("", QmsScript.originOf("   "))
        assertEquals("", QmsScript.originOf("not a url"))
    }
}
