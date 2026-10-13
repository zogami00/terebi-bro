package com.terebibro.tv.mqtt

import com.terebibro.tv.server.ApiResult
import com.terebibro.tv.web.BrowserState
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the **real** connect-time publish path ([MqttManager.connectComplete]
 * -> availability/subscribe/discovery/status) against a stub [MqttClientHandle].
 *
 * Why this class of bug appeared in the field: Paho 1.2.5 defaults the QoS-1
 * in-flight window to 10. `ClientState.send()` throws
 * `MqttException(REASON_CODE_MAX_INFLIGHT, 32202)` once the window is full, and
 * slots are only released on Paho's callback thread via
 * `ClientState.notifyComplete`. Publishing the whole burst from inside
 * `connectComplete` ran on that very thread, so no slot was ever released: the
 * 10th publish (availability + the first 9 configs) failed, and because
 * `publishDiscovery` isolated each failure, every entity after it silently
 * failed the same way. The stub below reproduces the thrown
 * `MqttException(32202)` and the tests prove the loop still reaches every
 * entity and reports the failure honestly.
 */
class MqttManagerPublishTest {

    private val state = BrowserState(
        url = "https://example.com/",
        title = "Example",
        loading = false,
        progress = 100,
        canGoBack = true,
        canGoForward = false,
        homeUrl = "https://example.com/",
        fullscreen = true,
        keepAwake = true,
        deviceName = "terebi-tv",
        ip = "10.0.0.5",
        port = 8765,
        network = "wifi",
        webViewVersion = "com.google.android.webview 138.0",
        webViewOutdated = false,
        appVersion = "0.3.2",
        androidVersion = "9",
        sdkInt = 28,
        uptimeMs = 123_456L,
        seq = 7L
    )

    private val settings = MqttSettings(
        enabled = true,
        host = "broker.local",
        baseTopic = "terebibro/dev1"
    )

    // ---------------------------------------------------------------------
    // Discovery survives an in-flight-window failure
    // ---------------------------------------------------------------------

