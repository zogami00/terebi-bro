package com.terebibro.tv.server

import com.terebibro.tv.media.MediaMode
import com.terebibro.tv.media.MediaSettings
import com.terebibro.tv.mqtt.MqttStatus
import com.terebibro.tv.web.BrowserState

/** Display metrics in dp, as the device reports them (not physical pixels). */
data class ScreenMetrics(val widthDp: Int, val heightDp: Int, val densityDpi: Int)

/**
 * The application-side operations the control API needs, implemented by
 * [com.terebibro.tv.MainActivity]. Every method must be safe to call from a
 * server thread (the implementation hops to the UI thread itself).
 */
interface ControllerHost {

    fun buildState(): BrowserState?

    fun currentPin(): String?

    fun issuePin(): String?

    fun clearPin()

    fun pairedCount(): Int

    fun openUrl(url: String, setHome: Boolean): ApiResult

    fun setHomeUrl(url: String): ApiResult

    /** [action] is one of home/back/forward/reload/stop. */
    fun navAction(action: String): ApiResult

    fun restartWebView(): ApiResult

    fun clearCache(): ApiResult

    fun clearSiteData(): ApiResult

    fun setDisplay(fullscreen: Boolean?, keepAwake: Boolean?): ApiResult

    fun dpad(key: String): ApiResult

    fun setDeviceName(name: String): ApiResult

    fun setControllerPort(port: Int): ApiResult

    /** Read-only MQTT connection status for `GET /api/settings/mqtt` and `/api/device`. */
    fun mqttStatus(): MqttStatus

    /** Called after the persisted MQTT settings change, so the client reconnects. */
    fun onMqttSettingsChanged()

    /** The persisted media settings, for `GET /api/settings/media`. */
    fun mediaSettingsSnapshot(): MediaSettings

    /**
     * Applies a manual mode; `QMS_FOCUS` is refused, as is any mode while media
     * mode is disabled. `QMS_ONLY` holds indefinitely, `MEDIA_ONLY` for
     * `MANUAL_MEDIA_HOLD_MS`, and `SPLIT` drops the hold.
     */
    fun setMediaMode(mode: MediaMode): ApiResult

    /** Called after the persisted media settings change, so the mode is re-derived. */
    fun onMediaSettingsChanged()

    /** Plays an ad-hoc URL on the media band; `invalid_url` for a non-http(s) value. */
    fun openMediaUrl(url: String): ApiResult

    /** The dp metrics the layout is authored against. */
    fun screenMetrics(): ScreenMetrics
}
