package com.terebibro.tv.media

import android.os.Handler
import android.os.Looper
import com.terebibro.tv.web.UrlValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

/** The published playback view of the media layer. */
data class MediaRuntime(
    val mode: MediaMode,
    val source: String,
    val item: String,
    val index: Int,
    val count: Int,
    val paused: Boolean,
    val error: String,
    val localFiles: Int,
    val localBytes: Long,
    val freeBytes: Long,
    val qmsNow: String = "",
    val qmsLocation: String = "",
    val qmsDetectionHealthy: Boolean = false,
    val qmsLastCallAtMs: Long = 0L,
    val documentStartSupported: Boolean = false,
    /** Why the last playlist fetch (or parse) failed; empty when it did not. */
    val playlistLastError: String = "",
    /** Wall time the last playlist fetch attempt took, in ms; 0 when never fetched. */
    val playlistLastFetchMs: Long = 0L
)

/**
 * Owns the queue, the still-image timer, the manual-hold timer and the
 * [MediaState] the reducer produces. Every mode decision is delegated to
 * [MediaStateMachine]; this class only turns the resulting state into player
 * effects and layout notifications.
 *
 * All methods run on the main thread. The reducer is fed the wall clock, and
 * the timers deliver the two deadline events the reducer does not own: a
 * manual `MEDIA_ONLY` hold expiring and a still's display time elapsing.
 *
 * The library walk is plain file I/O, so it runs on the shared [io] executor and
 * only its result is applied on the main thread. A generation guard discards a
 * result that a newer reload has already superseded.
 */
