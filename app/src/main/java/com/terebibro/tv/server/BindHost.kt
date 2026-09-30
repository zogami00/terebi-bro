package com.terebibro.tv.server

/**
 * Pure-JVM choice of listener bind host, kept separate from [ControllerServer]
 * so it can be unit tested without the Android framework.
 *
 * `null` means "bind the wildcard address" (all interfaces). NanoHTTPD 2.3.1
 * binds `new InetSocketAddress(port)` for a null hostname — verified against the
 * pinned `nanohttpd-2.3.1` source and bytecode — which is the wildcard address,
 * so loopback is reachable through `adb forward`. In release builds
 * [allowLocalhost] is always false and the advertised LAN address is bound
 * explicitly.
 */
object BindHost {

    /**
     * @return [ip] to bind it explicitly, or `null` to bind the wildcard address
     *   when loopback must be reachable (debug only).
     */
    fun of(allowLocalhost: Boolean, ip: String): String? =
        if (allowLocalhost) null else ip
}
