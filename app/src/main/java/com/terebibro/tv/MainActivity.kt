package com.terebibro.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebChromeClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.terebibro.tv.BuildConfig
import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.device.DeviceInfo
import com.terebibro.tv.device.WebViewSupport
import com.terebibro.tv.mdns.MdnsAdvertiser
import com.terebibro.tv.media.MediaController
import com.terebibro.tv.media.MediaKey
import com.terebibro.tv.media.MediaKeyGuard
import com.terebibro.tv.media.MediaLayout
import com.terebibro.tv.media.MediaMode
import com.terebibro.tv.media.MediaPlayerHost
import com.terebibro.tv.media.MediaSettings
import com.terebibro.tv.media.MediaStore
import com.terebibro.tv.media.MediaWebSurface
import com.terebibro.tv.media.MediaWebFullscreenHost
import com.terebibro.tv.media.MediaWebPolicy
import com.terebibro.tv.media.QmsScript
import com.terebibro.tv.mqtt.MqttAction
import com.terebibro.tv.mqtt.MqttCommand
import com.terebibro.tv.mqtt.MqttManager
import com.terebibro.tv.mqtt.MqttStatus
import com.terebibro.tv.mqtt.MqttTopics
import com.terebibro.tv.net.ClientNetworks
import com.terebibro.tv.net.LocalNetwork
import com.terebibro.tv.server.ApiResult
import com.terebibro.tv.server.ApiRoutes
import com.terebibro.tv.server.AssetSource
import com.terebibro.tv.server.AuthManager
import com.terebibro.tv.server.ControllerHost
import com.terebibro.tv.server.ControllerServer
import com.terebibro.tv.server.MainThreadBridge
import com.terebibro.tv.server.ScreenMetrics
import com.terebibro.tv.server.StateJson
import com.terebibro.tv.server.WsHub
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import com.terebibro.tv.web.HomeUrlMatcher
import com.terebibro.tv.web.UrlValidator
import com.terebibro.tv.web.WebViewController
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Full screen, TV-first WebView host plus the embedded controller server,
 * mDNS advertisement, the optional MQTT client and the TV-side setup / error /
 * menu overlays.
 */
