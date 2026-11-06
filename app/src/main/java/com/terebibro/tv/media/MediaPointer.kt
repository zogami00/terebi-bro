package com.terebibro.tv.media

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.terebibro.tv.R

/**
 * Pure pointer movement, kept apart from the view so it can be unit tested.
 *
 * Speeds are fractions of the band width, so a 720p and a 4K panel feel the
 * same. Key auto-repeat accelerates the pointer up to [MAX_FRACTION].
 */
object PointerMotion {
    // ponytail: tuning knobs, adjust on the real remote if it feels slow/fast.
    const val BASE_FRACTION = 1f / 160f
    const val MAX_FRACTION = 1f / 32f

    /** Result of one move: the new position and an edge scroll (-1, 0, 1) per axis. */
    data class Step(val x: Float, val y: Float, val scrollX: Int, val scrollY: Int)

    /** Pixels per key event for the given auto-repeat count. */
    fun speed(repeatCount: Int, width: Int): Float =
        minOf(width * MAX_FRACTION, width * BASE_FRACTION * (1 + repeatCount / 2f))

    /**
     * Moves by ([dx], [dy]) * [speed]. A push past an edge pins the pointer to
     * it and asks for a scroll in that direction instead.
     */
    fun move(x: Float, y: Float, dx: Int, dy: Int, speed: Float, width: Int, height: Int): Step {
        val nx = x + dx * speed
        val ny = y + dy * speed
        val maxX = (width - 1).coerceAtLeast(0).toFloat()
        val maxY = (height - 1).coerceAtLeast(0).toFloat()
        return Step(
            x = nx.coerceIn(0f, maxX),
            y = ny.coerceIn(0f, maxY),
            scrollX = if (nx < 0f) -1 else if (nx > maxX) 1 else 0,
            scrollY = if (ny < 0f) -1 else if (ny > maxY) 1 else 0
        )
    }
}

/**
 * On-screen pointer for mouse-only web pages (YouTube desktop, RTMKlik) in the
 * media band. The D-pad moves it, OK taps under it, pushing an edge scrolls.
 * Movement also sends mouse hover so players reveal their controls.
 *
 * [target] is the view that receives the synthetic input: the media WebView,
 * or the page's fullscreen view while one covers the band. It fills
 * [container], so container coordinates are its coordinates.
 */
class MediaPointer(
    private val container: FrameLayout,
    private val target: () -> View?
) {
    private val cursor = ImageView(container.context).apply {
        setImageResource(R.drawable.ic_pointer)
        visibility = View.GONE
    }
    private var x = -1f
    private var y = -1f

    var isOn = false
        private set

    init {
        container.addView(
            cursor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun toggle(allowed: Boolean) {
        isOn = !isOn
        sync(allowed)
    }

    /** Shows the cursor only while it is on and the media page owns the keys. */
    fun sync(allowed: Boolean) {
        val show = isOn && allowed
        cursor.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        if (x < 0f) {
            x = container.width / 2f
            y = container.height / 2f
        }
        // A fullscreen view added after the cursor would cover it.
        cursor.bringToFront()
        place()
    }

    /** Handles one DPAD press (and its auto-repeats). */
    fun move(keyCode: Int, repeatCount: Int) {
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> return
        }
        val w = container.width
        val h = container.height
        val step = PointerMotion.move(x, y, dx, dy, PointerMotion.speed(repeatCount, w), w, h)
        x = step.x
        y = step.y
        place()
        if (step.scrollX != 0 || step.scrollY != 0) {
            // Wheel axes: positive VSCROLL scrolls up, positive HSCROLL right.
            mouse(MotionEvent.ACTION_SCROLL, -step.scrollY.toFloat(), step.scrollX.toFloat())
        } else {
            mouse(MotionEvent.ACTION_HOVER_MOVE, 0f, 0f)
        }
    }

    /** A tap under the pointer. Touch, because every page handles it. */
    fun click() {
        val view = target() ?: return
        val px = x - view.left
        val py = y - view.top
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun place() {
        cursor.translationX = x
        cursor.translationY = y
    }

    private fun mouse(action: Int, vScroll: Float, hScroll: Float) {
        val view = target() ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = this@MediaPointer.x - view.left
            y = this@MediaPointer.y - view.top
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(
            now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        view.dispatchGenericMotionEvent(e)
        e.recycle()
    }
}