class MediaController(
    private val store: MediaStore,
    private val settings: () -> MediaSettings,
    private val io: Executor,
    private val onChanged: () -> Unit,
    /** Runs a controller-built snippet against the page's current document. */
    private val evaluate: (String) -> Unit = {},
    /** The same factor the native bands use; kept as a provider so it tracks the surface. */
    private val renderScale: () -> Float = { 1f },
    private val documentStartSupported: () -> Boolean = { false },
    /** `scheme://host[:port]` of the configured QMS page, or empty. */
    private val qmsOrigin: () -> String = { "" },
    /**
     * Where the last-good playlist is kept (`filesDir/playlist-cache.json`).
     * A 256 KiB cache file is not media, so it may live on internal storage;
     * null disables caching, which only costs the offline cold boot.
     */
    private val playlistCacheFile: File? = null
) {

    private val main = Handler(Looper.getMainLooper())

    private var host: MediaPlayerHost? = null
    private var state = MediaState()
    private var cfg = MediaSettings()
    private var queue: List<MediaItem> = emptyList()
    private var source = ""
    private var index = 0

    /**
     * The menu's Play/Pause. It is a third pause source beside the overlay and
     * the lifecycle, kept out of [MediaState] so the reducer stays mode-only.
     */
    private var manualPaused = false

    /**
     * True while the system made us pause for audio focus. The host owns the
     * actual resume; this flag only keeps the published state honest and clears
     * itself as soon as focus returns or a new item starts.
     */
    private var focusPaused = false

    /**
     * When the still on screen is due to advance, or `0` when no image timer is
     * armed. Kept beside the `Handler` because the handler cannot be asked.
     */
    private var imageDeadlineMs = 0L

    /**
     * What is left of the current still's dwell when its timer was stopped by a
     * pause. Timers do not survive a pause, so the remainder is remembered to be
     * re-armed on the resume.
     */
    private var imageRemainingMs = 0L

    /** Bumped for every library read, so a late result cannot overwrite a newer one. */
    private var storeGeneration = 0L
    private var statsGeneration = 0L

    private var localFiles = 0
    private var localBytes = 0L
    private var freeBytes = 0L

    /** The last local read's items; the unconditional fallback queue. */
    private var localItems: List<MediaItem> = emptyList()

    /** The last playlist that parsed, kept across a failed refresh. */
    private var playlistItems: List<MediaItem> = emptyList()

    /**
     * Bumped for every playlist fetch, so a late response from a superseded
     * settings change (or from before `release()`) is dropped on the floor.
     */
    private var playlistGeneration = 0L
    private var playlistLastError = ""
    private var playlistLastFetchMs = 0L

    /**
     * Remote items that failed in a row without a user action in between. A
     * broken URL must advance the queue, but a whole playlist of broken URLs
     * must not spin through it forever: after one full pass the queue stops
     * and the error is published.
     */
    private var remoteFailures = 0

    // Detection memory. `hasMatchedSinceLaunch` and `detectionHealthy` are
    // reducer fields (qmsMatched / qmsDetectionHealthy); these are the values
    // the page reported and the key comparison needs.
    private val signalGuard = QmsSignalGuard()
    private var lastKey: QmsCallKey? = null
    private var qmsNow = ""
    private var qmsLocation = ""
    private var qmsLastCallAtMs = 0L

    private val paused: Boolean
        get() = state.enabled && MediaPausePolicy.shouldPause(
            mode = state.mode,
            overlayVisible = state.overlayVisible,
            lifecyclePaused = state.lifecyclePaused,
            manualPaused = manualPaused,
            focusPaused = focusPaused
        )

    private val currentItem: MediaItem? get() = queue.getOrNull(index)

    private val manualExpiry = Runnable {
        val previous = state.mode
        val wasPaused = paused
        dispatch(MediaEvent.ManualExpired)
        applyPause(wasPaused)
        if (state.mode != previous) onChanged()
    }

    private val imageAdvance = Runnable {
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        if (state.enabled && !paused && currentItem?.kind == MediaItem.Kind.IMAGE) next()
    }

    private val dwellExpiry = Runnable {
        val previous = state.mode
        val wasPaused = paused
        dispatch(MediaEvent.DwellElapsed)
        applyPause(wasPaused)
        if (state.mode != previous) onChanged()
    }

    /** The periodic playlist refresh; re-armed after every completed fetch. */
    private val playlistRefresh = Runnable {
        if (state.enabled) refreshPlaylist(loadCacheFirst = false)
    }

    fun attach(host: MediaPlayerHost) {
        this.host = host
        syncSettings()
    }

    fun settingsChanged() = syncSettings()

    // ---------------------------------------------------------------------
    // QMS detection
    // ---------------------------------------------------------------------

    /**
     * The bridge's only entry point (already hopped to the main thread by
     * `QmsBridge`). Unknown kinds and clamped repeats are dropped here; `now`,
     * `probe`, `ready` and the optional `audio` chime are consumed, and
     * `visibility` is accepted but unused.
     */
    fun onQmsSignal(kindWire: String, value: String?) {
        val kind = QmsSignalKind.fromWire(kindWire) ?: return
        val clean = signalGuard.accept(kind, value) ?: return
        when (kind) {
            QmsSignalKind.NOW -> onQmsNow(clean)
            // Three probe states: `ok` (a card resolved), `empty` (the list
            // container resolved but has no cards yet) and `missing` (the
            // selectors did not resolve at all). An empty list is healthy: the
            // board is simply awaiting the first call of the day.
            QmsSignalKind.PROBE -> onQmsProbe(clean == "ok" || clean == "empty")
            QmsSignalKind.READY -> Unit
            // The chime is the optional second signal. It never carries a
            // number; see [onQmsAudio]. Page Visibility remains unused.
            QmsSignalKind.AUDIO -> onQmsAudio()
            QmsSignalKind.VISIBILITY -> Unit
        }
    }

    /**
     * An accepted chime signal. With `callSource = dom` it is ignored; with
     * `dom_or_audio` or `audio` it re-arms the dwell and takes the screen
     * exactly like a DOM call, except that it never invents a call number.
     * The strip keeps whatever the DOM last reported — a chime-only trigger
     * moves the screen, not the text.
     */
    private fun onQmsAudio() {
        if (!MediaStateMachine.audioTriggersCall(cfg, System.currentTimeMillis(), qmsLastCallAtMs)) return
        dispatch(MediaEvent.Call(true))
        onChanged()
    }

    /** Every page load re-applies the strip while it is on screen. */
    fun onPageLoaded() {
        val previous = state.mode
        dispatch(MediaEvent.QmsPageLoaded)
        if (state.mode == MediaMode.SPLIT) applyStrip()
        if (state.mode != previous) onChanged()
    }

    /** The page will not load: there is nothing to restyle. */
    fun onQmsPageFailed() {
        val previous = state.mode
        dispatch(MediaEvent.QmsPageFailed)
        if (state.mode != previous) onChanged()
    }

    /** A rebuilt WebView lands on the activation page; detection starts over. */
    fun onWebViewRestarted() {
        resetDetection()
        dispatch(MediaEvent.QmsReset)
        onChanged()
    }

    /** Re-applies the stylesheet after a surface or settings change. */
    fun reapplyStrip() {
        if (state.enabled && state.mode == MediaMode.SPLIT) applyStrip()
    }

    private fun onQmsNow(payload: String) {
        val parts = payload.split(QmsSignalGuard.FIELD_SEPARATOR)
        val number = parts.getOrNull(0).orEmpty()
        val location = parts.getOrNull(1).orEmpty()
        val time = parts.getOrNull(2).orEmpty()
        if (number.isBlank()) return

        val displayChanged = number != qmsNow || location != qmsLocation
        // The published values are the page's own strings, verbatim.
        qmsNow = number
        qmsLocation = location

        val key = QmsCallKey.from(number, location, time)
        // The first observation only seeds `lastKey`: a cold start with a stale
        // head card must not take the screen (see QmsCallKey.isNewCall).
        val newCall = QmsCallKey.isNewCall(lastKey, key)
        lastKey = key

        // Reading a complete card is itself proof that the selectors resolve.
        if (!state.qmsDetectionHealthy || !state.qmsMatched) {
            val before = state.mode
            dispatch(MediaEvent.QmsProbe(true))
            if (state.mode == before && state.mode == MediaMode.SPLIT) applyStrip()
        }

        // With `callSource = audio` the DOM is text-only: it updates
        // qmsNow/qmsLocation unconditionally, but only the chime may take the
        // screen. `qmsLastCallAtMs` is left alone too, so the chime that
        // accompanies this DOM change is not mistaken for a duplicate.
        if (newCall && MediaStateMachine.domTriggersCall(cfg.callSource)) {
            qmsLastCallAtMs = System.currentTimeMillis()
            dispatch(MediaEvent.Call(true))
        }
        if (newCall || displayChanged) onChanged()
    }

    private fun onQmsProbe(matched: Boolean) {
        val previousMode = state.mode
        dispatch(MediaEvent.QmsProbe(matched))
        if (state.mode == previousMode && state.mode == MediaMode.SPLIT) applyStrip()
        onChanged()
    }

    private fun resetDetection() {
        lastKey = null
        qmsNow = ""
        qmsLocation = ""
        qmsLastCallAtMs = 0L
    }

    fun setMode(mode: MediaMode): MediaMode {
        // `QMS_FOCUS` is the reducer's transient mode, and a manual mode means
        // nothing while media mode is off.
        if (mode == MediaMode.QMS_FOCUS) return state.mode
        if (!cfg.enabled) return state.mode
        val previous = state.mode
        val wasPaused = paused
        // The media workspace is a fresh start, not a continuation of a pause
        // the operator set before leaving it. Clear the hold before the
        // dispatch so the new state's pause decision already sees it.
        if (mode == MediaMode.MEDIA_ONLY && previous != MediaMode.MEDIA_ONLY) {
            manualPaused = false
        }
        dispatch(MediaEvent.Manual(mode))
        // A mode change can itself pause or resume (QMS_FOCUS pauses, SPLIT
        // resumes); the player has to follow the state, not just the sources.
        applyPause(wasPaused)
        if (state.mode != previous) onChanged()
        return state.mode
    }

    fun togglePlayPause() {
        if (!state.enabled || currentItem == null) return
        val wasPaused = paused
        // While an overlay, the mode or a focus loss holds playback paused the
        // button means "play"; otherwise it flips the operator's own pause.
        val heldByMode = MediaPausePolicy.shouldPause(state.mode, false, false, false)
        if (state.paused || focusPaused || heldByMode) {
            manualPaused = false
        } else {
            manualPaused = !manualPaused
        }
        focusPaused = false
        applyPause(wasPaused)
        onChanged()
    }

    /** Explicit pause from the remote or the controller. */
    fun pause() {
        if (!state.enabled || currentItem == null) return
        val wasPaused = paused
        manualPaused = true
        focusPaused = false
        applyPause(wasPaused)
        onChanged()
    }

    /** Explicit resume from the remote or the controller. */
    fun resume() {
        if (!state.enabled) return
        val wasPaused = paused
        manualPaused = false
        focusPaused = false
        applyPause(wasPaused)
        onChanged()
    }

    fun next() = skip(1)

    fun previous() = skip(-1)

    /**
     * Replaces the queue with a single ad-hoc URL. A URL is not a file in the
     * library, so the synthetic item carries it as its id (the player resolves
     * http(s) ids directly) and is always treated as video.
     */
    fun playUrl(url: String) {
        if (!state.enabled) return
        // A library read may still be in flight; it must not replace this queue.
        storeGeneration++
        queue = listOf(
            MediaItem(
                id = url,
                name = url,
                kind = MediaItem.Kind.VIDEO,
                durationMs = 0L,
                remote = true
            )
        )
        remoteFailures = 0
        source = SOURCE_URL
        index = 0
        manualPaused = false
        focusPaused = false
        refreshStats()
        publishQueue()
        startCurrent()
        onChanged()
    }

    /**
     * The library changed through an upload or a delete. The queue is re-read
     * off the UI thread and re-applied on main: the playing item is kept when
     * it still exists, and playback advances to the item that took its place
     * when it was the one deleted. An unrelated change never restarts the clip.
     *
     * While an ad-hoc URL is playing the library is not the queue, so only the
     * counters are refreshed: an upload must not yank the URL off screen.
     */
    fun onLibraryChanged() {
        if (!state.enabled) return
        if (source == SOURCE_URL && queue.isNotEmpty()) {
            refreshStats()
        } else {
            reloadQueue(reset = false, advanceOnMissing = true)
        }
    }

    /**
     * The app returned to the foreground. The media folder is re-read on the
     * shared IO executor, so an `adb push` while the app is open appears
     * without a settings change. The playing item is kept when it still
     * exists; with media mode off there is no folder to rescan.
     */
    fun onResumeRescan() {
        if (!state.enabled) return
        reloadQueue(reset = false, advanceOnMissing = true)
    }

    fun overlay(visible: Boolean) {
        val wasPaused = paused
        dispatch(MediaEvent.Overlay(visible))
        applyPause(wasPaused)
        onChanged()
    }

    fun lifecycle(paused: Boolean) {
        val wasPaused = this.paused
        dispatch(MediaEvent.Lifecycle(paused))
        applyPause(wasPaused)
        onChanged()
    }

    /**
     * A player failure. A local file that will not play stops the queue and
     * waits for the operator (the file is on the TV, so a retry loop would
     * only flash the same error).
     *
     * A remote item is different: the network may recover, so the queue
     * advances to the next item instead of parking on a black surface. The
     * error is still published, and the advance is bounded — after one full
     * pass over the queue the failures stop and the published error stays.
     */
    fun playbackError(message: String) {
        dispatch(MediaEvent.PlaybackError(message))
        val item = currentItem
        if (item != null && item.remote && queue.size > 1) {
            remoteFailures++
            if (remoteFailures < queue.size) {
                advanceAfterRemoteError()
                onChanged()
                return
            }
            remoteFailures = 0
        }
        manualPaused = true
        // A local failure never advances, so nothing else will take the band.
        // Show the MEDIA_UNAVAILABLE notice instead of leaving it black.
        if (item != null && !item.remote) host?.showPlaceholder()
        onChanged()
    }

    /**
     * The host paused because audio focus was lost. This is not an error: the
     * state is published as paused, never as `lastError`, and the host resumes
     * on its own when focus returns.
     */
    fun onPlaybackPausedByFocus() {
        focusPaused = true
        // The still's timer has to stop here too; scheduleImageTimer keeps the
        // leftover dwell so the gain resumes it instead of restarting it.
        scheduleImageTimer()
        onChanged()
    }

    /**
     * Audio focus came back: only the focus hold is dropped, any other pause
     * source keeps the published state paused.
     *
     * The host paused itself for the focus loss and remembers why, so on a gain
     * it resumes unless it is told otherwise. If a second pause source was added
     * while focus was still lost, [applyPause] saw no change (the predicate was
     * already true) and the host never recorded `callerPaused` — so on the gain
     * it would resume on its own while the controller still publishes paused.
     * Re-asserting the still-true pause here, before notifying, makes the host
     * record `callerPaused` and short-circuits that auto-resume. Ordering is the
     * whole point: the host calls this listener before its own resume check.
     */
    fun onFocusGained() {
        focusPaused = false
        if (paused) {
            host?.pause()
        } else {
            // The host resumes itself on the gain; the image timer does not, so
            // it is re-armed here for the rest of the still's dwell.
            resumeImageTimer()
        }
        onChanged()
    }

    fun snapshot(): MediaRuntime = MediaRuntime(
        mode = state.mode,
        source = source,
        item = currentItem?.name ?: "",
        index = if (queue.isEmpty()) 0 else index,
        count = queue.size,
        paused = paused,
        error = state.lastError,
        localFiles = localFiles,
        localBytes = localBytes,
        freeBytes = freeBytes,
        qmsNow = qmsNow,
        qmsLocation = qmsLocation,
        qmsDetectionHealthy = state.qmsDetectionHealthy,
        qmsLastCallAtMs = qmsLastCallAtMs,
        documentStartSupported = documentStartSupported(),
        playlistLastError = playlistLastError,
        playlistLastFetchMs = playlistLastFetchMs
    )

    fun currentMode(): MediaMode = state.mode

    fun manualUntilMs(): Long = state.manualUntilMs

    fun release() {
        focusPaused = false
        // A fetch already on the IO thread must not land after this point.
        playlistGeneration++
        main.removeCallbacksAndMessages(null)
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        host?.release()
        host = null
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun syncSettings() {
        val previous = cfg
        cfg = settings()
        val wasEnabled = state.enabled
        dispatch(MediaEvent.Enabled(cfg.enabled))
        if (!cfg.enabled) {
            // An error belonged to a playlist that is no longer configured; it
            // must not survive on the published state.
            playlistLastError = ""
            stopPlayback()
        } else {
            if (!wasEnabled) {
                manualPaused = false
                focusPaused = false
            }
            host?.setVolume(cfg.volumePercent)
            // The "nothing playable" notice is patient-facing, so it follows
            // the language selector instead of the layout's English default.
            host?.setPlaceholderLabel(
                SignageStrings.label(cfg.uiLanguage, SignageStrings.Key.MEDIA_UNAVAILABLE)
            )
            // Any settings change may have replaced the playlist URL, so an
            // in-flight fetch belongs to a configuration that no longer exists.
            playlistGeneration++
            main.removeCallbacks(playlistRefresh)
            val urlChanged = previous.playlistUrl != cfg.playlistUrl
            if (urlChanged) {
                // The last-good items were resolved against the old base URL;
                // keeping them would play another playlist's entries.
                playlistItems = emptyList()
                // The cache holds absolute URLs for the same reason, so a cold
                // start after the change would replay the previous playlist.
                deletePlaylistCache()
                playlistLastError = ""
            }
            // A disabled playlist cannot have a live error; drop the stale one
            // so the controller never reports a configuration that is gone.
            if (!cfg.playlistEnabled) playlistLastError = ""
            // Re-read the folder first: a settings change is the point at which
            // the library (and the free-space figures) are expected to be
            // current, and the folder is always the fallback queue. It is
            // queued before the playlist so a slow or hanging fetch (the `io`
            // executor is single-threaded) cannot delay the fallback.
            reloadQueue(reset = !wasEnabled)
            when {
                // A cold start reads the cache first: a device that boots with
                // no network must still play what it played yesterday.
                !wasEnabled -> refreshPlaylist(loadCacheFirst = true)
                urlChanged ||
                    previous.playlistEnabled != cfg.playlistEnabled ||
                    previous.playlistRefreshSec != cfg.playlistRefreshSec ->
                    refreshPlaylist(loadCacheFirst = false)
                // Every settings change discards the in-flight fetch above, so
                // an active playlist is re-armed here; waiting for the periodic
                // timer would leave it stale for up to `playlistRefreshSec`.
                playlistActive() -> refreshPlaylist(loadCacheFirst = false)
                else -> schedulePlaylistRefresh()
            }
        }
        // Strip dimensions, labels and the language all live in settings, so a
        // settings change is also a stylesheet change.
        if (state.enabled && state.mode == MediaMode.SPLIT) applyStrip()
        onChanged()
    }

    private fun reloadQueue(reset: Boolean, advanceOnMissing: Boolean = false) {
        val previousId = currentItem?.id
        val previousIndex = index
        val generation = ++storeGeneration
        // Three directory walks plus StatFs: never on the UI thread.
        try {
            io.execute {
                val items = store.list()
                val total = store.totalBytes()
                val free = store.freeBytes()
                main.post {
                    if (generation != storeGeneration) return@post
                    localItems = items
                    localFiles = items.size
                    localBytes = total
                    freeBytes = free
                    applyActiveQueue(previousId, previousIndex, reset, advanceOnMissing)
                }
            }
        } catch (e: Exception) {
            // The executor is shut down during onDestroy; nothing may start.
        }
    }

    /**
     * The playlist wins when it is enabled and has at least one parsed item;
     * otherwise the local folder is the queue. The two are never merged.
     */
    private fun playlistActive(): Boolean =
        cfg.playlistEnabled &&
            cfg.playlistUrl.isNotEmpty() &&
            UrlValidator.validate(cfg.playlistUrl) is UrlValidator.Result.Valid

    /**
     * Applies whichever source currently owns the queue.
     *
     * Playback is not interrupted when the playing item exists in the new
     * queue: the item keeps its place wherever it moved to. Otherwise the
     * previous index is clamped into the new list — a source switch advances
     * from where it was, it never restarts from zero.
     */
    private fun applyActiveQueue(
        previousId: String?,
        previousIndex: Int,
        reset: Boolean,
        advanceOnMissing: Boolean
    ) {
        if (!state.enabled) return
        // An ad-hoc URL owns the queue until another source is chosen for it.
        if (source == SOURCE_URL && queue.isNotEmpty() && !reset) {
            onChanged()
            return
        }
        val playlist = playlistItems.isNotEmpty() && playlistActive()
        val items = when {
            playlist -> playlistItems
            cfg.mediaLocalEnabled -> localItems
            else -> emptyList()
        }
        queue = items
        if (items.isEmpty()) {
            source = ""
            index = 0
            manualPaused = false
            focusPaused = false
            host?.showPlaceholder()
            publishQueue()
            onChanged()
            return
        }
        source = if (playlist) SOURCE_PLAYLIST else SOURCE_LOCAL
        index = MediaQueue.indexAfterReload(items, previousId, previousIndex, reset, advanceOnMissing)
        publishQueue()
        // Settings edits must not restart the clip that is already on screen;
        // only a changed or missing item starts playback again.
        if (reset || queue.getOrNull(index)?.id != previousId) startCurrent()
        onChanged()
    }

    // ---------------------------------------------------------------------
    // Playlist
    // ---------------------------------------------------------------------

    /**
     * Fetches the playlist off the UI thread and applies it on main.
     *
     * [loadCacheFirst] is only set on the first load after startup: the cached
     * last-good copy is applied before the network is even tried, so a cold
     * boot with no network still has a queue.
     */
    private fun refreshPlaylist(loadCacheFirst: Boolean) {
        if (!playlistActive()) return
        val url = cfg.playlistUrl
        val generation = ++playlistGeneration
        val cacheFirst = loadCacheFirst && playlistItems.isEmpty()
        try {
            io.execute {
                if (cacheFirst) {
                    val cached = readPlaylistCache()
                    if (cached != null) {
                        main.post {
                            if (generation != playlistGeneration || !state.enabled) return@post
                            if (playlistItems.isEmpty()) applyPlaylistBody(cached, url, fromCache = true)
                        }
                    }
                }
                val startedAt = System.currentTimeMillis()
                val outcome = fetchPlaylist(url)
                val elapsed = System.currentTimeMillis() - startedAt
                main.post {
                    if (generation != playlistGeneration || !state.enabled) return@post
                    playlistLastFetchMs = elapsed
                    when (outcome) {
                        is PlaylistFetch.Ok ->
                            applyPlaylistBody(outcome.body, url, fromCache = false)
                        is PlaylistFetch.Failed -> {
                            // The last good playlist (memory, or the cache the
                            // cold start just applied) stays the queue.
                            playlistLastError = outcome.reason
                            applyActiveQueue(currentItem?.id, index, reset = false, advanceOnMissing = true)
                            onChanged()
                        }
                    }
                    schedulePlaylistRefresh()
                }
            }
        } catch (e: Exception) {
            // The executor is shut down during onDestroy; nothing may start.
        }
    }

    private fun applyPlaylistBody(body: String, base: String, fromCache: Boolean) {
        when (val parsed = MediaPlaylist.parse(body, base)) {
            is MediaPlaylist.Result.Invalid -> {
                // A fetched body that parses to nothing is a *successful*
                // answer — "this playlist has nothing playable" — not a
                // transport failure. Keeping the last-good items would play a
                // playlist the source no longer serves and would keep the local
                // folder locked out forever, so the playlist is cleared here
                // (memory and cache alike) and the local folder takes over,
                // advancing from the current index clamped into the new queue.
                // The same rule covers `[]` and an all-entries-invalid body.
                // Last-good is kept only for `PlaylistFetch.Failed`.
                playlistLastError = parsed.reason
                playlistItems = emptyList()
                deletePlaylistCache()
                applyActiveQueue(currentItem?.id, index, reset = false, advanceOnMissing = true)
                onChanged()
            }
            is MediaPlaylist.Result.Valid -> {
                val changed = parsed.items != playlistItems
                playlistLastError = ""
                playlistItems = parsed.items
                if (!fromCache) writePlaylistCache(parsed.items)
                val playlistIsQueue = source == SOURCE_PLAYLIST
                if (changed || (!playlistIsQueue && source != SOURCE_URL)) {
                    applyActiveQueue(currentItem?.id, index, reset = false, advanceOnMissing = true)
                } else {
                    onChanged()
                }
            }
        }
    }

    /** Re-arms the periodic refresh from now; every completed fetch calls it. */
    private fun schedulePlaylistRefresh() {
        main.removeCallbacks(playlistRefresh)
        if (!state.enabled || !playlistActive()) return
        val seconds = cfg.playlistRefreshSec.coerceIn(MIN_REFRESH_SEC, MAX_REFRESH_SEC)
        main.postDelayed(playlistRefresh, seconds * 1000L)
    }

    private sealed class PlaylistFetch {
        class Ok(val body: String) : PlaylistFetch()
        class Failed(val reason: String) : PlaylistFetch()
    }

    /** One bounded GET. Runs on the IO executor only. */
    private fun fetchPlaylist(url: String): PlaylistFetch {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(url).openConnection() as? HttpURLConnection
                ?: return PlaylistFetch.Failed("Unsupported URL")
            connection = opened
            opened.connectTimeout = PLAYLIST_TIMEOUT_MS
            opened.readTimeout = PLAYLIST_TIMEOUT_MS
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("Accept", "application/json")
            val code = opened.responseCode
            if (code != HttpURLConnection.HTTP_OK) return PlaylistFetch.Failed("HTTP $code")
            val body = opened.inputStream.use { MediaPlaylist.readBounded(it) }
                ?: return PlaylistFetch.Failed("Playlist is larger than 256 KiB")
            PlaylistFetch.Ok(body)
        } catch (e: java.net.SocketTimeoutException) {
            PlaylistFetch.Failed("Timed out")
        } catch (e: Exception) {
            PlaylistFetch.Failed(shortReason(e))
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Nothing to release.
            }
        }
    }

    private fun shortReason(e: Exception): String {
        val message = e.message?.trim().orEmpty()
        val text = if (message.isEmpty()) e.javaClass.simpleName else message
        return text.take(120)
    }

    private fun readPlaylistCache(): String? {
        val file = playlistCacheFile ?: return null
        return PlaylistCache.read(file, MediaPlaylist.MAX_BYTES)
    }

    /**
     * The cache is written back as a playlist body (`[{type,url,durationSec}]`),
     * so a cold start re-parses it through exactly the same strict path. It is
     * written on the IO executor: the queue must never wait for the disk.
     */
    private fun writePlaylistCache(items: List<MediaItem>) {
        val file = playlistCacheFile ?: return
        val body = try {
            playlistBodyOf(items)
        } catch (e: Exception) {
            return
        }
        if (body.length > MediaPlaylist.MAX_BYTES) return
        try {
            io.execute { PlaylistCache.write(file, body) }
        } catch (e: Exception) {
            // Executor gone; the in-memory playlist is unaffected.
        }
    }

    /**
     * Drops the cached body. The cache is last-good state for a URL; once that
     * URL is replaced or its playlist turns out to be empty it must not be
     * replayed on the next cold boot.
     */
    private fun deletePlaylistCache() {
        val file = playlistCacheFile ?: return
        try {
            io.execute { PlaylistCache.delete(file) }
        } catch (e: Exception) {
            // Executor gone; nothing to delete.
        }
    }

    private fun playlistBodyOf(items: List<MediaItem>): String {
        val array = JSONArray()
        for (item in items) {
            val entry = JSONObject()
            entry.put("type", if (item.kind == MediaItem.Kind.IMAGE) "image" else "video")
            entry.put("url", item.id)
            entry.put("durationSec", (item.durationMs / 1000L).toInt())
            array.put(entry)
        }
        return array.toString()
    }

    private fun skip(step: Int) {
        if (!state.enabled || queue.isEmpty()) return
        val wasPaused = paused
        // A deliberate move is a fresh attempt at a remote queue.
        remoteFailures = 0
        index = MediaQueue.advance(index, queue.size, step)
        manualPaused = false
        publishQueue()
        startCurrent()
        applyPause(wasPaused)
        onChanged()
    }

    /**
     * The error path for a remote item: move to the next item and play it,
     * without clearing the error that was just published and without
     * restarting the still timer of the item that failed.
     */
    private fun advanceAfterRemoteError() {
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        main.removeCallbacks(imageAdvance)
        index = MediaQueue.advance(index, queue.size, 1)
        publishQueue()
        val item = currentItem ?: return
        host?.play(item, cfg.volumePercent, cfg.mediaFillMode, loop = queue.size == 1)
        if (paused) host?.pause() else scheduleImageTimer()
    }

    private fun startCurrent() {
        main.removeCallbacks(imageAdvance)
        // A new item always gets a full dwell, never the previous still's rest.
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        // A genuine start is a fresh attempt at a remote queue.
        remoteFailures = 0
        val item = currentItem
        if (item == null) {
            host?.showPlaceholder()
            return
        }
        manualPaused = false
        focusPaused = false
        if (state.lastError.isNotEmpty()) dispatch(MediaEvent.PlaybackRecovered)
        // A one-item queue repeats itself; a longer one advances on completion,
        // which a looping player would never report.
        host?.play(item, cfg.volumePercent, cfg.mediaFillMode, loop = queue.size == 1)
        // A menu or the lifecycle may have been holding playback paused while
        // the item was chosen; the new item must start in that same state.
        if (paused) host?.pause() else scheduleImageTimer()
    }

    private fun stopPlayback() {
        // Invalidate any library read or playlist fetch that is still in flight.
        storeGeneration++
        playlistGeneration++
        main.removeCallbacks(playlistRefresh)
        main.removeCallbacks(imageAdvance)
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        manualPaused = false
        focusPaused = false
        queue = emptyList()
        source = ""
        index = 0
        host?.pause()
        host?.showPlaceholder()
        publishQueue()
    }

    private fun refreshStats() {
        val generation = ++statsGeneration
        try {
            io.execute {
                val files = store.list().size
                val total = store.totalBytes()
                val free = store.freeBytes()
                main.post {
                    if (generation != statsGeneration) return@post
                    localFiles = files
                    localBytes = total
                    freeBytes = free
                    onChanged()
                }
            }
        } catch (e: Exception) {
            // The executor is shut down during onDestroy; nothing may start.
        }
    }

    private fun publishQueue() {
        index = if (queue.isEmpty()) 0 else index.coerceIn(0, queue.size - 1)
        dispatch(MediaEvent.SourceChanged(source, queue.size))
        // The queue position belongs to the controller; SourceChanged only
        // re-clamps a position the reducer already held.
        state = state.copy(index = index)
    }

    private fun dispatch(event: MediaEvent) {
        val previousMode = state.mode
        val wasPaused = paused
        state = MediaStateMachine.reduce(state, event, cfg, System.currentTimeMillis())
        // Any transition into or out of a media-owning mode changes whether
        // playback may run; keep the player in step even for the reducer's own
        // transient modes. `Enabled` is handled by syncSettings/stopPlayback.
        if (event !is MediaEvent.Enabled && state.mode != previousMode) {
            applyPause(wasPaused)
        }
        if (state.mode != previousMode) {
            applyModeEffects()
        }
        scheduleTimers()
    }

    /**
     * The strip exists only while `SPLIT` is on screen. Entering it asserts the
     * attribute and the stylesheet; leaving it removes the attribute, which is
     * all the restore needs.
     */
    private fun applyModeEffects() {
        when (state.mode) {
            MediaMode.SPLIT -> applyStrip()
            MediaMode.QMS_FOCUS, MediaMode.MEDIA_ONLY, MediaMode.QMS_ONLY -> removeStrip()
        }
    }

    private fun applyStrip() {
        val origin = qmsOrigin()
        if (origin.isEmpty()) return
        val css = QmsStripCss.build(cfg, renderScale())
        val stripAttribute = QmsScript.jsString(QmsStripCss.attribute())
        val stateAttribute = QmsScript.jsString(QmsStripCss.stateAttribute())
        val unhealthy = QmsScript.jsString("unavailable")
        val script = buildString {
            append("(function(){try{")
            // The same origin gate as the injected script: the evaluate call
            // must never restyle an unrelated page the operator navigated to.
            append("if(location.origin!==")
                .append(QmsScript.jsString(origin))
                .append("){return;}")
            append("var d=document.documentElement;")
            append("d.setAttribute(").append(stripAttribute).append(",'');")
            if (state.qmsDetectionHealthy) {
                append("d.removeAttribute(").append(stateAttribute).append(");")
            } else {
                append("d.setAttribute(").append(stateAttribute).append(',').append(unhealthy).append(");")
            }
            append("var id='terebi-qms-strip';")
            append("var s=document.getElementById(id);")
            append("if(!s){s=document.createElement('style');s.id=id;")
            append("(document.head||document.documentElement).appendChild(s);}")
            append("s.textContent=").append(QmsScript.jsString(css)).append(";")
            append("}catch(e){}})();")
        }
        evaluate(script)
    }

    private fun removeStrip() {
        val stripAttribute = QmsScript.jsString(QmsStripCss.attribute())
        val stateAttribute = QmsScript.jsString(QmsStripCss.stateAttribute())
        evaluate(
            "(function(){try{var d=document.documentElement;" +
                "d.removeAttribute($stripAttribute);" +
                "d.removeAttribute($stateAttribute);" +
                "}catch(e){}})();"
        )
    }

    private fun applyPause(wasPaused: Boolean) {
        if (paused == wasPaused) return
        if (paused) {
            // Closing the timer here keeps the still's leftover dwell, so the
            // resume can finish it rather than restarting the whole delay.
            scheduleImageTimer()
            host?.pause()
        } else {
            host?.resume()
            resumeImageTimer()
        }
    }

    private fun scheduleTimers() {
        main.removeCallbacks(manualExpiry)
        val until = state.manualUntilMs
        if (state.enabled && until > 0L && until != MediaStateMachine.MANUAL_INDEFINITE_UNTIL_MS) {
            main.postDelayed(manualExpiry, (until - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        // The dwell is re-armable: every new call in `QMS_FOCUS` replaces the
        // deadline, and the callback only acts while the mode is still focus.
        main.removeCallbacks(dwellExpiry)
        val dwellUntil = state.dwellUntilMs
        if (state.enabled && dwellUntil > 0L) {
            main.postDelayed(
                dwellExpiry,
                (dwellUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            )
        }
        scheduleImageTimer()
    }

    /**
     * Re-arms the still's timer after a paused -> playing transition. Timers do
     * not survive a pause: the advance callback was removed (or fired against a
     * paused state and gave up), so without this a still that was on screen when
     * playback stopped would never advance again. It is a no-op unless the item
     * is an image that is actually showing, which is exactly the pause predicate
     * plus the item kind.
     */
    private fun resumeImageTimer() {
        if (currentItem?.kind != MediaItem.Kind.IMAGE) return
        val remaining = imageRemainingMs.takeIf { it > 0L }
        imageRemainingMs = 0L
        // Same helper as the item-start path: remove-before-post, so the two can
        // never leave two timers armed for one still.
        scheduleImageTimer(remaining)
    }

    /**
     * Arms the still's advance timer, or stops it while playback is paused.
     * [remainingMs] is the dwell left from before a pause; when it is unknown the
     * full `imageDurationSec` is used (a full dwell beats a still that never
     * advances). An already armed timer keeps its own deadline unless an explicit
     * remainder is supplied, so a redundant call cannot restart the dwell.
     */
    private fun scheduleImageTimer(remainingMs: Long? = null) {
        main.removeCallbacks(imageAdvance)
        val now = System.currentTimeMillis()
        val armedRemaining = if (imageDeadlineMs > 0L) {
            (imageDeadlineMs - now).coerceAtLeast(0L)
        } else {
            0L
        }
        imageDeadlineMs = 0L
        val item = currentItem
        if (item == null || !state.enabled || paused || item.kind != MediaItem.Kind.IMAGE) {
            if (armedRemaining > 0L) imageRemainingMs = armedRemaining
            return
        }
        // A playlist entry carries its own dwell; the global default is only
        // the fallback for local files (which have no stored duration).
        val fullMs = item.durationMs.takeIf { it > 0L }
            ?: cfg.imageDurationSec.coerceAtLeast(1) * 1000L
        val delayMs = (remainingMs
            ?: armedRemaining.takeIf { it > 0L }
            ?: imageRemainingMs.takeIf { it > 0L }
            ?: fullMs).coerceIn(1L, fullMs)
        imageRemainingMs = 0L
        imageDeadlineMs = now + delayMs
        main.postDelayed(imageAdvance, delayMs)
    }

    private companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_PLAYLIST = "playlist"
        const val SOURCE_URL = "url"

        /** Connect and read timeout for the playlist fetch, in milliseconds. */
        const val PLAYLIST_TIMEOUT_MS = 10_000

        /** Mirrors the validator's bounds; a stale value must not fetch in a loop. */
        const val MIN_REFRESH_SEC = 30
        const val MAX_REFRESH_SEC = 3600
    }
}
