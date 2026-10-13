package com.terebibro.tv.mqtt

import com.terebibro.tv.server.ApiResult
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
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
    /** The stable, sanitised device segment used for the discovery node id. */
    private val deviceIdProvider: () -> String,
    /** Invoked whenever the connection state changes; must be cheap and non-blocking. */
    private val onStatusChanged: () -> Unit = {},
    /** The discovery prefix persisted on the previous connect, or null if never published. */
    private val lastDiscoveryPrefixProvider: () -> String? = { null },
    /** Persists the discovery prefix actually used so a later change can clear the old one. */
    private val lastDiscoveryPrefixStore: (String) -> Unit = {}
) {

    /**
     * How the underlying Paho client is created. The default builds a real
     * [MqttAsyncClient]; a test can inject a stub through the internal
     * constructor below to drive the real connect/publish paths and reproduce
     * Paho's failure modes (e.g. `REASON_CODE_MAX_INFLIGHT`).
     */
    private var clientFactory: (serverUri: String, clientId: String) -> MqttClientHandle =
        { uri, id -> PahoClientHandle(MqttAsyncClient(uri, id, MemoryPersistence())) }

    /**
     * Test-only constructor: identical to the primary one but with the client
     * factory injected. Kept `internal` so the Paho client seam does not become
     * part of the public API.
     */
    internal constructor(
        settingsProvider: () -> MqttSettings,
        clientIdProvider: () -> String,
        dispatcher: Dispatcher,
        stateProvider: () -> BrowserState?,
        deviceIdProvider: () -> String,
        onStatusChanged: () -> Unit,
        lastDiscoveryPrefixProvider: () -> String?,
        lastDiscoveryPrefixStore: (String) -> Unit,
        clientFactory: (String, String) -> MqttClientHandle
    ) : this(
        settingsProvider,
        clientIdProvider,
        dispatcher,
        stateProvider,
        deviceIdProvider,
        onStatusChanged,
        lastDiscoveryPrefixProvider,
        lastDiscoveryPrefixStore
    ) {
        this.clientFactory = clientFactory
    }

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

    /** Discovery topics successfully published by the last [publishDiscovery] attempt. */
    @Volatile
    private var discoveryTopics = 0

    /** Last discovery publish failure, already redacted; null when the last attempt succeeded. */
    @Volatile
    private var discoveryError: String? = null

    /**
     * Last retained `status` publish failure, already redacted; null when the
     * last attempt succeeded. Recorded the same way as [discoveryError] so a
     * status publish blocked by Paho (e.g. a full in-flight window) is visible
     * rather than silently dropped.
     */
    @Volatile
    private var statusError: String? = null

    /**
     * Last retained `availability` publish failure, already redacted; null when
     * the last availability publish succeeded. Availability is a separate
     * retained topic, so it is tracked apart from [statusError]: a later
     * successful status publish must not erase it, or Home Assistant would keep
     * showing the device offline with nothing to explain it. It is surfaced
     * through the existing [statusError] slot on [status] and on the retained
     * status payload.
     */
    @Volatile
    private var availabilityError: String? = null

    @Volatile
    private var client: MqttClientHandle? = null

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

    fun status(): MqttStatus =
        MqttStatus(connected, state, lastError, discoveryTopics, discoveryError, reportedPublishError())

    /**
     * The redacted retained-publish failure to report, if any: the last `status`
     * publish failure, or failing that the last `availability` publish failure.
     * Both are retained publishes whose failure leaves the broker (and Home
     * Assistant) with no explanation, so they share the one existing
     * [MqttStatus.statusError] channel rather than a new one.
     */
    private fun reportedPublishError(): String? = statusError ?: availabilityError

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
            if (DISCOVERY_REMOVE_ON_SHUTDOWN) removeDiscoveryOnShutdown()
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
            val cli = clientFactory(serverUri(settings), clientIdProvider())
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
                // Paho's default window is 10 QoS-1 messages in flight. One
                // availability, the status and 21 discovery configs (plus
                // command results) share it, so the default silently refuses
                // every publish past the tenth with REASON_CODE_MAX_INFLIGHT.
                // Raise it well above the entity count so a connect-time burst
                // always fits.
                maxInflight = MAX_INFLIGHT
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
                MqttStatusPayload.build(snapshot, discoveryTopics, discoveryError, reportedPublishError())
                    .toByteArray(Charsets.UTF_8),
                QOS,
                true
            )
            // A delivered status means the previous failure (if any) has now
            // been surfaced to the broker, so the recorded error is cleared.
            statusError = null
        } catch (t: Throwable) {
            // A status publish failure must be observable: record it exactly
            // as a discovery failure is recorded rather than swallowing it.
            // Catch Throwable, not just MqttException, so a runtime failure
            // cannot silently drop the retained status.
            statusError = safeFailureReason(t)
            SafeLog.w(TAG, "MQTT status publish failed")
        }
    }

    /**
     * Publishes the Home Assistant MQTT Discovery configs, retained, so the
     * device appears as one Home Assistant device with its entities. Called on
     * every connect (including every reconnect).
     *
     * When discovery is disabled the app instead publishes an **empty retained
     * payload** to every discovery topic it owns, so Home Assistant removes the
     * entities rather than showing them permanently unavailable.
     *
     * Discovery payloads carry no credentials: only the device id, its name,
     * the app version and the base topic.
     *
     * If the discovery prefix changed since the last connect, the configs left
     * under the previous prefix are first cleared with empty retained payloads
     * (otherwise they would linger in Home Assistant forever), then the new
     * prefix is persisted. Both topic lists are built from the same
     * [MqttDiscovery.entities] result so they cannot drift.
     */
    private fun publishDiscovery() {
        // A new attempt starts from a clean slate. If anything below aborts
        // before the counters are assigned — the `?: return` below, or a throw
        // caught by the caller's task — the status must not keep the previous
        // connect's discoveryTopics alongside this attempt's error.
        discoveryTopics = 0
        discoveryError = null
        val settings = settingsProvider()
        val snapshot = stateProvider() ?: return
        val deviceId = deviceIdProvider()
        val entities = MqttDiscovery.entities(
            deviceId = deviceId,
            deviceName = snapshot.deviceName,
            appVersion = snapshot.appVersion,
            base = settings.baseTopic
        )
        // Clear configs left under a previous prefix. Failures are isolated so
        // one bad topic cannot abort the loop (or the main publish loop below).
        val cleared = runIsolated(
            MqttDiscovery.staleConfigTopics(
                previousPrefix = lastDiscoveryPrefixProvider(),
                currentPrefix = settings.discoveryPrefix,
                deviceId = deviceId,
                entities = entities
            )
        ) { topic -> tryPublishRetained(topic, "") }

        // Publish (or clear) every config independently: one entity that throws
        // must never cost the ones after it. An enabled device publishes its
        // config; a disabled one clears it with an empty retained payload.
        val published = runIsolated(entities) { entity ->
            tryPublishRetained(
                MqttDiscovery.topic(settings.discoveryPrefix, deviceId, entity),
                if (settings.discoveryEnabled) entity.config.toString() else ""
            )
        }

        discoveryTopics = published.succeeded
        discoveryError = published.lastError ?: cleared.lastError
        if (published.failed > 0 || cleared.failed > 0) {
            SafeLog.w(
                TAG,
                "MQTT discovery publish incomplete: " +
                    "${published.succeeded}/${entities.size} entities, " +
                    "${published.failed} failed, ${cleared.failed} stale clears failed"
            )
        }
        // Only persist the new prefix when every stale clear succeeded. If any
        // clear failed, the previous prefix is still stored so the next connect
        // recomputes and retries the stale configs; overwriting it now would
        // strand those configs in Home Assistant forever.
        if (cleared.failed == 0) {
            lastDiscoveryPrefixStore(settings.discoveryPrefix)
        }
    }

    /**
     * Clears every discovery config this device owns (empty retained payload).
     * Only performed on a clean shutdown when [DISCOVERY_REMOVE_ON_SHUTDOWN] is
     * on; by default the configs are left in place — the retained `availability`
     * topic already reports the device offline, which is what `availability`
     * exists for.
     */
    private fun removeDiscoveryOnShutdown() {
        if (!connected) return
        val settings = settingsProvider()
        val snapshot = stateProvider() ?: return
        val deviceId = deviceIdProvider()
        for (entity in MqttDiscovery.entities(
            deviceId,
            snapshot.deviceName,
            snapshot.appVersion,
            settings.baseTopic
        )) {
            publishRetained(MqttDiscovery.topic(settings.discoveryPrefix, deviceId, entity), "")
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

    /**
     * The discovery-loop publish: unlike [publish] it lets every failure escape
     * (and treats a missing client as a failure rather than a silent no-op), so
     * the [runIsolated] loop can count it and record the reason. A single
     * failure here never aborts the loop.
     */
    private fun tryPublishRetained(topic: String, payload: String) {
        val cli = client ?: error("no MQTT client")
        cli.publish(topic, payload.toByteArray(Charsets.UTF_8), QOS, true)
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

    private inner class Callbacks(private val cli: MqttClientHandle) : MqttCallbackExtended {

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            // Keep Paho's callback thread fast: it is the thread on which Paho
            // releases QoS-1 in-flight slots, so blocking it for the whole
            // publish burst leaves the window permanently full. Only the cheap
            // state mutation happens here; the network work is scheduled onto
            // the manager's executor.
            if (cli !== client || stopped) return
            connected = true
            state = MqttStatus.State.CONNECTED
            lastError = null
            statusError = null
            availabilityError = null
            synchronized(lock) { reconnectAttempt = 0 }
            // Schedule the connect-time burst *before* notifying the listener:
            // the listener is app code (a WebSocket broadcast in MainActivity)
            // and must never be able to prevent availability, discovery and
            // status from running by throwing.
            execute {
                // Re-check identity: settings may have replaced this client
                // between the callback and this task running.
                if (stopped || cli !== client) return@execute
                // Every step below is isolated with its own Throwable catch: a
                // RuntimeException or Error escaping a step (e.g. a send racing
                // a teardown) must never abort the remaining steps, and must
                // never escape this task onto the executor thread — on Android
                // that reaches the uncaught-exception handler on the
                // `terebi-mqtt` thread and can kill the process.
                val settings = try {
                    settingsProvider()
                } catch (t: Throwable) {
                    // Nothing below can build a topic without settings; this is
                    // the one step that legitimately gates the rest. Record it
                    // through the same channel as the other publish failures so
                    // a burst that never ran is not silent.
                    statusError = safeFailureReason(t)
                    SafeLog.w(TAG, "MQTT connect task: settings unavailable")
                    return@execute
                }
                try {
                    // tryPublishRetained, not publishRetained: publish() swallows
                    // a Paho refusal, and a failed retained availability leaves
                    // Home Assistant showing the device offline, so it must be
                    // recorded rather than only logged.
                    tryPublishRetained(MqttTopics.availability(settings.baseTopic), PAYLOAD_ONLINE)
                    availabilityError = null
                } catch (t: Throwable) {
                    availabilityError = safeFailureReason(t)
                    SafeLog.w(TAG, "MQTT availability publish failed")
                }
                try {
                    subscribe(MqttTopics.command(settings.baseTopic))
                } catch (t: Throwable) {
                    SafeLog.w(TAG, "MQTT subscribe failed")
                }
                // Discovery first, so the retained status below carries this
                // attempt's discoveryTopics/discoveryError. Recorded the same
                // way an entity failure is: discoveryError + a warning.
                try {
                    publishDiscovery()
                } catch (t: Throwable) {
                    // publishDiscovery already reset discoveryTopics at its
                    // start, but reset it again here so a throw from anywhere
                    // else in the attempt can never report the previous
                    // connect's topic count alongside this error.
                    discoveryTopics = 0
                    discoveryError = safeFailureReason(t)
                    SafeLog.w(TAG, "MQTT discovery failed")
                }
                try {
                    publishStatusNow()
                } catch (t: Throwable) {
                    statusError = safeFailureReason(t)
                    SafeLog.w(TAG, "MQTT status publish failed")
                }
                try {
                    onStatusChanged()
                } catch (t: Throwable) {
                    SafeLog.w(TAG, "MQTT status listener failed")
                }
            }
            // The burst is already queued above, so a throwing listener cannot
            // suppress it. Guard the call regardless: this runs on Paho's
            // callback thread and must not escape into Paho.
            try {
                onStatusChanged()
            } catch (t: Throwable) {
                SafeLog.w(TAG, "MQTT status listener failed")
            }
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

        /**
         * Paho's QoS-1 in-flight window. The default of 10 is too small: a
         * single connect publishes availability, the status and 21 discovery
         * configs, and command results share the same window. Raise it well
         * above the entity count so a connect-time burst always fits.
         */
        const val MAX_INFLIGHT = 64

        /**
         * Whether a clean shutdown removes the Home Assistant discovery configs.
         * Off by default: the retained `availability` topic already reports the
         * device offline, so the entities are correctly shown as unavailable and
         * are re-discovered on the next start without a gap.
         */
        const val DISCOVERY_REMOVE_ON_SHUTDOWN = false

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

        /** The outcome of one failure-isolated run: counts and the last failure reason. */
        data class IsolatedResult(val succeeded: Int, val failed: Int, val lastError: String?)

        /**
         * Runs [action] for every element of [items], isolating each failure: a
         * throwing item never prevents the ones after it. Returns the
         * success/failure counts and a short, redacted reason for the last
         * failure (null when none failed).
         *
         * Pure and framework-free so the isolation guarantee and the counting
         * are directly unit-testable.
         */
        fun <T> runIsolated(items: List<T>, action: (T) -> Unit): IsolatedResult {
            var succeeded = 0
            var failed = 0
            var lastError: String? = null
            for (item in items) {
                try {
                    action(item)
                    succeeded++
                } catch (t: Throwable) {
                    failed++
                    // discoveryFailureReason touches t.message and runs regexes;
                    // a Throwable whose getMessage() throws (or an Error such as
                    // OutOfMemoryError) must not escape here and skip the count.
                    lastError = safeFailureReason(t)
                }
            }
            return IsolatedResult(succeeded, failed, lastError)
        }

        /**
         * A short reason for a failed publish: the exception class simple name
         * plus its redacted message. A `scheme://user:password@host` user-info
         * is stripped first — [SafeLog.redact] handles query strings, tokens and
         * bearer values but not a non-http user-info URL — so a broker
         * credential can never reach the retained status payload.
         */
        fun discoveryFailureReason(cause: Throwable): String {
            val name = cause.javaClass.simpleName.ifEmpty { "Throwable" }
            val raw = cause.message
            if (raw.isNullOrBlank()) return name
            val scrubbed = USERINFO_REGEX.replace(raw) { "${it.groupValues[1]}://<redacted>@" }
            return "$name: ${SafeLog.redact(scrubbed)}"
        }

        /**
         * [discoveryFailureReason] guarded against a hostile throwable: reading
         * `message` or running the redaction regexes can itself throw (a
         * `getMessage()` that throws, or an `OutOfMemoryError`), which must not
         * escape a failure-isolation catch and skip the failure count. Falls
         * back to the exception's class name.
         */
        fun safeFailureReason(cause: Throwable): String =
            try {
                discoveryFailureReason(cause)
            } catch (t: Throwable) {
                cause.javaClass.simpleName.ifEmpty { "Throwable" }
            }

        /** Matches `scheme://<user-info>@` so credentials in a URL are removed. */
        private val USERINFO_REGEX = Regex("""(?i)([a-z][a-z0-9+.\-]*)://[^/\s@]+@""")
    }
}

/**
 * The subset of the Eclipse Paho client [MqttManager] drives.
 *
 * It exists as a seam so the real connect/publish code paths — in particular
 * the Home Assistant discovery loop and the retained status publish — can be
 * exercised in JVM unit tests with a stub that reproduces Paho's failure
 * modes, most importantly `MqttException(REASON_CODE_MAX_INFLIGHT)` thrown when
 * the QoS-1 in-flight window fills up.
 */
internal interface MqttClientHandle {
    val isConnected: Boolean
    fun setCallback(callback: MqttCallback)
    fun connect(options: MqttConnectOptions, userContext: Any?, listener: IMqttActionListener)
    fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean): IMqttToken
    fun subscribe(topic: String, qos: Int): IMqttToken
    fun disconnect(quiesceTimeout: Long)
    fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long)
    fun close()
}

/** [MqttClientHandle] backed by a real Eclipse Paho [MqttAsyncClient]. */
private class PahoClientHandle(private val delegate: MqttAsyncClient) : MqttClientHandle {
    override val isConnected: Boolean get() = delegate.isConnected

    override fun setCallback(callback: MqttCallback) {
        delegate.setCallback(callback)
    }

    override fun connect(options: MqttConnectOptions, userContext: Any?, listener: IMqttActionListener) {
        delegate.connect(options, userContext, listener)
    }

    override fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean): IMqttToken =
        delegate.publish(topic, payload, qos, retained)

    override fun subscribe(topic: String, qos: Int): IMqttToken = delegate.subscribe(topic, qos)

    override fun disconnect(quiesceTimeout: Long) {
        delegate.disconnect(quiesceTimeout)
    }

    override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) {
        delegate.disconnectForcibly(quiesceTimeout, disconnectTimeout)
    }

    override fun close() {
        delegate.close()
    }
}
