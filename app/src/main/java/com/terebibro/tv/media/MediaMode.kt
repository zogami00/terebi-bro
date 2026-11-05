package com.terebibro.tv.media

/**
 * The four display modes. Pure-JVM: the reducer, the settings and the API all
 * speak this enum, so no Android type is needed to test a transition.
 */
enum class MediaMode(val wire: String) {
    QMS_ONLY("qms_only"),
    MEDIA_ONLY("media_only"),
    SPLIT("split"),
    QMS_FOCUS("qms_focus");

    companion object {
        /** Parses the wire name; null for anything unknown. */
        fun fromWire(value: String): MediaMode? = entries.firstOrNull { it.wire == value }

        /**
         * Parses a mode an operator may request from `POST /api/media/mode`.
         * `QMS_FOCUS` is the reducer's own transient mode, so it is rejected
         * exactly like an unknown value.
         */
        fun manualOrNull(wire: String): MediaMode? =
            fromWire(wire)?.takeIf { it != QMS_FOCUS }
    }
}
