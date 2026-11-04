package com.terebibro.tv.media

import kotlin.math.roundToInt

/**
 * The strip stylesheet. It restyles the vendor's own DOM and never touches its
 * text: every rule is scoped under `html[data-terebi-strip]`, an element React
 * does not own, so a re-render cannot wipe it.
 *
 * All authored dimensions are 1080p values multiplied by `renderScale` and
 * emitted as `px`, the same factor the native bands use.
 *
 * ## Compatibility floor
 *
 * The reference device runs Chromium 66. Everything emitted here is limited to
 * what that engine supports: no flexbox `gap` (Chrome 84), no `aspect-ratio`
 * (88), no `inset` shorthand (87), no `:is()` / `:where()` (88), no
 * `clamp()`/`min()`/`max()` (79). Card spacing is therefore adjacent-sibling
 * margins and the layout degrades gracefully on a non-grid parent. The
 * `QmsStripCssTest` guard fails the build if one of those features returns.
 *
 * ## Vendor structure
 *
 * The left column is [MediaSettings.qmsStripSelector]. Two levels below it is
 * the card list (`<column> > section > div`) and one more level down are the
 * cards themselves (`<column> > section > div > div`); that deeper card path is
 * fixed because no card selector is configurable. The number, location and
 * time selectors are configurable and are always applied relative to a card.
 */
object QmsStripCss {

    /** Used when the configured strip selector is blank or unsafe. */
    private const val DEFAULT_STRIP_SELECTOR = "main > div:nth-child(1)"

    /** Used when the configured field selector is blank or unsafe. */
    private const val DEFAULT_NUMBER_SELECTOR = "div:first-child > span"
    private const val DEFAULT_LOCATION_SELECTOR = "div:nth-child(2) > span:first-child"
    private const val DEFAULT_TIME_SELECTOR = "div:nth-child(2) > span:nth-child(2)"

    /**
     * True when a selector would escape its scoped position or break the sheet.
     * A comma starts a second selector outside the `html[data-terebi-strip]`
     * scope; a comment opener or closer swallows every later rule; and
     * an odd number of double quotes opens a string that swallows the next rule's
     * prelude. The validator rejects these, but recovery can bypass it, so the
     * builder re-checks and falls back to the slot's default instead.
     */
    internal fun selectorBreaksScope(value: String): Boolean =
        value.contains(',') ||
            value.contains("/*") ||
            value.contains("*/") ||
            value.count { it == '"' } % 2 == 1

    /** A configured selector, or the built-in default when it is blank or unsafe. */
    private fun safeSelector(value: String, default: String): String {
        val trimmed = value.trim()
        return if (trimmed.isEmpty() || selectorBreaksScope(trimmed)) default else trimmed
    }

    /** The single source of the strip attribute name. */
    fun attribute(): String = "data-terebi-strip"

    /**
     * Selector health is a second attribute rather than a class on a vendor
     * node: the native `qmsUnavailable` card is a `::after` on the root, so it
     * cannot be removed by a re-render and cannot contaminate the call key.
     */
    fun stateAttribute(): String = "data-terebi-qms"

    /** Removing the attribute is the whole restore; no stylesheet has to be undone. */
    fun restore(): String = ""

    fun build(settings: MediaSettings, renderScale: Float): String = build(
        settings = settings,
        renderScale = renderScale,
        currentLabel = SignageStrings.label(settings.uiLanguage, SignageStrings.Key.CURRENT),
        historyLabel = SignageStrings.label(settings.uiLanguage, SignageStrings.Key.HISTORY),
        emptyLabel = SignageStrings.label(settings.uiLanguage, SignageStrings.Key.NO_CALL),
        unavailableLabel = SignageStrings.label(
            settings.uiLanguage,
            SignageStrings.Key.QMS_UNAVAILABLE
        )
    )

