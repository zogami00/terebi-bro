package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class QmsStripCssTest {

    private val settings = MediaSettings()
    private val atOne = QmsStripCss.build(settings, 1f)
    private val atTwo = QmsStripCss.build(settings, 2f)

    /**
     * The card type is derived from the queue band (a fraction of the 1080p
     * authoring surface), with the setting acting as a multiplier around the
     * default. Mirrors the production formula so the assertions fail loudly if
     * either side drifts.
     */
    private fun derived(
        ratio: Double,
        setting: Int,
        base: Float,
        queuePercent: Int = settings.stripHeightPercent
    ): Int = (1080.0 * queuePercent / 100.0 * ratio * (setting / base)).roundToInt()

    /** The stylesheet is the one place the signage language reaches the page. */
    @Test
    fun `the labels follow the configured language`() {
        for (language in UiLanguage.entries) {
            val css = QmsStripCss.build(settings.copy(uiLanguage = language), 1f)
            val current = SignageStrings.label(language, SignageStrings.Key.CURRENT)
            val history = SignageStrings.label(language, SignageStrings.Key.HISTORY)
            val empty = SignageStrings.label(language, SignageStrings.Key.NO_CALL)
            val unavailable = SignageStrings.label(language, SignageStrings.Key.QMS_UNAVAILABLE)
            assertTrue("$language/current", css.contains(QmsStripCss.cssString(current)))
            assertTrue("$language/history", css.contains(QmsStripCss.cssString(history)))
            assertTrue("$language/empty", css.contains(QmsStripCss.cssString(empty)))
            assertTrue("$language/unavailable", css.contains(QmsStripCss.cssString(unavailable)))
        }
    }

    @Test
    fun `every rule is scoped under the strip attribute`() {
        val rule = Regex("([^{}]+)\\{([^{}]*)\\}")
        val selectors = rule.findAll(atOne).map { it.groupValues[1] }.toList()
        assertTrue(selectors.isNotEmpty())
        for (raw in selectors) {
            for (selector in raw.split(",")) {
                assertTrue(
                    "bare selector: ${selector.trim()}",
                    selector.trim().startsWith("html[${QmsStripCss.attribute()}]")
                )
            }
        }
    }

    /**
     * A configured selector must never be able to end the scope: the validator
     * rejects the four shapes below, and the builder falls back to the slot's
     * default in case recovery supplied them without validation.
     */
    @Test
    fun `a comma selector falls back to the default instead of escaping the scope`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val css = QmsStripCss.build(settings.copy(qmsStripSelector = "main > div, body"), 1f)

        assertTrue(css.contains("$scope main > div:nth-child(1) {"))
        assertFalse(css.contains("$scope main > div, body"))
        assertFalse(css.contains("\nbody {"))
    }

    @Test
    fun `an unsafe selector in any slot falls back and every rule stays scoped`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val cases = listOf(
            settings.copy(qmsStripSelector = "main > div, body"),
            settings.copy(qmsNumberSelector = "span, body"),
            settings.copy(qmsLocationSelector = "span/*"),
            settings.copy(qmsTimeSelector = "span[title=\"x]"),
            settings.copy(qmsStripSelector = "main > div*/")
        )
        val rule = Regex("([^{}]+)\\{([^{}]*)\\}")
        for (s in cases) {
            val css = QmsStripCss.build(s, 1f)
            val selectors = rule.findAll(css).map { it.groupValues[1] }.toList()
            assertTrue("no rules emitted for $s", selectors.isNotEmpty())
            for (raw in selectors) {
                for (selector in raw.split(",")) {
                    assertTrue(
                        "unscoped selector: ${selector.trim()}",
                        selector.trim().startsWith(scope)
                    )
                }
            }
            // A rejected selector must not survive anywhere in the sheet.
            assertFalse("comment leaked: $s", css.contains("/*"))
            assertFalse("comma selector leaked: $s", css.contains(", body"))
        }
    }

    @Test
    fun `the documented default selectors are accepted as-is`() {
        val defaults = MediaSettings()
        val css = QmsStripCss.build(defaults, 1f)
        val scope = "html[${QmsStripCss.attribute()}]"
        assertTrue(css.contains("$scope ${defaults.qmsStripSelector} {"))
        assertTrue(css.contains("${defaults.qmsNumberSelector} {"))
        assertTrue(css.contains("${defaults.qmsLocationSelector} {"))
        assertTrue(css.contains("${defaults.qmsTimeSelector} {"))
    }

    @Test
    fun `the column follows the configured selector and hides its later siblings`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        assertTrue(atOne.contains("$scope ${settings.qmsStripSelector} ~ div " +
            "{\ndisplay: none !important;\n}"))
        assertTrue(atOne.contains("grid-column: 1 / -1 !important;"))
        // The old hardcoded right column is gone; the only nth-child(2) left is
        // the history label on the second card.
        assertFalse(atOne.contains("main > div:nth-child(2)"))
    }

    @Test
    fun `a reconfigured strip selector moves the whole sheet`() {
        val custom = settings.copy(qmsStripSelector = "main > section#queue")
        val css = QmsStripCss.build(custom, 1f)
        val scope = "html[${QmsStripCss.attribute()}]"
        assertTrue(css.contains("$scope main > section#queue {"))
        assertTrue(css.contains("$scope main > section#queue > section > div {"))
        assertTrue(css.contains("$scope main > section#queue ~ div {"))
        assertTrue(css.contains("$scope main > section#queue > section > div > div {"))
        // The default column must not leak into a reconfigured sheet.
        assertFalse(css.contains("main > div:nth-child(1)"))
    }

    @Test
    fun `the page is pinned to the viewport as a block box`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        // A content-sized page would otherwise collapse the band to the height
        // of a single row; a 100 % block root with no margin or padding is the
        // only stable base for two positioned bands.
        assertTrue(atOne.contains("$scope, $scope body {\nheight: 100% !important;\n" +
            "margin: 0 !important;\npadding: 0 !important;\n" +
            "overflow: hidden !important;\ndisplay: block !important;\n}"))
        assertFalse("the root is still a flex column", atOne.contains(
            "$scope, $scope body {\ndisplay: flex"
        ))
        assertFalse(atOne.contains("flex-direction: column !important;\nmin-height: 0"))
    }

    /**
     * Live regression: the queue band was cut off at the bottom edge and the
     * cards had no visible gap, even with `flex: 1 1 0` and `min-height: 0`.
     * The vendor's flex column kept adding its own padding, so the height is
     * no longer distributed: the header and `main` are fixed and the list is
     * absolutely positioned inside `main`. Positions are deterministic on
     * Chromium 66, which is the device engine.
     */
    @Test
    fun `the header, main and card list are positioned instead of flexed`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        val headerPx = (1080 * settings.headerBandPercent / 100.0).roundToInt()

        val headerRule = Regex("(?m)^" + Regex.escape("$scope header") + " \\{([^}]*)\\}")
            .find(atOne)
        assertNotNull("header rule missing", headerRule)
        val header = headerRule!!.groupValues[1]
        assertTrue("header is not fixed", header.contains("position: fixed !important;"))
        assertTrue("header is not at the top", header.contains("top: 0 !important;"))
        assertTrue(header.contains("left: 0 !important;"))
        assertTrue(header.contains("right: 0 !important;"))
        assertTrue(header.contains("height: ${headerPx}px !important;"))
        assertTrue(header.contains("max-height: ${headerPx}px !important;"))
        assertFalse("the header still takes a flex basis", header.contains("flex: 0 0"))

        val mainRule = Regex("(?m)^" + Regex.escape("$scope main") + " \\{([^}]*)\\}")
            .find(atOne)
        assertNotNull("main rule missing", mainRule)
        val main = mainRule!!.groupValues[1]
        assertTrue("main is not fixed", main.contains("position: fixed !important;"))
        // `main` starts exactly where the header ends: no gap, no overlap.
        assertTrue(
            "main does not start at the header's height",
            main.contains("top: ${headerPx}px !important;")
        )
        assertTrue("main does not reach the bottom edge", main.contains("bottom: 0 !important;"))
        assertTrue(main.contains("height: auto !important;"))
        assertTrue(main.contains("max-height: none !important;"))
        assertTrue("main keeps vendor padding", main.contains("padding: 0 !important;"))
        assertTrue(main.contains("margin: 0 !important;"))
        assertFalse("main still flexes", main.contains("flex: 1 1 0"))

        val listRule = Regex("(?m)^" + Regex.escape(list) + " \\{([^}]*)\\}").find(atOne)
        assertNotNull("list rule missing", listRule)
        val stripped = listRule!!.groupValues[1]
        assertTrue(
            "the list is not absolutely positioned",
            stripped.contains("position: absolute !important;")
        )
        assertTrue(stripped.contains("top: 0 !important;"))
        assertTrue(stripped.contains("left: 0 !important;"))
        assertTrue(stripped.contains("right: 0 !important;"))
        assertTrue(stripped.contains("bottom: 0 !important;"))
        assertFalse("the list still flexes", stripped.contains("flex: 1 1 0"))

        // The vendor column and its section must not become the containing
        // block of the absolute list.
        assertTrue(atOne.contains("$scope ${settings.qmsStripSelector}, " +
            "$scope ${settings.qmsStripSelector} > section " +
            "{\nposition: static !important;\n}"))
    }


    /**
     * `vh` in the injected sheet resolves against the WebView viewport, which
     * is only the header band plus the strip; the vendor's `h-[8vh]` therefore
     * rendered ~25 px instead of the 76 px band. The header height must come
     * from `headerBandPercent`, and the logo must be capped to it.
     */
    @Test
    fun `the header height comes from the header band, not from vh`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val band = QmsStripCss.build(settings.copy(headerBandPercent = 7), 1f)
        assertTrue(band.contains("$scope header {\nposition: fixed !important;\n" +
            "top: 0 !important;\nleft: 0 !important;\nright: 0 !important;\n" +
            "height: 76px !important;\nmax-height: 76px !important;"))
        assertFalse(atOne.contains("vh"))
        // A percentage `max-height` resolves against the parent's definite
        // height; the logo's wrapper has none, so `78%` computed to `none` and
        // the vendor's 56 px logo clipped. The cap is the band in pixels now:
        // 76 * 0.72 = 54.72, truncated to 54.
        assertTrue(atOne.contains("$scope header img {\nheight: 54px !important;\n" +
            "width: auto !important;\nmax-width: none !important;\n" +
            "max-height: 54px !important;\nmin-height: 0 !important;"))
        assertFalse("the logo cap is a percentage again", atOne.contains("max-height: 78%"))
        // At the phone scale the cap scales with the band: 54 * 0.571 -> 31.
        val phone = QmsStripCss.build(settings, 0.571f)
        assertTrue(phone.contains("height: 31px !important;"))
        assertTrue(phone.contains("max-height: 31px !important;"))
        assertTrue(
            "the logo wrapper is a stretchable flex child",
            atOne.contains("$scope header > div {\nflex: 0 0 auto !important;\n" +
                "min-height: 0 !important;\n}")
        )
        assertTrue(atOne.contains("max-width: none !important;"))
        assertTrue(atOne.contains("object-fit: contain !important;"))
        // The clinic name grew to 0.38 of the band: 15 % of 1080 is 162
        // authored px, so 0.38 is 61.56, rounded to 62.
        val namePx = (1080 * 15 / 100.0 * 0.38).roundToInt()
        assertEquals(62, namePx)
        val tall = QmsStripCss.build(settings.copy(headerBandPercent = 15), 1f)
        assertTrue(tall.contains("$scope header p, $scope header h1, " +
            "$scope header p span, $scope header h1 span " +
            "{\nfont-size: ${namePx}px !important;\nfont-weight: 700 !important;"))
        // The date/clock stays smaller (0.22 of the band) so the name has room.
        val metaPx = (1080 * 15 / 100.0 * 0.22).roundToInt()
        assertTrue(tall.contains("$scope header span, $scope header time, " +
            "$scope header small {\nfont-size: ${metaPx}px !important;"))
        // The clinic name must not wrap to a second line.
        assertTrue(atOne.contains("flex-wrap: nowrap !important;"))
        assertTrue(atOne.contains("white-space: nowrap !important;"))
        assertTrue(atOne.contains("text-overflow: ellipsis !important;"))
        // A wider band must move the header with it.
        val taller = QmsStripCss.build(settings.copy(headerBandPercent = 10), 1f)
        assertTrue(taller.contains("height: 108px !important;"))
        // `main` starts where the fixed header ends instead of flexing.
        assertTrue(atOne.contains("$scope main {\nposition: fixed !important;\n" +
            "top: 76px !important;\nbottom: 0 !important;\nleft: 0 !important;\n" +
            "right: 0 !important;\nheight: auto !important;\n" +
            "max-height: none !important;\nmargin: 0 !important;\n" +
            "padding: 0 !important;\ndisplay: block !important;\n" +
            "overflow: hidden !important;\n}"))
    }

    /**
     * Live regression: the adjacent-sibling margin was emitted but the cards
     * still touched on the device, so the spacing is a right margin on every
     * card with the last one neutralised — one mechanism, no sibling selector.
     */
    @Test
    fun `cards are spaced by a right margin on every card, never flex gap`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val card = "$scope ${settings.qmsStripSelector} > section > div > div"
        val gap = (1080.0 * settings.stripHeightPercent / 100 * 0.09).toInt()
        assertEquals(21, gap)

        val cardRule = Regex("(?m)^" + Regex.escape(card) + " \\{([^}]*)\\}").find(atOne)
        assertNotNull("card rule missing", cardRule)
        assertTrue(
            "the card carries no right margin",
            cardRule!!.groupValues[1].contains("margin: 0 ${gap}px 0 0 !important;")
        )
        assertTrue(
            "the last card keeps a trailing margin",
            atOne.contains("$card:last-child {\nmargin-right: 0 !important;\n}")
        )
        // Exactly one mechanism: the adjacent-sibling rule is gone.
        assertFalse("the `+` rule is still emitted", atOne.contains("$card + $card"))
        // `gap` is only ever neutralised, never used to space the cards.
        assertTrue(atOne.contains("gap: 0 !important;"))
        assertFalse(atOne.contains("gap: ${gap}px"))
    }

    /**
     * Live regression: the gap was a constant 16 authored px — 9 CSS px on the
     * device — and each card's vendor `border-4` took 8 physical px of it, so
     * the faces nearly touched. The gap now follows the band, and the border is
     * drawn as an inset outline that costs no layout space.
     */
    @Test
    fun `the card gap follows the band and the vendor border is out of the layout`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        val card = "$list > div"
        val small = settings.copy(stripHeightPercent = 20)
        val css = QmsStripCss.build(small, 1f)
        // 216 px band * 0.09 = 19.44, truncated to 19.
        assertEquals(19, (216 * 0.09).toInt())

        val cardRule = Regex("(?m)^" + Regex.escape(card) + " \\{([^}]*)\\}").find(css)
        assertNotNull("card rule missing", cardRule)
        val body = cardRule!!.groupValues[1]
        assertTrue(
            "the card gap does not follow the band",
            body.contains("margin: 0 19px 0 0 !important;")
        )
        // The phone scale: 19 * 0.571 rounds to 11 CSS px.
        val phone = QmsStripCss.build(small, 0.571f)
        val phoneRule = Regex("(?m)^" + Regex.escape(card) + " \\{([^}]*)\\}").find(phone)
        assertNotNull("card rule missing at phone scale", phoneRule)
        assertTrue(phoneRule!!.groupValues[1].contains("margin: 0 11px 0 0 !important;"))

        assertTrue("card keeps a layout border", body.contains("border-width: 0 !important;"))
        assertTrue(
            "the outline is drawn outside the border box",
            body.contains("outline: 2px solid rgba(255, 255, 255, 0.18) !important;") &&
                body.contains("outline-offset: -2px !important;")
        )
        // The vendor's fill and radius stay: only the border width is removed.
        assertFalse("the card re-declares a background", body.contains("background"))
        assertFalse("the card re-declares a radius", body.contains("border-radius"))
    }

    /**
     * Live regression: the vendor's `space-y-2` list spacing, its card padding
     * and the fixed-width location pill all pushed the cards past the 22 % band
     * and clipped the pill. Each reset is asserted by its exact declaration.
     */
    @Test
    fun `the vendor vertical spacing and fixed pill width are reset`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        val card = "$list > div"
        // `space-y-2` puts margin-top on every card but the first.
        assertTrue(atOne.contains("$list > * {\nmargin-top: 0 !important;\n}"))
        // The number's `mb-2` and the card's `py-3` add height; only vertical
        // margins are reset so the card's own right margin survives.
        assertTrue(
            atOne.contains(
                "$card * {\nmargin-top: 0 !important;\nmargin-bottom: 0 !important;\n}"
            )
        )
        assertFalse(
            "the descendant reset clears the card's own right margin",
            atOne.substringAfter("$card * {").substringBefore("}").contains("margin-right")
        )
        val gap = (1080.0 * settings.stripHeightPercent / 100 * 0.09).toInt()
        assertTrue(atOne.contains("margin: 0 ${gap}px 0 0 !important;"))
        // `w-44`/`w-64`/`w-96` is wider than the capped card at 1920 px.
        val location = "$card ${settings.qmsLocationSelector}"
        val locationRule = Regex(Regex.escape(location) + " \\{([^}]*)\\}").find(atOne)
        assertTrue("location rule missing", locationRule != null)
        assertTrue(
            "location keeps its fixed width",
            locationRule!!.groupValues[1].contains("width: min-content !important;")
        )
        assertTrue(locationRule.groupValues[1].contains("min-width: 0 !important;"))
    }

    /**
     * The reference device is Chromium 66. Each feature below postdates it and
     * would be silently dropped by that engine, leaving the strip wrong.
     * `gap` is deliberately not on the list: the sheet only ever resets it to
     * `0`, which an engine without the property ignores, and the card spacing
     * is margins.
     */
    @Test
    fun `the sheet uses no CSS feature newer than Chromium 66`() {
        val forbidden = listOf(
            "aspect-ratio",
            "inset:",
            ":is(",
            ":where(",
            "clamp(",
            "min(",
            "max("
        )
        for (property in forbidden) {
            assertFalse("emitted sheet uses $property", atOne.contains(property))
            assertFalse("emitted sheet uses $property", atTwo.contains(property))
        }
        // The only `gap` declarations are the neutraliser, never spacing.
        val gaps = Regex("gap:\\s*([^;]*)").findAll(atOne).map { it.groupValues[1] }.toList()
        assertTrue("no gap neutraliser emitted", gaps.isNotEmpty())
        for (value in gaps) {
            assertEquals("gap is used for spacing", "0 !important", value.trim())
        }
    }

    @Test
    fun `field sizes come from the configured selectors relative to a card`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val card = "$scope ${settings.qmsStripSelector} > section > div > div"
        assertTrue(atOne.contains("$card ${settings.qmsNumberSelector} {"))
        assertTrue(atOne.contains("$card ${settings.qmsLocationSelector} {"))
        assertTrue(atOne.contains("$card ${settings.qmsTimeSelector} {"))
        val currentNumber = derived(0.32, settings.currentNumberPx, 96f)
        assertTrue(atOne.contains(
            "$card:first-child ${settings.qmsNumberSelector} " +
                "{\nfont-size: ${currentNumber}px !important;\n}"
        ))
    }

    @Test
    fun `a long location breaks mid-word instead of being clipped`() {
        assertTrue(atOne.contains("overflow-wrap: break-word !important;"))
    }

    @Test
    fun `an empty list renders the no-call label from a pseudo-element`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        assertTrue(atOne.contains("--terebi-label-empty:"))
        assertTrue(atOne.contains("content: var(--terebi-label-empty);"))
        assertTrue(atOne.contains("$list:empty::before {"))
    }

    @Test
    fun `the vendor fullscreen button is hidden`() {
        assertTrue(
            atOne.contains(
                "html[${QmsStripCss.attribute()}] header button " +
                    "{\ndisplay: none !important;\n}"
            )
        )
    }

    @Test
    fun `the card list becomes a horizontal, clipped row with a right fade`() {
        assertTrue(atOne.contains("flex-direction: row !important;"))
        assertTrue(atOne.contains("overflow: hidden !important;"))
        assertTrue(
            atOne.contains(
                "-webkit-mask-image: linear-gradient(to right, #000 88%, transparent);"
            )
        )
    }

    /**
     * Regression for the phone layout bug: at 617 dp the scale is 0.571, not
     * the old clamp's 1.0, so a 15 % header is 93 CSS px instead of 162 and the
     * WebView band (216 CSS px) is not consumed by it.
     *
     * 1080 * 15 % = 162 authored px; 162 x 0.571 rounds to 93 px. The old
     * `coerceAtLeast(1f)` kept the scale at 1.0 and emitted 162 px.
     */
    @Test
    fun `a phone render scale scales the header with the band`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val phone = QmsStripCss.build(settings.copy(headerBandPercent = 15), 0.571f)
        val headerPx = Regex(
            Regex.escape("$scope header") + " \\{[^}]*height: (\\d+)px"
        ).find(phone)
            ?.groupValues
            ?.get(1)
            ?.toInt()
        assertNotNull("no header rule at renderScale 0.571", headerPx)
        assertEquals(93, headerPx)
        // The header plus strip band is 35 % of the surface, 216 CSS px on the
        // device: the scaled header must leave the card row most of it.
        assertTrue("header $headerPx px does not fit the 216 px viewport", headerPx!! < 216)

        // The clamp would have emitted the authored 162 px here instead.
        val clamped = QmsStripCss.build(settings.copy(headerBandPercent = 15), 1f)
        assertTrue(clamped.contains("$scope header {\nposition: fixed !important;\n" +
            "top: 0 !important;"))
        assertTrue(clamped.contains("height: 162px !important;"))
    }

    @Test
    fun `renderScale doubles every emitted dimension`() {
        val currentNumber = derived(0.32, settings.currentNumberPx, 96f)
        val historyNumber = derived(0.22, settings.historyNumberPx, 56f)
        val label = derived(0.055, settings.labelPx, 15f)
        assertTrue(atOne.contains("min-width: ${settings.historyCardMinPx}px !important;"))
        assertTrue(atOne.contains("max-width: ${settings.historyCardMaxPx}px !important;"))
        assertTrue(atOne.contains("font-size: ${historyNumber}px !important;"))
        assertTrue(atOne.contains("font-size: ${label}px;"))
        assertTrue(atOne.contains("font-size: ${currentNumber}px !important;"))
        assertTrue(atOne.contains("flex: 0 0 ${settings.currentCardPx}px !important;"))

        assertTrue(atTwo.contains("min-width: ${settings.historyCardMinPx * 2}px !important;"))
        assertTrue(atTwo.contains("max-width: ${settings.historyCardMaxPx * 2}px !important;"))
        assertTrue(atTwo.contains("font-size: ${historyNumber * 2}px !important;"))
        assertTrue(atTwo.contains("font-size: ${label * 2}px;"))
        assertTrue(atTwo.contains("font-size: ${currentNumber * 2}px !important;"))
        assertTrue(atTwo.contains("flex: 0 0 ${settings.currentCardPx * 2}px !important;"))
    }

    /**
     * Live regression: the card was taller than the queue band, so its overflow
     * split above and below and the labels were hidden behind the fixed header.
     * The card must fit by construction: the type is derived from the band, the
     * card is the band's height less the list's 4 px top padding, and its own
     * padding is reduced to the label row plus one line.
     */
    @Test
    fun `the card fits the queue band by construction`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        val card = "$list > div"
        val small = settings.copy(stripHeightPercent = 20)
        // 1080 * 20 % = a 216 px band; 0.32 of it is 69.12, rounded to 69. The
        // ratio leaves room for a two-line location inside the band.
        assertEquals(69, (216 * 0.32).roundToInt())

        val css = QmsStripCss.build(small, 1f)
        val current = "$card:first-child ${small.qmsNumberSelector}"
        assertTrue(css.contains("$current {\nfont-size: 69px !important;\n}"))
        // The same ratio, scaled for a device with a different dp height.
        val atTwo = QmsStripCss.build(small, 2f)
        assertTrue(atTwo.contains("$current {\nfont-size: 138px !important;\n}"))

        // Anchored at a rule start: an unanchored match could find another rule
        // whose selector also ends in a card selector.
        val cardRule = Regex("(?m)^" + Regex.escape(card) + " \\{([^}]*)\\}").find(css)
        assertNotNull("card rule missing", cardRule)
        val cardBody = cardRule!!.groupValues[1]
        assertTrue(
            "card is not the band height",
            cardBody.contains("height: calc(100% - 4px) !important;")
        )
        assertTrue(
            "card does not cap its height",
            cardBody.contains("max-height: calc(100% - 4px) !important;")
        )
        assertFalse(
            "the card still claims the whole band",
            cardBody.contains("height: 100% !important;") ||
                cardBody.contains("max-height: 100% !important;")
        )
        assertTrue(
            "card padding still overflows the band",
            cardBody.contains("padding: 16px 8px 6px !important;")
        )

        val listRule = Regex("(?m)^" + Regex.escape(list) + " \\{([^}]*)\\}").find(css)
        assertNotNull("list rule missing", listRule)
        val listBody = listRule!!.groupValues[1]
        assertTrue(
            "the list has no top padding for the label",
            listBody.contains("padding: 4px 10px 10px 10px !important;")
        )
        assertTrue(
            "the list does not reset gap",
            listBody.contains("gap: 0 !important;")
        )
        assertTrue(
            "cards are not centred in the band",
            listBody.contains("align-items: center !important;")
        )
        assertTrue(
            "the list does not fill the queue band",
            listBody.contains("position: absolute !important;") &&
                listBody.contains("top: 0 !important;") &&
                listBody.contains("bottom: 0 !important;")
        )
        assertFalse(
            "the list still distributes height through flex",
            listBody.contains("flex: 1 1 0")
        )

        // The label sits inside the card's top padding and cannot straddle it.
        val label = "$card:first-child::before"
        val labelRule = Regex("(?m)^" + Regex.escape(label) + " \\{([^}]*)\\}").find(css)
        assertNotNull("label rule missing", labelRule)
        val labelBody = labelRule!!.groupValues[1]
        // 3px inset: at `top: 0` with `line-height: 1` the ascenders of the
        // uppercase label were clipped by the card's `overflow: hidden`.
        assertTrue(labelBody.contains("top: 3px !important;"))
        assertTrue(labelBody.contains("line-height: 1.1 !important;"))
        assertFalse(labelBody.contains("top: 0 !important;"))
        // 0.055 of the 216 px band is 11.88, rounded to 12.
        assertTrue(labelBody.contains("font-size: 12px;"))
    }

    /**
     * Two live device defects: the uppercase region labels (`SEDANG DIPANGGIL`,
     * `PANGGILAN TERDAHULU`) lost their ascenders at `top: 0` with
     * `line-height: 1`, and the location pill was left-aligned because
     * `display: -webkit-box` dropped the vendor's flex centring.
     */
    @Test
    fun `the region labels sit inside the card and the location is centred`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val list = "$scope ${settings.qmsStripSelector} > section > div"
        val card = "$list > div"

        // The label is absolutely positioned, so the card must be its
        // containing block; otherwise it resolves against the list and sits on
        // the band's top edge.
        val cardRule = Regex("(?m)^" + Regex.escape(card) + " \\{([^}]*)\\}").find(atOne)
        assertNotNull("card rule missing", cardRule)
        assertTrue(
            "the card is not the label's containing block",
            cardRule!!.groupValues[1].contains("position: relative !important;")
        )
        // `overflow: hidden` on the card is what clipped the ascenders, and the
        // card's top padding is what leaves the label room.
        assertTrue(cardRule.groupValues[1].contains("padding: 16px 8px 6px !important;"))

        for (label in listOf(
            "$card:first-child::before",
            "$card:nth-child(2)::before"
        )) {
            val rule = Regex("(?m)^" + Regex.escape(label) + " \\{([^}]*)\\}").find(atOne)
            assertNotNull("label rule missing: $label", rule)
            val body = rule!!.groupValues[1]
            assertTrue("$label keeps the old top", body.contains("top: 3px !important;"))
            assertTrue(
                "$label keeps the clipped line box",
                body.contains("line-height: 1.1 !important;")
            )
            assertTrue("$label moved off the card's left padding",
                body.contains("left: 10px !important;"))
            assertFalse("$label still straddles the card edge",
                body.contains("top: 0 !important;"))
        }
        // The inset scales with the render scale like every other dimension.
        val atTwoCard = Regex(
            "(?m)^" + Regex.escape(card) + ":first-child::before \\{([^}]*)\\}"
        ).find(atTwo)
        assertNotNull("label rule missing at renderScale 2", atTwoCard)
        assertTrue(atTwoCard!!.groupValues[1].contains("top: 6px !important;"))

        // The pill hugs its text, centres itself in the card and its row, and
        // puts each word on its own line ("Kaunter" over "11").
        val location = "$card ${settings.qmsLocationSelector}"
        val locationRule = Regex("(?m)^" + Regex.escape(location) + " \\{([^}]*)\\}").find(atOne)
        assertNotNull("location rule missing", locationRule)
        val locationBody = locationRule!!.groupValues[1]
        // A block container: `::first-line` does not apply to flex or -webkit-box.
        assertTrue(locationBody.contains("display: block !important;"))
        assertTrue(locationBody.contains("word-spacing: 100vw !important;"))
        assertTrue(locationBody.contains("width: min-content !important;"))
        assertTrue(locationBody.contains("height: auto !important;"))
        assertTrue(locationBody.contains("align-self: center !important;"))
        assertTrue(locationBody.contains("text-align: center !important;"))
        assertTrue(locationBody.contains("margin-left: auto !important;"))
        assertTrue(locationBody.contains("margin-right: auto !important;"))
    }

    @Test
    fun `the location word is smaller than its number`() {
        val card = "html[${QmsStripCss.attribute()}] ${settings.qmsStripSelector} > section > div > div"
        val location = "$card ${settings.qmsLocationSelector}"
        fun fontPx(body: String) = Regex("font-size: (\\d+)px").find(body)!!.groupValues[1].toInt()
        val pill = Regex("(?m)^" + Regex.escape(location) + " \\{([^}]*)\\}").find(atOne)!!.groupValues[1]
        val word = Regex(Regex.escape("$location::first-line") + " \\{([^}]*)\\}").find(atOne)
        assertNotNull("first-line rule missing", word)
        assertTrue(fontPx(word!!.groupValues[1]) < fontPx(pill))
    }

    @Test
    fun `labels are emitted as custom properties and read by the pseudo-elements`() {
        assertTrue(atOne.contains("--terebi-label-current:"))
        assertTrue(atOne.contains("--terebi-label-history:"))
        assertTrue(atOne.contains("content: var(--terebi-label-current);"))
        assertTrue(atOne.contains("content: var(--terebi-label-history);"))
        assertTrue(atOne.contains("html[${QmsStripCss.attribute()}] main > div:nth-child(1) " +
            "> section > div > div:first-child::before"))
    }

    @Test
    fun `labels are escaped`() {
        val css = QmsStripCss.build(
            settings = settings,
            renderScale = 1f,
            currentLabel = "A \"B\" \\ C",
            historyLabel = "x</style>",
            emptyLabel = "Tiada",
            unavailableLabel = "Q"
        )
        assertTrue(css.contains("--terebi-label-current: \"A \\\"B\\\" \\\\ C\";"))
        assertTrue(css.contains("--terebi-label-history: \"x\\3c /style\\3e \";"))
        assertFalse(css.contains("</style>"))
    }

    @Test
    fun `the stylesheet contains no closing style tag`() {
        assertFalse(atOne.contains("</style>", ignoreCase = true))
        assertFalse(atTwo.contains("</style>", ignoreCase = true))
    }

    @Test
    fun `the attribute name has one source and restore is an empty string`() {
        assertEquals("data-terebi-strip", QmsStripCss.attribute())
        assertEquals("", QmsStripCss.restore())
    }

    @Test
    fun `the unavailable notice is our string, not a vendor number`() {
        assertTrue(atOne.contains("[${QmsStripCss.stateAttribute()}=\"unavailable\"]"))
        assertTrue(atOne.contains("content: var(--terebi-label-unavailable);"))
        assertFalse(atOne.contains("qmsNow"))
    }
}
