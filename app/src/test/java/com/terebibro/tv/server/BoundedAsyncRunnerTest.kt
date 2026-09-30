package com.terebibro.tv.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ClientHandler
import java.io.InputStream
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAsyncRunnerTest {

    /**
     * A NanoHTTPD shell used only as the enclosing instance for handlers; it is
     * never started, so no socket is bound. Handlers are driven straight through
     * [BoundedAsyncRunner.exec] instead of a real accept loop, which keeps the
     * test pure JVM.
     */
    private class FakeServer : NanoHTTPD(0) {
        inner class FakeHandler(private val peer: String) :
            ClientHandler(InputStream.nullInputStream(), Socket()), PeerAddressAware {
            var closed = false
            override val peerAddress: String? get() = peer
            override fun run() {}
            override fun close() {
                closed = true
            }
        }
    }

    /**
     * Regression: a rebind shares one per-peer limiter across servers. closeAll
     * must release the slots of the handlers it closes, or a peer that held
     * several keep-alive connections sits at the cap forever and every new
     * connection is rejected.
     */
    @Test
    fun `closeAll releases every per-peer slot and accepts the peer again`() {
        val limiter = ConnectionLimiter(2)
        val runner = BoundedAsyncRunner(64, limiter)
        val server = FakeServer()
        val peer = "192.168.1.50"
        val handlers = (1..2).map { server.FakeHandler(peer).also(runner::exec) }

        assertEquals(2, limiter.countFor(peer))

        runner.closeAll()

        assertEquals(0, limiter.countFor(peer))
        assertTrue("a fresh connection from the same peer must be accepted", limiter.acquire(peer))
        assertTrue("closeAll must close every accepted handler", handlers.all { it.closed })
    }

    @Test
    fun `a handler closed after closeAll does not double release`() {
        val limiter = ConnectionLimiter(1)
        val runner = BoundedAsyncRunner(64, limiter)
        val server = FakeServer()
        val peer = "10.0.0.7"
        val handler = server.FakeHandler(peer)
        runner.exec(handler)

        runner.closeAll()
        assertEquals(0, limiter.countFor(peer))

        // The handler thread wakes from its closed socket and reports itself
        // closed; the already-removed handler must not release a second slot.
        runner.closed(handler)
        assertEquals(0, limiter.countFor(peer))
    }
}
