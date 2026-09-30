package com.terebibro.tv

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.terebibro.tv.BuildConfig
import com.terebibro.tv.config.ConfigStore
import com.terebibro.tv.device.DeviceInfo
import com.terebibro.tv.mdns.MdnsAdvertiser
import com.terebibro.tv.net.LocalNetwork
import com.terebibro.tv.server.ApiResult
import com.terebibro.tv.server.ApiRoutes
import com.terebibro.tv.server.AssetSource
import com.terebibro.tv.server.AuthManager
import com.terebibro.tv.server.ControllerHost
import com.terebibro.tv.server.ControllerServer
import com.terebibro.tv.server.MainThreadBridge
import com.terebibro.tv.server.StateJson
import com.terebibro.tv.server.WsHub
import com.terebibro.tv.util.SafeLog
import com.terebibro.tv.web.BrowserState
import com.terebibro.tv.web.HomeUrlMatcher
import com.terebibro.tv.web.UrlValidator
import com.terebibro.tv.web.WebViewController
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Full screen, TV-first WebView host plus the embedded controller server,
 * mDNS advertisement and the TV-side setup / error overlays.
 */
class MainActivity : Activity(), WebViewController.Listener, ControllerHost {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var infoOverlay: ScrollView
    private lateinit var errorOverlay: LinearLayout
    private lateinit var infoDeviceName: TextView
    private lateinit var infoControllerUrl: TextView
    private lateinit var infoIpUrl: TextView
    private lateinit var infoDebugUrl: TextView
    private lateinit var infoPin: TextView
    private lateinit var infoPairButton: Button
    private lateinit var infoRevokeButton: Button
    private lateinit var infoStartButton: Button
    private lateinit var infoExitButton: Button
    private lateinit var errorCountdown: TextView
    private lateinit var errorRetryButton: Button
    private lateinit var errorHomeButton: Button

    private lateinit var config: ConfigStore
    private lateinit var deviceInfo: DeviceInfo
    private lateinit var localNetwork: LocalNetwork
    private lateinit var webViewController: WebViewController
    private lateinit var auth: AuthManager
    private lateinit var wsHub: WsHub
    private lateinit var apiRoutes: ApiRoutes
    private lateinit var controllerServer: ControllerServer
    private lateinit var mdns: MdnsAdvertiser

