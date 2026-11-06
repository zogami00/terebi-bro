package com.terebibro.tv.media

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private val listener: Listener,
    /** The activity's store, so playback reads the same folder the queue was listed from. */
    private val store: MediaStore
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
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val decode = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terebi-media-decode").apply { isDaemon = true }
    }

    private var player: MediaPlayer? = null
    private var bitmap: Bitmap? = null

    /** The one holder this host ever attaches the video player to. */
    private val surfaceHolder: SurfaceHolder = surfaceView.holder

    /**
     * Whether [surfaceHolder] currently has a live surface. A `SurfaceView`
     * that is `GONE` has its surface destroyed, so a later `setDisplay` on that
     * stale holder throws `IllegalArgumentException`. The flag is maintained by
     * [surfaceCallback], which the framework invokes on the main thread.
     */
    private var surfaceReady = false

    /**
     * True while a player exists but has no display attached yet, i.e. it is
     * waiting for `surfaceCreated` to hand it one. Starting such a player would
     * play its audio with no picture, so [startIfPrepared] waits for the detach
     * to clear instead.
     */
    private var displayPending = false

    /** True once the player has prepared and may start as soon as a display is attached. */
    private var prepared = false

    /**
     * The frame size the current video reported, or 0 before
     * `onVideoSizeChanged` fires. The surface is letterboxed to exactly this
     * aspect inside the band; the band itself is black, so the unused area is
     * black bars rather than a stretched picture.
     */
    private var videoWidth = 0
    private var videoHeight = 0

    /**
     * `setDisplay` is the only call that needs a live surface, and the surface
     * can be destroyed and recreated at any time by `visibility` changes (the
     * placeholder hides the view). Attaching on creation keeps the player
     * usable across that cycle instead of failing the item forever.
     */
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            attachDisplayWhenReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = holder.surface.isValid
            // The surface is created after the view has been laid out, which is
            // the first moment the band has a real size to fit the video into.
            applyVideoFit()
            // A size change on an already attached surface needs no re-attach;
            // a surface that came back after a destroy does.
            if (!displayPending) return
            attachDisplayWhenReady()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            val current = player
            if (current != null) {
                try {
                    current.setDisplay(null)
                } catch (e: Exception) {
                    // The player is being released; nothing left to detach.
                }
                // The player stays prepared across the detach; the item is
                // resumed by [surfaceCreated] once a surface exists again.
                displayPending = true
            }
        }
    }

    init {
        surfaceReady = surfaceHolder.surface.isValid
        surfaceHolder.addCallback(surfaceCallback)
    }

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

    /** Whether the next video repeats itself; set by [play] from the queue size. */
    private var loop = false

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

    /**
     * Starts [item]. [loop] is only true for a single-item queue: a looping
     * video never reports completion, so looping a multi-item queue would pin
     * playback to its first clip instead of advancing.
     */
    fun play(item: MediaItem, volumePercent: Int, fillMode: MediaFillMode, loop: Boolean) {
        this.volumePercent = volumePercent.coerceIn(0, 100)
        this.fillMode = fillMode
        this.loop = loop
        callerPaused = false
        resumeOnFocusGain = false
        generation++
        releasePlayer()
        clearBitmap()
        // A previous video — or a still after one — must not inherit its
        // letterbox size; the surface starts at the full band again.
        videoWidth = 0
        videoHeight = 0
        resetSurfaceFit()
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
            // The media WebView covers the band; the native host renders
            // nothing for it.
            MediaItem.Kind.WEB -> showWeb()
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

    /**
     * The placeholder is the only sign that nothing is playable, so its text
     * follows the UI language like the strip labels do. The layout ships an
     * English default for the moment before the first settings sync.
     */
    fun setPlaceholderLabel(text: String) {
        placeholder.text = text
    }

    /** Shows the "nothing playable" state without holding a player or bitmap. */
    fun showPlaceholder() {        generation++
        resumeOnFocusGain = false
        releasePlayer()
        clearBitmap()
        imageView.setImageDrawable(null)
        backdropView.visibility = View.GONE
        imageView.visibility = View.GONE
        surfaceView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
    }

    /**
     * Clears the native band so the media WebView (a sibling in front of it) is
     * all that shows. The player and its audio focus are released; the still
     * bitmap is recycled. No placeholder: the page is the picture.
     */
    fun showWeb() {
        generation++
        resumeOnFocusGain = false
        releasePlayer()
        clearBitmap()
        imageView.setImageDrawable(null)
        backdropView.visibility = View.GONE
        imageView.visibility = View.GONE
        surfaceView.visibility = View.GONE
        placeholder.visibility = View.GONE
    }

    fun release() {
        released = true
        resumeOnFocusGain = false
        generation++
        releasePlayer()
        clearBitmap()
        surfaceHolder.removeCallback(surfaceCallback)
        decode.shutdownNow()
    }

    // ---------------------------------------------------------------------
    // Video
    // ---------------------------------------------------------------------

    private fun playVideo(item: MediaItem) {
        surfaceView.visibility = View.VISIBLE
        val source = videoSource(item)
        if (source == null) {
            fail("File not found")
            return
        }
        val current = MediaPlayer()
        player = current
        prepared = false
        displayPending = true
        // The phase is named in the failure message: `setDisplay` and
        // `setDataSource` fail for entirely different reasons and both throw
        // from the same try block.
        var phase = "configure"
        try {
            current.setAudioAttributes(videoAttributes)
            phase = "setDisplay"
            // Not a failure when false: the surface is created asynchronously
            // after the view turns visible, and `surfaceCreated` attaches it.
            // A throw, on the other hand, already failed and released the
            // player, so configuring it further would only mask the real error.
            attachDisplay(current)
            if (player !== current) return
            phase = "setLooping"
            current.setLooping(loop)
            current.setOnVideoSizeChangedListener { changed, width, height ->
                if (player !== changed) return@setOnVideoSizeChangedListener
                videoWidth = width
                videoHeight = height
                // The band may not have been laid out when the player reports;
                // posting defers the fit to the next UI loop, after layout.
                main.post { if (player === changed) applyVideoFit() }
            }
            current.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                ready.setVolume(volume, volume)
                if (callerPaused) return@setOnPreparedListener
                requestFocus()
                startIfPrepared()
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
            // A remote id is an http(s) URL; a local one is an absolute file
            // path. Both go in as strings: `setDataSource(Context, Uri)` reads
            // through a ContentResolver, which cannot open a `file://` Uri.
            phase = "setDataSource"
            current.setDataSource(source)
            phase = "prepareAsync"
            current.prepareAsync()
        } catch (e: Exception) {
            fail("Unable to play video ($phase/${e.javaClass.simpleName})")
        }
    }

    /**
     * Attaches the display only to a surface that is alive right now.
     *
     * `setDisplay` on a `SurfaceHolder` whose surface was destroyed (which is
     * exactly what happens after `showPlaceholder()` hides the view, and again
     * before the first layout) throws `IllegalArgumentException`. The attach is
     * therefore gated on a live surface and otherwise left pending for
     * [surfaceCallback] to complete when the surface is created.
     */
    private fun attachDisplay(current: MediaPlayer): Boolean {
        if (!surfaceReady || !surfaceHolder.surface.isValid) {
            displayPending = true
            return false
        }
        return try {
            current.setDisplay(surfaceHolder)
            displayPending = false
            true
        } catch (e: Exception) {
            fail("Unable to play video (setDisplay/${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Fits the video's own frame inside the media band and centres it.
     *
     * The surface is a `MATCH_PARENT` child of the band, so a `SurfaceView`
     * would stretch the picture to the band's aspect — a 16:9 clip on a 2.7:1
     * band came out horizontally stretched. The band keeps its black
     * background, so the space the video does not use is a black bar.
     */
    private fun applyVideoFit() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        // Before the band is laid out there is no box to fit into (and no
        // parent at all before the view is attached); the surface stays full.
        val parent = surfaceView.parent as? View ?: return
        val boxWidth = parent.width
        val boxHeight = parent.height
        if (boxWidth <= 0 || boxHeight <= 0) return
        val fit = MediaLayout.fit(videoWidth, videoHeight, boxWidth, boxHeight)
        if (fit.w <= 0 || fit.h <= 0) return
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(fit.w, fit.h)
        if (params.width == fit.w &&
            params.height == fit.h &&
            params.gravity == Gravity.CENTER
        ) {
            return
        }
        params.width = fit.w
        params.height = fit.h
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    /** Back to the full band, so a new item never inherits the last aspect. */
    private fun resetSurfaceFit() {
        val params = surfaceView.layoutParams
        if (params is FrameLayout.LayoutParams &&
            params.width == FrameLayout.LayoutParams.MATCH_PARENT &&
            params.height == FrameLayout.LayoutParams.MATCH_PARENT
        ) {
            return
        }
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    /** A surface arrived (or came back): give it the player and resume what waited. */
    private fun attachDisplayWhenReady() {
        val current = player ?: return
        if (!displayPending) return
        if (!attachDisplay(current)) return
        // attachDisplay may have failed and released the player already.
        if (player !== current) return
        startIfPrepared()
    }

    /**
     * Starts a prepared player only once it has somewhere to draw. A video that
     * starts while the surface is still pending plays its audio invisibly and
     * never recovers, so the start is deferred like the display itself.
     */
    private fun startIfPrepared() {
        val current = player ?: return
        if (!prepared || displayPending || callerPaused) return
        try {
            if (!current.isPlaying) current.start()
        } catch (e: Exception) {
            fail("Unable to start playback (start/${e.javaClass.simpleName})")
        }
    }

    /**
     * A remote id is already a URL; a local id is resolved to an absolute path
     * inside the media folder. Null means the item cannot be opened.
     */
    private fun videoSource(item: MediaItem): String? {
        if (item.remote) return item.id
        val base = store.playDir() ?: return null
        return MediaPaths.localPlaybackPath(base, item)
    }

    // ---------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------

    private fun playImage(item: MediaItem) {
        surfaceView.visibility = View.GONE
        backdropView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        applyFill(MediaLayout.fillFor(fillMode))
        if (item.remote) {
            decodeAndShowRemote(item.id)
            return
        }
        val base = store.playDir()
        if (base == null) {
            fail("Media storage is unavailable")
            return
        }
        val file = File(base, item.id)
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
                showDecoded(decoded, token)
            }
        } catch (e: Exception) {
            // The executor is shut down by release(); nothing may start after it.
        }
    }

    /**
     * A remote still is downloaded and decoded on the same single-threaded
     * executor as a local one, so the UI thread never blocks on the network
     * and a slow host cannot leave a half-drawn image behind. The download is
     * capped: an oversized body is a failed item, not a consumed heap.
     */
    private fun decodeAndShowRemote(url: String) {
        val token = generation
        val (targetWidth, targetHeight) = targetSize()
        try {
            decode.execute {
                val decoded = try {
                    decodeRemote(url, targetWidth, targetHeight)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                showDecoded(decoded, token)
            }
        } catch (e: Exception) {
            // The executor is shut down by release(); nothing may start after it.
        }
    }

    private fun showDecoded(decoded: Bitmap?, token: Int) {
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

    /**
     * The bytes are read into memory under a cap and decoded twice (bounds,
     * then pixels) so a large remote still is sampled like a local one.
     */
    private fun decodeRemote(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        try {
            connection.connectTimeout = REMOTE_TIMEOUT_MS
            connection.readTimeout = REMOTE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            val bytes = connection.inputStream.use { readCapped(it, REMOTE_MAX_BYTES) } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            try {
                connection.disconnect()
            } catch (e: Exception) {
                // Nothing to release.
            }
        }
    }

    private fun readCapped(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
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
        prepared = false
        abandonFocus()
        try {
            current.setOnPreparedListener(null)
            current.setOnCompletionListener(null)
            current.setOnErrorListener(null)
            current.setOnVideoSizeChangedListener(null)
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

        /** Connect and read timeout for a remote still, in milliseconds. */
        const val REMOTE_TIMEOUT_MS = 10_000

        /** A remote still larger than this is treated as a failed item. */
        const val REMOTE_MAX_BYTES = 12 * 1024 * 1024
    }
}
