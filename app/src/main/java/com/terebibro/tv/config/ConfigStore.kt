package com.terebibro.tv.config

import android.content.Context
import android.content.SharedPreferences
import com.terebibro.tv.media.MediaSettings
import com.terebibro.tv.media.MediaSettingsRecovery
import com.terebibro.tv.mqtt.MqttSettings
import com.terebibro.tv.mqtt.MqttTopics
import com.terebibro.tv.mqtt.MqttUpdate
import com.terebibro.tv.net.ClientNetworks
import com.terebibro.tv.security.PairedToken
import com.terebibro.tv.security.TokenStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences backed configuration.
 *
 * Token (and any future PIN) writes use [SharedPreferences.Editor.commit] so a
 * paired controller is never lost to a power cut; everything else uses apply().
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Set when the stored token blob could not be parsed; writes are refused to avoid data loss. */
    @Volatile
    private var tokenStorageCorrupt = false

    var homeUrl: String
        get() = prefs.getString(KEY_HOME_URL, DEFAULT_HOME_URL) ?: DEFAULT_HOME_URL
        set(value) = prefs.edit().putString(KEY_HOME_URL, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var controllerPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var fullscreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, DEFAULT_FULLSCREEN)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var autoRetry: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETRY, value).apply()

    var retryIntervalSec: Int
        get() = prefs.getInt(KEY_RETRY_INTERVAL, DEFAULT_RETRY_INTERVAL).coerceIn(1, 3600)
        set(value) = prefs.edit().putInt(KEY_RETRY_INTERVAL, value.coerceIn(1, 3600)).apply()

    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    /**
     * Additional trusted client networks (normalised `a.b.c.d/len` CIDR strings)
     * beyond the device's own subnet, empty by default. Stored as a JSON array
     * and written with the same `apply()` durability as the other settings; the
     * value is normalised on both read and write so a malformed or host-bit form
     * can never reach the peer policy.
     */
    var extraClientNetworks: List<String>
        get() = readExtraClientNetworks()
        set(value) = prefs.edit()
            .putString(KEY_EXTRA_NETWORKS, JSONArray(normaliseNetworks(value)).toString())
            .apply()

    /**
     * Sanitised DNS label for mDNS, without the `.local` suffix. Device names are
     * user supplied ("Living Room TV"), so they are lowercased, reduced to
     * `[a-z0-9-]`, trimmed of leading/trailing hyphens and capped at 63 chars.
     */
    val mdnsName: String get() = MdnsName.sanitize(deviceName, DEFAULT_DEVICE_NAME)

    // ---------------------------------------------------------------------
    // MQTT (off by default; broker credentials are the only gate for commands)
    // ---------------------------------------------------------------------

    var mqttEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ENABLED, MqttSettings.DEFAULT_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_ENABLED, value).apply()

    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, MqttSettings.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    var mqttTls: Boolean
        get() = prefs.getBoolean(KEY_MQTT_TLS, MqttSettings.DEFAULT_TLS)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_TLS, value).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASSWORD, value).apply()

    /**
     * The stored base topic, raw. Empty means "derive from the device name" so
     * the default (`terebibro/<mdnsName>`) tracks a device-name change.
     */
    var mqttBaseTopic: String
        get() = prefs.getString(KEY_MQTT_BASE_TOPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_BASE_TOPIC, value).apply()

    /** Home Assistant MQTT Discovery; only meaningful while MQTT is enabled. */
    var mqttDiscoveryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_DISCOVERY_ENABLED, MqttSettings.DEFAULT_DISCOVERY_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_DISCOVERY_ENABLED, value).apply()

    var mqttDiscoveryPrefix: String
        get() = prefs.getString(KEY_MQTT_DISCOVERY_PREFIX, MqttSettings.DEFAULT_DISCOVERY_PREFIX)
            ?: MqttSettings.DEFAULT_DISCOVERY_PREFIX
        set(value) = prefs.edit().putString(KEY_MQTT_DISCOVERY_PREFIX, value).apply()

    /**
     * The discovery prefix actually published on the last connect, or null if
     * discovery has never been published. Used to clear the configs left under
     * a previous prefix after the prefix setting changes; it is internal state,
     * not a user-facing setting, so it is not part of `MqttSettings`.
     */
    var mqttLastDiscoveryPrefix: String?
        get() = prefs.getString(KEY_MQTT_LAST_DISCOVERY_PREFIX, null)
        set(value) = prefs.edit().putString(KEY_MQTT_LAST_DISCOVERY_PREFIX, value).apply()

    /** The effective settings, with the base topic defaulted from the device name. */
    val mqttSettings: MqttSettings
        get() = MqttSettings(
            enabled = mqttEnabled,
            host = mqttHost,
            port = mqttPort,
            tls = mqttTls,
            username = mqttUsername,
            password = mqttPassword,
            baseTopic = MqttTopics.baseTopic(mqttBaseTopic, mdnsName),
            discoveryEnabled = mqttDiscoveryEnabled,
            discoveryPrefix = mqttDiscoveryPrefix
        )

    /**
     * Applies a validated partial update in one durable write, so a bad request
     * can never leave the MQTT settings half-changed.
     */
    fun applyMqttUpdate(update: MqttUpdate) {
        val editor = prefs.edit()
        update.enabled?.let { editor.putBoolean(KEY_MQTT_ENABLED, it) }
        update.host?.let { editor.putString(KEY_MQTT_HOST, it) }
        update.port?.let { editor.putInt(KEY_MQTT_PORT, it) }
        update.tls?.let { editor.putBoolean(KEY_MQTT_TLS, it) }
        update.username?.let { editor.putString(KEY_MQTT_USERNAME, it) }
        update.password?.let { editor.putString(KEY_MQTT_PASSWORD, it) }
        update.baseTopic?.let { editor.putString(KEY_MQTT_BASE_TOPIC, it) }
        update.discoveryEnabled?.let { editor.putBoolean(KEY_MQTT_DISCOVERY_ENABLED, it) }
        update.discoveryPrefix?.let { editor.putString(KEY_MQTT_DISCOVERY_PREFIX, it) }
        editor.commit()
    }

    // ---------------------------------------------------------------------
    // Media mode (off by default; behaviour is V1 while it is off)
    // ---------------------------------------------------------------------

    /**
     * The effective media settings, recovered field by field from the stored
     * blob (see [MediaSettingsRecovery]). A single bad value falls back to its
     * own default and never discards the rest of the settings.
     *
     * A blank stored QMS URL is reported as the Home URL so enabling media mode
     * without configuring one targets the dashboard; the blank stays stored, so
     * it keeps tracking later Home URL changes.
     */
    val mediaSettings: MediaSettings
        get() = MediaSettingsRecovery.recover(HashMap(prefs.all), homeUrl)

    /**
     * The merge base for `POST /api/settings/media`: the same stored blob, but
     * with a blank QMS URL left blank. Validating and persisting against this
     * view keeps the read-time Home URL substitution from being written back,
     * so a blank value keeps tracking later Home URL changes.
     */
    val mediaSettingsForUpdate: MediaSettings
        get() = MediaSettingsRecovery.recover(HashMap(prefs.all))

    /** Writes every media setting in one durable edit, mirroring [applyMqttUpdate]. */
    fun applyMediaUpdate(settings: MediaSettings) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_MEDIA_ENABLED, settings.enabled)
        editor.putString(KEY_MEDIA_QMS_URL, settings.qmsUrl)
        editor.putString(KEY_MEDIA_QMS_STRIP_SELECTOR, settings.qmsStripSelector)
        editor.putString(KEY_MEDIA_QMS_NUMBER_SELECTOR, settings.qmsNumberSelector)
        editor.putString(KEY_MEDIA_QMS_LOCATION_SELECTOR, settings.qmsLocationSelector)
        editor.putString(KEY_MEDIA_QMS_TIME_SELECTOR, settings.qmsTimeSelector)
        editor.putString(KEY_MEDIA_CALL_SOURCE, settings.callSource.wire)
        editor.putInt(KEY_MEDIA_DWELL_SEC, settings.dwellSec)
        editor.putInt(KEY_MEDIA_SPLIT_PERCENT, settings.mediaSplitPercent)
        editor.putInt(KEY_MEDIA_HEADER_PERCENT, settings.headerBandPercent)
        editor.putInt(KEY_MEDIA_STRIP_PERCENT, settings.stripHeightPercent)
        editor.putInt(KEY_MEDIA_CURRENT_NUMBER_PX, settings.currentNumberPx)
        editor.putInt(KEY_MEDIA_HISTORY_NUMBER_PX, settings.historyNumberPx)
        editor.putInt(KEY_MEDIA_LABEL_PX, settings.labelPx)
        editor.putInt(KEY_MEDIA_CURRENT_CARD_PX, settings.currentCardPx)
        editor.putInt(KEY_MEDIA_HISTORY_CARD_MIN_PX, settings.historyCardMinPx)
        editor.putInt(KEY_MEDIA_HISTORY_CARD_MAX_PX, settings.historyCardMaxPx)
        editor.putString(KEY_MEDIA_UI_LANGUAGE, settings.uiLanguage.wire)
        editor.putBoolean(KEY_MEDIA_LOCAL_ENABLED, settings.mediaLocalEnabled)
        editor.putBoolean(KEY_MEDIA_PLAYLIST_ENABLED, settings.playlistEnabled)
        editor.putString(KEY_MEDIA_PLAYLIST_URL, settings.playlistUrl)
        editor.putInt(KEY_MEDIA_PLAYLIST_REFRESH_SEC, settings.playlistRefreshSec)
        editor.putInt(KEY_MEDIA_IMAGE_DURATION_SEC, settings.imageDurationSec)
        editor.putInt(KEY_MEDIA_VOLUME_PERCENT, settings.volumePercent)
        editor.putString(KEY_MEDIA_FILL_MODE, settings.mediaFillMode.wire)
        editor.putBoolean(KEY_MEDIA_NEW_CALL_HIGHLIGHT, settings.newCallHighlight)
        editor.putString(KEY_MEDIA_STRIP_POSITION, settings.stripPosition.wire)
        editor.putInt(KEY_MEDIA_UI_SCALE, settings.uiScale)
        editor.commit()
    }

    /** Durable storage adapter for the paired-controller registry. */
    val tokenStorage: TokenStorage = object : TokenStorage {
        override fun load(): List<PairedToken> = readTokens()

        override fun save(tokens: List<PairedToken>): Boolean {
            if (tokenStorageCorrupt) {
                // Refuse to overwrite a blob we could not parse: doing so would
                // silently destroy every previously paired controller.
                return false
            }
            return writeTokens(tokens)
        }

        override fun forceSave(tokens: List<PairedToken>): Boolean {
            // An explicit TV-side revoke-all is the recovery path: drop the
            // corrupt guard and overwrite the unreadable blob.
            tokenStorageCorrupt = false
            return writeTokens(tokens)
        }
    }

    private fun writeTokens(tokens: List<PairedToken>): Boolean {
        val array = JSONArray()
        for (token in tokens) {
            val obj = JSONObject()
            obj.put("id", token.id)
            obj.put("sha256Hex", token.sha256Hex)
            obj.put("clientName", token.clientName)
            obj.put("createdAt", token.createdAt)
            obj.put("lastSeen", token.lastSeen)
            array.put(obj)
        }
        return prefs.edit().putString(KEY_PAIRED_TOKENS, array.toString()).commit()
    }

    private fun readExtraClientNetworks(): List<String> {
        val raw = prefs.getString(KEY_EXTRA_NETWORKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            normaliseNetworks(List(array.length()) { array.optString(it) })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Normalises to valid CIDR strings, dropping anything unparseable, de-duplicated in order. */
    private fun normaliseNetworks(values: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (value in values) ClientNetworks.normalise(value)?.let { result.add(it) }
        return result.toList().take(ClientNetworks.MAX_ENTRIES)
    }

    private fun readTokens(): List<PairedToken> {
        val raw = prefs.getString(KEY_PAIRED_TOKENS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        PairedToken(
                            id = obj.optString("id"),
                            sha256Hex = obj.optString("sha256Hex"),
                            clientName = obj.optString("clientName"),
                            createdAt = obj.optLong("createdAt"),
                            lastSeen = obj.optLong("lastSeen")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            tokenStorageCorrupt = true
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "terebi_config"
        private const val KEY_HOME_URL = "homeUrl"
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_PORT = "controllerPort"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_KEEP_AWAKE = "keepScreenAwake"
        private const val KEY_AUTO_RETRY = "autoRetry"
        private const val KEY_RETRY_INTERVAL = "retryIntervalSec"
        private const val KEY_PAIRED_TOKENS = "pairedTokens"
        private const val KEY_SETUP_DONE = "setupDone"
        private const val KEY_EXTRA_NETWORKS = "extraClientNetworks"
        private const val KEY_MQTT_ENABLED = "mqttEnabled"
        private const val KEY_MQTT_HOST = "mqttHost"
        private const val KEY_MQTT_PORT = "mqttPort"
        private const val KEY_MQTT_TLS = "mqttTls"
        private const val KEY_MQTT_USERNAME = "mqttUsername"
        private const val KEY_MQTT_PASSWORD = "mqttPassword"
        private const val KEY_MQTT_BASE_TOPIC = "mqttBaseTopic"
        private const val KEY_MQTT_DISCOVERY_ENABLED = "mqttDiscoveryEnabled"
        private const val KEY_MQTT_DISCOVERY_PREFIX = "mqttDiscoveryPrefix"
        private const val KEY_MQTT_LAST_DISCOVERY_PREFIX = "mqttLastDiscoveryPrefix"

        private const val KEY_MEDIA_ENABLED = MediaSettingsRecovery.Keys.ENABLED
        private const val KEY_MEDIA_QMS_URL = MediaSettingsRecovery.Keys.QMS_URL
        private const val KEY_MEDIA_QMS_STRIP_SELECTOR = MediaSettingsRecovery.Keys.QMS_STRIP_SELECTOR
        private const val KEY_MEDIA_QMS_NUMBER_SELECTOR = MediaSettingsRecovery.Keys.QMS_NUMBER_SELECTOR
        private const val KEY_MEDIA_QMS_LOCATION_SELECTOR = MediaSettingsRecovery.Keys.QMS_LOCATION_SELECTOR
        private const val KEY_MEDIA_QMS_TIME_SELECTOR = MediaSettingsRecovery.Keys.QMS_TIME_SELECTOR
        private const val KEY_MEDIA_CALL_SOURCE = MediaSettingsRecovery.Keys.CALL_SOURCE
        private const val KEY_MEDIA_DWELL_SEC = MediaSettingsRecovery.Keys.DWELL_SEC
        private const val KEY_MEDIA_SPLIT_PERCENT = MediaSettingsRecovery.Keys.SPLIT_PERCENT
        private const val KEY_MEDIA_HEADER_PERCENT = MediaSettingsRecovery.Keys.HEADER_PERCENT
        private const val KEY_MEDIA_STRIP_PERCENT = MediaSettingsRecovery.Keys.STRIP_PERCENT
        private const val KEY_MEDIA_CURRENT_NUMBER_PX = MediaSettingsRecovery.Keys.CURRENT_NUMBER_PX
        private const val KEY_MEDIA_HISTORY_NUMBER_PX = MediaSettingsRecovery.Keys.HISTORY_NUMBER_PX
        private const val KEY_MEDIA_LABEL_PX = MediaSettingsRecovery.Keys.LABEL_PX
        private const val KEY_MEDIA_CURRENT_CARD_PX = MediaSettingsRecovery.Keys.CURRENT_CARD_PX
        private const val KEY_MEDIA_HISTORY_CARD_MIN_PX = MediaSettingsRecovery.Keys.HISTORY_CARD_MIN_PX
        private const val KEY_MEDIA_HISTORY_CARD_MAX_PX = MediaSettingsRecovery.Keys.HISTORY_CARD_MAX_PX
        private const val KEY_MEDIA_UI_LANGUAGE = MediaSettingsRecovery.Keys.UI_LANGUAGE
        private const val KEY_MEDIA_LOCAL_ENABLED = MediaSettingsRecovery.Keys.LOCAL_ENABLED
        private const val KEY_MEDIA_PLAYLIST_ENABLED = MediaSettingsRecovery.Keys.PLAYLIST_ENABLED
        private const val KEY_MEDIA_PLAYLIST_URL = MediaSettingsRecovery.Keys.PLAYLIST_URL
        private const val KEY_MEDIA_PLAYLIST_REFRESH_SEC = MediaSettingsRecovery.Keys.PLAYLIST_REFRESH_SEC
        private const val KEY_MEDIA_IMAGE_DURATION_SEC = MediaSettingsRecovery.Keys.IMAGE_DURATION_SEC
        private const val KEY_MEDIA_VOLUME_PERCENT = MediaSettingsRecovery.Keys.VOLUME_PERCENT
        private const val KEY_MEDIA_FILL_MODE = MediaSettingsRecovery.Keys.FILL_MODE
        private const val KEY_MEDIA_NEW_CALL_HIGHLIGHT = MediaSettingsRecovery.Keys.NEW_CALL_HIGHLIGHT
        private const val KEY_MEDIA_STRIP_POSITION = MediaSettingsRecovery.Keys.STRIP_POSITION
        private const val KEY_MEDIA_UI_SCALE = MediaSettingsRecovery.Keys.UI_SCALE

        const val DEFAULT_HOME_URL = "https://example.com"
        const val DEFAULT_DEVICE_NAME = "terebi-tv"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_FULLSCREEN = true
        const val DEFAULT_RETRY_INTERVAL = 10
    }
}