class MainActivity : Activity(), WebViewController.Listener, ControllerHost, MqttManager.Dispatcher,
    MediaWebFullscreenHost {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var mediaContainer: FrameLayout
    private lateinit var mediaSurface: SurfaceView
    private lateinit var mediaBackdrop: ImageView
    private lateinit var mediaImage: ImageView
    private lateinit var mediaPlaceholder: TextView
    private lateinit var infoOverlay: ScrollView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var infoDeviceName: TextView
    private lateinit var infoControllerUrl: TextView
    private lateinit var infoIpUrl: TextView
    private lateinit var infoDebugUrl: TextView
    private lateinit var infoPin: TextView
    private lateinit var infoWebViewWarning: TextView
    private lateinit var infoPairButton: Button
    private lateinit var infoRevokeButton: Button
    private lateinit var infoBlockedHeader: TextView
    private lateinit var infoBlockedList: LinearLayout
    private lateinit var infoNetworksHeader: TextView
    private lateinit var infoNetworksList: LinearLayout
    private lateinit var infoStartButton: Button
    private lateinit var infoExitButton: Button
    private lateinit var errorCountdown: TextView
    private lateinit var errorRetryButton: Button
    private lateinit var errorHomeButton: Button

    private lateinit var menuOverlay: ScrollView
    private lateinit var menuHomeButton: Button
    private lateinit var menuReloadButton: Button
    private lateinit var menuRefreshButton: Button
    private lateinit var menuEnterUrlButton: Button
    private lateinit var menuMediaPlayPauseButton: Button
    private lateinit var menuMediaNextButton: Button
    private lateinit var menuMediaUrlButton: Button
    private lateinit var menuMediaWebButton: Button
    private lateinit var menuMediaModeButton: Button
    private lateinit var menuMediaKeyboardButton: Button
    private lateinit var menuMediaBanner: TextView
    private lateinit var menuSettingsButton: Button
    private lateinit var menuDeviceButton: Button
    private lateinit var menuExitButton: Button

    private lateinit var deviceOverlay: LinearLayout
    private lateinit var deviceInfoText: TextView
    private lateinit var deviceCloseButton: Button

    private lateinit var config: ConfigStore
    private lateinit var deviceInfo: DeviceInfo
    private lateinit var localNetwork: LocalNetwork
    private lateinit var webViewController: WebViewController
    private lateinit var mediaStore: MediaStore
    private lateinit var mediaWebSurface: MediaWebSurface
    private lateinit var mediaController: MediaController
    private lateinit var auth: AuthManager
    private lateinit var wsHub: WsHub
    private lateinit var apiRoutes: ApiRoutes
    private lateinit var controllerServer: ControllerServer
    private lateinit var mdns: MdnsAdvertiser
    private lateinit var mqttManager: MqttManager

    private val seq = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terebi-io").apply { isDaemon = true }
    }

    private var backCallback: OnBackInvokedCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Hold duration that turns a Back press into opening the on-device menu. A
     * long press is detected from the gesture's own DOWN->UP elapsed time rather
     * than by counting repeats, so it also works on remotes that never send
     * repeat events. Only consulted on API < 33 (see [BackOrder.isLongPress]
     * and [dispatchKeyEvent]).
     */
    private val backLongPressMs = 700L

    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    /**
     * The media band's own HTML5 fullscreen view. Deliberately separate from
     * [customView]: the QMS page swaps its view into `root_container`, while
     * this one belongs to `media_container` and must disappear with the band.
     */
    private var mediaFullscreenView: View? = null
    private var mediaFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var retryRunnable: Runnable? = null
    private var retryRemaining = 0
    private var pageHasError = false

    /**
     * True while one of the two text-entry dialogs is up. A dialog is not one of
     * the four overlay views, but it must pause media and keep the D-pad to
     * itself just like them.
     */
    private var dialogVisible = false

    /** The mode the last layout pass applied; used to notice a mode change. */
    private var lastMediaMode = MediaMode.QMS_ONLY

    /** The inputs the injected script was last built from; avoids needless rebuilds. */
    private var appliedQmsSignature: String? = null

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        config = ConfigStore(this)
        deviceInfo = DeviceInfo(this)
        localNetwork = LocalNetwork(this)
        MainThreadBridge.host = WeakReference(this)

        applyKeepAwake(config.keepScreenAwake)
        applyFullscreen(config.fullscreen)
        registerBackHandling()

        webViewController = WebViewController(this, this, { config.homeUrl })
        webViewController.attach(webViewContainer, config.homeUrl)
        // The sink is stored here; the bridge itself is registered only while
        // the injected script is installed, so media mode off exposes nothing.
        webViewController.setQmsSignalSink { kind, value ->
            if (::mediaController.isInitialized) mediaController.onQmsSignal(kind, value)
        }

        mediaStore = MediaStore(this)
        // The second renderer is created lazily on the first web source, but
        // the surface object exists now so the controller can own its focus and
        // lifecycle without Android types.
        mediaWebSurface = MediaWebSurface(
            activity = this,
            container = mediaContainer,
            onLoadFailed = {
                // A page that will not load must not keep the band: the
                // controller clears the web source and the folder/playlist
                // takes over.
                if (::mediaController.isInitialized) mediaController.onWebLoadFailed()
            },
            fullscreenHost = this
        )
        mediaController = MediaController(
            mediaStore,
            { config.mediaSettings },
            io,
            {
                applyMediaLayoutOnMain()
                if (::wsHub.isInitialized) broadcastState()
            },
            evaluate = { script -> webViewController.evaluateQms(script) },
            renderScale = { screenRenderScale() },
            documentStartSupported = { webViewController.documentStartSupported() },
            qmsOrigin = { qmsOriginOf(config.mediaSettings) },
            // The last-good playlist lives on internal storage: it is a small
            // JSON cache, not media, and it must exist before any network does.
            playlistCacheFile = File(filesDir, "playlist-cache.json"),
            web = mediaWebSurface
        )
        val host = MediaPlayerHost(
            this,
            mediaSurface,
            mediaBackdrop,
            mediaImage,
            mediaPlaceholder,
            object : MediaPlayerHost.Listener {
                override fun onItemFinished() = mediaController.next()
                override fun onPlaybackError(message: String) = mediaController.playbackError(message)
                override fun onPlaybackPausedByFocus() = mediaController.onPlaybackPausedByFocus()
                override fun onFocusGained() = mediaController.onFocusGained()
            }
        )
        mediaController.attach(host)
        // Install the observer before the first load when media mode is already
        // on; the origin gate means a non-QMS home page is untouched.
        applyQmsScriptOnMain(force = true)
        // attach() has now applied the persisted settings, including
        // applyDefaultSource(), which can open the web page and change what the
        // band shows. End the startup path with a QMS probe: a page that was
        // already loaded must never be left unstyled or unprobed. Before the
        // first load this is a no-op; onWebPageFinished probes again once the
        // document commits.
        reprobeQmsOnLoadedPage()

        auth = AuthManager(
            config = config,
            localNetwork = localNetwork,
            boundAddress = {
                if (::controllerServer.isInitialized) controllerServer.boundAddress() else null
            },
            allowLocalhost = BuildConfig.DEBUG
        )
        wsHub = WsHub(auth, { buildState() }, { key -> dpad(key) })
        apiRoutes = ApiRoutes(
            auth = auth,
            config = config,
            deviceInfo = deviceInfo,
            host = this,
            wsHub = wsHub,
            assets = AssetSource(this),
            mediaStore = mediaStore,
            canBindPort = { port -> controllerServer.canBindPort(port) },
            onPortChanged = {
                runOnIo {
                    controllerServer.rebind()
                    // Re-advertise the actual bound port: a fallback rebind may
                    // have moved it, and the SRV record would otherwise keep the
                    // old port until the next network or device-name change.
                    readvertise()
                    broadcastState()
                }
            }
        )
        controllerServer = ControllerServer(
            config,
            localNetwork,
            auth,
            apiRoutes,
            wsHub,
            allowLocalhostBind = BuildConfig.DEBUG
        ) {
            runOnIo {
                // A fallback or retry bind may land on a different port than the
                // one mDNS was started with; re-advertise the port actually
                // serving so the SRV record does not keep the old one.
                readvertise()
                broadcastState()
            }
        }
        mdns = MdnsAdvertiser(this, config)

        mqttManager = MqttManager(
            settingsProvider = { config.mqttSettings },
            clientIdProvider = { MqttTopics.clientId(config.mdnsName) },
            dispatcher = this,
            stateProvider = { buildState() },
            deviceIdProvider = { MqttTopics.segment(config.mdnsName) },
            lastDiscoveryPrefixProvider = { config.mqttLastDiscoveryPrefix },
            lastDiscoveryPrefixStore = { config.mqttLastDiscoveryPrefix = it },
            onStatusChanged = {
                // Surface connect/disconnect in the controller without touching
                // MQTT again (this only enqueues a WebSocket state broadcast).
                wsHub.broadcastState()
            }
        )
        mqttManager.start()

        io.execute {
            // Remove `.part` leftovers from an interrupted upload off the UI
            // thread, before the controller server can accept a new upload.
            mediaStore.sweepPartFiles()
            controllerServer.rebind()
            localNetwork.current()?.let { mdns.start(it.ip) }
        }

        registerNetworkCallback()

        applyMediaLayoutOnMain()
        updateMediaMenuVisibility()

        if (config.setupDone) {
            webViewController.loadOnMain(config.homeUrl)
        } else {
            showInfoOverlay(issueNewPin = true)
        }
        broadcastState()
    }

    override fun onResume() {
        super.onResume()
        webViewController.resume()
        // Only resume a page that is actually on screen: hide() paused it
        // because another source took the band, and resuming it here would
        // restart its timers and audio behind that source.
        if (::mediaWebSurface.isInitialized && mediaWebSurface.isShowing) mediaWebSurface.resume()
        mediaController.lifecycle(false)
        // A file pushed with `adb` while the app is in the foreground must
        // appear without a settings change; the controller re-reads the folder
        // off the UI thread and keeps the playing item when it still exists.
        mediaController.onResumeRescan()
        if (config.fullscreen) enterImmersiveMode()
    }

    override fun onPause() {
        webViewController.pause()
        if (::mediaWebSurface.isInitialized) mediaWebSurface.pause()
        mediaController.lifecycle(true)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // This activity consumes screen-size/density changes, so it is never
        // recreated and the band heights have to be recomputed here.
        applyMediaLayoutOnMain()
        // renderScale is derived from the same surface; the strip stylesheet
        // carries scaled dimensions, so it is rebuilt with it.
        if (::mediaController.isInitialized) mediaController.reapplyStrip()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Re-applying immersive mode while the soft keyboard is up dismisses
        // it again; the kiosk fullscreen is restored as soon as it is gone.
        if (MediaWebPolicy.shouldReapplyImmersive(config.fullscreen, softKeyboardMayBeVisible())) {
            enterImmersiveMode()
        }
    }

    /**
     * Whether the IME may be covering the window. API 30+ answers this
     * directly from the window insets. Below 30 there is no reliable query, so
     * the safe approximation is "the media page has focus in MEDIA_ONLY", the
     * one place in the app where text is typed into a web page.
     */
    private fun softKeyboardMayBeVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = window.decorView.rootWindowInsets ?: return false
            return insets.isVisible(WindowInsets.Type.ime())
        }
        return ::mediaController.isInitialized &&
            ::mediaWebSurface.isInitialized &&
            config.mediaSettings.enabled &&
            mediaController.currentMode() == MediaMode.MEDIA_ONLY &&
            mediaController.isWebSource() &&
            mediaWebSurface.hasPageFocus()
    }

    override fun onDestroy() {
        unregisterBackHandling()
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (ignored: Exception) {
                // ignore
            }
        }
        networkCallback = null
        cancelRetry()
        wsHub.closeAll()
        // The controller releases the player and the bitmap. The media WebView
        // is destroyed first, in the same order the QMS WebView is detached, so
        // no release callback can touch a dead renderer.
        if (::mediaWebSurface.isInitialized) mediaWebSurface.destroy()
        mediaController.release()
        webViewController.detach()
        MainThreadBridge.host = null
        // jmDNS.close() blocks while sending cancel announcements and
        // server.stop() joins the listener thread; run both off the UI thread
        // (and independently of io.shutdownNow()) so onDestroy cannot ANR.
        Thread({
            try {
                controllerServer.stop()
            } catch (ignored: Exception) {
                // ignore
            }
            try {
                mdns.stop()
            } catch (ignored: Exception) {
                // ignore
            }
            try {
                // Publishes a retained `offline` (so a crash is not reported as
                // still-online) and then disconnects.
                if (::mqttManager.isInitialized) mqttManager.stop()
            } catch (ignored: Exception) {
                // ignore
            }
        }, "terebi-shutdown").apply { isDaemon = true }.start()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.root_container)
        webViewContainer = findViewById(R.id.webview_container)
        mediaContainer = findViewById(R.id.media_container)
        mediaSurface = findViewById(R.id.media_surface)
        mediaBackdrop = findViewById(R.id.media_backdrop)
        mediaImage = findViewById(R.id.media_image)
        mediaPlaceholder = findViewById(R.id.media_placeholder)
        infoOverlay = findViewById(R.id.info_overlay)
        errorOverlay = findViewById(R.id.error_overlay)
        infoDeviceName = findViewById(R.id.info_device_name)
        infoControllerUrl = findViewById(R.id.info_controller_url)
        infoIpUrl = findViewById(R.id.info_ip_url)
        infoDebugUrl = findViewById(R.id.info_debug_url)
        infoPin = findViewById(R.id.info_pin)
        infoWebViewWarning = findViewById(R.id.info_webview_warning)
        infoPairButton = findViewById(R.id.info_pair_button)
        infoRevokeButton = findViewById(R.id.info_revoke_button)
        infoBlockedHeader = findViewById(R.id.info_blocked_header)
        infoBlockedList = findViewById(R.id.info_blocked_list)
        infoNetworksHeader = findViewById(R.id.info_networks_header)
        infoNetworksList = findViewById(R.id.info_networks_list)
        infoStartButton = findViewById(R.id.info_start_button)
        infoExitButton = findViewById(R.id.info_exit)
        errorCountdown = findViewById(R.id.error_countdown)
        errorRetryButton = findViewById(R.id.error_retry_button)
        errorHomeButton = findViewById(R.id.error_home_button)
        menuOverlay = findViewById(R.id.menu_overlay)
        menuHomeButton = findViewById(R.id.menu_home)
        menuReloadButton = findViewById(R.id.menu_reload)
        menuRefreshButton = findViewById(R.id.menu_refresh_webview)
        menuEnterUrlButton = findViewById(R.id.menu_enter_url)
        menuMediaPlayPauseButton = findViewById(R.id.menu_media_play_pause)
        menuMediaNextButton = findViewById(R.id.menu_media_next)
        menuMediaUrlButton = findViewById(R.id.menu_media_url)
        menuMediaWebButton = findViewById(R.id.menu_media_web)
        menuMediaModeButton = findViewById(R.id.menu_media_mode)
        menuMediaKeyboardButton = findViewById(R.id.menu_media_keyboard)
        menuMediaBanner = findViewById(R.id.menu_media_banner)
        menuSettingsButton = findViewById(R.id.menu_settings)
        menuDeviceButton = findViewById(R.id.menu_device_info)
        menuExitButton = findViewById(R.id.menu_exit)
        deviceOverlay = findViewById(R.id.device_overlay)
        deviceInfoText = findViewById(R.id.device_info_text)
        deviceCloseButton = findViewById(R.id.device_close)

        infoPairButton.setOnClickListener {
            auth.issuePin()
            updateInfoOverlay()
        }
        infoRevokeButton.setOnClickListener {
            if (!auth.revokeAll()) {
                SafeLog.w(TAG, "Revoke-all could not be persisted")
            }
            wsHub.closeAllRevoked()
            auth.issuePin()
            updateInfoOverlay()
        }
        infoStartButton.setOnClickListener {
            config.setupDone = true
            hideInfoOverlay()
            webViewController.loadOnMain(config.homeUrl)
        }
        infoExitButton.setOnClickListener { exitApp() }
        errorRetryButton.setOnClickListener { retryNow() }
        errorHomeButton.setOnClickListener {
            hideErrorOverlay()
            webViewController.goHome(config.homeUrl)
        }

        menuHomeButton.setOnClickListener {
            hideMenu()
            webViewController.goHome(config.homeUrl)
        }
        menuReloadButton.setOnClickListener {
            hideMenu()
            webViewController.reload()
        }
        menuRefreshButton.setOnClickListener {
            hideMenu()
            restartWebView()
        }
        menuEnterUrlButton.setOnClickListener { showEnterUrlDialog() }
        menuMediaPlayPauseButton.setOnClickListener {
            mediaController.togglePlayPause()
            hideMenu()
        }
        menuMediaNextButton.setOnClickListener {
            mediaController.next()
            hideMenu()
        }
        menuMediaUrlButton.setOnClickListener { showMediaUrlDialog() }
        menuMediaWebButton.setOnClickListener { showMediaWebDialog() }
        menuMediaModeButton.setOnClickListener { cycleMediaMode() }
        menuMediaKeyboardButton.setOnClickListener { showMediaKeyboard() }
        menuSettingsButton.setOnClickListener {
            hideMenu()
            showInfoOverlay(issueNewPin = auth.currentPin() == null)
        }
        menuDeviceButton.setOnClickListener { showDeviceOverlay() }
        menuExitButton.setOnClickListener { exitApp() }
        deviceCloseButton.setOnClickListener { hideDeviceOverlay() }
    }

    // ---------------------------------------------------------------------
    // Overlays
    // ---------------------------------------------------------------------

    private fun showInfoOverlay(issueNewPin: Boolean) {
        if (issueNewPin) auth.issuePin()
        updateInfoOverlay()
        infoOverlay.visibility = View.VISIBLE
        syncMediaOverlay()
        infoPairButton.requestFocus()
    }

    private fun hideInfoOverlay() {
        auth.clearPin()
        infoOverlay.visibility = View.GONE
        syncMediaOverlay()
        restoreContentFocus()
    }

    private fun updateInfoOverlay() {
        val info = localNetwork.current()
        val port = effectiveControllerPort()
        val host = "${config.mdnsName}.local"
        infoDeviceName.text = config.deviceName
        infoControllerUrl.text = "Controller: http://$host:$port"
        infoIpUrl.text = "IP:         http://${info?.ip ?: "unavailable"}:$port"
        if (BuildConfig.DEBUG) {
            infoDebugUrl.text = "Debug (adb forward): http://localhost:$port"
            infoDebugUrl.visibility = View.VISIBLE
        } else {
            infoDebugUrl.visibility = View.GONE
        }
        infoPin.text = "Pairing PIN: ${auth.currentPin() ?: "------"}"
        infoRevokeButton.text = "Revoke all devices (${auth.pairedCount()} paired)"

        // Advisory: an old system WebView renders modern CSS as unstyled HTML.
        // Shown only on a parseable version below the floor — never unknown.
        val webViewVersion = deviceInfo.webViewInfo().version
        val webViewMajor = WebViewSupport.majorOf(webViewVersion)
        if (webViewMajor != null && WebViewSupport.isOutdated(webViewVersion)) {
            infoWebViewWarning.text = getString(R.string.webview_outdated, webViewMajor)
            infoWebViewWarning.visibility = View.VISIBLE
        } else {
            infoWebViewWarning.visibility = View.GONE
        }

        updateNetworkControls()
    }

    /**
     * Renders the two dynamic TV-side lists that bootstrap the extra trusted
     * networks setting. Both containers are rebuilt on each refresh so the
     * overlay always reflects the current configuration; every button is
     * focusable, keeping the overlay D-pad navigable.
     */
    private fun updateNetworkControls() {
        // The rebuild below removes and re-adds every dynamic button, so a focused
        // button would be detached and focus would fall back to the top. Remember
        // which section owned focus and restore it afterwards.
        val focused = currentFocus
        val focusWasInBlocked = isWithin(focused, infoBlockedList)
        val focusWasInNetworks = isWithin(focused, infoNetworksList)

        val blocked = if (::auth.isInitialized) auth.blockedPeers() else emptyList()
        infoBlockedList.removeAllViews()
        var suggestionsShown = 0
        for (client in blocked) {
            val suggestion = ClientNetworks.containingNetworkFor(client.ip) ?: continue
            val button = Button(this)
            button.text = getString(R.string.allow_network, suggestion)
            button.setOnClickListener { allowNetwork(suggestion) }
            infoBlockedList.addView(button)
            suggestionsShown++
        }
        infoBlockedHeader.visibility = if (suggestionsShown > 0) View.VISIBLE else View.GONE

        val networks = config.extraClientNetworks
        infoNetworksList.removeAllViews()
        for (network in networks) {
            val button = Button(this)
            button.text = getString(R.string.remove_network, network)
            button.setOnClickListener { removeNetwork(network) }
            infoNetworksList.addView(button)
        }
        infoNetworksHeader.visibility = if (networks.isEmpty()) View.GONE else View.VISIBLE

        // Re-request focus on the first remaining dynamic button in the section that
        // held it, falling back to the start button when that section is now empty.
        if (focusWasInBlocked || focusWasInNetworks) {
            val section = if (focusWasInBlocked) infoBlockedList else infoNetworksList
            (section.getChildAt(0) ?: infoStartButton).requestFocus()
        }
    }

    /** True when [view] is [section] itself or one of its descendants. */
    private fun isWithin(view: View?, section: LinearLayout): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === section) return true
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }

    /** One-press allow from the TV overlay; no CIDR typing on a remote. */
    private fun allowNetwork(cidr: String) {
        val current = config.extraClientNetworks
        // Enforce the hard cap on the TV path too: refuse a brand-new entry once
        // the list is full, but still allow a no-op re-add of an existing entry.
        if (cidr !in current && current.size >= ClientNetworks.MAX_ENTRIES) return
        config.extraClientNetworks = (current + cidr).distinct()
        // The peer is now admitted, so the bootstrap list has served its purpose.
        auth.clearBlockedPeers()
        broadcastState()
        updateInfoOverlay()
    }

    private fun removeNetwork(cidr: String) {
        config.extraClientNetworks = config.extraClientNetworks.filterNot { it == cidr }
        broadcastState()
        updateInfoOverlay()
    }

    private fun showErrorOverlay() {
        errorOverlay.visibility = View.VISIBLE
        syncMediaOverlay()
        errorRetryButton.requestFocus()
        scheduleRetry()
    }

    private fun hideErrorOverlay() {
        cancelRetry()
        errorOverlay.visibility = View.GONE
        syncMediaOverlay()
        restoreContentFocus()
    }

    private fun scheduleRetry() {
        cancelRetry()
        if (!config.autoRetry) {
            errorCountdown.text = ""
            return
        }
        retryRemaining = config.retryIntervalSec
        errorCountdown.text = "Retrying in ${retryRemaining}s"
        val runnable = object : Runnable {
            override fun run() {
                retryRemaining--
                if (retryRemaining <= 0) {
                    retryRemaining = config.retryIntervalSec
                    webViewController.reload()
                }
                errorCountdown.text = "Retrying in ${retryRemaining}s"
                mainHandler.postDelayed(this, 1000L)
            }
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, 1000L)
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun retryNow() {
        retryRemaining = config.retryIntervalSec
        webViewController.reload()
        scheduleRetry()
    }

    // ---------------------------------------------------------------------
    // On-device menu, device information and Enter URL
    // ---------------------------------------------------------------------

    /**
     * The single state-change fan-out. Both the WebSocket broadcast and (when
     * enabled) the retained MQTT `status` publish hang off this one path, so
     * MQTT can never become a second source of truth.
     */
    private fun broadcastState() {
        wsHub.broadcastState()
        if (::mqttManager.isInitialized) mqttManager.publishStatus()
    }

    /**
     * Opens the on-device menu. It replaces the old long-press-to-exit and the
     * MENU/INFO overlay toggle; the setup / pairing page is reachable from it
     * via **Settings**, and exiting is now a deliberate menu item.
     */
    private fun showMenu() {
        // The menu is the top-level TV surface; do not stack it over the setup
        // overlay. (Settings re-opens the setup overlay.)
        if (infoOverlay.visibility == View.VISIBLE) hideInfoOverlay()
        // device_overlay is declared after menu_overlay, so it wins z-order: if
        // it is visible the menu would be focused but invisible and the next
        // Back would appear to do nothing. Leave fullscreen HTML5 video first
        // too, since its custom view is appended above every overlay.
        if (customView != null) hideCustomView()
        if (deviceOverlay.visibility == View.VISIBLE) hideDeviceOverlay()
        menuMediaBanner.visibility = View.GONE
        menuOverlay.visibility = View.VISIBLE
        syncMediaOverlay()
        menuHomeButton.requestFocus()
    }

    private fun hideMenu() {
        menuOverlay.visibility = View.GONE
        syncMediaOverlay()
        restoreContentFocus()
    }

    private fun showDeviceOverlay() {
        hideMenu()
        val webView = deviceInfo.webViewInfo()
        val info = localNetwork.current()
        deviceInfoText.text = buildString {
            appendLine("Device name: ${config.deviceName}")
            appendLine("App version: ${deviceInfo.appVersion}")
            appendLine("Android: ${deviceInfo.androidVersion} (API ${deviceInfo.sdkInt})")
            appendLine("WebView: ${webView.provider} ${webView.version}")
            appendLine("IP: ${info?.ip ?: "unavailable"}")
            appendLine("Port: ${effectiveControllerPort()}")
            appendLine("Uptime: ${deviceInfo.uptimeMs / 1000} s")
        }
        deviceOverlay.visibility = View.VISIBLE
        syncMediaOverlay()
        deviceCloseButton.requestFocus()
    }

    private fun hideDeviceOverlay() {
        deviceOverlay.visibility = View.GONE
        syncMediaOverlay()
        restoreContentFocus()
    }

    /**
     * Enter URL dialog (spec §16). Works with both touch and a D-pad/keyboard:
     * the field is a normal focusable `EditText` and the three actions are
     * focusable buttons. By default the URL goes through [UrlValidator] via
     * [WebViewController.navigate] (a missing scheme is upgraded to https, and
     * a non-http(s) scheme is refused), so it behaves exactly like the REST
     * path. The dialog stays open with an inline error until the URL is valid.
     *
     * [onOpen], when supplied, replaces the navigation with that callback and
     * hides "Open + Set as Home": the media web source reuses this dialog but
     * is not a page for the QMS WebView.
     */
    private fun showEnterUrlDialog(onOpen: ((String) -> Boolean)? = null) {
        hideMenu()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.enter_url_title)
            .setView(input)
            .setPositiveButton(R.string.open, null)
            .setNegativeButton(R.string.cancel, null)
        if (onOpen == null) builder.setNeutralButton(R.string.open_set_home, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onOpen == null) {
                    submitEnteredUrl(dialog, input, setHome = false)
                } else if (onOpen(input.text.toString())) {
                    dialog.dismiss()
                } else {
                    input.error = "Invalid URL"
                }
            }
            if (onOpen == null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    submitEnteredUrl(dialog, input, setHome = true)
                }
            }
        }
        dialog.setOnDismissListener {
            dialogVisible = false
            syncMediaOverlay()
            restoreContentFocus()
        }
        dialogVisible = true
        syncMediaOverlay()
        dialog.show()
    }

    /**
     * "Open web in media": the same dialog and validation as Enter URL, but the
     * result opens in the media band's web renderer rather than navigating the
     * QMS page.
     */
    private fun showMediaWebDialog() {
        showEnterUrlDialog { raw ->
            when (val validated = UrlValidator.validate(raw)) {
                is UrlValidator.Result.Invalid -> false
                is UrlValidator.Result.Valid -> {
                    val opened = mediaController.openWeb(validated.url)
                    if (opened) {
                        applyMediaLayoutOnMain()
                        seq.incrementAndGet()
                        broadcastState()
                    }
                    opened
                }
            }
        }
    }

    /**
     * "Play from URL" for the media band. It reuses the Enter URL dialog's
     * contract — [UrlValidator] decides, a missing scheme becomes https, a
     * non-http(s) value is refused inline — but hands the result to the media
     * controller instead of the WebView.
     */
    private fun showMediaUrlDialog() {
        hideMenu()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/video.mp4"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_media_url)
            .setView(input)
            .setPositiveButton(R.string.open, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val validated = UrlValidator.validate(input.text.toString())) {
                    is UrlValidator.Result.Invalid -> input.error = "Invalid URL"
                    is UrlValidator.Result.Valid -> {
                        mediaController.playUrl(validated.url)
                        applyMediaLayoutOnMain()
                        broadcastState()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            dialogVisible = false
            syncMediaOverlay()
            restoreContentFocus()
        }
        dialogVisible = true
        syncMediaOverlay()
        dialog.show()
    }

    /** QMS only -> Split -> Media only -> QMS only. */
    private fun cycleMediaMode() {
        val next = when (mediaController.currentMode()) {
            MediaMode.QMS_ONLY -> MediaMode.SPLIT
            MediaMode.SPLIT -> MediaMode.MEDIA_ONLY
            MediaMode.MEDIA_ONLY -> MediaMode.QMS_ONLY
            // QMS_FOCUS is the reducer's own transient mode; leaving it means
            // choosing the automatic layout.
            MediaMode.QMS_FOCUS -> MediaMode.SPLIT
        }
        mediaController.setMode(next)
        applyMediaLayoutOnMain()
        if (next == MediaMode.MEDIA_ONLY) {
            // The workspace opens the panel so the media can be chosen at once;
            // Back closes it first and then returns to SPLIT.
            showMenu()
        } else {
            hideMenu()
        }
        broadcastState()
    }

    private fun updateMediaMenuVisibility() {
        val visibility = if (config.mediaSettings.enabled) View.VISIBLE else View.GONE
        menuMediaPlayPauseButton.visibility = visibility
        menuMediaNextButton.visibility = visibility
        menuMediaUrlButton.visibility = visibility
        menuMediaWebButton.visibility = visibility
        menuMediaModeButton.visibility = visibility
        // The soft keyboard is only useful where the page owns the D-pad and
        // the IME; SPLIT leaves the page inert on purpose.
        val keyboardVisibility = if (
            config.mediaSettings.enabled &&
            mediaController.currentMode() == MediaMode.MEDIA_ONLY &&
            mediaController.isWebSource()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        menuMediaKeyboardButton.visibility = keyboardVisibility
    }

    /**
     * On-device fallback for a Jellyfin login field on a television with no
     * usable remote keyboard. The page must have focus before the IME is asked
     * for it, so the menu closes first. A device with no IME at all is told so
     * in the menu banner rather than silently doing nothing.
     */
    private fun showMediaKeyboard() {
        val page = mediaWebSurface.view
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (page == null || imm == null || imm.inputMethodList.isNullOrEmpty()) {
            menuMediaBanner.setText(R.string.menu_media_keyboard_unavailable)
            menuMediaBanner.visibility = View.VISIBLE
            return
        }
        hideMenu()
        page.requestFocus()
        // Let the menu's dismissal settle before the IME is raised.
        page.post { imm.showSoftInput(page, InputMethodManager.SHOW_FORCED) }
    }

    private fun submitEnteredUrl(dialog: AlertDialog, input: EditText, setHome: Boolean) {
        when (val outcome = webViewController.navigate(input.text.toString())) {
            is WebViewController.Outcome.Ok -> {
                if (setHome) config.homeUrl = outcome.url
                dialog.dismiss()
                broadcastState()
            }
            is WebViewController.Outcome.Error -> {
                // Keep the dialog open and show the reason inline; a D-pad can
                // reach the field again to correct it.
                input.error = outcome.message
            }
        }
    }

    // ---------------------------------------------------------------------
    // Display
    // ---------------------------------------------------------------------

    private fun applyFullscreen(enabled: Boolean) {
        if (enabled) {
            enterImmersiveMode()
        } else {
            showSystemUi()
        }
    }

    /**
     * Hides the system bars. On API 30+ this uses the modern window-insets
     * controller exactly as before; on API 28-29 the legacy
     * `systemUiVisibility` flags are the only mechanism available (the
     * `Window`/`WindowInsetsController` API does not exist there).
     */
    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentWindow = window
            currentWindow.setDecorFitsSystemWindows(false)
            currentWindow.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * Restores the system bars, the counterpart of [enterImmersiveMode]. On
     * API 30+ this is the `setDecorFitsSystemWindows(true)` +
     * `insetsController.show(...)` pair; below API 30 clearing the legacy
     * flags removes the immersive layout flags.
     */
    @Suppress("DEPRECATION")
    private fun showSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentWindow = window
            currentWindow.setDecorFitsSystemWindows(true)
            currentWindow.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = 0
        }
    }

    private fun applyKeepAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Returns focus to whatever the current mode shows. In `MEDIA_ONLY` the QMS
     * WebView is hidden, so focus goes to the media band; requesting it on a
     * gone WebView would leave the D-pad with nowhere to land.
     *
     * A page owns the band rather than the container, so focus must be given to
     * the page itself — this runs on every overlay/dialog dismissal, including
     * the one right after "Open web in media", and landing on the container
     * would swallow the first OK press.
     */
    private fun restoreContentFocus() {
        val mediaOnly = config.mediaSettings.enabled &&
            mediaController.currentMode() == MediaMode.MEDIA_ONLY
        if (mediaOnly && !anyOverlayVisible()) {
            if (mediaController.isWebSource()) {
                mediaController.refocusWeb()
            } else {
                mediaContainer.requestFocus()
            }
        } else {
            webViewController.requestWebFocus()
        }
    }

    /**
     * Applies the band geometry for the current mode. The physical surface
     * height is split into px bands whose three parts sum exactly, so a
     * fractional density can neither open a seam nor clip the queue band.
     */
    private fun applyMediaLayoutOnMain() {
        val mode = if (config.mediaSettings.enabled) {
            mediaController.currentMode()
        } else {
            MediaMode.QMS_ONLY
        }
        // A fullscreen view only makes sense while the band owns the screen: a
        // QMS_FOCUS takeover (or leaving the web source) must hide it first,
        // exactly like showMenu() does for the QMS custom view.
        if (mediaFullscreenView != null &&
            !MediaWebPolicy.allowsMediaFullscreen(
                config.mediaSettings.enabled,
                mediaController.isWebSource(),
                mode
            )
        ) {
            hideMediaFullscreen()
        }
        val mediaParams = mediaContainer.layoutParams as FrameLayout.LayoutParams
        val webParams = webViewContainer.layoutParams as FrameLayout.LayoutParams

        // Baseline: both surfaces full screen; a mode then overrides the one it
        // does not use, so no stale height can leak across a mode change.
        val previousMode = lastMediaMode
        lastMediaMode = mode

        mediaParams.width = FrameLayout.LayoutParams.MATCH_PARENT
        mediaParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        mediaParams.topMargin = 0
        webParams.width = FrameLayout.LayoutParams.MATCH_PARENT
        webParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        webParams.topMargin = 0

        when (mode) {
            MediaMode.QMS_ONLY, MediaMode.QMS_FOCUS -> {
                mediaContainer.visibility = View.GONE
                if (customView == null) webViewContainer.visibility = View.VISIBLE
            }
            MediaMode.MEDIA_ONLY -> {
                webViewContainer.visibility = View.GONE
                mediaContainer.visibility = View.VISIBLE
            }
            MediaMode.SPLIT -> {
                val metrics = resources.displayMetrics
                val density = if (metrics.density > 0f) metrics.density else 1f
                val bands = MediaLayout.bandsPx(
                    metrics.heightPixels.coerceAtLeast(0),
                    density,
                    config.mediaSettings
                )
                mediaParams.height = bands.mediaPx
                webParams.height = bands.headerPx + bands.queuePx
                webParams.topMargin = bands.mediaPx
                mediaContainer.visibility = View.VISIBLE
                if (customView == null) webViewContainer.visibility = View.VISIBLE
            }
        }
        mediaContainer.layoutParams = mediaParams
        webViewContainer.layoutParams = webParams

        // A page in MEDIA_ONLY owns the D-pad itself. The container is
        // focusable so a native item has somewhere to land, but while the page
        // is showing it must not compete: a focused container would take the
        // key and the guard no longer swallows it. Restore the container as
        // soon as the page stops being the band's source.
        val mediaPageActive = mode == MediaMode.MEDIA_ONLY && mediaController.isWebSource()
        mediaContainer.isFocusable = !mediaPageActive
        if (mediaPageActive && mediaFullscreenView == null && mediaContainer.hasFocus()) {
            mediaController.refocusWeb()
        }

        // Focus follows the visible surface. Leaving the workspace hides the
        // media band, and the D-pad must go back to the QMS page rather than to
        // a gone view; entering it puts focus on the band (the page itself when
        // one is open). An overlay keeps its own focus.
        if (previousMode != mode && !anyOverlayVisible()) {
            if (mode == MediaMode.MEDIA_ONLY) {
                if (mediaController.isWebSource()) {
                    mediaController.refocusWeb()
                } else {
                    mediaContainer.requestFocus()
                }
            } else if (previousMode == MediaMode.MEDIA_ONLY) {
                webViewController.requestWebFocus()
            }
        }
        updateMediaMenuVisibility()
    }

    // ---------------------------------------------------------------------
    // Back handling
    // ---------------------------------------------------------------------

    private fun registerBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // On API 33+ the system is expected to route Back to this callback
            // rather than to dispatchKeyEvent (reasoned from the platform's
            // Back-dispatch design, not verified on a 33+ device). The code
            // therefore does not rely on dispatchKeyEvent seeing the key here:
            // the callback runs the same ladder (Back at the root opens the
            // setup page), and exiting is done deliberately from the Exit App
            // button on the setup page.
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun unregisterBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    /**
     * Runs the (now total) Back ladder. Every rung performs a real action, so
     * this always consumes the press.
     */
    fun handleBack(): Boolean {
        return when (BackOrder.decision(backState())) {
            BackOrder.Action.CLOSE_MENU -> {
                hideMenu()
                true
            }
            BackOrder.Action.CLOSE_DEVICE -> {
                hideDeviceOverlay()
                true
            }
            BackOrder.Action.CLOSE_INFO -> {
                hideInfoOverlay()
                true
            }
            BackOrder.Action.CLOSE_ERROR -> {
                hideErrorOverlay()
                true
            }
            BackOrder.Action.HIDE_FULLSCREEN -> {
                if (mediaFullscreenView != null) hideMediaFullscreen() else hideCustomView()
                true
            }
            // Back inside the media workspace: leave it. Without this rung the
            // press would fall through to the WebView ladder and navigate the
            // QMS page away from the dashboard.
            BackOrder.Action.EXIT_MEDIA_MODE -> {
                mediaController.setMode(MediaMode.SPLIT)
                applyMediaLayoutOnMain()
                broadcastState()
                true
            }
            BackOrder.Action.GO_BACK -> {
                webViewController.goBack()
                true
            }
            // Back not at the root: fall back to the home page. The comparison
            // is normalised (HomeUrlMatcher) so a home page that redirects
            // (trailing slash, `www.`) terminates instead of oscillating.
            BackOrder.Action.GO_HOME -> {
                webViewController.goHome(config.homeUrl)
                true
            }
            // Back at the true root: open the setup / pairing page. Exiting the
            // kiosk is deliberate (the Exit App item in the on-device menu, or
            // the Exit App button on the setup page) and never a side effect of
            // one press.
            BackOrder.Action.SHOW_SETUP -> {
                showInfoOverlay(issueNewPin = auth.currentPin() == null)
                true
            }
        }
    }

    /**
     * Deliberate exit, shared by the on-device menu's Exit App item and the
     * Exit App button on the setup page. [finishAndRemoveTask] leaves Recents
     * too so the kiosk is not relaunched from there.
     */
    private fun exitApp() {
        SafeLog.i(TAG, "Exiting app")
        finishAndRemoveTask()
    }

    /** Snapshot of the state the Back ladder is evaluated against. */
    private fun backState(): BackOrder.BackState = BackOrder.BackState(
        infoOverlayVisible = infoOverlay.visibility == View.VISIBLE,
        errorOverlayVisible = errorOverlay.visibility == View.VISIBLE,
        fullscreenVisible = customView != null || mediaFullscreenView != null,
        canGoBack = webViewController.canGoBackOnMain(),
        atHome = HomeUrlMatcher.samePage(webViewController.currentUrl(), config.homeUrl),
        menuVisible = menuOverlay.visibility == View.VISIBLE,
        deviceInfoVisible = deviceOverlay.visibility == View.VISIBLE,
        mediaOnly = mediaController.currentMode() == MediaMode.MEDIA_ONLY
    )

    /**
     * Maps a remote key to the media layer and, when [MediaKeyGuard] says the
     * press belongs to it, performs the action and consumes the event before
     * the WebView or the platform sees it.
     */
    private fun handleMediaKey(event: KeyEvent): Boolean {
        val key = mediaKeyFor(event.keyCode)
        val mode = mediaController.currentMode()
        if (!MediaKeyGuard.shouldSwallow(
                mode,
                anyOverlayVisible(),
                mediaController.isWebSource(),
                key
            )
        ) {
            return false
        }
        // Act once, on the release; the press itself is still consumed.
        if (event.action != KeyEvent.ACTION_UP) return true
        when (key) {
            MediaKey.PLAY_PAUSE -> mediaController.togglePlayPause()
            MediaKey.RESUME -> mediaController.resume()
            MediaKey.PAUSE -> mediaController.pause()
            MediaKey.NEXT -> mediaController.next()
            MediaKey.PREVIOUS -> mediaController.previous()
            // Only a native band item reaches here (the guard never swallows a
            // D-pad key while a web page is active), and it has nothing to
            // navigate.
            MediaKey.DPAD, MediaKey.ENTER -> Unit
            MediaKey.MENU, MediaKey.OTHER -> return false
        }
        broadcastState()
        return true
    }

    private fun mediaKeyFor(keyCode: Int): MediaKey = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT -> MediaKey.DPAD

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> MediaKey.ENTER

        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO -> MediaKey.MENU

        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> MediaKey.PLAY_PAUSE

        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaKey.RESUME

        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaKey.PAUSE

        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKey.NEXT

        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND -> MediaKey.PREVIOUS

        else -> MediaKey.OTHER
    }

    private fun anyOverlayVisible(): Boolean =
        dialogVisible ||
            menuOverlay.visibility == View.VISIBLE ||
            infoOverlay.visibility == View.VISIBLE ||
            errorOverlay.visibility == View.VISIBLE ||
            deviceOverlay.visibility == View.VISIBLE

    /**
     * Whether the media band's web page owns the D-pad/ENTER keys: media mode
     * is on, the band is full screen in `MEDIA_ONLY`, the page is the source,
     * and no overlay is layered above it.
     *
     * A page-owned fullscreen view does not make this false. That view is a
     * plain `View` supplied by Chromium; it is not a key target of its own, so
     * the keys must still reach the page's WebView, which is `GONE` behind the
     * view while it is up. [ensureMediaPageFocus] handles that case separately
     * because an invisible view cannot take focus.
     */
    private fun mediaPageOwnsKeys(): Boolean =
        config.mediaSettings.enabled &&
            mediaController.currentMode() == MediaMode.MEDIA_ONLY &&
            mediaController.isWebSource() &&
            !anyOverlayVisible()

    /**
     * Keeps the media page as the focused view before a key is routed. Focus is
     * only a fallback for the first key after a transition: the device has been
     * observed reporting focus on the page while the page still never saw the
     * D-pad, so [dispatchKeyEvent] also dispatches directly to the WebView.
     *
     * A page-owned fullscreen view covers the band and the WebView is `GONE`:
     * `requestFocus()` cannot land on it, and the direct dispatch does not need
     * focus, so it is skipped there.
     */
    private fun ensureMediaPageFocus() {
        if (!::mediaWebSurface.isInitialized) return
        if (!mediaPageOwnsKeys()) return
        if (mediaFullscreenView != null) return
        if (!mediaWebSurface.hasPageFocus()) mediaController.refocusWeb()
    }

    /**
     * Debug-only, content-free record of how a key was routed. It never logs the
     * URL or the key's character; the enum name and the two booleans are enough
     * to tell "the guard took it" from "the page took it" in `adb logcat`.
     */
    private fun logMediaKeyDispatch(
        event: KeyEvent,
        key: MediaKey,
        guardSwallowed: Boolean,
        directResult: Boolean? = null
    ) {
        if (!BuildConfig.DEBUG) return
        SafeLog.d(
            TAG,
            "media key=${key.name} action=${event.action} " +
                "mediaOnly=${mediaController.currentMode() == MediaMode.MEDIA_ONLY} " +
                "web=${mediaController.isWebSource()} guard=$guardSwallowed " +
                "direct=${directResult?.toString() ?: "n/a"}"
        )
    }

    /** The four overlays (and dialogs) are independent; derive one pause bit from them. */
    private fun syncMediaOverlay() {
        mediaController.overlay(anyOverlayVisible())
    }

    @Suppress("DEPRECATION")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The remote's MENU / INFO button opens the on-device menu (the setup /
        // pairing page is a menu item). This used to toggle the setup overlay.
        if ((event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) &&
            event.action == KeyEvent.ACTION_UP
        ) {
            if (menuOverlay.visibility == View.VISIBLE) hideMenu() else showMenu()
            return true
        }
        // Media keys are consumed before the WebView (and before Back, which is
        // not in the media key set) when the current mode owns them.
        val mediaKey = mediaKeyFor(event.keyCode)
        if (handleMediaKey(event)) {
            logMediaKeyDispatch(event, mediaKey, guardSwallowed = true)
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            // Back is always meaningful now (the ladder is total), so it is
            // always consumed and never falls through to the system.
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Consume the press; do not act on DOWN. The hold is
                    // measured on UP from the event's own gesture start
                    // (event.downTime), which auto-repeat cannot shift the way
                    // event.eventTime can.
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    // A cancelled press (FLAG_CANCELED) is not a press at all:
                    // no ladder, no menu.
                    if (event.isCanceled) return true
                    // Some remotes never send repeats, so the DOWN->UP elapsed
                    // time is the hold duration. A missing/malformed gesture is
                    // never a long press (see BackOrder.isLongPress). A long
                    // press opens the on-device menu; a short press runs the
                    // Back ladder.
                    if (BackOrder.isLongPress(event.downTime, event.eventTime, backLongPressMs)) {
                        showMenu()
                    } else {
                        handleBack()
                    }
                    return true
                }
                else -> return true
            }
        }
        // A media page in MEDIA_ONLY does not reliably receive keys through
        // window focus: on the device the WebView reports focus yet never sees
        // the D-pad. Hand DPAD/ENTER to it directly, for both the press and the
        // release, and consume the event even when the page ignores it so it
        // cannot fall through to the hidden QMS page or the band container.
        // This also covers the page-owned fullscreen view: the keys still go to
        // the WebView, which is hidden behind that view.
        if ((mediaKey == MediaKey.DPAD || mediaKey == MediaKey.ENTER) &&
            mediaPageOwnsKeys()
        ) {
            ensureMediaPageFocus()
            val direct = mediaWebSurface.view?.dispatchKeyEvent(event) == true
            logMediaKeyDispatch(event, mediaKey, guardSwallowed = false, directResult = direct)
            return true
        }
        ensureMediaPageFocus()
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------
    // HTML5 fullscreen view
    // ---------------------------------------------------------------------

    override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        rootContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewContainer.visibility = View.GONE
    }

    override fun onHideCustomView() {
        hideCustomView()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        rootContainer.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        // Re-apply the mode instead of unconditionally showing the WebView: in
        // MEDIA_ONLY the media container, not the WebView, owns the screen.
        applyMediaLayoutOnMain()
    }

    // ---------------------------------------------------------------------
    // MediaWebFullscreenHost — the media band's HTML5 fullscreen
    // ---------------------------------------------------------------------

    /**
     * Adds the page's fullscreen view inside `media_container`, above the
     * media WebView. It is band-scoped by construction: in `MEDIA_ONLY` the
     * band is the whole screen (real fullscreen); in `SPLIT` it fills only the
     * media band, leaving the queue visible.
     */
    override fun onMediaShowFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback
    ): Boolean {
        if (mediaFullscreenView != null) return false
        if (!MediaWebPolicy.allowsMediaFullscreen(
                config.mediaSettings.enabled,
                mediaController.isWebSource(),
                mediaController.currentMode()
            )
        ) {
            return false
        }
        mediaFullscreenView = view
        mediaFullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        mediaContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // The page is paused visually, not in the media sense: it must keep
        // running behind the fullscreen video.
        mediaWebSurface.hideForFullscreen()
        return true
    }

    override fun onMediaHideFullscreen() {
        hideMediaFullscreen(notifyPage = true)
    }

    override fun onMediaFullscreenGone() {
        hideMediaFullscreen(notifyPage = false)
    }

    /**
     * Removes the stored fullscreen view. [notifyPage] is false when the page
     * (or its renderer) is already gone, where calling back would touch a dead
     * WebView.
     */
    private fun hideMediaFullscreen(notifyPage: Boolean = true) {
        val view = mediaFullscreenView ?: return
        mediaContainer.removeView(view)
        mediaFullscreenView = null
        val callback = mediaFullscreenCallback
        mediaFullscreenCallback = null
        if (notifyPage) {
            try {
                callback?.onCustomViewHidden()
            } catch (e: Exception) {
                // The renderer died between the request and the hide.
            }
        }
        // The page owns the band again unless another source has taken it.
        if (::mediaWebSurface.isInitialized && mediaController.isWebSource()) {
            mediaWebSurface.restoreAfterFullscreen()
        }
    }

    // ---------------------------------------------------------------------
    // WebViewController.Listener
    // ---------------------------------------------------------------------

    override fun onWebStateChanged() {
        seq.incrementAndGet()
        broadcastState()
    }

    override fun onWebProgress(progress: Int) {
        broadcastState()
    }

    override fun onWebPageStarted(url: String?) {
        pageHasError = false
        broadcastState()
    }

    override fun onWebPageFinished(url: String?) {
        if (!pageHasError) hideErrorOverlay()
        // Chromium 66 fires onPageFinished for its own error page too. Calling
        // onPageLoaded() then would immediately undo the page failure the error
        // page just recorded (and revert the documented MEDIA_ONLY), so a load
        // is only a recovery when the page did not fail.
        if (!pageHasError) {
            mediaController.onPageLoaded()
            // Applying the persisted default source (or a mode change) must not
            // leave the strip dependent on a probe that ran before the document
            // committed. Re-request the probe on the loaded page, exactly like a
            // settings save does.
            reprobeQmsOnLoadedPage()
        }
        broadcastState()
    }

    override fun onWebPageError(description: String) {
        pageHasError = true
        SafeLog.w(TAG, "Page load failed")
        // Page-level failure: there is nothing to restyle. With media mode off
        // the reducer is inert and the V1 error page stays full screen.
        mediaController.onQmsPageFailed()
        showErrorOverlay()
        wsHub.broadcastEvent("page_error", description)
    }

    override fun onWebViewRestarted() {
        // Rebuilds land on the activation page; detection must start again.
        mediaController.onWebViewRestarted()
        wsHub.broadcastEvent("webview_restarted", null)
    }

    override fun onRemoteBack(): Boolean = handleBack()

    // ---------------------------------------------------------------------
    // ControllerHost
    // ---------------------------------------------------------------------

    override fun buildState(): BrowserState? =
        MainThreadBridge.runOnMain { it.stateOnMain() }

    private fun stateOnMain(): BrowserState {
        val snapshot = webViewController.snapshot()
        val info = localNetwork.current()
        val webView = deviceInfo.webViewInfo()
        val media = config.mediaSettings
        val runtime = mediaController.snapshot()
        val metrics = screenMetrics()
        return BrowserState(
            url = snapshot.url,
            title = snapshot.title,
            loading = snapshot.loading,
            progress = snapshot.progress,
            canGoBack = snapshot.canGoBack,
            canGoForward = snapshot.canGoForward,
            homeUrl = config.homeUrl,
            fullscreen = config.fullscreen,
            keepAwake = config.keepScreenAwake,
            deviceName = config.deviceName,
            ip = info?.ip ?: "",
            port = effectiveControllerPort(),
            network = info?.network ?: "none",
            webViewVersion = "${webView.provider} ${webView.version}",
            webViewOutdated = WebViewSupport.isOutdated(webView.version),
            appVersion = deviceInfo.appVersion,
            androidVersion = deviceInfo.androidVersion,
            sdkInt = deviceInfo.sdkInt,
            uptimeMs = deviceInfo.uptimeMs,
            seq = seq.get(),
            extraNetworks = config.extraClientNetworks,
            mediaEnabled = media.enabled,
            mediaMode = runtime.mode.wire,
            mediaSource = runtime.source,
            mediaItem = runtime.item,
            mediaIndex = runtime.index,
            mediaCount = runtime.count,
            mediaPaused = runtime.paused,
            mediaError = runtime.error,
            qmsNow = runtime.qmsNow,
            qmsLocation = runtime.qmsLocation,
            qmsDetectionHealthy = runtime.qmsDetectionHealthy,
            qmsLastCallAtMs = runtime.qmsLastCallAtMs,
            documentStartSupported = runtime.documentStartSupported,
            mediaLocalFiles = runtime.localFiles,
            mediaLocalBytes = runtime.localBytes,
            mediaFreeBytes = runtime.freeBytes,
            playlistLastError = runtime.playlistLastError,
            playlistLastFetchMs = runtime.playlistLastFetchMs,
            uiLanguage = media.uiLanguage.wire,
            screenWidthDp = metrics.widthDp,
            screenHeightDp = metrics.heightDp,
            densityDpi = metrics.densityDpi,
            renderScale = MediaLayout.safeRenderScale(metrics.heightDp)
        )
    }

    private fun stateResult(): ApiResult {
        val state = buildState() ?: return ApiResult.unavailable()
        return ApiResult.Ok(mapOf("state" to StateJson.toJson(state)))
    }

    override fun openUrl(url: String, setHome: Boolean): ApiResult {
        return when (val outcome = webViewController.navigate(url)) {
            is WebViewController.Outcome.Ok -> {
                if (setHome) config.homeUrl = outcome.url
                stateResult()
            }
            is WebViewController.Outcome.Error -> {
                val status = if (outcome.code == "webview_not_ready") 409 else 400
                ApiResult.Err(status, outcome.code, outcome.message)
            }
        }
    }

    override fun setHomeUrl(url: String): ApiResult {
        return when (val validated = UrlValidator.validate(url)) {
            is UrlValidator.Result.Invalid -> ApiResult.invalidUrl()
            is UrlValidator.Result.Valid -> {
                config.homeUrl = validated.url
                seq.incrementAndGet()
                broadcastState()
                ApiResult.ok("homeUrl" to validated.url)
            }
        }
    }

    override fun navAction(action: String): ApiResult {
        return when (action) {
            "back" ->
                if (webViewController.goBack()) stateResult()
                else ApiResult.conflict("cannot_go_back", "No back history")
            "forward" ->
                if (webViewController.goForward()) stateResult()
                else ApiResult.conflict("cannot_go_forward", "No forward history")
            "home" -> {
                webViewController.goHome(config.homeUrl)
                stateResult()
            }
            "reload" -> {
                webViewController.reload()
                stateResult()
            }
            "stop" -> {
                webViewController.stop()
                stateResult()
            }
            else -> ApiResult.badRequest("bad_request", "Unknown navigation action")
        }
    }

    override fun restartWebView(): ApiResult {
        val ok = MainThreadBridge.runOnMain { it.webViewController.restartOnMain(it.config.homeUrl) }
            ?: false
        return if (ok) ApiResult.ok() else ApiResult.unavailable()
    }

    override fun clearCache(): ApiResult {
        MainThreadBridge.runOnMain { it.webViewController.clearCacheOnMain() }
        return ApiResult.ok()
    }

    override fun clearSiteData(): ApiResult {
        MainThreadBridge.runOnMain { it.webViewController.clearSiteDataOnMain() }
        return ApiResult.ok()
    }

    override fun setDisplay(fullscreen: Boolean?, keepAwake: Boolean?): ApiResult {
        if (fullscreen != null) config.fullscreen = fullscreen
        if (keepAwake != null) config.keepScreenAwake = keepAwake
        MainThreadBridge.post {
            if (fullscreen != null) applyFullscreen(fullscreen)
            if (keepAwake != null) applyKeepAwake(keepAwake)
        }
        seq.incrementAndGet()
        broadcastState()
        return ApiResult.ok(
            "fullscreen" to config.fullscreen,
            "keepAwake" to config.keepScreenAwake
        )
    }

    override fun dpad(key: String): ApiResult {
        return when (val outcome = webViewController.dispatchDpad(key)) {
            is WebViewController.Outcome.Ok -> ApiResult.ok()
            is WebViewController.Outcome.Error -> ApiResult.badRequest(outcome.code, outcome.message)
        }
    }

    override fun setDeviceName(name: String): ApiResult {
        config.deviceName = name
        MainThreadBridge.post { updateInfoOverlay() }
        runOnIo {
            readvertise()
            broadcastState()
        }
        return ApiResult.ok("deviceName" to name)
    }

    override fun setControllerPort(port: Int): ApiResult {
        config.controllerPort = port
        val ip = localNetwork.current()?.ip
        val url = if (ip != null) "http://$ip:$port" else "http://<ip>:$port"
        return ApiResult.ok("port" to port, "url" to url)
    }

    override fun currentPin(): String? = auth.currentPin()

    override fun issuePin(): String? {
        val pin = auth.issuePin()
        MainThreadBridge.post { updateInfoOverlay() }
        return pin
    }

    override fun clearPin() = auth.clearPin()

    override fun pairedCount(): Int = auth.pairedCount()

    override fun mqttStatus(): MqttStatus =
        if (::mqttManager.isInitialized) mqttManager.status() else MqttStatus.DISABLED

    override fun onMqttSettingsChanged() {
        if (::mqttManager.isInitialized) mqttManager.restart()
    }

    override fun mediaSettingsSnapshot(): MediaSettings =
        MainThreadBridge.runOnMain { it.config.mediaSettings } ?: config.mediaSettings

    override fun setMediaMode(mode: MediaMode): ApiResult =
        MainThreadBridge.runOnMain { it.applyMediaMode(mode) }
            ?: ApiResult.unavailable()

    /**
     * Plays an ad-hoc URL on the media band. The same [UrlValidator] as
     * `/api/nav/open`, so a non-http(s) value is `invalid_url`; the queue is
     * replaced by the single URL until another source is chosen.
     *
     * With [web] true the URL opens in the media band's web renderer instead:
     * the result still reports the URL (a caller already supplied it), and the
     * published state masks it.
     */
    override fun openMediaUrl(url: String, web: Boolean): ApiResult {
        return when (val validated = UrlValidator.validate(url)) {
            is UrlValidator.Result.Invalid -> ApiResult.invalidUrl()
            is UrlValidator.Result.Valid -> {
                if (!config.mediaSettings.enabled) {
                    return ApiResult.conflict("media_disabled", "Enable media mode first")
                }
                MainThreadBridge.post {
                    if (web) {
                        mediaController.openWeb(validated.url)
                    } else {
                        mediaController.playUrl(validated.url)
                    }
                    applyMediaLayoutOnMain()
                    seq.incrementAndGet()
                    broadcastState()
                }
                ApiResult.ok("url" to validated.url, "web" to web)
            }
        }
    }

    /**
     * Plays the next queue item. A no-op while media mode is off: the result is
     * still `ok`, with `applied:false` and a reason, so an automation never sees
     * a failure for a feature that is simply turned off.
     */
    override fun mediaNext(): ApiResult = mediaCommand { it.next() }

    /** Resumes playback. A no-op (see [mediaNext]) without media mode. */
    override fun mediaPlay(): ApiResult = mediaCommand { it.resume() }

    /** Pauses playback. A no-op (see [mediaNext]) without media mode. */
    override fun mediaPause(): ApiResult = mediaCommand { it.pause() }

    /**
     * Runs a media action on the UI thread through the same controller methods
     * the on-device menu and the REST routes use, so MQTT cannot introduce a
     * second playback path.
     */
    private fun mediaCommand(action: (MediaController) -> Unit): ApiResult {
        if (!config.mediaSettings.enabled) {
            return ApiResult.ok("applied" to false, "reason" to "media_disabled")
        }
        MainThreadBridge.post {
            action(mediaController)
            seq.incrementAndGet()
            broadcastState()
        }
        return ApiResult.ok("applied" to true)
    }

    private fun applyMediaMode(mode: MediaMode): ApiResult {
        if (mode == MediaMode.QMS_FOCUS) {
            return ApiResult.badRequest("bad_request", "qms_focus is not a manual mode")
        }
        // A manual mode is meaningless while media mode is off: it would leave
        // the state claiming `mediaEnabled:false` with a non-QMS mode. Check
        // before mutating anything so a refused request has no side effects.
        if (!config.mediaSettings.enabled) {
            return ApiResult.conflict("media_disabled", "Enable media mode first")
        }
        val applied = mediaController.setMode(mode)
        applyMediaLayoutOnMain()
        seq.incrementAndGet()
        broadcastState()
        return ApiResult.ok("mode" to applied.wire, "heldUntilMs" to mediaController.manualUntilMs())
    }

    /**
     * A library upload or delete succeeded. The queue is re-read on the IO
     * executor (inside the controller) and re-applied on main; the playing item
     * is kept when it still exists. Nothing is started here on the UI thread.
     */
    override fun onMediaLibraryChanged() {
        MainThreadBridge.post {
            mediaController.onLibraryChanged()
            seq.incrementAndGet()
            broadcastState()
        }
    }

    override fun onMediaSettingsChanged() {
        // ControllerHost methods run on server threads; every field below is
        // UI-thread state, so the whole body hops to the main thread.
        MainThreadBridge.post { applyMediaSettingsChangedOnMain() }
    }

    private fun applyMediaSettingsChangedOnMain() {
        mediaController.settingsChanged()
        updateMediaMenuVisibility()
        // qmsUrl, a selector or the master switch changing rebuilds the
        // injected script; the signature check makes a no-op edit cheap.
        applyQmsScriptOnMain()
        // A settings save must be visible on the board that is already on
        // screen, not on the next navigation: re-read it once. A passing probe
        // re-applies the strip (MediaController.onQmsProbe), and a failed one
        // still publishes the unavailable state.
        reprobeQmsOnLoadedPage()
        applyMediaLayoutOnMain()
        seq.incrementAndGet()
        broadcastState()
    }

    /**
     * Re-probes the page that is already showing when a settings save lands.
     * Only the two QMS-owning modes have a board to read: `QMS_ONLY` is where
     * the boot rule waits for the first match, and `SPLIT` is the strip.
     * `QMS_FOCUS` is a live call and `MEDIA_ONLY` has no strip on screen, so
     * neither is disturbed.
     */
    private fun reprobeQmsOnLoadedPage() {
        val mode = mediaController.currentMode()
        if (mode != MediaMode.SPLIT && mode != MediaMode.QMS_ONLY) return
        if (!webViewController.isPageLoaded()) return
        webViewController.evaluateQms(QmsScript.probeCall())
    }

    /**
     * Rebuilds the injected script only when something it depends on changed.
     * The origin is resolved exactly as the settings are: a blank qmsUrl tracks
     * the Home URL at read time.
     */
    private fun applyQmsScriptOnMain(force: Boolean = false) {
        val media = config.mediaSettings
        val origin = qmsOriginOf(media)
        val signature = listOf(
            media.enabled,
            media.qmsUrl,
            media.qmsStripSelector,
            media.qmsNumberSelector,
            media.qmsLocationSelector,
            media.qmsTimeSelector,
            origin
        ).joinToString("\u001f")
        if (!force && signature == appliedQmsSignature) return
        appliedQmsSignature = signature

        val script = if (media.enabled && origin.isNotEmpty()) {
            QmsScript.build(media, origin)
        } else {
            ""
        }
        // An empty script also removes a previously installed handler.
        webViewController.setQmsScript(script, if (script.isEmpty()) "" else origin)
    }

    private fun qmsOriginOf(media: MediaSettings): String {
        val url = media.qmsUrl.ifBlank { config.homeUrl }
        return QmsScript.originOf(url)
    }

    private fun screenRenderScale(): Float {
        val metrics = resources.displayMetrics
        val density = if (metrics.density > 0f) metrics.density else 1f
        if (metrics.heightPixels <= 0) return 1f
        val heightDp = (metrics.heightPixels / density).roundToInt()
        return MediaLayout.safeRenderScale(heightDp)
    }

    override fun screenMetrics(): ScreenMetrics =
        MainThreadBridge.runOnMain { it.displayMetricsOnMain() } ?: FALLBACK_SCREEN_METRICS

    private fun displayMetricsOnMain(): ScreenMetrics {
        val metrics = resources.displayMetrics
        val density = if (metrics.density > 0f) metrics.density else 1f
        // A reported dimension of 0 would be published as a zero band height
        // and a zero render scale; fall back rather than emit it.
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return FALLBACK_SCREEN_METRICS
        return ScreenMetrics(
            widthDp = (metrics.widthPixels / density).roundToInt(),
            heightDp = (metrics.heightPixels / density).roundToInt(),
            densityDpi = metrics.densityDpi
        )
    }

    // ---------------------------------------------------------------------
    // MqttManager.Dispatcher
    // ---------------------------------------------------------------------

    /**
     * Executes a parsed MQTT command. Every branch routes through the same
     * ControllerHost operations the REST API uses, so the command is applied on
     * the UI thread exactly like a controller request and MQTT cannot bypass
     * the browser's own behaviour.
     */
    override fun dispatch(command: MqttCommand): ApiResult = when (command.action) {
        MqttAction.OPEN -> command.url?.let { openUrl(it, false) }
            ?: ApiResult.badRequest("missing_url", "open requires a url")
        MqttAction.SET_HOME -> command.url?.let { setHomeUrl(it) }
            ?: ApiResult.badRequest("missing_url", "set-home requires a url")
        MqttAction.HOME -> navAction("home")
        MqttAction.BACK -> navAction("back")
        MqttAction.FORWARD -> navAction("forward")
        MqttAction.RELOAD -> navAction("reload")
        MqttAction.STOP -> navAction("stop")
        MqttAction.RESTART_WEBVIEW -> restartWebView()
        MqttAction.CLEAR_CACHE -> clearCache()
        MqttAction.CLEAR_SITE_DATA -> clearSiteData()
        // `set-mode` calls the same setMediaMode as `/api/media/mode`,
        // including its `media_disabled` refusal. The three playback actions
        // call the same MediaController methods as the on-device menu; only
        // `set-mode` has a REST route.
        MqttAction.MEDIA_NEXT -> mediaNext()
        MqttAction.MEDIA_PLAY -> mediaPlay()
        MqttAction.MEDIA_PAUSE -> mediaPause()
        MqttAction.SET_MODE -> command.mode?.let { setMediaMode(it) }
            ?: ApiResult.badRequest("bad_request", "set-mode requires a mode")
    }

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()

            override fun onLost(network: Network) = onNetworkChanged()
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            SafeLog.w(TAG, "Network callback registration failed")
        }
    }

    private fun onNetworkChanged() {
        runOnIo {
            val info = localNetwork.current()
            val bound = controllerServer.boundAddress()
            val ipChanged = info?.ip != bound?.first
            controllerServer.rebind()
            if (ipChanged) {
                mdns.stop()
                if (info != null) mdns.start(info.ip)
            }
            broadcastState()
        }
        MainThreadBridge.post {
            if (infoOverlay.visibility == View.VISIBLE) updateInfoOverlay()
        }
        if (pageHasError) {
            MainThreadBridge.post { retryNow() }
        }
    }

    /**
     * Restarts the mDNS registration against the current LAN address and port,
     * dropping it entirely when there is no network. [MdnsAdvertiser.start]
     * stops any previous registration itself.
     */
    private fun readvertise() {
        mdns.stop()
        localNetwork.current()?.let { mdns.start(it.ip) }
    }

    /**
     * Runs [task] on the IO thread, tolerating the executor having been shut
     * down in [onDestroy]. A delayed callback (port change, device name) can
     * still fire in that window; it must be dropped, not crash the app.
     */
    private fun runOnIo(task: () -> Unit) {
        try {
            io.execute(task)
        } catch (e: RejectedExecutionException) {
            SafeLog.w(TAG, "Background task dropped during shutdown")
        }
    }

    /** The port the control server is actually serving on, if it is listening. */
    private fun effectiveControllerPort(): Int {
        if (!::controllerServer.isInitialized) return config.controllerPort
        return controllerServer.boundAddress()?.second ?: config.controllerPort
    }

    private companion object {
        const val TAG = "MainActivity"

        /**
         * Last-resort metrics for when the main-thread hop times out: the
         * 1080p mdpi authoring surface. Used only so a reported `renderScale`
         * is never `0.0` and `screenHeightDp` is never zero.
         */
        val FALLBACK_SCREEN_METRICS = ScreenMetrics(widthDp = 1920, heightDp = 1080, densityDpi = 160)
    }
}
