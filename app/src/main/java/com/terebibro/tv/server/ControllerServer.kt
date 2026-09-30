package com.terebibro.tv.server

import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.net.LocalNetwork
import com.terebibro.tv.util.SafeLog
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ClientHandler
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Lets [BoundedAsyncRunner] read the accepted socket address of a handler. */
internal interface PeerAddressAware {
    val peerAddress: String?
}

/**
 * NanoWSD control server bound explicitly to the active LAN IPv4 address
 * (IPv4 only, no IPv6 listener).
 *
 * It rebinds when the address or configured port changes and stops entirely
 * when there is no network. A failed bind never leaves the previous listener
 * down, and it falls back to the default port. Whenever the listener ends up on
 * a port other than the configured one, the real port is written back to the
 * config so the Host allowlist, the mDNS advertisement and the URL shown on the
 * TV all keep pointing at the address that is actually serving. The socket read
 * timeout is raised above the WebSocket heartbeat so idle sockets are not
 * dropped.
 */
class ControllerServer(
    private val config: ConfigStore,
    private val localNetwork: LocalNetwork,
    private val auth: AuthManager,
    private val apiRoutes: ApiRoutes,
    private val wsHub: WsHub,
    /**
     * Debug-only: bind the wildcard address instead of the LAN address so a
     * NAT'd emulator's loopback is reachable through `adb forward`. Always false
     * in release builds. [boundIp] still records the advertised LAN address, so
     * the Host allowlist, the TV overlay URL and the mDNS advertisement are
     * unaffected.
     */
    private val allowLocalhostBind: Boolean = false,
    /**
     * Invoked whenever the listener comes up on a port that differs from the
     * previous one, including after a successful fallback retry. Lets the owner
     * re-advertise the actual port over mDNS; without it the SRV record would
     * keep pointing at the old port. Runs on whatever thread bound the server,
     * so the callback must hop off it before touching the UI.
     */
    private val onBound: () -> Unit = {}
) {

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var boundIp: String? = null

    @Volatile
    private var boundPort: Int = -1

    /** Set by [stop]; keeps a queued or racing retry from starting a listener. */
    @Volatile
    private var stopped = false

    private val perPeerLimiter = ConnectionLimiter(MAX_CONNECTIONS_PER_PEER)

    private val retryAttempts = AtomicInteger(0)

    @Volatile
    private var retryFuture: ScheduledFuture<*>? = null

    private val retryExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "terebi-bind-retry").apply { isDaemon = true }
        }

    /**
     * Binds to the current LAN address; safe to call repeatedly. Returns early
     * when already bound to the same address and port. If the configured port
     * cannot be bound it retries the default port and then keeps the previously
     * working listener rather than stopping the server.
     */
    @Synchronized
    fun rebind() {
        // A bind retry already blocked on this monitor can still run after
        // stop(); never start a listener on a stopped server.
        if (stopped) return
        val info = localNetwork.current()
        if (info == null) {
            stopLocked()
            SafeLog.i(TAG, "No LAN address; control server not started")
            return
        }
        val desiredPort = config.controllerPort
        val current = server
        if (current != null && current.wasStarted() && boundIp == info.ip && boundPort == desiredPort) {
            retryAttempts.set(0)
            return
        }

        val oldServer = server
        val oldIp = boundIp
        val oldPort = boundPort

        for (port in linkedSetOf(desiredPort, ConfigStore.DEFAULT_PORT)) {
            if (oldServer != null && oldIp == info.ip && oldPort == port && oldServer.wasStarted()) {
                adopt(oldServer, oldIp, port, desiredPort)
                return
            }
            val instance = HttpServer(BindHost.of(allowLocalhostBind, info.ip), port)
            try {
                instance.start(SOCKET_READ_TIMEOUT_MS, false)
                if (oldServer != null && oldServer !== instance) {
                    try {
                        oldServer.stop()
                    } catch (ignored: Exception) {
                        // ignore
                    }
                }
                if (port != desiredPort) {
                    SafeLog.w(TAG, "Configured port $desiredPort unavailable; using $port")
                }
                adopt(instance, info.ip, port, desiredPort)
                SafeLog.i(TAG, "Control server listening on ${info.ip}:$port")
                return
            } catch (e: IOException) {
                SafeLog.e(TAG, "Failed to bind control server on ${info.ip}:$port (${e.javaClass.simpleName})")
                try {
                    instance.stop()
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }

        // Could not bind anything: never leave the previous listener down.
        if (oldServer != null) {
            adopt(oldServer, oldIp, oldPort, desiredPort)
        } else {
            SafeLog.e(TAG, "Control server could not bind on ${info.ip}")
            scheduleBindRetry()
        }
    }

    /**
     * Publishes the listener that is actually serving and keeps the persisted
     * port in sync with it. The bound address is the single source of truth for
     * the Host allowlist, the mDNS SRV record and the URL shown on the TV.
     */
    private fun adopt(instance: HttpServer, ip: String?, port: Int, desiredPort: Int) {
        val previousPort = boundPort
        server = instance
        boundIp = ip
        boundPort = port
        retryAttempts.set(0)
        PortReconciliation.sync(desiredPort, port)?.let { realPort ->
            config.controllerPort = realPort
            SafeLog.w(TAG, "Persisting bound port $realPort (configured $desiredPort)")
        }
        if (previousPort != -1 && previousPort != port) {
            onBound()
        }
    }

    /**
     * A boot-time fallback: if the very first bind fails on every candidate and
     * there is no old listener to restore, the server would otherwise stay down
     * until the next network callback. Retry the bind (configured port, then the
     * default) a bounded number of times with a short delay, then give up loudly.
     */
    private fun scheduleBindRetry() {
        val attempt = retryAttempts.incrementAndGet()
        if (attempt > MAX_BIND_RETRIES) {
            SafeLog.e(TAG, "Control server bind retries exhausted after $MAX_BIND_RETRIES attempts")
            return
        }
        SafeLog.w(TAG, "Control server bind failed; retry $attempt/$MAX_BIND_RETRIES")
        try {
            retryFuture = retryExecutor.schedule({
                rebind()
                // A successful fallback retry may land on a different port than
                // mDNS was started with; re-advertise the one that is serving.
                if (isRunning()) onBound()
            }, BIND_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            SafeLog.w(TAG, "Control server bind retry rejected while shutting down")
        }
    }

    /** Probes whether the LAN address can bind [port] right now, without persisting anything. */
    fun canBindPort(port: Int): Boolean {
        val info = localNetwork.current() ?: return false
        return try {
            ServerSocket().use { socket ->
                // NanoHTTPD binds with SO_REUSEADDR on, so the probe must too;
                // otherwise a port whose old sockets are in TIME_WAIT is reported
                // unavailable even though the real bind would succeed.
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(info.ip, port))
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        stopLocked()
        // A retry already blocked on the monitor above would otherwise run
        // rebind() after shutdown and start a fresh listener.
        retryExecutor.shutdownNow()
    }

    private fun stopLocked() {
        retryFuture?.cancel(false)
        retryFuture = null
        server?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
                // ignore
            }
        }
        server = null
        boundIp = null
        boundPort = -1
    }

    fun isRunning(): Boolean = server?.wasStarted() == true

    fun boundAddress(): Pair<String, Int>? {
        val ip = boundIp ?: return null
        return ip to boundPort
    }

    private inner class HttpServer(host: String?, port: Int) : NanoWSD(host, port) {

        init {
            setAsyncRunner(BoundedAsyncRunner(MAX_CONNECTIONS, perPeerLimiter))
        }

        /**
         * Tags each accepted socket with its peer address, which NanoHTTPD's
         * stock [ClientHandler] does not expose, so the runner can enforce the
         * per-peer cap before the handler starts.
         */
        override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler =
            TrackingClientHandler(inputStream, finalAccept)

        private inner class TrackingClientHandler(
            inputStream: InputStream,
            socket: Socket
        ) : ClientHandler(inputStream, socket), PeerAddressAware {
            override val peerAddress: String? = socket.inetAddress?.hostAddress
        }

        override fun serve(session: IHTTPSession): Response {
            // (1) peer must be in the same subnet; taken from the socket, never a header.
            val peer = session.remoteIpAddress
            if (!auth.isPeerAllowed(peer)) return closing(Http.forbidden("remote"))

            if (isWebsocketRequested(session)) {
                if (session.uri != "/ws" || session.method != Method.GET) {
                    return closing(Http.error(Response.Status.NOT_FOUND, "not_found", "Unknown route"))
                }
                if (!auth.isHostAllowed(session.headers["host"])) return closing(Http.forbidden("host"))
                if (!auth.isOriginAllowed(session.headers["origin"])) return closing(Http.forbidden("origin"))
                return super.serve(session)
            }

            return apiRoutes.handle(session, peer)
        }

        /** Any rejected request may carry an undrained body; never keep-alive on it. */
        private fun closing(response: Response): Response {
            response.closeConnection(true)
            return response
        }

        override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket =
            wsHub.openWebSocket(WebSocketGuardSession(handshake))
    }

    private companion object {
        const val TAG = "ControllerServer"

        /** Above the 60 s WebSocket heartbeat so idle sockets survive. */
        const val SOCKET_READ_TIMEOUT_MS = 70_000

        /** Hard cap on concurrent HTTP/WebSocket handlers. */
        const val MAX_CONNECTIONS = 64

        /** Cap on concurrent connections from a single LAN peer. */
        const val MAX_CONNECTIONS_PER_PEER = 8

        /** Bounded boot-time retry when the very first bind fails everywhere. */
        const val MAX_BIND_RETRIES = 3
        const val BIND_RETRY_DELAY_MS = 2_000L
    }
}

/**
 * NanoHTTPD's default runner spawns an unbounded thread per accepted socket.
 * This caps concurrent handlers so an unauthenticated peer cannot exhaust
 * threads; connections over the global or per-peer cap are closed immediately.
 *
 * [closeAll] closes every live handler (not just the executor): after `stop()`
 * or a rebind, established HTTP and WebSocket connections must not survive on
 * the old listener until their read timeout.
 */
internal class BoundedAsyncRunner(
    private val maxConcurrent: Int,
    private val perPeer: ConnectionLimiter
) : NanoHTTPD.AsyncRunner {

    private val running = AtomicInteger(0)
    private val accepted = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "terebi-http").apply { isDaemon = true }
    }

    override fun closeAll() {
        // Release under the same `accepted.remove` guard as closed() so a
        // handler thread racing out of its closed socket cannot double-release,
        // and so a rebind (which shares the per-peer limiter with the next
        // HttpServer) never leaks the peer's slots.
        for (handler in accepted.toList()) {
            if (accepted.remove(handler)) {
                running.decrementAndGet()
                perPeer.release(peerOf(handler))
            }
            closeQuietly(handler)
        }
        executor.shutdownNow()
    }

    override fun closed(handler: ClientHandler) {
        if (accepted.remove(handler)) {
            running.decrementAndGet()
            perPeer.release(peerOf(handler))
        }
    }

    override fun exec(handler: ClientHandler) {
        if (running.incrementAndGet() > maxConcurrent) {
            running.decrementAndGet()
            SafeLog.w(TAG, "Connection cap reached; rejecting connection")
            closeQuietly(handler)
            return
        }
        val peer = peerOf(handler)
        if (!perPeer.acquire(peer)) {
            running.decrementAndGet()
            SafeLog.w(TAG, "Per-peer connection cap reached; rejecting connection")
            closeQuietly(handler)
            return
        }
        accepted.add(handler)
        try {
            executor.execute(handler)
        } catch (e: Exception) {
            if (accepted.remove(handler)) {
                running.decrementAndGet()
                perPeer.release(peer)
            }
        }
    }

    private fun peerOf(handler: ClientHandler): String? =
        (handler as? PeerAddressAware)?.peerAddress

    private fun closeQuietly(handler: ClientHandler) {
        try {
            handler.close()
        } catch (ignored: Exception) {
            // ignore
        }
    }

    private companion object {
        const val TAG = "BoundedAsyncRunner"
    }
}
