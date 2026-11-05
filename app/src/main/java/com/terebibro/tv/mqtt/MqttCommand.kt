package com.terebibro.tv.mqtt

import com.terebibro.tv.media.MediaMode
import com.terebibro.tv.server.Json
import com.terebibro.tv.web.UrlValidator
import java.io.ByteArrayInputStream

/** The fixed set of MQTT command actions (spec contract, agreed with the user). */
enum class MqttAction {
    OPEN,
    SET_HOME,
    HOME,
    BACK,
    FORWARD,
    RELOAD,
    STOP,
    RESTART_WEBVIEW,
    CLEAR_CACHE,
    CLEAR_SITE_DATA,
    MEDIA_NEXT,
    MEDIA_PLAY,
    MEDIA_PAUSE,
    SET_MODE;

    companion object {
        /** Parses the wire name; null for anything unknown. */
        fun fromWire(value: String): MqttAction? = when (value) {
            "open" -> OPEN
            "set-home" -> SET_HOME
            "home" -> HOME
            "back" -> BACK
            "forward" -> FORWARD
            "reload" -> RELOAD
            "stop" -> STOP
            "restart-webview" -> RESTART_WEBVIEW
            "clear-cache" -> CLEAR_CACHE
            "clear-site-data" -> CLEAR_SITE_DATA
            "media-next" -> MEDIA_NEXT
            "media-play" -> MEDIA_PLAY
            "media-pause" -> MEDIA_PAUSE
            "set-mode" -> SET_MODE
            else -> null
        }
    }
}

/**
 * A parsed, validated MQTT command.
 *
 * [url] is non-null exactly for [MqttAction.OPEN] and [MqttAction.SET_HOME], and
 * it has already been through [UrlValidator] (so a `javascript:`/`data:` URL can
 * never reach the WebView). [mode] is non-null exactly for
 * [MqttAction.SET_MODE] and is already restricted to a manual mode. [id] is the
 * optional caller correlation id echoed back on `command/result`.
 */
data class MqttCommand(
    val action: MqttAction,
    val url: String? = null,
    val id: String? = null,
    val mode: MediaMode? = null
)

/**
 * Strict parser for the `<base>/command` payload:
 * `{"action":"...", "url":"...", "id":"optional"}`.
 *
 * Pure-JVM and total: unknown actions, a missing `url` for `open`/`set-home`,
 * an invalid URL, a wrong `id` type and malformed JSON all return a [Result.Failed]
 * with a stable code and a human-readable message — never an exception, so a
 * bad message on the broker can never crash or wedge the app. Unknown keys are
 * rejected, matching the REST API's strict-JSON rules.
 */
object MqttCommandParser {

    /**
     * The union of every key any action may carry. The per-action allowed set
     * is enforced below with [allowedKeysFor], so `mode` is not silently
     * accepted on an action that has no use for it.
     */
    private val ALLOWED_KEYS = setOf("action", "url", "id", "mode")

    /** Actions that carry no value: `media-next`, `media-play`, `media-pause`. */
    private val VALUE_FREE_KEYS = setOf("action", "id")

    /** `set-mode` requires `mode`; a URL is meaningless on it. */
    private val SET_MODE_KEYS = setOf("action", "mode", "id")

    /** The existing browser actions: only `open`/`set-home` *require* a URL. */
    private val BROWSER_KEYS = setOf("action", "url", "id")

    /** The modes an operator may request; `qms_focus` is the reducer's own. */
    private const val ALLOWED_MODES = "qms_only, media_only, split"

    /** Bounds any free-form string from the broker so a payload cannot be unbounded. */
    private const val MAX_ID = 128
    private const val MAX_URL = UrlValidator.MAX_LENGTH

    /**
     * Longest unknown action name echoed back verbatim. The name is
     * attacker-controlled, so it is truncated before it reaches
     * `command/result`.
     */
    const val MAX_ACTION_ECHO = 32