    @Test
    fun `discovery attempts every entity and keeps publishing after an in-flight failure`() {
        // The 10th QoS-1 publish fails: availability is #1, so entities 1..8
        // (publish #2..#9) succeed and the 9th entity (publish #10) throws —
        // exactly the `android_version` failure seen in the field. Later
        // publishes succeed because the failure is transient and the window is
        // no longer blocked by the publish burst running on the callback thread.
        val fake = FakeClient(failPublish = { attempt, _ ->
            if (attempt == 10) MqttException(MqttException.REASON_CODE_MAX_INFLIGHT.toInt()) else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.size >= 23 }

        val attemptedConfigs = fake.attempted.filter { it.topic.endsWith("/config") }
        val publishedConfigs = fake.published.filter { it.topic.endsWith("/config") }
        val androidVersion = "homeassistant/sensor/${MqttDiscovery.nodeId("dev1")}/android_version/config"

        // (a) Every entity is still attempted: one failure never aborts the loop.
        assertEquals(21, attemptedConfigs.size)
        assertTrue(attemptedConfigs.any { it.topic == androidVersion })

        // (c) Entities after the failure still publish.
        assertEquals(20, publishedConfigs.size)
        assertFalse(publishedConfigs.any { it.topic == androidVersion })
        assertTrue(publishedConfigs.any { it.topic.endsWith("/loading/config") })
        assertTrue(publishedConfigs.last().topic.endsWith("/open_url/config"))

        // (b) The reported result is honest: 20/21 topics and a real reason.
        val status = manager.status()
        assertEquals(20, status.discoveryTopics)
        assertNotNull(status.discoveryError)
        assertTrue(status.discoveryError!!.startsWith("MqttException"))
        assertFalse(status.discoveryError!!.contains("android_version")) // no config leaked

        // The retained status publish must still be attempted after discovery.
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })
    }

    @Test
    fun `a discovery failure is still surfaced in the retained status payload`() {
        val fake = FakeClient(failPublish = { attempt, _ ->
            if (attempt == 2) MqttException(MqttException.REASON_CODE_MAX_INFLIGHT.toInt()) else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.published.any { it.topic.endsWith("/status") } }

        val statusMessage = fake.published.last { it.topic.endsWith("/status") }
        // The status payload carries this attempt's discovery result (20/21).
        assertTrue(statusMessage.payload.contains("\"discoveryTopics\":20"))
        assertTrue(statusMessage.payload.contains("\"discoveryError\":\"MqttException"))
    }

    // ---------------------------------------------------------------------
    // Status publish is never silently dropped
    // ---------------------------------------------------------------------

    @Test
    fun `a failed status publish is recorded instead of being swallowed`() {
        val fake = FakeClient(failPublish = { _, topic ->
            if (topic.endsWith("/status")) MqttException(MqttException.REASON_CODE_MAX_INFLIGHT.toInt()) else null
        })
        val manager = manager(fake)

        manager.start()
        await { manager.status().statusError != null }

        // Discovery is unaffected; only the status publish failed.
        assertEquals(21, manager.status().discoveryTopics)
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        val statusError = manager.status().statusError
        assertNotNull(statusError)
        assertTrue(statusError!!.startsWith("MqttException"))
    }

    // ---------------------------------------------------------------------
    // The connect task isolates every step: a RuntimeException/Error from one
    // step (e.g. a send racing a teardown) must not skip the later ones, and
    // must never escape the task onto the `terebi-mqtt` executor thread.
    // ---------------------------------------------------------------------

    @Test
    fun `availability RuntimeException never stops discovery or status`() {
        val fake = FakeClient(failPublish = { _, topic ->
            if (topic.endsWith("/availability")) RuntimeException("send raced teardown") else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.any { it.topic.endsWith("/status") } }

        // The availability publish was reached (and threw), yet discovery and
        // status still ran: one failed step never prevents a later one.
        assertTrue(fake.attempted.any { it.topic.endsWith("/availability") })
        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        // The counters and error fields stay honest: availability is not part
        // of the discovery result, and nothing else was perturbed. The failed
        // availability is now recorded (it must not be only logged).
        val status = manager.status()
        assertEquals(21, status.discoveryTopics)
        assertEquals(null, status.discoveryError)
        assertNotNull(status.statusError)
        assertTrue(status.statusError!!.startsWith("RuntimeException"))
    }

    @Test
    fun `subscribe RuntimeException never stops discovery or status`() {
        val fake = FakeClient(failSubscribe = { RuntimeException("subscribe raced teardown") })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.any { it.topic.endsWith("/status") } }

        // The subscribe was reached (and threw), yet discovery and status still
        // ran. Before the task-level isolation this RuntimeException escaped and
        // both were skipped.
        assertTrue(fake.subscribedTopics.any { it.endsWith("/command") })
        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        val status = manager.status()
        assertEquals(21, status.discoveryTopics)
        assertEquals(null, status.discoveryError)
        assertEquals(null, status.statusError)
    }

    @Test
    fun `a RuntimeException from one discovery publish is recorded and status still runs`() {
        val fake = FakeClient(failPublish = { _, topic ->
            if (topic.endsWith("/android_version/config")) RuntimeException("boom") else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.any { it.topic.endsWith("/status") } }

        // Every entity is still attempted; the failure is counted, not skipped.
        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        val status = manager.status()
        assertEquals(20, status.discoveryTopics)
        assertNotNull(status.discoveryError)
        assertTrue(status.discoveryError!!.startsWith("RuntimeException"))
        assertEquals(null, status.statusError)

        // The retained status payload carries the honest discovery result.
        val statusMessage = fake.published.last { it.topic.endsWith("/status") }
        assertTrue(statusMessage.payload.contains("\"discoveryTopics\":20"))
        assertTrue(statusMessage.payload.contains("\"discoveryError\":\"RuntimeException"))
    }

    @Test
    fun `an Error from the availability publish never escapes the connect task`() {
        val fake = FakeClient(failPublish = { _, topic ->
            if (topic.endsWith("/availability")) OutOfMemoryError("no memory") else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.any { it.topic.endsWith("/status") } }

        // An Error, not just an Exception, must be contained: discovery and
        // status still run and nothing reaches the executor's thread handler.
        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        val status = manager.status()
        assertEquals(21, status.discoveryTopics)
        assertEquals(null, status.discoveryError)
        // The failed availability is recorded even when the failure is an Error.
        assertNotNull(status.statusError)
        assertTrue(status.statusError!!.startsWith("OutOfMemoryError"))
    }

    @Test
    fun `an Error from a discovery publish is recorded honestly and status still runs`() {
        val fake = FakeClient(failPublish = { _, topic ->
            if (topic.endsWith("/android_version/config")) OutOfMemoryError("no memory") else null
        })
        val manager = manager(fake)

        manager.start()
        await { fake.attempted.any { it.topic.endsWith("/status") } }

        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })

        val status = manager.status()
        assertEquals(20, status.discoveryTopics)
        assertNotNull(status.discoveryError)
        assertTrue(status.discoveryError!!.startsWith("OutOfMemoryError"))
        assertEquals(null, status.statusError)

        val statusMessage = fake.published.last { it.topic.endsWith("/status") }
        assertTrue(statusMessage.payload.contains("\"discoveryError\":\"OutOfMemoryError"))
    }

    // ---------------------------------------------------------------------
    // The in-flight window itself
    // ---------------------------------------------------------------------

    @Test
    fun `the in-flight window is raised above the discovery entity count`() {
        val fake = FakeClient()
        val manager = manager(fake)

        manager.start()
        await { fake.connectOptions != null }

        val inflight = fake.connectOptions!!.maxInflight
        assertTrue(
            "maxInflight=$inflight must be large enough for every entity plus " +
                "availability, status and command results",
            inflight >= 21
        )
    }

    // ---------------------------------------------------------------------
    // F1: an aborted attempt must not report the previous connect's counters
    // ---------------------------------------------------------------------

    @Test
    fun `a second connect whose state is unavailable does not carry over discoveryTopics`() {
        val fake = FakeClient()
        var provider: () -> BrowserState? = { state }
        val manager = manager(fake, stateProvider = { provider() })

        manager.start()
        await { manager.status().discoveryTopics == 21 }
        await { fake.published.any { it.topic.endsWith("/status") } }

        // The first connect published every entity and recorded no error.
        assertEquals(21, manager.status().discoveryTopics)
        assertEquals(null, manager.status().discoveryError)

        // The second connect cannot read the state, so its attempt aborts
        // before the counters would otherwise be assigned.
        provider = { throw RuntimeException("state unavailable") }
        manager.restart()
        await { manager.status().discoveryError != null }

        // The previous connect's 21 topics must not survive alongside this
        // attempt's error: that would be dishonest about what was published.
        assertEquals(0, manager.status().discoveryTopics)
        assertTrue(manager.status().discoveryError!!.startsWith("RuntimeException"))
    }

    // ---------------------------------------------------------------------
    // F2: a throwing status listener must not suppress the connect burst
    // ---------------------------------------------------------------------

    @Test
    fun `a throwing status listener does not stop the connect burst`() {
        val fake = FakeClient()
        var calls = 0
        val manager = manager(
            fake,
            onStatusChanged = {
                calls++
                // connect() notifies once (call 1); connectComplete notifies
                // again (call 2). Throw there, exactly where an unguarded call
                // used to run before the burst was scheduled.
                if (calls == 2) throw RuntimeException("listener boom")
            }
        )

        manager.start()
        await { fake.published.any { it.topic.endsWith("/status") } }

        // The listener threw, yet availability, discovery and status all ran.
        assertTrue(calls >= 2)
        assertEquals(21, fake.attempted.count { it.topic.endsWith("/config") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/availability") })
        assertTrue(fake.attempted.any { it.topic.endsWith("/status") })
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    private fun manager(
        fake: FakeClient,
        stateProvider: () -> BrowserState? = { state },
        onStatusChanged: () -> Unit = {}
    ): MqttManager = MqttManager(
        settingsProvider = { settings },
        clientIdProvider = { "test-client" },
        dispatcher = object : MqttManager.Dispatcher {
            override fun dispatch(command: MqttCommand): ApiResult = ApiResult.Ok()
        },
        stateProvider = stateProvider,
        deviceIdProvider = { "dev1" },
        onStatusChanged = onStatusChanged,
        lastDiscoveryPrefixProvider = { null },
        lastDiscoveryPrefixStore = {},
        clientFactory = { _, _ -> fake }
    )

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private data class Attempt(
        val topic: String,
        val payload: String,
        val retained: Boolean,
        val qos: Int
    )

    /**
     * A stub Paho client. [failPublish] may throw (typically
     * `MqttException(REASON_CODE_MAX_INFLIGHT)`, but the tests also use a
     * `RuntimeException`/`Error`) before the publish is recorded, so
     * `attempted` counts everything reached and `published` only what
     * succeeded. [failSubscribe] does the same for the connect-time subscribe.
     */
    private class FakeClient(
        private val failPublish: (attempt: Int, topic: String) -> Throwable? = { _, _ -> null },
        private val failSubscribe: (topic: String) -> Throwable? = { null }
    ) : MqttClientHandle {
        // Copy-on-write and volatile so the test thread observes the executor
        // thread's publishes without an explicit barrier.
        val attempted = CopyOnWriteArrayList<Attempt>()
        val published = CopyOnWriteArrayList<Attempt>()
        val subscribedTopics = CopyOnWriteArrayList<String>()
        var callback: MqttCallbackExtended? = null

        @Volatile
        var connectOptions: MqttConnectOptions? = null

        @Volatile
        override var isConnected: Boolean = false

        override fun setCallback(callback: MqttCallback) {
            this.callback = callback as? MqttCallbackExtended
        }

        override fun connect(
            options: MqttConnectOptions,
            userContext: Any?,
            listener: IMqttActionListener
        ) {
            connectOptions = options
            isConnected = true
            // Paho signals a successful connection on its callback thread.
            callback?.connectComplete(false, "tcp://fake")
        }

        override fun publish(
            topic: String,
            payload: ByteArray,
            qos: Int,
            retained: Boolean
        ): IMqttToken {
            val attempt = attempted.size + 1
            val record = Attempt(topic, String(payload, Charsets.UTF_8), retained, qos)
            attempted += record
            failPublish(attempt, topic)?.let { throw it }
            published += record
            return MqttToken("test")
        }

        override fun subscribe(topic: String, qos: Int): IMqttToken {
            subscribedTopics += topic
            failSubscribe(topic)?.let { throw it }
            return MqttToken("test")
        }

        override fun disconnect(quiesceTimeout: Long) {}

        override fun disconnectForcibly(quiesceTimeout: Long, disconnectTimeout: Long) {}

        override fun close() {}
    }
}