    internal fun build(
        settings: MediaSettings,
        renderScale: Float,
        currentLabel: String,
        historyLabel: String,
        emptyLabel: String,
        unavailableLabel: String
    ): String {
        val scale = if (renderScale.isFinite() && renderScale > 0f) renderScale else 1f
        fun px(value: Int): String = "${(value * scale).roundToInt()}px"

        val scope = "html[${attribute()}]"
        val configured = safeSelector(settings.qmsStripSelector, DEFAULT_STRIP_SELECTOR)
        val numberSelector = safeSelector(settings.qmsNumberSelector, DEFAULT_NUMBER_SELECTOR)
        val locationSelector = safeSelector(settings.qmsLocationSelector, DEFAULT_LOCATION_SELECTOR)
        val timeSelector = safeSelector(settings.qmsTimeSelector, DEFAULT_TIME_SELECTOR)
        // The configured selector is relative to `main` (its default is
        // `main > div:nth-child(1)`), so it is scoped, never rewritten.
        val column = "$scope $configured"
        val list = "$column > section > div"
        // No card selector is configurable: cards are the list's own children,
        // i.e. the list sits two levels below the left column and a card is one
        // level further down.
        val card = "$list > div"
        val current = "$card:first-child"
        val unavailable = "$scope[${stateAttribute()}=\"unavailable\"]"

        // Field selectors come from settings and are applied relative to a card.
        val numberOnCard = "$card $numberSelector"
        val numberOnCurrent = "$current $numberSelector"
        val locationOnCard = "$card $locationSelector"
        val timeOnCard = "$card $timeSelector"

        val rules = ArrayList<Pair<String, String>>()

        rules += scope to """
            --terebi-label-current: ${cssString(currentLabel)};
            --terebi-label-history: ${cssString(historyLabel)};
            --terebi-label-empty: ${cssString(emptyLabel)};
            --terebi-label-unavailable: ${cssString(unavailableLabel)};
        """.trimIndent()

        // Only the call list's column stays. Every later sibling of that column
        // is dropped: the default layout's right-hand hero/QR block is just the
        // first of them, and a reconfigured column must hide its own followers.
        rules += "$column ~ div" to "display: none !important;"

        // Full-width column for both a grid parent (`grid-column`) and a plain
        // block/flex parent (`width` + `flex`), so the band cannot stay a narrow
        // slice when the vendor page is not a grid.
        rules += column to """
            width: 100% !important;
            flex: 1 1 100% !important;
            grid-column: 1 / -1 !important;
        """.trimIndent()

        // The height chain: a content-sized page would otherwise collapse the
        // band to the height of a single row.
        rules += "$scope, $scope body, $scope main, $column" to "height: 100% !important;"

        // A stray D-pad press on the page's fullscreen button would push the
        // app into its HTML5 custom-view path mid-signage.
        rules += "$scope header button" to "display: none !important;"
        rules += "$scope header" to """
            padding: 0 ${px(12)} !important;
            align-items: center !important;
        """.trimIndent()
        rules += "$scope header *" to """
            margin: 0 !important;
            line-height: 1.05 !important;
        """.trimIndent()

        rules += list to """
            display: flex !important;
            flex-direction: row !important;
            align-items: stretch !important;
            height: 100% !important;
            padding: ${px(18)} ${px(16)} 0 !important;
            box-sizing: border-box !important;
            overflow: hidden !important;
            -webkit-mask-image: linear-gradient(to right, #000 88%, transparent);
            mask-image: linear-gradient(to right, #000 88%, transparent);
        """.trimIndent()

        // Flexbox `gap` needs Chrome 84; the reference device is Chromium 66.
        rules += "$card + $card" to "margin-left: ${px(12)} !important;"

        rules += card to """
            flex: 0 0 auto !important;
            display: flex !important;
            flex-direction: column !important;
            justify-content: center !important;
            position: relative !important;
            min-width: ${px(settings.historyCardMinPx)} !important;
            max-width: ${px(settings.historyCardMaxPx)} !important;
            padding: ${px(20)} ${px(10)} ${px(8)} !important;
            box-sizing: border-box !important;
            overflow: hidden !important;
        """.trimIndent()

        rules += numberOnCard to """
            font-size: ${px(settings.historyNumberPx)} !important;
            line-height: 1 !important;
        """.trimIndent()

        // A long room name wraps to two lines instead of being cut with an
        // ellipsis: an abbreviated counter is a wrong counter. An unspaced value
        // breaks mid-word rather than being clipped at the card edge.
        rules += locationOnCard to """
            display: -webkit-box !important;
            -webkit-box-orient: vertical !important;
            -webkit-line-clamp: 2 !important;
            white-space: normal !important;
            overflow: hidden !important;
            overflow-wrap: break-word !important;
            word-wrap: break-word !important;
            max-width: 100% !important;
            font-size: ${px(settings.labelPx * 2)} !important;
        """.trimIndent()

        rules += timeOnCard to """
            font-size: ${px(settings.labelPx)} !important;
            line-height: 1.2 !important;
        """.trimIndent()

        rules += current to """
            flex: 0 0 ${px(settings.currentCardPx)} !important;
            width: ${px(settings.currentCardPx)} !important;
            max-width: ${px(settings.currentCardPx)} !important;
            border-right: ${px(2)} solid rgba(255, 255, 255, 0.25) !important;
        """.trimIndent()

        rules += numberOnCurrent to
            "font-size: ${px(settings.currentNumberPx)} !important;"

        // Pseudo-elements cannot be wiped by a re-render and are not part of
        // textContent, so they cannot contaminate the detection key.
        rules += "$current::before" to """
            content: var(--terebi-label-current);
            position: absolute;
            top: ${px(2)};
            left: ${px(10)};
            font-size: ${px(settings.labelPx)};
            text-transform: uppercase;
            pointer-events: none;
        """.trimIndent()

        rules += "$card:nth-child(2)::before" to """
            content: var(--terebi-label-history);
            position: absolute;
            top: ${px(2)};
            left: ${px(10)};
            font-size: ${px(settings.labelPx)};
            text-transform: uppercase;
            pointer-events: none;
        """.trimIndent()

        // An empty call list is healthy, not a failure: the container resolved,
        // there is simply nothing to show yet (the board before the first call
        // of the day). `:empty` is the honest test and the label is our own.
        rules += "$list:empty::before" to """
            content: var(--terebi-label-empty);
            display: block;
            margin: auto;
            padding: 0 ${px(16)};
            font-size: ${px(settings.labelPx * 2)};
            text-transform: uppercase;
            text-align: center;
            pointer-events: none;
        """.trimIndent()

        // Selector-level failure: the page is alive but the structure moved.
        // The vendor text is hidden rather than shown stale; the notice is our
        // own string, and no number appears.
        rules += "$unavailable body > *" to "visibility: hidden !important;"
        rules += "$unavailable::after" to """
            content: var(--terebi-label-unavailable);
            position: fixed;
            top: 50%;
            left: 0;
            right: 0;
            transform: translateY(-50%);
            text-align: center;
            font-size: ${px(settings.labelPx * 2)};
            text-transform: uppercase;
            pointer-events: none;
        """.trimIndent()

        return rules.joinToString("\n") { (selector, body) -> "$selector {\n$body\n}" }
    }

    /** A CSS string literal; a label must not be able to terminate the declaration. */
    internal fun cssString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when {
                ch == '\\' -> out.append("\\\\")
                ch == '"' -> out.append("\\\"")
                ch == '\n' -> out.append("\\A ")
                ch == '\r' -> out.append("\\D ")
                ch == '\u000C' -> out.append("\\C ")
                ch.code < 0x20 || ch.code == 0x7F || ch == '<' || ch == '>' ->
                    out.append('\\').append(ch.code.toString(16)).append(' ')
                else -> out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }
}
