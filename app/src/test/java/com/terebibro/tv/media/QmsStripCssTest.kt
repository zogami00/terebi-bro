package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QmsStripCssTest {

    private val settings = MediaSettings()
    private val atOne = QmsStripCss.build(settings, 1f)
    private val atTwo = QmsStripCss.build(settings, 2f)

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
    fun `the height chain is forced under the attribute`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        assertTrue(atOne.contains("$scope, $scope body, $scope main, " +
            "$scope ${settings.qmsStripSelector} {\nheight: 100% !important;\n}"))
    }

    @Test
    fun `cards are spaced with adjacent-sibling margins, never flex gap`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val card = "$scope ${settings.qmsStripSelector} > section > div > div"
        assertTrue(atOne.contains("$card + $card {\nmargin-left: 12px !important;\n}"))
        assertFalse(atOne.contains("gap:"))
    }

    /**
     * The reference device is Chromium 66. Each feature below postdates it and
     * would be silently dropped by that engine, leaving the strip wrong.
     */
    @Test
    fun `the sheet uses no CSS feature newer than Chromium 66`() {
        val forbidden = listOf(
            "gap:",
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
    }

    @Test
    fun `field sizes come from the configured selectors relative to a card`() {
        val scope = "html[${QmsStripCss.attribute()}]"
        val card = "$scope ${settings.qmsStripSelector} > section > div > div"
        assertTrue(atOne.contains("$card ${settings.qmsNumberSelector} {"))
        assertTrue(atOne.contains("$card ${settings.qmsLocationSelector} {"))
        assertTrue(atOne.contains("$card ${settings.qmsTimeSelector} {"))
        assertTrue(atOne.contains(
            "$card:first-child ${settings.qmsNumberSelector} " +
                "{\nfont-size: ${settings.currentNumberPx}px !important;\n}"
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

    @Test
    fun `renderScale doubles every emitted dimension`() {
        assertTrue(atOne.contains("min-width: ${settings.historyCardMinPx}px !important;"))
        assertTrue(atOne.contains("max-width: ${settings.historyCardMaxPx}px !important;"))
        assertTrue(atOne.contains("font-size: ${settings.historyNumberPx}px !important;"))
        assertTrue(atOne.contains("font-size: ${settings.labelPx}px;"))
        assertTrue(atOne.contains("font-size: ${settings.currentNumberPx}px !important;"))
        assertTrue(atOne.contains("flex: 0 0 ${settings.currentCardPx}px !important;"))

        assertTrue(atTwo.contains("min-width: ${settings.historyCardMinPx * 2}px !important;"))
        assertTrue(atTwo.contains("max-width: ${settings.historyCardMaxPx * 2}px !important;"))
        assertTrue(atTwo.contains("font-size: ${settings.historyNumberPx * 2}px !important;"))
        assertTrue(atTwo.contains("font-size: ${settings.labelPx * 2}px;"))
        assertTrue(atTwo.contains("font-size: ${settings.currentNumberPx * 2}px !important;"))
        assertTrue(atTwo.contains("flex: 0 0 ${settings.currentCardPx * 2}px !important;"))
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
