package com.terebibro.tv.media

import java.net.URI

/**
 * Builds the one self-contained script injected into the QMS page.
 *
 * The script only observes and reports. It never copies, rewrites or injects
 * the vendor's text: the strip is a stylesheet applied to the vendor's own DOM,
 * and the only strings the app contributes are its own five labels (see
 * [SignageStrings]).
 *
 * The origin check is the real gate: the WebView's `@JavascriptInterface` is
 * registered per WebView, not per origin, so a page other than the configured
 * QMS origin must be unable to reach it even by accident.
 */
object QmsScript {

    private const val SIGNAL = "terebiQms"

    fun build(settings: MediaSettings, origin: String): String {
        val originLiteral = jsString(origin)
        val strip = jsString(settings.qmsStripSelector)
        val number = jsString(settings.qmsNumberSelector)
        val location = jsString(settings.qmsLocationSelector)
        val time = jsString(settings.qmsTimeSelector)
        return """
            (function () {
              try {
                if (window.__terebiQmsInstalled === true) { return; }
                if (location.origin !== $originLiteral) { return; }
                window.__terebiQmsInstalled = true;

                var STRIP = $strip;
                var NUMBER = $number;
                var LOCATION = $location;
                var TIME = $time;

                function post(kind, value) {
                  try {
                    if (window.$SIGNAL && typeof window.$SIGNAL.signal === "function") {
                      window.$SIGNAL.signal(kind, value);
                      return true;
                    }
                  } catch (e) {}
                  return false;
                }

                function listContainer() {
                  try {
                    var strip = document.querySelector(STRIP);
                    if (!strip) { return null; }
                    return strip.querySelector("section > div") || strip;
                  } catch (e) { return null; }
                }

                function headCard(list) {
                  if (!list || !list.children || !list.children.length) { return null; }
                  return list.children[0];
                }

                function field(card, selector) {
                  try {
                    var el = card.querySelector(selector);
                    return el ? (el.textContent || "") : null;
                  } catch (e) { return null; }
                }

                function probeState() {
                  var list = listContainer();
                  if (!list) { return "missing"; }
                  if (!list.children || !list.children.length) { return "empty"; }
                  var card = list.children[0];
                  if (!card) { return "empty"; }
                  return (
                    field(card, NUMBER) !== null &&
                    field(card, LOCATION) !== null &&
                    field(card, TIME) !== null
                  ) ? "ok" : "missing";
                }

                var lastProbe = null;
                var observedList = null;
                var observer = null;

                function checkProbe() {
                  var state = probeState();
                  // Advance the dedupe key only once the post actually
                  // happened: a bridge that is not installed yet must not
                  // swallow the first transition.
                  if (state !== lastProbe) {
                    if (post("probe", state)) { lastProbe = state; }
                  }
                  return state;
                }

                // Re-post unconditionally on the interval tick. The app clamps
                // repeats, so a transition dropped inside that 500 ms window is
                // delivered on the next tick instead of being lost forever.
                function forceProbe() {
                  var state = probeState();
                  if (post("probe", state)) { lastProbe = state; }
                  return state;
                }

                function report() {
                  checkProbe();
                  var card = headCard(listContainer());
                  if (!card) { return; }
                  var number = field(card, NUMBER);
                  var location = field(card, LOCATION);
                  var time = field(card, TIME);
                  if (number === null || location === null || time === null) { return; }
                  // An empty number is not a call: the activation form can
                  // render a card-shaped node before the dashboard exists.
                  if (!number.replace(/\s/g, "")) { return; }
                  // No client-side dedupe: the app clamps repeats to 500 ms, so
                  // a signal dropped by that clamp is still re-posted on the
                  // next tick instead of being lost forever.
                  post("now", number + "\u001f" + location + "\u001f" + time);
                }

                function observe() {
                  try {
                    var list = listContainer();
                    if (!observer || !list || list === observedList) { return; }
                    if (observedList) { observer.disconnect(); }
                    observer.observe(list, {
                      childList: true,
                      subtree: true,
                      characterData: true
                    });
                    observedList = list;
                  } catch (e) {}
                }

                function signalAudio() { post("audio", "1"); }

                // Audio hook. Best effort by design: a page that blocks one of
                // these patches keeps its DOM watch, and the chime is simply
                // not detected. The result is only consumed when the call
                // source opts in, and it never carries a call number.
                try {
                  var OriginalAudio = window.Audio;
                  if (typeof OriginalAudio === "function") {
                    var WrappedAudio = function () {
                      signalAudio();
                      return new (Function.prototype.bind.apply(
                        OriginalAudio, [null].concat(Array.prototype.slice.call(arguments))))();
                    };
                    WrappedAudio.prototype = OriginalAudio.prototype;
                    window.Audio = WrappedAudio;
                  }
                } catch (e) {}

                try {
                  if (window.HTMLMediaElement && HTMLMediaElement.prototype &&
                      typeof HTMLMediaElement.prototype.play === "function") {
                    var originalPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function () {
                      signalAudio();
                      return originalPlay.apply(this, arguments);
                    };
                  }
                } catch (e) {}

                try {
                  var AudioCtx = window.AudioContext || window.webkitAudioContext;
                  if (AudioCtx && AudioCtx.prototype &&
                      typeof AudioCtx.prototype.createBufferSource === "function") {
                    var originalSource = AudioCtx.prototype.createBufferSource;
                    AudioCtx.prototype.createBufferSource = function () {
                      signalAudio();
                      return originalSource.apply(this, arguments);
                    };
                  }
                } catch (e) {}

                try {
                  if (window.MutationObserver) {
                    observer = new MutationObserver(function () { observe(); report(); });
                    observe();
                  }
                } catch (e) { observer = null; }

                checkProbe();
                post("ready", "1");

                // The interval is not a fallback in name only: the reference
                // device runs Chromium 66, where the observer can miss a
                // wholesale list replacement. It also re-attaches the observer
                // when the list container itself is replaced.
                try {
                  setInterval(function () { observe(); report(); forceProbe(); }, 1000);
                } catch (e) {}
              } catch (e) {}
            })();
        """.trimIndent()
    }

    /**
     * A JavaScript string literal. Escapes quotes, backslashes, line breaks and
     * `<` so a selector or an origin can never terminate the literal or a
     * surrounding `<script>` element.
     */
    internal fun jsString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when {
                ch == '\\' -> out.append("\\\\")
                ch == '"' -> out.append("\\\"")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '<' -> out.append("\\u003C")
                ch == '\u2028' -> out.append("\\u2028")
                ch == '\u2029' -> out.append("\\u2029")
                ch.code < 0x20 || ch.code == 0x7F ->
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * `scheme://host[:port]` for an http(s) URL, or `""` when it is not one.
     * Default ports are omitted, matching `location.origin`.
     */
    fun originOf(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return ""
            val host = uri.host?.lowercase() ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val port = uri.port
            when {
                port <= 0 -> "$scheme://$host"
                scheme == "http" && port == 80 -> "$scheme://$host"
                scheme == "https" && port == 443 -> "$scheme://$host"
                else -> "$scheme://$host:$port"
            }
        } catch (e: Exception) {
            ""
        }
    }
}
