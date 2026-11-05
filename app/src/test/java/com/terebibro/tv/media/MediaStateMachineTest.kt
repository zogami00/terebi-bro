package com.terebibro.tv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStateMachineTest {

    private val cfg = MediaSettings()

    private fun split() = MediaState(enabled = true, mode = MediaMode.SPLIT)

    private fun mediaOnly() = MediaState(enabled = true, mode = MediaMode.MEDIA_ONLY)

    private fun reduce(state: MediaState, event: MediaEvent, nowMs: Long = 0L): MediaState =
        MediaStateMachine.reduce(state, event, cfg, nowMs)

    // ----------------------------------------------------------------- enabled

    @Test
    fun `media mode starts disabled in qms only`() {
        val state = MediaState()
        assertFalse(state.enabled)
        assertEquals(MediaMode.QMS_ONLY, state.mode)
    }

    @Test
    fun `enabling from qms only starts in split`() {
        val state = reduce(MediaState(), MediaEvent.Enabled(true))
        assertTrue(state.enabled)
        assertEquals(MediaMode.SPLIT, state.mode)
    }

    @Test
    fun `enabling again keeps the current mode`() {
        assertEquals(MediaMode.MEDIA_ONLY, reduce(mediaOnly(), MediaEvent.Enabled(true)).mode)
    }

    @Test
    fun `disabling from every mode collapses to qms only`() {
        val states = listOf(
            MediaState(mode = MediaMode.QMS_ONLY, enabled = true),
            MediaState(mode = MediaMode.MEDIA_ONLY, enabled = true),
            MediaState(mode = MediaMode.SPLIT, enabled = true),
            MediaState(mode = MediaMode.QMS_FOCUS, enabled = true)
        )
        for (state in states) {
            val next = reduce(state, MediaEvent.Enabled(false))
            assertEquals(MediaMode.QMS_ONLY, next.mode)
            assertFalse(next.enabled)
            assertEquals(0L, next.manualUntilMs)
            assertEquals(0L, next.dwellUntilMs)
        }
    }

    @Test
    fun `while disabled every other event is a no-op`() {
        val state = MediaState()
        val events = listOf(
            MediaEvent.Call(true),
            MediaEvent.Call(false),
            MediaEvent.Manual(MediaMode.SPLIT),
            MediaEvent.ManualExpired,
            MediaEvent.DwellElapsed,
            MediaEvent.QmsUnavailable,
            MediaEvent.QmsAvailable,
            MediaEvent.Overlay(true),
            MediaEvent.Lifecycle(true),
            MediaEvent.PlaybackError("boom"),
            MediaEvent.PlaybackRecovered,
            MediaEvent.SourceChanged("local", 3)
        )
        for (event in events) {
            assertEquals(event.toString(), state, reduce(state, event))
        }
    }

    // -------------------------------------------------------------------- call

    @Test
    fun `a call in split focuses the qms`() {
        val state = reduce(split(), MediaEvent.Call(true), nowMs = 1_000)
        assertEquals(MediaMode.QMS_FOCUS, state.mode)
        assertEquals(MediaMode.SPLIT, state.returnMode)
        assertEquals(1_000L + cfg.dwellSec * 1000L, state.dwellUntilMs)
    }

    @Test
    fun `a call in media only focuses the qms`() {
        val state = reduce(mediaOnly(), MediaEvent.Call(true))
        assertEquals(MediaMode.QMS_FOCUS, state.mode)
        assertEquals(MediaMode.MEDIA_ONLY, state.returnMode)
    }

    @Test
    fun `a call during focus re-arms the dwell without changing the mode`() {
        val first = reduce(split(), MediaEvent.Call(true), nowMs = 1_000)
        val second = reduce(first, MediaEvent.Call(true), nowMs = 3_000)
        assertEquals(MediaMode.QMS_FOCUS, second.mode)
        assertEquals(MediaMode.SPLIT, second.returnMode)
        assertEquals(3_000L + cfg.dwellSec * 1000L, second.dwellUntilMs)
        assertTrue(second.dwellUntilMs > first.dwellUntilMs)
    }

    @Test
    fun `a missed call signal changes nothing`() {
        assertEquals(split(), reduce(split(), MediaEvent.Call(false)))
    }

    @Test
    fun `a call while qms only changes nothing`() {
        val state = MediaState(enabled = true, mode = MediaMode.QMS_ONLY)
        assertEquals(MediaMode.QMS_ONLY, reduce(state, MediaEvent.Call(true)).mode)
    }

    @Test
    fun `dwell elapsed returns to the mode recorded before the call`() {
        val fromSplit = reduce(reduce(split(), MediaEvent.Call(true)), MediaEvent.DwellElapsed)
        assertEquals(MediaMode.SPLIT, fromSplit.mode)

        val fromMedia = reduce(reduce(mediaOnly(), MediaEvent.Call(true)), MediaEvent.DwellElapsed)
        assertEquals(MediaMode.MEDIA_ONLY, fromMedia.mode)
    }

    @Test
    fun `dwell elapsed outside focus changes nothing`() {
        assertEquals(MediaMode.SPLIT, reduce(split(), MediaEvent.DwellElapsed).mode)
        assertEquals(MediaMode.MEDIA_ONLY, reduce(mediaOnly(), MediaEvent.DwellElapsed).mode)
    }

    // ---------------------------------------------------------------- qms state

    @Test
    fun `an unavailable qms falls back to media only and back to split`() {
        val degraded = reduce(split(), MediaEvent.QmsUnavailable)
        assertEquals(MediaMode.MEDIA_ONLY, degraded.mode)
        assertFalse(degraded.qmsAvailable)

        val restored = reduce(degraded, MediaEvent.QmsAvailable)
        assertEquals(MediaMode.SPLIT, restored.mode)
        assertTrue(restored.qmsAvailable)
    }

    @Test
    fun `qms available is a no-op while the qms is already available`() {
        val focusing = reduce(split(), MediaEvent.Call(true), nowMs = 1_000L)
        val after = reduce(focusing, MediaEvent.QmsAvailable, nowMs = 2_000L)
        assertEquals(MediaMode.QMS_FOCUS, after.mode)
        assertEquals(focusing.dwellUntilMs, after.dwellUntilMs)
    }

    @Test
    fun `qms available does not override a manual hold`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)
        val after = reduce(held, MediaEvent.QmsAvailable, nowMs = 1_000L)
        assertEquals(MediaMode.MEDIA_ONLY, after.mode)
        assertEquals(MediaStateMachine.MANUAL_MEDIA_HOLD_MS, after.manualUntilMs)
    }

    // ------------------------------------------------------------------ manual

    @Test
    fun `manual media only is held for 30 minutes`() {
        val state = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY))
        assertEquals(MediaMode.MEDIA_ONLY, state.mode)
        assertEquals(MediaStateMachine.MANUAL_MEDIA_HOLD_MS, state.manualUntilMs)
        assertEquals(1_800_000L, state.manualUntilMs)
    }

    @Test
    fun `manual qms only is held indefinitely`() {
        val state = reduce(split(), MediaEvent.Manual(MediaMode.QMS_ONLY))
        assertEquals(MediaMode.QMS_ONLY, state.mode)
        assertEquals(MediaStateMachine.MANUAL_INDEFINITE_UNTIL_MS, state.manualUntilMs)
        assertEquals(Long.MAX_VALUE, state.manualUntilMs)
    }

    @Test
    fun `manual split drops any hold`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)
        val split = reduce(held, MediaEvent.Manual(MediaMode.SPLIT), nowMs = 1_000L)
        assertEquals(MediaMode.SPLIT, split.mode)
        assertEquals(0L, split.manualUntilMs)
    }

    @Test
    fun `a manual hold is still valid just before its deadline`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)

        val before = reduce(held, MediaEvent.Lifecycle(false), nowMs = 1_799_999L)
        assertEquals(MediaMode.MEDIA_ONLY, before.mode)
        assertEquals(1_800_000L, before.manualUntilMs)
    }

    @Test
    fun `a manual hold expires at exactly its deadline`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)

        val atExpiry = reduce(held, MediaEvent.Lifecycle(false), nowMs = 1_800_000L)
        assertEquals(MediaMode.SPLIT, atExpiry.mode)
        assertEquals(0L, atExpiry.manualUntilMs)
    }

    @Test
    fun `an indefinite qms only hold never expires`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.QMS_ONLY), nowMs = 0L)
        val muchLater = reduce(held, MediaEvent.Lifecycle(false), nowMs = Long.MAX_VALUE - 1)
        assertEquals(MediaMode.QMS_ONLY, muchLater.mode)
        assertEquals(MediaStateMachine.MANUAL_INDEFINITE_UNTIL_MS, muchLater.manualUntilMs)
    }

    @Test
    fun `a manual hold expiring during a call does not cut the call short`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)
        val focusing = reduce(held, MediaEvent.Call(true), nowMs = 10_000L)
        val afterExpiry = reduce(focusing, MediaEvent.Lifecycle(false), nowMs = 1_800_001L)
        assertEquals(MediaMode.QMS_FOCUS, afterExpiry.mode)
        assertEquals(0L, afterExpiry.manualUntilMs)
    }

    /**
     * The stale-returnMode sequence: the hold expires while a call is live, so
     * the dwell must return to the automatic mode, never to the expired manual
     * mode it was recorded from.
     */
    @Test
    fun `a dwell recorded against an expired manual hold returns to split`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)
        // The hold expires at 1_800_000 ms; this call's 50 s dwell ends at 1_820_000.
        val called = reduce(held, MediaEvent.Call(true), nowMs = 1_770_000L)
        assertEquals(MediaMode.QMS_FOCUS, called.mode)
        assertEquals(MediaMode.MEDIA_ONLY, called.returnMode)

        val afterExpiry = reduce(called, MediaEvent.Lifecycle(false), nowMs = 1_800_000L)
        assertEquals(MediaMode.QMS_FOCUS, afterExpiry.mode)
        assertEquals(MediaMode.SPLIT, afterExpiry.returnMode)

        val afterDwell = reduce(afterExpiry, MediaEvent.DwellElapsed, nowMs = 1_820_000L)
        assertEquals(MediaMode.SPLIT, afterDwell.mode)
    }

    @Test
    fun `an explicit manual expiry returns to automatic behaviour`() {
        val held = reduce(split(), MediaEvent.Manual(MediaMode.MEDIA_ONLY), nowMs = 0L)
        val expired = reduce(held, MediaEvent.ManualExpired, nowMs = 1_000L)
        assertEquals(MediaMode.SPLIT, expired.mode)
        assertEquals(0L, expired.manualUntilMs)
    }

    @Test
    fun `qms focus cannot be selected manually`() {
        val state = reduce(split(), MediaEvent.Manual(MediaMode.QMS_FOCUS))
        assertEquals(MediaMode.SPLIT, state.mode)
        assertEquals(0L, state.manualUntilMs)
    }

    // ----------------------------------------------------------------- overlay

    @Test
    fun `overlay pause and resume never change the mode`() {
        val modes = listOf(
            MediaMode.QMS_ONLY,
            MediaMode.MEDIA_ONLY,
            MediaMode.SPLIT,
            MediaMode.QMS_FOCUS
        )
        for (mode in modes) {
            val state = MediaState(enabled = true, mode = mode)
            val paused = reduce(state, MediaEvent.Overlay(true))
            assertEquals(mode, paused.mode)
            assertTrue(paused.paused)
            assertTrue(paused.overlayVisible)

            val resumed = reduce(paused, MediaEvent.Overlay(false))
            assertEquals(mode, resumed.mode)
            assertFalse(resumed.paused)
            assertFalse(resumed.overlayVisible)
        }
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    fun `lifecycle pause and resume are recorded`() {
        val paused = reduce(split(), MediaEvent.Lifecycle(true))
        assertTrue(paused.paused)
        assertFalse(reduce(paused, MediaEvent.Lifecycle(false)).paused)
    }

    @Test
    fun `closing an overlay while the activity is paused leaves it paused`() {
        var state = reduce(split(), MediaEvent.Lifecycle(true))
        state = reduce(state, MediaEvent.Overlay(true))
        state = reduce(state, MediaEvent.Overlay(false))
        assertFalse(state.overlayVisible)
        assertTrue(state.lifecyclePaused)
        assertTrue(state.paused)
    }

    @Test
    fun `resuming the lifecycle while an overlay is open leaves it paused`() {
        var state = reduce(split(), MediaEvent.Overlay(true))
        state = reduce(state, MediaEvent.Lifecycle(true))
        state = reduce(state, MediaEvent.Lifecycle(false))
        assertTrue(state.overlayVisible)
        assertFalse(state.lifecyclePaused)
        assertTrue(state.paused)
    }

    @Test
    fun `re-enabling after a pause and an error starts clean in split`() {
        var state = reduce(split(), MediaEvent.Lifecycle(true))
        state = reduce(state, MediaEvent.Overlay(true))
        state = reduce(state, MediaEvent.PlaybackError("boom"))

        val disabled = reduce(state, MediaEvent.Enabled(false))
        assertFalse(disabled.paused)
        assertFalse(disabled.overlayVisible)
        assertFalse(disabled.lifecyclePaused)
        assertEquals("", disabled.lastError)

        val enabled = reduce(disabled, MediaEvent.Enabled(true))
        assertTrue(enabled.enabled)
        assertEquals(MediaMode.SPLIT, enabled.mode)
        assertFalse(enabled.paused)
        assertFalse(enabled.overlayVisible)
        assertFalse(enabled.lifecyclePaused)
        assertEquals("", enabled.lastError)
    }

    // ---------------------------------------------------------------- playback

    @Test
    fun `a playback error is recorded and cleared without changing the mode`() {
        val failed = reduce(split(), MediaEvent.PlaybackError("unsupported codec"))
        assertEquals("unsupported codec", failed.lastError)
        assertEquals(MediaMode.SPLIT, failed.mode)

        val recovered = reduce(failed, MediaEvent.PlaybackRecovered)
        assertEquals("", recovered.lastError)
        assertEquals(MediaMode.SPLIT, recovered.mode)
    }

    @Test
    fun `a source change clamps the index and records the count`() {
        val state = reduce(split(), MediaEvent.SourceChanged("local", 3))
        assertEquals("local", state.source)
        assertEquals(3, state.count)

        val clamped = reduce(state.copy(index = 9), MediaEvent.SourceChanged("local", 3))
        assertEquals(2, clamped.index)

        val empty = reduce(state, MediaEvent.SourceChanged("", 0))
        assertEquals(0, empty.count)
        assertEquals(0, empty.index)
    }
}
