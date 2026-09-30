package com.terebibro.tv.server

import android.os.Handler
import android.os.Looper
import com.terebibro.tv.MainActivity
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The one place where server threads hand work to the UI thread.
 *
 * Server code never touches the WebView directly and never blocks the main
 * thread waiting for a server thread.
 */
object MainThreadBridge {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var host: WeakReference<MainActivity>? = null

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun hostOrNull(): MainActivity? = host?.get()

    /** Fire-and-forget on the UI thread. */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    /**
     * Runs [block] on the UI thread and waits up to 3 seconds for the result.
     * Returns null (or the block's null) when the activity is gone or the wait
     * times out; callers map null to HTTP 503 / a WS error.
     *
     * Must never be called from the UI thread.
     */
    fun <T> postAndWait(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: (MainActivity) -> T): T? {
        if (isMainThread()) {
            throw IllegalStateException("postAndWait must not be called from the main thread")
        }

        val activity = hostOrNull()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return null

        val future = CompletableFuture<T>()
        val posted = handler.post {
            if (future.isCancelled) return@post
            val current = hostOrNull()
            if (current == null || current.isFinishing || current.isDestroyed) {
                future.completeExceptionally(IllegalStateException("activity unavailable"))
            } else {
                try {
                    future.complete(block(current))
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
        }
        if (!posted) return null

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // The waiter gave up and can be interrupted from elsewhere; do not
            // let the cancellation swallow the interrupt status.
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            // Timeout or failure: cancel so the posted block does not run later
            // and double-apply a client retry.
            future.cancel(true)
            null
        }
    }

    /** Runs [block] inline when already on the UI thread, otherwise via postAndWait. */
    fun <T> runOnMain(block: (MainActivity) -> T): T? {
        if (isMainThread()) {
            val current = hostOrNull() ?: return null
            if (current.isFinishing || current.isDestroyed) return null
            return block(current)
        }
        return postAndWait(block = block)
    }

    private const val DEFAULT_TIMEOUT_MS = 3_000L
}
