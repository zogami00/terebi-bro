package com.terebibro.tv.media

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Plays one [MediaItem] at a time on the media band: `MediaPlayer` on the
 * [SurfaceView] for video, a sampled [Bitmap] on the [ImageView] for stills.
 *
 * This class owns the player, the surface/image swap and the audio focus; the
 * controller owns the queue, the timing of stills and every mode decision.
 * Every failure path reports through [Listener.onPlaybackError] and leaves the
 * host usable for the next item.
 */
class MediaPlayerHost(
    private val activity: Activity,
    private val surfaceView: SurfaceView,
    private val backdropView: ImageView,
    private val imageView: ImageView,
    private val placeholder: TextView,
    private val listener: Listener
) {

    interface Listener {
        fun onItemFinished()
        fun onPlaybackError(message: String)

        /** Playback paused because another app took audio focus; not an error. */
        fun onPlaybackPausedByFocus()

        /** Audio focus returned; the published pause state may be cleared. */
        fun onFocusGained()
    }

    private val main = Handler(Looper.getMainLooper())
    private val store = MediaStore(activity)
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val decode = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terebi-media-decode").apply { isDaemon = true }
    }

    private var player: MediaPlayer? = null
    private var bitmap: Bitmap? = null

    /** Bumped whenever the item changes, so a late decode cannot win. */
    private var generation = 0
    private var volumePercent = 100
    private var focusHeld = false
    private var released = false

    /**
     * Set only when the host itself paused because audio focus was lost, so a
     * later gain may resume. A user pause, an item change or release clears it.
     */
    private var resumeOnFocusGain = false

    private var fillMode = MediaFillMode.LETTERBOX_BLUR

    /**
     * True while the controller is holding playback paused. The player may not
     * be prepared yet at that point, so the prepared callback has to obey it
     * instead of starting playback behind the pause.
     */
    private var callerPaused = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusLost()
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private val videoAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    fun play(item: MediaItem, volumePercent: Int, fillMode: MediaFillMode) {
        this.volumePercent = volumePercent.coerceIn(0, 100)
        this.fillMode = fillMode
        callerPaused = false
        resumeOnFocusGain = false
        generation++
        releasePlayer()
        clearBitmap()
        imageView.visibility = View.GONE
        backdropView.visibility = View.GONE
        placeholder.visibility = View.GONE
        when (item.kind) {
            MediaItem.Kind.VIDEO -> {
                // Stills carry no audio, so only a video asks for focus.
                requestFocus()
                playVideo(item)
            }
            // Video deliberately ignores the fill mode: a video's own
            // letterbox is part of the frame and cannot be cropped behind it.
            MediaItem.Kind.IMAGE -> playImage(item)
        }
    }

    fun pause() {
        callerPaused = true
        // A pause the operator asked for must not be undone by a focus gain.
        resumeOnFocusGain = false
        abandonFocus()
        val current = player ?: return
        try {
            if (current.isPlaying) current.pause()
        } catch (e: Exception) {
            // A player released or still preparing: nothing to pause.
        }
    }

    fun resume() {
        callerPaused = false
        resumeOnFocusGain = false
        val current = player ?: return
        requestFocus()
        try {
            if (!current.isPlaying) {
                current.start()
            }
        } catch (e: Exception) {
            // Not prepared yet; the onPrepared callback starts it instead.
        }
    }

    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 100)
        try {
            player?.setVolume(volume, volume)
        } catch (e: Exception) {
            // Released mid-call; the next play applies the new volume.
        }
    }

    /** Shows the "nothing playable" state without holding a player or bitmap. */
    fun showPlaceholder() {
        generation++
        resumeOnFocusGain = false
        releasePlayer()
        clearBitmap()
        imageView.setImageDrawable(null)
        backdropView.visibility = View.GONE
        imageView.visibility = View.GONE
        surfaceView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
    }

    fun release() {
        released = true
        resumeOnFocusGain = false
        generation++
        releasePlayer()
        clearBitmap()
        decode.shutdownNow()
    }

    // ---------------------------------------------------------------------
    // Video
    // ---------------------------------------------------------------------

    private fun playVideo(item: MediaItem) {
        surfaceView.visibility = View.VISIBLE
        val uri = uriFor(item)
        if (uri == null) {
            fail("File not found")
            return
        }
        val current = MediaPlayer()
        player = current
        try {
            current.setAudioAttributes(videoAttributes)
            current.setDisplay(surfaceView.holder)
            current.setLooping(true)
            current.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                prepared.setVolume(volume, volume)
                if (callerPaused) return@setOnPreparedListener
                requestFocus()
                try {
                    prepared.start()
                } catch (e: Exception) {
                    fail("Unable to start playback")
                }
            }
            current.setOnCompletionListener { finished ->
                if (player === finished) listener.onItemFinished()
            }
            current.setOnErrorListener { _, what, extra ->
                // Releasing a player from inside its own callback is unreliable
                // on some TV boxes, so the failure handling is posted.
                main.post {
                    if (player === current) fail("Playback failed ($what/$extra)")
                }
                true
            }
            current.setDataSource(activity, uri)
            current.prepareAsync()
        } catch (e: Exception) {
            fail("Unable to play video")
        }
    }

    private fun uriFor(item: MediaItem): Uri? {
        if (item.id.startsWith("http://") || item.id.startsWith("https://")) {
            return Uri.parse(item.id)
        }
        val file = File(store.dir(), item.id)
        return if (file.isFile) Uri.fromFile(file) else null
    }

    // ---------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------

    private fun playImage(item: MediaItem) {
        surfaceView.visibility = View.GONE
        backdropView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        applyFill(MediaLayout.fillFor(fillMode))
        val file = File(store.dir(), item.id)
        val token = generation
        val (targetWidth, targetHeight) = targetSize()
        try {
            decode.execute {
                val decoded = try {
                    decodeSampled(file, targetWidth, targetHeight)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (released || token != generation) {
                        decoded?.recycle()
                        return@post
                    }
                    if (decoded == null) {
                        fail("Unable to read image")
                    } else {
                        bitmap = decoded
                        imageView.setImageBitmap(decoded)
                        if (backdropView.visibility == View.VISIBLE) {
                            backdropView.setImageBitmap(decoded)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // The executor is shut down by release(); nothing may start after it.
        }
    }

    /**
     * Stills honour [MediaFillMode]; the backdrop is a second copy of the same
     * bitmap, cropped and dimmed so a portrait image does not sit on a hard
     * black box.
     */
    private fun applyFill(fill: MediaLayout.Fill) {
        when (fill) {
            MediaLayout.Fill.FIT -> {
                backdropView.visibility = View.GONE
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            MediaLayout.Fill.CROP -> {
                backdropView.visibility = View.GONE
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            MediaLayout.Fill.FIT_OVER_BACKDROP -> {
                backdropView.scaleType = ImageView.ScaleType.CENTER_CROP
                backdropView.alpha = BACKDROP_ALPHA
                backdropView.visibility = View.VISIBLE
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
    }

    /**
     * The largest bitmap worth keeping in memory. Before the first layout the
     * view has no size, so the screen is the bound instead.
     */
    private fun targetSize(): Pair<Int, Int> {
        val width = imageView.width
        val height = imageView.height
        if (width > 0 && height > 0) return width to height
        val metrics = activity.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    // ---------------------------------------------------------------------
    // Shared state
    // ---------------------------------------------------------------------

    private val volume: Float get() = volumePercent / 100f

    private fun fail(message: String) {
        generation++
        resumeOnFocusGain = false
        releasePlayer()
        clearBitmap()
        imageView.visibility = View.GONE
        backdropView.visibility = View.GONE
        surfaceView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
        listener.onPlaybackError(message)
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        abandonFocus()
        try {
            current.setOnPreparedListener(null)
            current.setOnCompletionListener(null)
            current.setOnErrorListener(null)
        } catch (e: Exception) {
            // ignore
        }
        try {
            current.reset()
        } catch (e: Exception) {
            // ignore
        }
        try {
            current.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun clearBitmap() {
        imageView.setImageDrawable(null)
        backdropView.setImageDrawable(null)
        backdropView.visibility = View.GONE
        bitmap?.recycle()
        bitmap = null
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        if (focusHeld) return
        focusHeld = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * Another app took audio focus. Pausing here is not a playback failure: the
     * host remembers that it paused for this reason and tells the controller
     * through the benign callback, so no fake `mediaError` is published.
     */
    private fun onFocusLost() {
        if (released) return
        val current = player ?: return
        val wasPlaying = try {
            current.isPlaying
        } catch (e: Exception) {
            false
        }
        if (!wasPlaying) return
        try {
            current.pause()
        } catch (e: Exception) {
            return
        }
        // The system already revoked focus; drop the stale grant flag so a
        // later request actually asks again.
        focusHeld = false
        resumeOnFocusGain = true
        listener.onPlaybackPausedByFocus()
    }

    private fun onFocusGained() {
        if (released) return
        focusHeld = true
        listener.onFocusGained()
        // Only resume something this host paused because of focus, and never
        // over a pause the controller is still holding.
        if (!resumeOnFocusGain || callerPaused) return
        resumeOnFocusGain = false
        try {
            val current = player ?: return
            if (!current.isPlaying) current.start()
        } catch (e: Exception) {
            // The player may have been released between the callbacks.
        }
    }

    private companion object {
        const val BACKDROP_ALPHA = 0.35f
    }
}
