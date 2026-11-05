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
 * what that engine supports: no flexbox `gap` as a spacing mechanism (Chrome
 * 84 — the list only resets it to `0`, which an engine without support simply
 * ignores), no `aspect-ratio` (88), no `inset` shorthand (87), no `:is()` /
 * `:where()` (88), no `clamp()`/`min()`/`max()` (79). `calc()` predates the
 * floor and is used for the card height. Card spacing is therefore a right
 * margin on every card, neutralised on the last one, and the layout degrades
 * gracefully on a non-grid parent. The `QmsStripCssTest` guard fails the build
 * if one of those features returns.
 *
 * ## Layout model
 *
 * There is exactly one: the page is a block box pinned to the viewport, the
 * header is `position: fixed` at the top, `main` is `position: fixed` between
 * the header's bottom and the viewport's bottom, and the card list is
 * `position: absolute` filling `main`. No height is distributed through the
 * vendor's own flex column, so the queue band cannot be pushed off-screen by
 * the vendor's padding or `gap`.
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

    /** Clinic-name size as a fraction of the header band height. */
    private const val HEADER_TEXT_RATIO = 0.38

    /** Date/clock size as a fraction of the header band height. */
    private const val HEADER_META_RATIO = 0.22

    /**
     * Card field sizes as fractions of the queue band height. The ratios leave
     * room for a two-line location inside the band: at 0.36 the location plus
     * the number and the time overflowed the queue row on the device, and the
     * label row above the number needed its share as well.
     */
    private const val CURRENT_NUMBER_RATIO = 0.32
    private const val HISTORY_NUMBER_RATIO = 0.22
    private const val LOCATION_RATIO = 0.11
    private const val TIME_RATIO = 0.065
    private const val LABEL_RATIO = 0.055

    /** Logo height as a fraction of the header band: leaves the band's padding. */
    private const val LOGO_HEIGHT_RATIO = 0.72

    /** Space between two cards as a fraction of the queue band height. */
    private const val CARD_GAP_RATIO = 0.09

    /** The authored value each ratio is tuned against; a setting scales the ratio. */
    private const val CURRENT_NUMBER_BASE = 96f
    private const val HISTORY_NUMBER_BASE = 56f
    private const val LABEL_BASE = 15f

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

        // The cards must fit the queue band by construction, so their type is
        // derived from the band height rather than taken from an authored
        // absolute size. The settings stay as multipliers around the default,
        // so an operator can still enlarge or shrink them.
        val queueAuthoredPx = 1080.0 * settings.stripHeightPercent / 100.0
        fun fromQueue(ratio: Double, multiplier: Float): Int =
            (queueAuthoredPx * ratio * multiplier).roundToInt()

        val currentNumberSize = fromQueue(
            CURRENT_NUMBER_RATIO,
            settings.currentNumberPx / CURRENT_NUMBER_BASE
        )
        val historyNumberSize = fromQueue(
            HISTORY_NUMBER_RATIO,
            settings.historyNumberPx / HISTORY_NUMBER_BASE
        )
        val locationSize = fromQueue(LOCATION_RATIO, settings.labelPx / LABEL_BASE)
        val timeSize = fromQueue(TIME_RATIO, settings.labelPx / LABEL_BASE)
        val labelSize = fromQueue(LABEL_RATIO, settings.labelPx / LABEL_BASE)

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

        // Neither intermediate box may become the containing block of the
        // absolute card list: they are vendor flow elements whose height comes
        // from their content, so a vendor `relative` would make the queue band
        // depend on that content instead of on `main`. Only `main` is positioned
        // for the band, and it owns the inset. A card is positioned as well, but
        // only to be the containing block of its own `::before` label: without
        // it the absolute label resolves against the list and the label sits on
        // the band's top edge, losing its ascenders.
        rules += "$column, $column > section" to "position: static !important;"

        // The page is pinned to the viewport and both bands are positioned,
        // not flexed. The vendor page is a flex column with its own padding and
        // `gap`; every attempt to win that column (`flex: 1 1 0`,
        // `min-height: 0`) still let `main` overflow the bottom edge, because
        // the vendor's own box model kept re-adding space. Absolute positioning
        // against the viewport is deterministic on Chromium 66 and ignores the
        // vendor's flow entirely.
        rules += "$scope, $scope body" to """
            height: 100% !important;
            margin: 0 !important;
            padding: 0 !important;
            overflow: hidden !important;
            display: block !important;
        """.trimIndent()
        // `main` owns everything below the header: its top is the header's
        // height and its bottom is the viewport edge, so the queue band can
        // never be pushed below the screen. It is block (not flex) so its own
        // `gap`/padding cannot add another slice, and the padding is zeroed for
        // the absolute list inside it.
        val headerPx = (1080 * settings.headerBandPercent / 100.0).roundToInt()
        val headerHeight = px(headerPx)
        rules += "$scope main" to """
            position: fixed !important;
            top: $headerHeight !important;
            bottom: 0 !important;
            left: 0 !important;
            right: 0 !important;
            height: auto !important;
            max-height: none !important;
            margin: 0 !important;
            padding: 0 !important;
            display: block !important;
            overflow: hidden !important;
        """.trimIndent()

        // A stray D-pad press on the page's fullscreen button would push the
        // app into its HTML5 custom-view path mid-signage.
        rules += "$scope header button" to "display: none !important;"
        // The vendor sizes the header with `h-[8vh]`, but `vh` resolves against
        // this WebView's viewport, which is only the header band plus the strip
        // (~29 % of the screen). The band is a fixed 7 % of the screen, so its
        // height is derived from the setting instead: 1080 * band / 100, scaled
        // by the same factor the native bands use.
        //
        // Fixed positioning takes the header out of the vendor column: it no
        // longer competes with `main` for the remaining height, so no flex
        // basis or `min-height: 0` is needed to pin it.
        rules += "$scope header" to """
            position: fixed !important;
            top: 0 !important;
            left: 0 !important;
            right: 0 !important;
            height: $headerHeight !important;
            max-height: $headerHeight !important;
            display: flex !important;
            align-items: center !important;
            flex-wrap: nowrap !important;
            overflow: hidden !important;
            box-sizing: border-box !important;
            padding: 0 ${px(8)} !important;
        """.trimIndent()
        // The logo's own wrapper is a flex child too; without a fixed basis it
        // can grow past the band and drag the header with it.
        rules += "$scope header > div" to """
            flex: 0 0 auto !important;
            min-height: 0 !important;
        """.trimIndent()
        rules += "$scope header *" to """
            margin: 0 !important;
            line-height: 1.05 !important;
        """.trimIndent()
        // The vendor's `2xl:h-14` logo is 56 CSS px, taller than the whole
        // band. A percentage `max-height` cannot cap it: the wrapper is a flex
        // item with no height of its own, so `78%` resolves to `none` and the
        // vendor height wins. The cap is derived from the band in pixels, like
        // every other dimension here (`width: auto` + `max-width: none` keeps
        // its aspect ratio and stops a vendor `w-*` from squeezing it).
        val logoPx = (headerPx * LOGO_HEIGHT_RATIO).toInt()
        rules += "$scope header img" to """
            height: ${px(logoPx)} !important;
            width: auto !important;
            max-width: none !important;
            max-height: ${px(logoPx)} !important;
            min-height: 0 !important;
            object-fit: contain !important;
            flex: 0 0 auto !important;
        """.trimIndent()
        // Text is sized from the band, not from the vendor's breakpoints: a
        // breakpoint measured against the 29 % viewport is not the screen. The
        // clinic name must stay on one line, so the size leaves room for the
        // longest label instead of wrapping it.
        val headerNameSize = (headerPx * HEADER_TEXT_RATIO).roundToInt()
        val headerMetaSize = (headerPx * HEADER_META_RATIO).roundToInt()
        rules += "$scope header p, $scope header h1, $scope header p span, $scope header h1 span" to """
            font-size: ${px(headerNameSize)} !important;
            font-weight: 700 !important;
            white-space: nowrap !important;
            overflow: hidden !important;
            text-overflow: ellipsis !important;
        """.trimIndent()
        // The date and the clock sit in the same header but are not the name:
        // they keep a smaller size so the name has room to grow. A span inside
        // the name (`h1 > span`) keeps the name size above by specificity.
        rules += "$scope header span, $scope header time, $scope header small" to """
            font-size: ${px(headerMetaSize)} !important;
            white-space: nowrap !important;
            overflow: hidden !important;
            text-overflow: ellipsis !important;
        """.trimIndent()

        // The card row is absolutely positioned inside `main`, which is itself
        // fixed: `top/left/right/bottom: 0` resolves against `main`'s padding
        // box, so the row is exactly the queue band. `align-items: center` and
        // the bottom padding keep the cards clear of the band's lower edge, and
        // the 4 px top padding gives the card's label row air. `gap` is pinned
        // to zero so a vendor grid/flex `gap` cannot interfere with the card
        // margins that carry the spacing (Chromium 66 ignores the reset).
        rules += list to """
            position: absolute !important;
            top: 0 !important;
            left: 0 !important;
            right: 0 !important;
            bottom: 0 !important;
            display: flex !important;
            flex-direction: row !important;
            align-items: center !important;
            gap: 0 !important;
            padding: ${px(4)} ${px(10)} ${px(10)} ${px(10)} !important;
            box-sizing: border-box !important;
            overflow: hidden !important;
            -webkit-mask-image: linear-gradient(to right, #000 88%, transparent);
            mask-image: linear-gradient(to right, #000 88%, transparent);
        """.trimIndent()

        // `space-y-*` on a vertical list becomes a bug once the list is a row:
        // it applies `margin-top` to every child but the first, pushing the
        // later cards down by 8 px inside the fixed-height band.
        rules += "$list > *" to "margin-top: 0 !important;"

        // Flexbox `gap` needs Chrome 84; the reference device is Chromium 66.
        // The space is a right margin on every card instead of an
        // adjacent-sibling selector, which the vendor DOM did not reliably
        // match: the last card cancels its own margin so the row does not gain
        // a trailing gap. The margin follows the band so it stays visible when
        // the band is small: a constant 16 authored px is only 9 CSS px on the
        // device, and the vendor card border ate all of it.
        val cardGapPx = (queueAuthoredPx * CARD_GAP_RATIO).toInt()

        // The vendor's `border-4` is 4 CSS px per side and consumed most of
        // the inter-card gap. It is removed from the layout; the same hairline
        // is drawn with an inset outline, which costs no space. The card is
        // also `position: relative`: it is the containing block of its own
        // `::before` label, so the label tracks the card's top padding instead
        // of the band's edge.
        rules += card to """
            flex: 0 0 auto !important;
            display: flex !important;
            flex-direction: column !important;
            align-items: flex-start !important;
            justify-content: center !important;
            position: relative !important;
            height: calc(100% - ${px(4)}) !important;
            max-height: calc(100% - ${px(4)}) !important;
            margin: 0 ${px(cardGapPx)} 0 0 !important;
            min-width: ${px(settings.historyCardMinPx)} !important;
            max-width: ${px(settings.historyCardMaxPx)} !important;
            min-height: 0 !important;
            padding: ${px(16)} ${px(8)} ${px(6)} !important;
            box-sizing: border-box !important;
            border-width: 0 !important;
            outline: ${px(2)} solid rgba(255, 255, 255, 0.18) !important;
            outline-offset: -${px(2)} !important;
            overflow: hidden !important;
        """.trimIndent()

        // The trailing margin of the final card is not spacing between cards.
        rules += "$card:last-child" to "margin-right: 0 !important;"

        // The vendor's own spacing is authored for a roomy card, not a 22 %
        // band: `mb-2` on the number and `py-3` on the card make every card
        // taller than the band and clip the pills. Only the vertical margins
        // are reset — the card's own right margin (on the card itself, not on
        // its children) carries the spacing above.
        rules += "$card *" to """
            margin-top: 0 !important;
            margin-bottom: 0 !important;
        """.trimIndent()

        rules += numberOnCard to """
            font-size: ${px(historyNumberSize)} !important;
            line-height: 1 !important;
            display: block !important;
            white-space: nowrap !important;
            max-height: 1.15em !important;
        """.trimIndent()

        // A long room name wraps to two lines instead of being cut with an
        // ellipsis: an abbreviated counter is a wrong counter. An unspaced value
        // breaks mid-word rather than being clipped at the card edge. The
        // vendor's `w-44`/`w-64`/`w-96` resolves wider than the card itself, so
        // the fixed width is cancelled and the pill sizes to the card.
        //
        // `display: -webkit-box` replaces the vendor's flex centring, so the
        // text is centred explicitly: `text-align` centres each line,
        // `-webkit-box-pack` centres the line boxes, and the auto side margins
        // centre a pill narrower than the card.
        rules += locationOnCard to """
            display: -webkit-box !important;
            -webkit-box-orient: vertical !important;
            -webkit-line-clamp: 2 !important;
            white-space: normal !important;
            overflow: hidden !important;
            overflow-wrap: break-word !important;
            word-wrap: break-word !important;
            width: auto !important;
            min-width: 0 !important;
            max-width: 100% !important;
            padding: 0 ${px(10)} !important;
            line-height: 1.15 !important;
            font-size: ${px(locationSize)} !important;
            text-align: center !important;
            -webkit-box-pack: center !important;
            justify-content: center !important;
            margin-left: auto !important;
            margin-right: auto !important;
        """.trimIndent()

        rules += timeOnCard to """
            font-size: ${px(timeSize)} !important;
            line-height: 1.2 !important;
            white-space: nowrap !important;
        """.trimIndent()

        rules += current to """
            flex: 0 0 ${px(settings.currentCardPx)} !important;
            width: ${px(settings.currentCardPx)} !important;
            max-width: ${px(settings.currentCardPx)} !important;
            border-right: ${px(2)} solid rgba(255, 255, 255, 0.25) !important;
        """.trimIndent()

        rules += numberOnCurrent to
            "font-size: ${px(currentNumberSize)} !important;"

        // Pseudo-elements cannot be wiped by a re-render and are not part of
        // textContent, so they cannot contaminate the detection key. The label
        // is inset from the card's top edge: at `top: 0` with `line-height: 1`
        // its ascenders were cut by the card's `overflow: hidden`.
        rules += "$current::before" to """
            content: var(--terebi-label-current);
            position: absolute;
            top: ${px(3)} !important;
            left: ${px(10)} !important;
            font-size: ${px(labelSize)};
            line-height: 1.1 !important;
            text-transform: uppercase;
            pointer-events: none;
        """.trimIndent()

        rules += "$card:nth-child(2)::before" to """
            content: var(--terebi-label-history);
            position: absolute;
            top: ${px(3)} !important;
            left: ${px(10)} !important;
            font-size: ${px(labelSize)};
            line-height: 1.1 !important;
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
            font-size: ${px(labelSize * 2)};
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
            font-size: ${px(labelSize * 2)};
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
