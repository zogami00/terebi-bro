package com.terebibro.tv.mqtt

/**
 * Pure-JVM validation for `POST /api/settings/mqtt`.
 *
 * The whole request is validated before anything is written, so a bad value is
 * always `400` naming the field and **never** results in a partial write (the
 * same rule the trusted-networks endpoint follows). A `null` field means "leave
 * unchanged"; the returned [MqttUpdate] is the normalised value the store
 * applies in a single edit.
 */
object MqttSettingsValidator {

    const val MAX_HOST = 255
    const val MAX_CREDENTIAL = 512
    const val MAX_TOPIC = 256

    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    sealed class Result {
        data class Valid(val update: MqttUpdate) : Result()
        data class Invalid(val field: String, val message: String) : Result()
    }

    fun validate(update: MqttUpdate): Result {
        val host = update.host?.let { raw ->
            val trimmed = raw.trim()
            // A blank host is allowed: it simply means "not configured yet"
            // (the client is not connectable while the host is blank).
            if (trimmed.isNotEmpty()) {
                if (trimmed.length > MAX_HOST) return Result.Invalid("host", "host is too long (max $MAX_HOST)")
                if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
                    return Result.Invalid("host", "host must not contain whitespace or control characters")
                }
                if (trimmed.contains("://") || trimmed.contains('/') ||
                    trimmed.contains('+') || trimmed.contains('#') ||
                    trimmed.contains('@')
                ) {
                    return Result.Invalid("host", "host must be a bare hostname or IP address")
                }
                // A colon is only legal inside a bracketed IPv6 literal; elsewhere
                // it is the credential separator in `user:pw@host`, which Paho
                // would echo back in an IllegalArgumentException.
                if (trimmed.contains(':')) {
                    val bracketedIpv6 = trimmed.startsWith("[") && trimmed.endsWith("]")
                    if (!bracketedIpv6) {
                        return Result.Invalid("host", "host must be a bare hostname or IP address")
                    }
                }
            }
            trimmed
        }

        val port = update.port?.let { value ->
            if (value < MIN_PORT || value > MAX_PORT) {
                return Result.Invalid("port", "port must be between $MIN_PORT and $MAX_PORT")
            }
            value
        }

        val username = update.username?.let { value ->
            if (value.length > MAX_CREDENTIAL) {
                return Result.Invalid("username", "username is too long (max $MAX_CREDENTIAL)")
            }
            value
        }

        val password = update.password?.let { value ->
            if (value.length > MAX_CREDENTIAL) {
                return Result.Invalid("password", "password is too long (max $MAX_CREDENTIAL)")
            }
            value
        }

        val baseTopic = update.baseTopic?.let { raw ->
            val trimmed = raw.trim().trim('/')
            if (trimmed.length > MAX_TOPIC) {
                return Result.Invalid("baseTopic", "baseTopic is too long (max $MAX_TOPIC)")
            }
            if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
                return Result.Invalid("baseTopic", "baseTopic must not contain whitespace or control characters")
            }
            if (trimmed.contains('+') || trimmed.contains('#')) {
                return Result.Invalid("baseTopic", "baseTopic must not contain the wildcards '+' or '#'")
            }
            trimmed
        }

        return Result.Valid(
            MqttUpdate(
                enabled = update.enabled,
                host = host,
                port = port,
                tls = update.tls,
                username = username,
                password = password,
                baseTopic = baseTopic
            )
        )
    }
}
