package com.terebibro.tv.server

import android.os.SystemClock
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `/ws` connection hub.
 *
 * The token is never placed in the URL: a fresh connection starts
 * unauthenticated and must send `{"type":"auth","token":"..."}` within 5 s or
 * the server closes it with code 4401.
 *
 * Each connection owns a bounded outbound queue and a dedicated writer thread,
 * so one slow or half-open socket cannot wedge state delivery, timeouts or
 * heartbeats for everyone else. State broadcasts are coalesced to at most one
 * per 250 ms and the state JSON is built outside the coalescing lock.
 */
class WsHub(
    private val auth: AuthManager,
    private val stateProvider: () -> BrowserState?,
    private val dpadHandler: (key: String) -> ApiResult
) {

    private val connections = CopyOnWriteArrayList<Connection>()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "terebi-ws").apply { isDaemon = true }
        }

    private val coalesceLock = Any()
    private var lastStateSentAt = 0L
    private var trailingScheduled = false

    fun openWebSocket(session: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        val connection = Connection(session)
        connections.add(connection)
        return connection
    }

    // ---------------------------------------------------------------------
    // Broadcasts
    // ---------------------------------------------------------------------

    fun broadcastState() {
        scheduler.execute {
            var sendNow = false
            synchronized(coalesceLock) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastStateSentAt
                if (elapsed >= COALESCE_MS && !trailingScheduled) {
                    lastStateSentAt = now
                    sendNow = true
                } else if (!trailingScheduled) {
                    trailingScheduled = true
                    scheduler.schedule({
                        synchronized(coalesceLock) {
                            trailingScheduled = false
                            lastStateSentAt = SystemClock.elapsedRealtime()
                        }
                        sendState()
                    }, COALESCE_MS - elapsed, TimeUnit.MILLISECONDS)
                }
            }
            // Built outside the lock: the state provider can block up to 3 s.
            if (sendNow) sendState()
        }
    }

    private fun sendState() {
        val state = stateProvider() ?: return
        val message = JSONObject()
        message.put("type", "state")
        message.put("state", StateJson.toJson(state))
        broadcast(message.toString())
    }

    fun broadcastEvent(name: String, detail: String? = null) {
        val message = JSONObject()
        message.put("type", "event")
        message.put("name", name)
        if (detail != null) message.put("detail", detail)
        val text = message.toString()
        scheduler.execute { broadcast(text) }
    }

    private fun broadcast(text: String) {
        for (connection in connections) {
            if (!connection.authenticated) continue
            connection.sendText(text)
        }
    }

    /** Revocation closes every socket authenticated with [sha256Hex] as 4401 "revoked". */
    fun closeForTokenHash(sha256Hex: String, reason: String = "revoked") {
        for (connection in connections) {
            if (connection.tokenHash == sha256Hex) {
                connection.closeCustom(4401, reason)
            }
        }
    }

    /** Deliberate shutdown: 1001 (going away), not 4401, so controllers keep their token. */
    fun closeAll() {
        for (connection in connections) {
            connection.closeCustom(1001, "server_stopped")
        }
        connections.clear()
    }

    /** TV-side revocation: every controller is told 4401 "revoked" so it drops its token. */
    fun closeAllRevoked() {
        for (connection in connections) {
            connection.closeCustom(4401, "revoked")
        }
        connections.clear()
    }

    // ---------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------

    private sealed class Out {
        data class Text(val text: String) : Out()
        data class Close(val code: Int, val reason: String) : Out()
        object Ping : Out()
    }

    private inner class Connection(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {

        @Volatile
        var authenticated: Boolean = false
            private set

        @Volatile
        var tokenHash: String? = null
            private set

        private val peerIp: String? = handshake.remoteIpAddress

        @Volatile
        private var lastMessageAt = System.currentTimeMillis()

        private val outQueue = LinkedBlockingQueue<Out>(MAX_QUEUE)

        @Volatile
        private var writerThread: Thread? = null

        private var authTimeoutTask: java.util.concurrent.ScheduledFuture<*>? = null
        private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null
        private var pingTask: java.util.concurrent.ScheduledFuture<*>? = null

        override fun onOpen() {
            writerThread = Thread({ writeLoop() }, "terebi-ws-tx").apply {
                isDaemon = true
                start()
            }

            authTimeoutTask = scheduler.schedule({
                if (!authenticated) {
                    SafeLog.i(TAG, "WebSocket handshake timeout")
                    closeCustom(4401, "auth_timeout")
                }
            }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            heartbeatTask = scheduler.scheduleWithFixedDelay({
                if (authenticated && System.currentTimeMillis() - lastMessageAt > HEARTBEAT_TIMEOUT_MS) {
                    SafeLog.i(TAG, "WebSocket heartbeat timeout")
                    closeCustom(1000, "heartbeat_timeout")
                }
            }, HEARTBEAT_CHECK_MS, HEARTBEAT_CHECK_MS, TimeUnit.MILLISECONDS)

            pingTask = scheduler.scheduleWithFixedDelay({
                if (authenticated && isOpen) {
                    // Enqueue only: the per-connection writer thread performs
                    // the actual write. Writing here would run NanoWSD's
                    // synchronized sendFrame on the shared scheduler thread, so
                    // one peer with a full TCP window would block every
                    // broadcast, timeout and heartbeat for all clients.
                    if (!outQueue.offer(Out.Ping)) {
                        SafeLog.w(TAG, "WebSocket ping queue full; dropping slow client")
                        closeCustom(1013, "slow_client")
                    }
                }
            }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        private fun writeLoop() {
            try {
                while (true) {
                    val item = try {
                        outQueue.take()
                    } catch (e: InterruptedException) {
                        return
                    }
                    when (item) {
                        is Out.Text -> if (isOpen) send(item.text)
                        is Out.Close -> {
                            try {
                                sendCloseFrame(item.code, item.reason)
                            } catch (e: IOException) {
                                // fall through to removal
                            }
                            return
                        }
                        is Out.Ping -> if (isOpen) ping(ByteArray(0))
                    }
                }
            } catch (e: IOException) {
                // socket gone
            } finally {
                removeSelf()
            }
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            lastMessageAt = System.currentTimeMillis()
            if (message.opCode != NanoWSD.WebSocketFrame.OpCode.Text) return
            val text = try {
                message.textPayload
            } catch (e: Exception) {
                return
            }
            if (text.length > MAX_MESSAGE_CHARS) {
                sendError("bad_request", "Message too large", null)
                return
            }
            val parsed = try {
                Json.parseObject(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), ALLOWED_KEYS)
            } catch (e: Json.BadJson) {
                sendError("bad_request", "Malformed message", null)
                return
            }
            val type = (parsed["type"] as? Json.Value.Str)?.value
            val allowedForType = type?.let { ALLOWED_BY_TYPE[it] }
            if (type == null || allowedForType == null || !allowedForType.containsAll(parsed.keys)) {
                sendError("bad_request", "Unknown message type", null)
                return
            }

            val isDpad = type == "dpad"
            if (!auth.rateLimit(tokenHash ?: peerIp ?: "anonymous", isDpad)) {
                sendError("rate_limited", "Too many messages", null)
                return
            }

            if (!authenticated) {
                if (type == "auth") handleAuthMessage(parsed)
                return
            }

            when (type) {
                "ping" -> {
                    val pong = JSONObject()
                    pong.put("type", "pong")
                    pong.put("t", (parsed["t"] as? Json.Value.Num)?.value?.toLong() ?: 0L)
                    sendText(pong.toString())
                }
                "dpad" -> handleDpad(parsed)
                else -> sendError("bad_request", "Unknown message type", null)
            }
        }

        private fun handleAuthMessage(parsed: Map<String, Json.Value>) {
            val token = (parsed["token"] as? Json.Value.Str)?.value
            val paired = auth.authenticate(token)
            if (paired == null) {
                closeCustom(4401, "unauthorized")
                return
            }
            authenticated = true
            tokenHash = paired.sha256Hex
            authTimeoutTask?.cancel(false)

            val message = JSONObject()
            message.put("type", "auth_ok")
            stateProvider()?.let { message.put("state", StateJson.toJson(it)) }
            sendText(message.toString())
        }

        private fun handleDpad(parsed: Map<String, Json.Value>) {
            val key = (parsed["key"] as? Json.Value.Str)?.value ?: ""
            val reqId = (parsed["reqId"] as? Json.Value.Str)?.value
            if (key.isEmpty()) {
                sendError("bad_key", "Missing key", reqId)
                return
            }
            when (val result = dpadHandler(key)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Err -> sendError(result.code, result.message, reqId)
            }
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            cleanup()
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {
            lastMessageAt = System.currentTimeMillis()
        }

        override fun onException(exception: IOException) {
            cleanup()
        }

        /**
         * Enqueues [text] on this connection's bounded queue. When the peer is
         * too slow the queue fills up and the socket is dropped rather than
         * blocking the broadcaster.
         */
        fun sendText(text: String) {
            if (!isOpen) return
            if (!outQueue.offer(Out.Text(text))) {
                SafeLog.w(TAG, "WebSocket send queue full; dropping slow client")
                closeCustom(1013, "slow_client")
            }
        }

        private fun sendError(code: String, message: String, reqId: String?) {
            val obj = JSONObject()
            obj.put("type", "error")
            obj.put("code", code)
            obj.put("message", message)
            if (reqId != null) obj.put("reqId", reqId)
            sendText(obj.toString())
        }

        private fun sendCloseFrame(code: Int, reason: String) {
            val reasonBytes = reason.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(2 + reasonBytes.size)
            payload[0] = ((code ushr 8) and 0xFF).toByte()
            payload[1] = (code and 0xFF).toByte()
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
            sendFrame(NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Close, true, payload))
        }

        /**
         * Sends a close frame carrying a non-standard code (4401) via the writer
         * queue, removes the connection immediately and schedules an
         * unconditional forced close so it can never be left in the list.
         */
        fun closeCustom(code: Int, reason: String) {
            cancelTasks()
            removeSelf()
            if (!outQueue.offer(Out.Close(code, reason))) {
                writerThread?.interrupt()
            }
            forceCloseAsync(reason)
        }

        private fun forceCloseAsync(reason: String) {
            Thread({
                try {
                    Thread.sleep(FORCE_CLOSE_DELAY_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                // Kill the socket by closing the guarded input stream. Calling
                // close()/sendFrame() again would take NanoWSD's synchronized
                // frame monitor and could block behind a writer that is stuck in
                // a socket write to this very peer.
                try {
                    handshakeRequest.inputStream.close()
                } catch (e: Exception) {
                    // ignore
                }
            }, "terebi-ws-close").apply {
                isDaemon = true
                start()
            }
        }

        private fun cleanup() {
            cancelTasks()
            removeSelf()
            writerThread?.interrupt()
            writerThread = null
        }

        private fun cancelTasks() {
            authTimeoutTask?.cancel(false)
            heartbeatTask?.cancel(false)
            pingTask?.cancel(false)
            authTimeoutTask = null
            heartbeatTask = null
            pingTask = null
        }

        private fun removeSelf() {
            connections.remove(this)
        }
    }

    private companion object {
        const val TAG = "WsHub"
        const val AUTH_TIMEOUT_MS = 5_000L
        const val HEARTBEAT_TIMEOUT_MS = 60_000L
        const val HEARTBEAT_CHECK_MS = 10_000L
        const val PING_INTERVAL_MS = 30_000L
        const val COALESCE_MS = 250L
        const val FORCE_CLOSE_DELAY_MS = 1_000L
        const val MAX_QUEUE = 32
        const val MAX_MESSAGE_CHARS = 8 * 1024

        val ALLOWED_KEYS = setOf("type", "token", "t", "key", "reqId")
        val ALLOWED_BY_TYPE: Map<String, Set<String>> = mapOf(
            "auth" to setOf("type", "token"),
            "ping" to setOf("type", "t"),
            "dpad" to setOf("type", "key", "reqId")
        )
    }
}
