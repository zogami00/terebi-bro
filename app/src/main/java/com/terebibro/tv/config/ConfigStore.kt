package com.terebibro.tv.config

import android.content.Context
import android.content.SharedPreferences
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

    /** The effective settings, with the base topic defaulted from the device name. */
    val mqttSettings: MqttSettings
        get() = MqttSettings(
            enabled = mqttEnabled,
            host = mqttHost,
            port = mqttPort,
            tls = mqttTls,
            username = mqttUsername,
            password = mqttPassword,
            baseTopic = MqttTopics.baseTopic(mqttBaseTopic, mdnsName)
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

        const val DEFAULT_HOME_URL = "https://example.com"
        const val DEFAULT_DEVICE_NAME = "terebi-tv"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_FULLSCREEN = true
        const val DEFAULT_RETRY_INTERVAL = 10
    }
}