    sealed class Result {
        data class Parsed(val command: MqttCommand) : Result()
        data class Failed(val code: String, val message: String) : Result()
    }

    fun parse(text: String): Result {
        val values = try {
            Json.parseObject(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), ALLOWED_KEYS)
        } catch (e: Json.BadJson) {
            return Result.Failed("malformed_json", "Command payload is not valid JSON (${e.message})")
        } catch (e: Exception) {
            return Result.Failed("malformed_json", "Command payload is not valid JSON")
        }

        val actionText = (values["action"] as? Json.Value.Str)?.value
            ?: return Result.Failed("missing_action", "Missing or non-string \"action\"")
        val action = MqttAction.fromWire(actionText)
            ?: return Result.Failed("unknown_action", "Unknown action: ${actionText.take(MAX_ACTION_ECHO)}")

        // Each action has its own strict key set: a value carried on an action
        // that cannot use it is rejected, not silently ignored.
        val unexpected = values.keys - allowedKeysFor(action)
        if (unexpected.isNotEmpty()) {
            return Result.Failed(
                "malformed_json",
                "Unexpected key for \"$actionText\": ${unexpected.sorted().first().take(MAX_ACTION_ECHO)}"
            )
        }

        val id = when (val parsedId = parseId(values)) {
            is IdResult.Ok -> parsedId.value
            is IdResult.Bad -> return Result.Failed(parsedId.code, parsedId.message)
        }

        var mode: MediaMode? = null
        if (action == MqttAction.SET_MODE) {
            val raw = values["mode"] as? Json.Value.Str
                ?: return Result.Failed(
                    "bad_request",
                    "Action \"set-mode\" requires a \"mode\": $ALLOWED_MODES"
                )
            mode = MediaMode.manualOrNull(raw.value)
                ?: return Result.Failed(
                    "bad_request",
                    "Unknown mode \"${raw.value.take(MAX_ACTION_ECHO)}\": allowed values are $ALLOWED_MODES"
                )
        }

        var url: String? = null
        if (action == MqttAction.OPEN || action == MqttAction.SET_HOME) {
            val raw = values["url"] as? Json.Value.Str
                ?: return Result.Failed("missing_url", "Action \"$actionText\" requires a \"url\"")
            if (raw.value.length > MAX_URL) {
                return Result.Failed("invalid_url", "Rejected URL (too_long)")
            }
            url = when (val validated = UrlValidator.validate(raw.value)) {
                is UrlValidator.Result.Invalid ->
                    return Result.Failed("invalid_url", "Rejected URL (${validated.error})")
                is UrlValidator.Result.Valid -> validated.url
            }
        }

        return Result.Parsed(MqttCommand(action, url, id, mode))
    }

    /** The keys each action may carry. Anything else is a malformed command. */
    private fun allowedKeysFor(action: MqttAction): Set<String> = when (action) {
        MqttAction.MEDIA_NEXT, MqttAction.MEDIA_PLAY, MqttAction.MEDIA_PAUSE -> VALUE_FREE_KEYS
        MqttAction.SET_MODE -> SET_MODE_KEYS
        else -> BROWSER_KEYS
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private sealed class IdResult {
        data class Ok(val value: String?) : IdResult()
        data class Bad(val code: String, val message: String) : IdResult()
    }

    /** The `id` is optional and may be a string or a whole number. */
    private fun parseId(values: Map<String, Json.Value>): IdResult = when (val raw = values["id"]) {
        null -> IdResult.Ok(null)
        is Json.Value.Str ->
            if (raw.value.length > MAX_ID) {
                IdResult.Bad("bad_id", "\"id\" is too long")
            } else {
                IdResult.Ok(raw.value)
            }
        is Json.Value.Num ->
            if (raw.value % 1.0 != 0.0) {
                IdResult.Bad("bad_id", "\"id\" must be a string or whole number")
            } else {
                IdResult.Ok(raw.value.toLong().toString())
            }
        else -> IdResult.Bad("bad_id", "\"id\" must be a string or whole number")
    }
}
