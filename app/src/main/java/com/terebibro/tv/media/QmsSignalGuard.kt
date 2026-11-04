package com.terebibro.tv.media

/** The only five signals the content-free JavaScript sink accepts. */
enum class QmsSignalKind(val wire: String) {
    NOW("now"),
    PROBE("probe"),
    AUDIO("audio"),
    READY("ready"),
    VISIBILITY("visibility");

    companion object {
        fun fromWire(s: String): QmsSignalKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Sanitises and clamps the values the page may push through the bridge.
 *
 * A value is truncated to [MAX_VALUE_LENGTH] characters and stripped of control
 * characters before it reaches the controller, so no amount of page content can
 * grow the published state without bound. The unit separator is the one
 * exception: it is the field delimiter the `now` signal is built from.
 * A signal of the same kind within [CLAMP_MS] of the previous accepted one is
 * dropped: the observer and the fallback interval can otherwise report the same
 * DOM change twice.
 */
class QmsSignalGuard(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastAcceptedMs = HashMap<QmsSignalKind, Long>()

    fun accept(kind: QmsSignalKind, value: String?, nowMs: Long = clock()): String? {
        if (value == null) return null
        val clean = value
            .filterNot { (it.code < 0x20 && it != FIELD_SEPARATOR) || it.code == 0x7F }
            .take(MAX_VALUE_LENGTH)
        val previous = lastAcceptedMs[kind]
        // A backwards clock is treated like a too-close signal rather than
        // letting a stale timestamp win.
        if (previous != null && nowMs - previous < CLAMP_MS) return null
        lastAcceptedMs[kind] = nowMs
        return clean
    }

    companion object {
        const val CLAMP_MS = 500L
        const val MAX_VALUE_LENGTH = 64

        /** Separates `number`, `location` and `time` in a `now` signal. */
        const val FIELD_SEPARATOR = '\u001f'
    }
}
