package com.terebibro.tv.media

/**
 * The identity of one queue call: `number | location | time`.
 *
 * The location and time are part of the key on purpose. A re-call
 * (`A1001` at `Kaunter 1`, then `A1001` at `Kaunter 3`) must fire again, and a
 * "did the number change" comparison would miss it.
 *
 * Whitespace is collapsed **for the key only**. The display strings reach the
 * state unchanged because a normalised copy on screen is a rewritten copy, and
 * a wording change on the vendor page must never require an app change.
 */
data class QmsCallKey(val number: String, val location: String, val time: String) {

    fun composite(): String = "$number|$location|$time"

    companion object {

        private val WHITESPACE = Regex("\\s+")

        fun from(number: String, location: String, time: String): QmsCallKey = QmsCallKey(
            number = normalise(number),
            location = normalise(location),
            time = normalise(time)
        )

        /**
         * True when [current] is a call that has not been seen as the head of
         * the list before.
         *
         * The first observation after launch is **not** a call: [previous] is
         * null because no baseline exists yet, and a cold start with a stale
         * head card would otherwise take the whole screen for `dwellSec`. The
         * first observation only seeds the baseline; a call needs a change from
         * it. An identical key is a re-render, and a missing current key is
         * never a call.
         */
        fun isNewCall(previous: QmsCallKey?, current: QmsCallKey?): Boolean = when {
            current == null -> false
            previous == null -> false
            else -> previous != current
        }

        private fun normalise(value: String): String = value.trim().replace(WHITESPACE, " ")
    }
}