    private val seq = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terebi-io").apply { isDaemon = true }
    }

    private var backCallback: OnBackInvokedCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Hold duration that turns a Back press into a deliberate exit. A long
     * press is detected from the gesture's own DOWN->UP elapsed time rather
     * than by counting repeats, so it also works on remotes that never send
     * repeat events. Only consulted on API < 33 (see [BackOrder.isLongPress]
     * and [dispatchKeyEvent]).
     */
    private val backLongPressMs = 700L

    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    private var retryRunnable: Runnable? = null
    private var retryRemaining = 0
    private var pageHasError = false

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
            canBindPort = { port -> controllerServer.canBindPort(port) },
            onPortChanged = {
                runOnIo {
                    controllerServer.rebind()
                    // Re-advertise the actual bound port: a fallback rebind may
                    // have moved it, and the SRV record would otherwise keep the
                    // old port until the next network or device-name change.
                    readvertise()
                    wsHub.broadcastState()
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
                wsHub.broadcastState()
            }
        }
        mdns = MdnsAdvertiser(this, config)

        io.execute {
            controllerServer.rebind()
            localNetwork.current()?.let { mdns.start(it.ip) }
        }

        registerNetworkCallback()

        if (config.setupDone) {
            webViewController.loadOnMain(config.homeUrl)
        } else {
            showInfoOverlay(issueNewPin = true)
        }
        wsHub.broadcastState()
    }

    override fun onResume() {
        super.onResume()
        webViewController.resume()
        if (config.fullscreen) enterImmersiveMode()
    }

    override fun onPause() {
        webViewController.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && config.fullscreen) enterImmersiveMode()
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
        }, "terebi-shutdown").apply { isDaemon = true }.start()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.root_container)
        webViewContainer = findViewById(R.id.webview_container)
        infoOverlay = findViewById(R.id.info_overlay)
        errorOverlay = findViewById(R.id.error_overlay)
        infoDeviceName = findViewById(R.id.info_device_name)
        infoControllerUrl = findViewById(R.id.info_controller_url)
        infoIpUrl = findViewById(R.id.info_ip_url)
        infoDebugUrl = findViewById(R.id.info_debug_url)
        infoPin = findViewById(R.id.info_pin)
        infoPairButton = findViewById(R.id.info_pair_button)
        infoRevokeButton = findViewById(R.id.info_revoke_button)
        infoStartButton = findViewById(R.id.info_start_button)
        infoExitButton = findViewById(R.id.info_exit)
        errorCountdown = findViewById(R.id.error_countdown)
        errorRetryButton = findViewById(R.id.error_retry_button)
        errorHomeButton = findViewById(R.id.error_home_button)

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
    }

    // ---------------------------------------------------------------------
    // Overlays
    // ---------------------------------------------------------------------

    private fun showInfoOverlay(issueNewPin: Boolean) {
        if (issueNewPin) auth.issuePin()
        updateInfoOverlay()
        infoOverlay.visibility = View.VISIBLE
        infoPairButton.requestFocus()
    }

    private fun hideInfoOverlay() {
        auth.clearPin()
        infoOverlay.visibility = View.GONE
        webViewController.requestWebFocus()
    }

    private fun toggleInfoOverlay() {
        if (infoOverlay.visibility == View.VISIBLE) {
            hideInfoOverlay()
        } else {
            showInfoOverlay(issueNewPin = auth.currentPin() == null)
        }
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
    }

    private fun showErrorOverlay() {
        errorOverlay.visibility = View.VISIBLE
        errorRetryButton.requestFocus()
        scheduleRetry()
    }

    private fun hideErrorOverlay() {
        cancelRetry()
        errorOverlay.visibility = View.GONE
        webViewController.requestWebFocus()
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
    // Display
    // ---------------------------------------------------------------------

    private fun applyFullscreen(enabled: Boolean) {
        val currentWindow = window
        if (enabled) {
            enterImmersiveMode()
        } else {
            currentWindow.setDecorFitsSystemWindows(true)
            currentWindow.insetsController?.show(WindowInsets.Type.systemBars())
        }
    }

    private fun enterImmersiveMode() {
        val currentWindow = window
        currentWindow.setDecorFitsSystemWindows(false)
        currentWindow.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyKeepAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
            BackOrder.Action.CLOSE_INFO -> {
                hideInfoOverlay()
                true
            }
            BackOrder.Action.CLOSE_ERROR -> {
                hideErrorOverlay()
                true
            }
            BackOrder.Action.HIDE_FULLSCREEN -> {
                hideCustomView()
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
            // kiosk is deliberate (long-press Back on API < 33, or the Exit App
            // button on the setup page) and never a side effect of one press.
            BackOrder.Action.SHOW_SETUP -> {
                showInfoOverlay(issueNewPin = auth.currentPin() == null)
                true
            }
        }
    }

    /**
     * Deliberate exit, shared by the long-press of Back (API < 33) and the Exit
     * App button on the setup page. [finishAndRemoveTask] leaves Recents too so
     * the kiosk is not relaunched from there.
     */
    private fun exitApp() {
        SafeLog.i(TAG, "Exiting app")
        finishAndRemoveTask()
    }

    /** Snapshot of the state the Back ladder is evaluated against. */
    private fun backState(): BackOrder.BackState = BackOrder.BackState(
        infoOverlayVisible = infoOverlay.visibility == View.VISIBLE,
        errorOverlayVisible = errorOverlay.visibility == View.VISIBLE,
        fullscreenVisible = customView != null,
        canGoBack = webViewController.canGoBackOnMain(),
        atHome = HomeUrlMatcher.samePage(webViewController.currentUrl(), config.homeUrl)
    )

    @Suppress("DEPRECATION")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TV remote access to the setup overlay: MENU / INFO buttons.
        if ((event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) &&
            event.action == KeyEvent.ACTION_UP
        ) {
            toggleInfoOverlay()
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
                    // no ladder, no exit.
                    if (event.isCanceled) return true
                    // Some remotes never send repeats, so the DOWN->UP elapsed
                    // time is the hold duration. A missing/malformed gesture is
                    // never a long press (see BackOrder.isLongPress).
                    if (BackOrder.isLongPress(event.downTime, event.eventTime, backLongPressMs)) {
                        exitApp()
                    } else {
                        handleBack()
                    }
                    return true
                }
                else -> return true
            }
        }
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
        webViewContainer.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ---------------------------------------------------------------------
    // WebViewController.Listener
    // ---------------------------------------------------------------------

    override fun onWebStateChanged() {
        seq.incrementAndGet()
        wsHub.broadcastState()
    }

    override fun onWebProgress(progress: Int) {
        wsHub.broadcastState()
    }

    override fun onWebPageStarted(url: String?) {
        pageHasError = false
        wsHub.broadcastState()
    }

    override fun onWebPageFinished(url: String?) {
        if (!pageHasError) hideErrorOverlay()
        wsHub.broadcastState()
    }

    override fun onWebPageError(description: String) {
        pageHasError = true
        SafeLog.w(TAG, "Page load failed")
        showErrorOverlay()
        wsHub.broadcastEvent("page_error", description)
    }

    override fun onWebViewRestarted() {
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
            appVersion = deviceInfo.appVersion,
            androidVersion = deviceInfo.androidVersion,
            sdkInt = deviceInfo.sdkInt,
            uptimeMs = deviceInfo.uptimeMs,
            seq = seq.get()
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
                wsHub.broadcastState()
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
        wsHub.broadcastState()
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
            wsHub.broadcastState()
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
            wsHub.broadcastState()
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
    }
}
