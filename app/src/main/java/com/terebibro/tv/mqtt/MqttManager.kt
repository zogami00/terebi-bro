package com.terebibro.tv.mqtt

import com.terebibro.tv.server.ApiResult
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the optional MQTT connection (Eclipse Paho, plain Java; no Android
 * service and no Netty).
 *
 * Responsibilities:
 * - connect when the setting is enabled, with exponential backoff (capped) and
 *   a resubscribe on every reconnect;
 * - publish retained `availability` (`online`/`offline`, with the last will set
 *   to a retained `offline` so a crash or network loss reports itself);
 * - publish the retained `status`, driven from the same state-change path as
 *   the REST API and WebSocket (so it is not a second source of truth);
 * - subscribe to `command` at QoS 1, parse strictly, dispatch through
 *   [Dispatcher] (which routes onto the UI thread exactly like the REST API)
 *   and publish `command/result`.
 *
 * Everything runs on a single daemon executor, so MQTT never blocks the UI
 * thread, and a broker outage leaves the control server and the WebView
 * completely unaffected. The password and username are never logged and never
 * appear in a published payload.
 */
class MqttManager(
    private val settingsProvider: () -> MqttSettings,
    private val clientIdProvider: () -> String,
    private val dispatcher: Dispatcher,
    private val stateProvider: () -> BrowserState?,
    /** Invoked whenever the connection state changes; must be cheap and non-blocking. */
    private val onStatusChanged: () -> Unit = {}
) {

    /** Dispatches a parsed command; the implementation must hop to the UI thread itself. */
    interface Dispatcher {
        fun dispatch(command: MqttCommand): ApiResult
    }

    @Volatile
    private var connected = false

    @Volatile
    private var state: MqttStatus.State = MqttStatus.State.DISABLED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var client: MqttAsyncClient? = null

    @Volatile
    private var stopped = false

    private val lock = Any()
    private var reconnectAttempt = 0
    private var statusScheduled = false

    /**
     * Scheduled reconnect task. Volatile because [scheduleReconnect]/[cancelReconnect]
     * run on the executor while [restart] and the connect listener may observe it
     * from another of the manager's entry points.
     */
    @Volatile
    private var pendingReconnect: ScheduledFuture<*>? = null

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "terebi-mqtt").apply { isDaemon = true }
    }

    fun status(): MqttStatus = MqttStatus(connected, state, lastError)

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun start() {
        stopped = false
        execute {
            val settings = settingsProvider()
            if (settings.connectable) connect(settings) else markDisabled()
        }
    }

    /** Re-reads the settings and reconnects (or disconnects) accordingly. */
    fun restart() {
        stopped = false
        execute {
            cancelReconnect()
            teardownClient()
            reconnectAttempt = 0
            val settings = settingsProvider()
            if (settings.connectable) connect(settings) else markDisabled()
        }
    }

    /**
     * Clean shutdown: publish a retained `offline`, then disconnect. Safe to
     * call from the Activity's shutdown thread; it never blocks the caller.
     */
    fun stop() {
        stopped = true
        execute {
            cancelReconnect()
            publishOffline()
            teardownClient()
            connected = false
            state = MqttStatus.State.DISABLED
            lastError = null
            onStatusChanged()
        }
        executor.shutdown()
    }

    /**
     * Requests a (coalesced) retained `status` publish. Called from the shared
     * state-change path; it is a no-op while disconnected and never blocks.
     */
    fun publishStatus() {
        if (!connected) return
        synchronized(lock) {
            if (statusScheduled) return
            statusScheduled = true
        }
        execute {
            synchronized(lock) { statusScheduled = false }
            publishStatusNow()
        }
    }

    // ---------------------------------------------------------------------
    // Connect / reconnect
    // ---------------------------------------------------------------------

    private fun connect(settings: MqttSettings) {
        if (stopped || !settings.connectable) {
            markDisabled()
            return
        }
        teardownClient()
        try {
            val cli = MqttAsyncClient(serverUri(settings), clientIdProvider(), MemoryPersistence())
            // Bind the callback to the exact client instance: a callback from a
            // client that settings changes have already replaced must not touch
            // the shared state (see teardownClient).
            cli.setCallback(Callbacks(cli))
            client = cli

            val options = MqttConnectOptions().apply {
                // Backoff and resubscribe are managed here, not by Paho, so the
                // delay is capped and observable.
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = CONNECT_TIMEOUT_SEC
                keepAliveInterval = KEEP_ALIVE_SEC
                if (settings.username.isNotEmpty()) {
                    userName = settings.username
                    password = settings.password.toCharArray()
                }
                setWill(
                    MqttTopics.availability(settings.baseTopic),
                    PAYLOAD_OFFLINE.toByteArray(Charsets.UTF_8),
                    QOS,
                    true
                )
            }

            state = MqttStatus.State.CONNECTING
            lastError = null
            onStatusChanged()
            cli.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken) {
                    // connectComplete() performs the online/resubscribe/status work.
                }

                override fun onFailure(token: IMqttToken?, e: Throwable?) {
                    // Ignore a failure from a client that is no longer current
                    // (settings changed mid-connect): acting on it would mark a
                    // healthy replacement disconnected and tear it down.
                    if (cli !== client || stopped) return
                    markDisconnected(e)
                    scheduleReconnect()
                }
            })
        } catch (e: MqttException) {
            SafeLog.w(TAG, "MQTT connect setup failed")
            markDisconnected(e)
            scheduleReconnect()
        } catch (e: Exception) {
            SafeLog.w(TAG, "MQTT client error")
            markDisconnected(e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        if (!settingsProvider().connectable) return
        val attempt = synchronized(lock) { reconnectAttempt++ }
        val delay = backoffMs(attempt)
        cancelReconnect()
        pendingReconnect = try {
            executor.schedule({
                if (!stopped && settingsProvider().connectable) connect(settingsProvider())
            }, delay, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    private fun cancelReconnect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
    }

    private fun markDisabled() {
        connected = false
        state = MqttStatus.State.DISABLED
        lastError = null
        onStatusChanged()
    }

    private fun markDisconnected(cause: Throwable?) {
        connected = false
        state = MqttStatus.State.DISCONNECTED
        lastError = describe(cause)
        onStatusChanged()
    }

    private fun teardownClient() {
        val cli = client ?: return
        client = null
        connected = false
        try {
            if (cli.isConnected) {
                cli.disconnect(DISCONNECT_TIMEOUT_MS)
            } else {
                // Not cleanly connected: the client may be mid-connect, where
                // disconnect()/close() would throw CONNECT_IN_PROGRESS (32110)
                // and leave the attempt alive. disconnectForcibly has no
                // connecting guard and moves the client out of CONNECTING first.
                cli.disconnectForcibly(0, 0)
            }
        } catch (e: Exception) {
            // ignore: we are replacing or shutting down the client
        }
        try {
            cli.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------------
    // Publishing
    // ---------------------------------------------------------------------

    private fun publishStatusNow() {
        if (!connected) return
        val cli = client ?: return
        val settings = settingsProvider()
        val snapshot = stateProvider() ?: return
        try {
            cli.publish(
                MqttTopics.status(settings.baseTopic),
                MqttStatusPayload.build(snapshot).toByteArray(Charsets.UTF_8),
                QOS,
                true
            )
        } catch (e: MqttException) {
            SafeLog.w(TAG, "MQTT status publish failed")
        }
    }

    private fun publishOffline() {
        val cli = client ?: return
        if (!connected) return
        val settings = settingsProvider()
        try {
            cli.publish(
                MqttTopics.availability(settings.baseTopic),
                PAYLOAD_OFFLINE.toByteArray(Charsets.UTF_8),
                QOS,
                true
            ).waitForCompletion(PUBLISH_TIMEOUT_MS)
        } catch (e: Exception) {
            SafeLog.w(TAG, "MQTT offline publish failed")
        }
    }

    private fun publishRetained(topic: String, payload: String) = publish(topic, payload, true)

    private fun publish(topic: String, payload: String, retained: Boolean) {
        val cli = client ?: return
        try {
            cli.publish(topic, payload.toByteArray(Charsets.UTF_8), QOS, retained)
        } catch (e: MqttException) {
            SafeLog.w(TAG, "MQTT publish failed")
        }
    }

    private fun subscribe(topic: String) {
        val cli = client ?: return
        try {
            cli.subscribe(topic, QOS)
        } catch (e: MqttException) {
            SafeLog.w(TAG, "MQTT subscribe failed")
        }
    }

    // ---------------------------------------------------------------------
    // Inbound commands
    // ---------------------------------------------------------------------

    private fun handleCommandPayload(payload: String) {
        when (val parsed = MqttCommandParser.parse(payload)) {
            is MqttCommandParser.Result.Parsed -> {
                val result = try {
                    dispatcher.dispatch(parsed.command)
                } catch (e: Exception) {
                    SafeLog.w(TAG, "MQTT command dispatch failed")
                    ApiResult.Err(500, "internal_error", "Command failed")
                }
                publishResult(parsed.command.id, result)
            }
            is MqttCommandParser.Result.Failed -> {
                publishResult(null, ApiResult.Err(400, parsed.code, parsed.message))
            }
        }
    }

    private fun publishResult(id: String?, result: ApiResult) {
        val obj = JSONObject()
        obj.put("id", id ?: JSONObject.NULL)
        when (result) {
            is ApiResult.Ok -> obj.put("ok", true)
            is ApiResult.Err -> {
                obj.put("ok", false)
                obj.put(
                    "error",
                    JSONObject().put("code", result.code).put("message", result.message)
                )
            }
        }
        publish(MqttTopics.commandResult(settingsProvider().baseTopic), obj.toString(), false)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun execute(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            SafeLog.w(TAG, "MQTT task dropped (shutting down)")
        }
    }

    private fun serverUri(settings: MqttSettings): String =
        (if (settings.tls) "ssl" else "tcp") + "://" + settings.host + ":" + settings.port

    /** A short, redacted reason; never includes the username or the password. */
    private fun describe(cause: Throwable?): String {
        val name = cause?.javaClass?.simpleName ?: return "unknown error"
        // Only a Paho exception's message is known-safe to surface; any other
        // throwable (e.g. IllegalArgumentException(address) from a malformed
        // host) may carry credentials, so it is reported by class name only.
        if (cause !is MqttException) return name
        val message = cause.message?.let { SafeLog.redact(it) }
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    private fun payloadOf(message: MqttMessage?): String? =
        try {
            message?.payload?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }

    private inner class Callbacks(private val cli: MqttAsyncClient) : MqttCallbackExtended {

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            if (cli !== client || stopped) return
            connected = true
            state = MqttStatus.State.CONNECTED
            lastError = null
            synchronized(lock) { reconnectAttempt = 0 }
            val settings = settingsProvider()
            publishRetained(MqttTopics.availability(settings.baseTopic), PAYLOAD_ONLINE)
            subscribe(MqttTopics.command(settings.baseTopic))
            publishStatusNow()
            onStatusChanged()
        }

        override fun connectionLost(cause: Throwable?) {
            if (cli !== client || stopped) return
            markDisconnected(cause)
            scheduleReconnect()
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            // Paho 1.2.5 drops the connection if an exception escapes this
            // callback, so the whole body is wrapped in a Throwable guard.
            try {
                if (cli !== client) return
                val msg = message ?: return
                val payloadSize = msg.payload?.size ?: return
                when (classifyIncoming(msg.isRetained, payloadSize)) {
                    IncomingDecision.IGNORE_RETAINED -> {
                        // A retained command would otherwise re-run on every
                        // reconnect and every reboot (isCleanSession resubscribes).
                        SafeLog.w(TAG, RETAINED_COMMAND_IGNORED)
                    }
                    IncomingDecision.TOO_LARGE -> {
                        publishResult(
                            null,
                            ApiResult.Err(
                                PAYLOAD_TOO_LARGE_STATUS,
                                "payload_too_large",
                                "Command payload is too large"
                            )
                        )
                    }
                    IncomingDecision.PROCESS -> {
                        val payload = payloadOf(msg) ?: return
                        handleCommandPayload(payload)
                    }
                }
            } catch (t: Throwable) {
                SafeLog.w(TAG, "MQTT message callback error")
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {
            // Nothing to do: publishes are fire-and-forget.
        }
    }

    companion object {
        private const val TAG = "MqttManager"

        const val QOS = 1
        const val PAYLOAD_ONLINE = "online"
        const val PAYLOAD_OFFLINE = "offline"

        /**
         * Largest inbound `command` payload that is decoded. A larger payload is
         * rejected before any UTF-8 decode (which would allocate the whole byte
         * array) so a publisher cannot trigger an OutOfMemoryError on a low-RAM
         * device.
         */
        const val MAX_INCOMING_PAYLOAD = 4096

        private const val PAYLOAD_TOO_LARGE_STATUS = 413

        /** Constant log line for a retained command that is intentionally ignored. */
        const val RETAINED_COMMAND_IGNORED =
            "Ignoring retained command: publish commands with retain=false"

        private const val CONNECT_TIMEOUT_SEC = 15
        private const val KEEP_ALIVE_SEC = 30
        private const val DISCONNECT_TIMEOUT_MS = 3_000L
        private const val PUBLISH_TIMEOUT_MS = 3_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 60_000L

        /** How an inbound message is handled, decided before any payload decode. */
        enum class IncomingDecision { PROCESS, IGNORE_RETAINED, TOO_LARGE }

        /**
         * Pure decision for an inbound message: retained messages are ignored, an
         * over-large payload is rejected before decoding, everything else is
         * processed. Kept pure so both bounds are unit-testable.
         */
        fun classifyIncoming(isRetained: Boolean, payloadSize: Int): IncomingDecision = when {
            isRetained -> IncomingDecision.IGNORE_RETAINED
            payloadSize > MAX_INCOMING_PAYLOAD -> IncomingDecision.TOO_LARGE
            else -> IncomingDecision.PROCESS
        }

        /**
         * Exponential backoff with a hard cap: `base * 2^(attempt-1)`, capped at
         * [RECONNECT_MAX_MS]. Pure and testable.
         */
        fun backoffMs(attempt: Int): Long {
            val n = attempt.coerceIn(1, 30)
            val exponent = 1L shl (n - 1)
            return (RECONNECT_BASE_MS * exponent).coerceAtMost(RECONNECT_MAX_MS)
        }
    }
}
