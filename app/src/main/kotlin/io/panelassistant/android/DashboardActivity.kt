package io.panelassistant.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import io.panelassistant.android.control.PanelTouchObserver
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.panelassistant.android.audio.MicrophoneAdmission
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.AndroidSystemEnv
import io.panelassistant.android.dashboard.InjectionScript
import io.panelassistant.android.dashboard.EntityLearningProtocol
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.dashboard.HomeDashboardLaunchCache
import io.panelassistant.android.util.DashboardTheme
import io.panelassistant.android.util.HaTransportEvidence
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun shouldRouteDashboardHomeToAdmin(
    configuredLauncherPackage: String,
    ownPackage: String,
    action: String?,
    categories: Set<String>?,
): Boolean = configuredLauncherPackage == ownPackage &&
    action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true

/**
 * Built-in dashboard renderer (experimental): a full-screen WebView onto the configured Home
 * Assistant URL, signed in through the frontend's documented external-auth bridge — the same
 * `?external_auth=1` + `window.externalAppV2` contract the HA Companion app uses. Requires `ha_url`
 * and `ha_token` (a long-lived access token set at provisioning); with either blank this activity
 * shows nothing useful, so entry points should gate on [Config.haUrl].
 *
 * Deliberately NOT a Companion replacement: no notifications, no sensors, no media/file-chooser
 * support — users who need those run the Companion app instead. The renderer's whole job is to
 * paint the dashboard and stay alive: a killed renderer process is rebuilt in place rather than
 * crashing the app (the page-level half of the never-blank guarantee).
 */
class DashboardActivity : AppCompatActivity() {

    private val maintenanceFence = GuardDbActivityMaintenanceFence()
    private lateinit var activityConfig: Config
    private var web: WebView? = null
    private var swipe: SwipeRefreshLayout? = null
    private var root: FrameLayout? = null                       // holds the swipe layout + fullscreen video
    private var lifecycleBar: HaLifecycleBar? = null            // native HA outage bar, lives inside `root`
    private var networkChip: HaNetworkChip? = null              // native "HA network slow" chip, same root
    private var substituteChip: DashboardSubstituteChip? = null // "Dashboard not found", same root

    /**
     * Marshals to the UI thread because the state machine is driven from the service's IO scope.
     * Registered for the activity's whole life, not just while a WebView exists, so the bar is correct
     * the moment one is built.
     */
    /**
     * A poke, not a payload: the bar re-reads the one source of truth. Marshals to the UI thread because
     * the state machine is driven from the service's IO scope.
     */
    private val haLifecycleListener: () -> Unit = {
        main.post { if (!destroyed) redrawLifecycleBar() }
    }

    private fun redrawLifecycleBar() {
        // Each owner supplies one atomic snapshot. Cached admission can explain a disconnected
        // dashboard even before the service has observed a lifecycle event.
        lifecycleBar?.update(
            io.panelassistant.android.sensors.HaLifecycleRuntime.snapshot(),
            RendererAdmissionRuntime.current()?.takeIf { it.owner == activityOwner },
        )
        networkChip?.update(io.panelassistant.android.sensors.HaNetworkPathRuntime.snapshot(), activityConfig.dashboardNetworkWarning)
    }

    /** Drop the bar and chip with their container. Every content-view swap must reach here, or they outlive their root. */
    private fun detachLifecycleBar() {
        lifecycleBar?.detach()
        lifecycleBar = null
        networkChip?.detach()
        networkChip = null
        substituteChip?.detach()
        substituteChip = null
    }

    private fun updateSubstituteChip() {
        val owned = homeDashboardResolution?.takeIf { it.owner == homeDashboardOwner(activityConfig) }
        substituteChip?.update(owned?.owner?.configuredPath.orEmpty(), owned?.resolution)
    }
    // The colour-scheme policy baked into the live WebView's document-start script. Document-start
    // scripts cannot be replaced in an existing WebView, so a policy change rebuilds it (below).
    private var dashboardThemeSignature = DashboardTheme.DEFAULT
    private var customView: View? = null                        // active onShowCustomView (fullscreen) view
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // --- long-run reliability state (all touched on the main thread only) ---
    private val main = Handler(Looper.getMainLooper())
    // Camera trial: the permission dialog may only be raised from an observably resumed activity, and
    // AndroidX reports RESUMED only after onResume returns; this gate holds a signal that lands too
    // early or while paused and delivers it at the lifecycle's own ON_RESUME.
    private val cameraPromptDelivery = io.panelassistant.android.camera.CameraPromptDelivery { requestCameraPermissionIfNeeded() }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge = DashboardFrontendBridge(main, activityScope, object : DashboardFrontendBridge.Host {
        override fun rendererCurrent(generation: Long) = this@DashboardActivity.rendererCurrent(generation)
        override fun routeCurrent() = rendererRoute?.let { HaConnectionRoutes.isCurrent(activityConfig, it) } == true
        override fun currentWeb() = web
        override fun entityFilterLease() = entityGate.entityFilterLease
        override fun onConnectionStatus(
            event: ExternalBusProtocol.ConnectionEvent,
            generation: Long,
            session: ExternalBusController.Session,
        ) = this@DashboardActivity.onConnectionStatus(event, generation, session)
        override fun onThemeUpdate(generation: Long, session: ExternalBusController.Session) =
            captureDashboardTheme(generation, session)
        override fun onAuthRejected(generation: Long) = this@DashboardActivity.onAuthRejected(generation)
        override fun openConfigScreen() {
            startActivity(Intent(this@DashboardActivity, ConfigActivity::class.java).putExtra("path", "/configure"))
        }
    })
    private var newIntentJob: Job? = null
    private val rendererGate = RendererGenerationGate()
    private val wakeMediaRecovery = WakeMediaRecoveryGate()
    private var rendererGeneration = 0L
    private var activityOwner = 0L
    private var dashboardIsTopResumed = false
    private var conn: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRecovery: NetworkRecoveryGate? = null
    // Set in onDestroy. Bridge/network callbacks marshal onto the main thread, so one of their posts can
    // land AFTER onDestroy's removeCallbacksAndMessages and re-arm the self-perpetuating watchdog on a
    // dead activity. Every posted handler checks this first so nothing runs (or re-schedules) post-destroy.
    @Volatile private var destroyed = false
    private var rendererRoute: HaConnectionRoute? = null
    private val rendererPowerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HaConnectionRoutes.EPOCH_KEY) runOnUiThread {
            if (!destroyed && ::activityConfig.isInitialized && BuiltinDashboard.ownsActivity(activityOwner)) {
                compatibilityJob?.cancel()
                compatibilityCheckingOwner = null
                compatibilityReadyUrl = null
                compatibilityAttempts.invalidate()
                invalidateHomeDashboardResolution()
                teardownWeb()
                buildAndLoad(activityConfig)
            }
        }
        if (key == "dashboard_network_warning") runOnUiThread { if (!destroyed) redrawLifecycleBar() }
        if (key == "prevent_idle_dim") {
            runOnUiThread {
                if (!destroyed && ::activityConfig.isInitialized) applyRendererScreenPolicy()
            }
        }
        // A persisted theme change has to reach whatever is on the display NOW. Without this only a
        // SYSTEM uiMode change redrew anything, so a panel switched between light and dark from its own
        // settings left an installed compatibility or recovery screen on the old palette and artwork —
        // and the two WebView-drawn pages bake their colours into static HTML, so they stayed stale
        // even where the system change did redraw the native ones.
        // The Ambient verdict and auto-brightness both move the effective theme without touching
        // `dashboard_theme` itself, so they redraw the same surfaces.
        if (key == "dashboard_theme_dark" || key == "dark_mode" || key == "dashboard_theme" ||
            key == "dashboard_theme_ambient_dark" || key == "auto_brightness"
        ) {
            runOnUiThread { if (!destroyed) statusScreens.convergeStatusTheme() }
        }
        // Camera trial: the runtime permission is requested only once the setting is on,
        // and only as a direct result of that switch turning on — never before, never as a side effect.
        // camera_enabled needs no branch here: the service's camera owner republishes
        // CameraPermissionPrompt on that change, asynchronously on its own lane, and the prompt's
        // listener — registered below while this activity is resumed — is what raises the dialog.
        // Asking on the raw preference change raced that publication and could see nothing to ask.
    }

    // Stored so onDestroy can clear it by identity — an old instance destroyed after a new one's onCreate
    // must not wipe the new instance's registration (see BuiltinDashboard.clearScreenListener).
    private val screenListener: (Boolean) -> Unit = { awake -> runOnUiThread { onScreenChanged(awake) } }
    private val rendererScreenHold = windowRendererScreenHold(this, { activityConfig.preventIdleDim }) { Log.i(TAG, it) }
    // Frontend-handshake watchdog: the page "loading" (onPageFinished) is NOT health — the frontend JS
    // app must post `connection-status: connected` on the external bus. Until it does, we retry with
    // backoff (a panel has no buttons to press). This catches the "loaded onto a blank/dead frontend"
    // class that onPageFinished and the crash budget both miss. The watchdog runs ONLY while the screen is
    // awake: a screen-off pauses JS timers, so a paused WebView could never complete the handshake and the
    // watchdog would just reload-loop all night behind a dark panel.
    /**
     * Whether the Home Assistant frontend is connected right now.
     *
     * The setter publishes to [RendererAdmissionRuntime] so `/api/v1/status`, `/api/v1/diag` and the
     * info card all learn it. It is a SETTER rather than a call beside each assignment on purpose:
     * five sites write this field (connect, disconnect, page start, teardown, destroy) and a
     * hand-maintained list of them would be one edit away from a surface that claims a dashboard is
     * up after it went down. The property initializer assigns the backing field directly, so the
     * pre-`onCreate` value never publishes against an unacquired lease.
     */
    private var themeObservationEpoch = 0L
    private var frontendConnected = false
        set(value) {
            // A pending evaluateJavascript callback belongs to the connection that started it. A
            // disconnect, page replacement or teardown must invalidate that callback before the
            // runtime clears its effective-theme observation, or the stale result can republish it.
            if (!value) themeObservationEpoch++
            field = value
            if (RendererAdmissionRuntime.setFrontendConnected(activityOwner, value) && !destroyed) {
                redrawLifecycleBar()
            }
        }
    private val retryPolicy = DashboardRetryPolicy()
    private var clearedThisLoad = false
    private val watchdog = Runnable { onWatchdogTimeout() }
    // After a screen-off reload (memory shed), let the fresh page load with timers LIVE for a settle
    // window, then freeze — so we never reload into a frozen WebView, but also never leave it churning
    // behind a dark screen forever. Fires only if the frontend hasn't already connected (which freezes early).
    private val darkSettle = Runnable {
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !screenAwake) {
            if (!frontendConnected) Log.w(TAG, "dark settle: the dashboard did not connect within ${DARK_SETTLE_MS}ms — freezing until the screen wakes")
            web?.let { it.onPause(); it.pauseTimers() }
        }
    }
    // Time-based memory ceiling. onTrimMemory's RUNNING_* levels are deprecated + never delivered on
    // Android 14 (the fleet), so a periodic check is the portable ceiling: reload invisibly at the next
    // screen-off past RELOAD_INTERVAL_MS, or force a (brief, visible) reload past RELOAD_HARD_MS so a
    // never-sleeping panel still sheds accreted WebView memory ~daily.
    private val periodicCheck = Runnable { onPeriodicCheck() }
    private var lastFullLoadAt = 0L
    private var reloadPending = false
    private var screenAwake = true
    private var compatibilityJob: Job? = null
    private var compatibilityCheckingOwner: DashboardV2CompatibilityOwner? = null
    private var compatibilityReadyUrl: String? = null
    private var homeDashboardJob: Job? = null
    private var homeDashboardCheckingOwner: HomeDashboardResolutionOwner? = null
    private var homeDashboardResolution: OwnedHomeDashboardResolution? = null
    // Deliberate-navigation generation (see noteDeliberateDashboardNavigation) and the generation at
    // which the provisional cached page was chosen; correction applies only while they still match.
    private var dashboardNavigationEpoch = 0L
    private var provisionalHomeDashboardEpoch: Long? = null
    // Full dashboard route the app itself last aimed the page at. Frontend-observed navigation away
    // from it is the user moving, and outranks a pending cache correction.
    private var appNavigatedDashboardRoute: String? = null
    private val homeDashboardAttempts = HomeDashboardResolutionAttemptGate()
    private val admissionRetryPolicy = AdmissionRetryPolicy()
    private val statusScreens = DashboardStatusScreens(this, main, activityScope, admissionRetryPolicy, object : DashboardStatusScreens.Host {
        override val activityOwner get() = this@DashboardActivity.activityOwner
        override val destroyed get() = this@DashboardActivity.destroyed
        override val hasWeb get() = web != null
        override fun teardownWeb() = this@DashboardActivity.teardownWeb()
        override var root: FrameLayout?
            get() = this@DashboardActivity.root
            set(value) { this@DashboardActivity.root = value }
        override fun detachLifecycleBar() = this@DashboardActivity.detachLifecycleBar()
        override fun activeNetworkPresent() = conn?.activeNetwork != null
        override fun retryAdmission(resetBackoff: Boolean) = this@DashboardActivity.retryAdmission(resetBackoff)
        override fun onAdmissionRetryDue() {
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
            Log.i(TAG, "renderer admission auto-retry firing")
            // A provisional cached dashboard is already rendering: retry the live resolution behind it
            // without invalidating what the user is watching. Everything else keeps the original
            // clear-and-rebuild retry.
            if (homeDashboardResolution?.confirmed == false) {
                buildAndLoad(Config(this@DashboardActivity))
            } else {
                retryAdmission(resetBackoff = false)
            }
        }
        // No native status screen is installed, so the only ha-paneld-owned surfaces that can be on
        // screen are the two pages drawn into the dashboard WebView. Re-issue whichever is showing.
        override fun redrawCoverPage() {
            if (authLatched) renderAuthLatchPage() else if (interstitialShown) reissueReconnecting()
        }
    })
    private val entityGate = DashboardEntityGate(this, main, activityScope, statusScreens, object : DashboardEntityGate.Host {
        override val activityOwner get() = this@DashboardActivity.activityOwner
        override val destroyed get() = this@DashboardActivity.destroyed
        override val screenAwake get() = this@DashboardActivity.screenAwake
        override fun buildAndLoad(config: Config) = this@DashboardActivity.buildAndLoad(config)
        override fun teardownWeb() = this@DashboardActivity.teardownWeb()
    })

    /** Endpoint the on-panel Home Assistant sign-in is currently showing, so a relaunch does not restart it. */
    private var signInShownForUrl: String? = null

    /** Quiet retries before the sign-in surface declares itself unavailable — its start page is served
     *  by the panel's own HTTP server, which restarts briefly during config reconfigures. */
    private var signInLoadRetries = 0

    private val compatibilityAttempts = DashboardV2AttemptGate()
    // Auth-failure latch. A terminal login-settings rejection (refresh token/client id, repeated
    // auth-invalid from the frontend) must not become an infinite reload loop on an unattended panel —
    // no unchanged retry can repair it. Latch: stop the retry machinery and show a clear on-panel
    // message naming the fix. Unlatched by a new load (reload/navigate, config change) or a
    // successful connect. Transient failures (HA down) never count toward the latch.
    private var authInvalids = 0
    private var refreshRejects = 0
    private var authLatched = false
        set(value) { field = value; syncWebCover() }
    // Idle return-to-home: after N minutes with no touch (configurable, 0 = off), navigate the frontend
    // back to the home dashboard via the bus — cheaper than a reload, bounds history growth from casual
    // navigation, and snaps a wandering kiosk back where it belongs.
    private var lastTouchAt = 0L
    private val idleCheck = Runnable { onIdleCheck() }
    private val idleUnresolvedNotice = DashboardIdleReturnPolicy.UnresolvedNotice()
    // Last light pull-to-refresh — a second pull inside the window escalates to a full hard reload.
    private var lastLightRefreshAt = 0L
    // True while the branded "Reconnecting…" page has replaced a failed main-frame load. Every reload
    // path must then loadUrl() the real dashboard rather than reload() (which would reload the
    // interstitial itself). Cleared on any real load or connect.
    private var interstitialShown = false
        set(value) { field = value; if (!value) reconnectingSinceMs = 0L; syncWebCover() }
    /** When the reconnecting cover first went up, for its "unreachable for" hero; 0 while it is down. */
    private var reconnectingSinceMs = 0L
    // The cover over the WebView: up while either flag above holds, down once neither does.
    private val webCover by lazy { DashboardWebCover(this, main) }
    private fun syncWebCover() { if (!authLatched && !interstitialShown) webCover.hide() }
    private fun showWebCover(fill: (StatusSurface) -> Unit) =
        webCover.show(swipe?.parent as? ViewGroup, StatusSurface.darkFor(this, Config(this)), fill)
    // The main-frame document the dashboard WebView is actually showing (onPageCommitVisible), or null
    // before its first. A retry decides reload() versus a fresh load from this; see retryNeedsFreshLoad.
    private val shownPage = ShownPageTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (maintenanceFence.stop(this)) return
        // Home stays put on Back: Android 11 finished this task root, dropping the panel onto Shelly's Stargate.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })
        supportActionBar?.hide()
        setContentView(TextView(this).apply {
            setText(R.string.preparing_dashboard)
            gravity = android.view.Gravity.CENTER
        })
        // Android 14's HOME role resolves at package granularity when one package exposes multiple HOME
        // activities: set-home-activity can report success for AdminLauncherActivity yet still resolve
        // DashboardActivity. Route an actual HOME intent according to the explicit Launcher app policy;
        // service/watchdog starts are component-explicit and therefore continue to foreground the dashboard.
        activityScope.launch {
            val config = readActivityStateOffMain {
                Config(this@DashboardActivity).also { config ->
                    // Android may launch this HOME activity directly after a package replacement,
                    // bypassing MainActivity. Repair HOME before the service can crash.
                    runCatching {
                        SystemController(AndroidSystemEnv(this@DashboardActivity)).applyLauncherHomePolicy(
                            config.launcherPackage, config.dashboardPackage, config.builtInRendererReady(),
                        )
                    }.onFailure { Log.w(TAG, "dashboard HOME policy apply failed", it) }
                }
            }
            if (!destroyed && !isFinishing) {
                // This visible start avoids arming Oreo's foreground deadline during cold class loading.
                PaneldService.start(this@DashboardActivity, fromVisibleActivity = true)
                initializeRenderer(config)
            }
        }
    }

    private suspend fun initializeRenderer(config: Config) {
        if (shouldRouteDashboardHomeToAdmin(config.launcherPackage, packageName, intent?.action, intent?.categories)) {
            Log.i(TAG, "HOME invoked with Panel admin selected — opening the admin launcher")
            fallbackToLauncher()
            return
        }
        activityOwner = BuiltinDashboard.acquireActivityOwner()
        if (dashboardIsTopResumed) {
            BuiltinDashboard.setActivityForeground(activityOwner, true)
        }
        if (!config.builtInRendererReady()) {
            // A configured URL with no credential is the one not-ready state that can fix itself: the
            // on-panel Home Assistant sign-in produces the missing token. Bouncing here instead is what
            // made first-run unfinishable from the panel — the sign-in screen lives past this gate, so
            // the gate was refusing entry to the only thing that could satisfy it.
            if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
                Log.i(TAG, "Home Assistant URL set without credentials — continuing to the on-panel sign-in")
            } else {
                // We're a HOME activity: never finish to a blank home. With no URL/auth to render, hand off
                // to ha-paneld's first-run QR/configure surface, not the admin launcher grid. A clean install
                // must be usable from the physical panel without ADB/debug handholding.
                Log.w(TAG, "built-in renderer not ready — opening the first-run configure surface instead")
                fallbackToFirstRunSurface()
                return
            }
        }
        var filterEpoch = entityGate.filterPreparationEpoch
        var prepared = entityGate.prepareEntityFilter(config)
        while (!destroyed && filterEpoch != entityGate.filterPreparationEpoch) {
            filterEpoch = entityGate.filterPreparationEpoch
            prepared = entityGate.prepareEntityFilter(config)
        }
        io.panelassistant.android.i18n.CatalogueLoader.assets(this).prepareNative(config.uiLanguage)
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        // onNewIntent may have arrived while preparation was suspended, before activityConfig existed.
        // Its epoch forced a fresh preparation; honor its latest HOME and readiness state as well.
        if (shouldRouteDashboardHomeToAdmin(config.launcherPackage, packageName, intent?.action, intent?.categories)) {
            fallbackToLauncher()
            return
        }
        if (!config.builtInRendererReady() && !haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
            fallbackToFirstRunSurface()
            return
        }
        activityConfig = config
        activityConfig.registerChangeListener(rendererPowerListener)
        // A resume during the suspended preparation returned early: catch up foreground (else ~300 s of dashboard_background) and the voice overlays.
        BuiltinDashboard.setActivityForeground(activityOwner, dashboardIsTopResumed)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) io.panelassistant.android.assist.VoiceOverlays.show(this)
        // The service's camera owner publishes the prompt state on its own lane; the listener posts
        // to main (never inline) and the delivery gate defers to the lifecycle's ON_RESUME, which is
        // the first point AndroidX reports RESUMED. Registering fires at once if an ask is already due.
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> cameraPromptDelivery.onResumed()
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        cameraPromptDelivery.onPaused()
                        io.panelassistant.android.camera.CameraPermissionPrompt.activityPaused()
                    }
                    else -> Unit
                }
            },
        )
        io.panelassistant.android.camera.CameraPermissionPrompt.setListener {
            main.post { if (!destroyed) cameraPromptDelivery.onSignal() }
        }
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            cameraPromptDelivery.onResumed()
            NativeLocale.apply(config.uiLanguage)
            applyFullscreen()
            applyZoom()
        }
        applyRendererScreenPolicy()
        entityGate.configureEntityFilter(config, prepared)
        // Freeze the WebView when the panel screen is off (CPU/heat/memory), and reload the moment
        // connectivity returns if the frontend isn't connected — registered for the activity's lifetime.
        BuiltinDashboard.setScreenListener(screenListener)
        rendererScreenHold.attach { apply ->
            runOnUiThread { if (!destroyed && ::activityConfig.isInitialized) apply() }
        }
        BuiltinDashboard.setHaLifecycleListener(haLifecycleListener)
        // Adopt the CURRENT screen state — an instance created while the panel is dark (a relaunch at
        // night) must not assume the screen is on: it would arm the handshake watchdog against a page
        // that's about to be frozen, and never freeze the WebView until the next real transition.
        screenAwake = BuiltinDashboard.screenAwakeNow
        val networkAvailable = registerNetworkCallback()
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
        lastTouchAt = SystemClock.elapsedRealtime()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (networkAvailable) buildAndLoad(config) else statusScreens.showWaitingForNetwork()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) {
            onAdmissionVisibilityChanged(true)
        }
        // Created dark: freeze after the settle window even if no dashboard load starts (the on-panel
        // sign-in view never calls onLoadStarted). A load that does start re-arms this window itself.
        if (!screenAwake) main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /** Any touch (the WebView consumes them, but the activity sees them first) resets the idle clock. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            PanelTouchObserver.shared(this).noteActivityTouch()
            BuiltinDashboard.noteTouch()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        destroyed = true
        compatibilityAttempts.invalidate()
        homeDashboardAttempts.invalidate()
        activityScope.cancel()
        if (::activityConfig.isInitialized) activityConfig.unregisterChangeListener(rendererPowerListener)
        io.panelassistant.android.camera.CameraPermissionPrompt.setListener(null)
        wakeMediaRecovery.close()
        rendererGate.close()
        entityGate.stopLease()
        BuiltinDashboard.releaseActivityOwner(activityOwner)
        BuiltinDashboard.clearScreenListener(screenListener)
        rendererScreenHold.detach()
        BuiltinDashboard.clearHaLifecycleListener(haLifecycleListener)
        netCallback?.let { cb -> runCatching { conn?.unregisterNetworkCallback(cb) } }
        main.removeCallbacksAndMessages(null)
        teardownWeb()
        super.onDestroy()
    }

    /** Fully release the WebView so a long-lived process doesn't leak it: stop background loading,
     *  detach the JS bridge, destroy. `resumeTimers()` is process-global and pauseTimers() may have been
     *  called on a screen-off — without this, an activity destroyed while dark would leave JS timers
     *  frozen for every future WebView in this forever process (a never-blank violation). */
    private fun teardownWeb() {
        rendererRoute = null
        signInShownForUrl = null // the sign-in WebView is going away; a later call must rebuild it
        wakeMediaRecovery.invalidate()
        rendererGate.invalidate()
        bridge.teardown()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(darkSettle)
        detachLifecycleBar()
        customView?.let { view -> runCatching { root?.removeView(view) } }
        runCatching { customViewCallback?.onCustomViewHidden() }
        customView = null
        customViewCallback = null
        web?.let { w ->
            runCatching { w.resumeTimers() }
            bridge.removeListeners(w)
            runCatching { w.loadUrl("about:blank") }
            (w.parent as? ViewGroup)?.removeView(w)
            runCatching { w.destroy() }
        }
        web = null
        shownPage.forget()
        swipe = null
        frontendConnected = false
    }

    private fun rendererCurrent(generation: Long, view: WebView? = null): Boolean =
        !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
            rendererGate.owns(generation) && (view == null || web === view)

    private fun rotateBusDocument(view: WebView, config: Config, generation: Long): Boolean = try {
        bridge.beginDocument(view, config, generation)
        true
    } catch (error: ExternalV2BridgeUnavailable) {
        Log.e(TAG, "secure V2 listener attachment failed", error)
        statusScreens.showBlockedAdmissionScreen(
            getString(R.string.home_assistant_stopped_loading),
            getString(R.string.home_assistant_stopped_loading_detail),
            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
        )
        false
    }

    /** Single scheduling authority for the post-reload settle window: cancel any pending settle and
     *  re-arm it [DARK_SETTLE_MS] out. See [darkSettle] for why a screen-off reload runs live for a
     *  window before freezing. */
    private fun scheduleDarkSettle() {
        main.removeCallbacks(darkSettle)
        main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /**
     * Screen on/off fan-out. Screen ON → resume rendering + JS timers, and re-arm the handshake watchdog
     * if the frontend isn't connected (recover a page that broke while frozen). Screen OFF → pause
     * rendering and disarm the watchdog (no handshake retries while timers are frozen); then either shed
     * memory with an invisible reload (kept live through a settle window, see [darkSettle]) if one is due,
     * or freeze JS timers. `pauseTimers()` is process-global, which is fine — the dashboard is our only
     * live WebView.
     */
    private fun onScreenChanged(awake: Boolean) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        val wasAwake = screenAwake
        screenAwake = awake
        entityGate.onScreenChanged(awake)
        val w = web ?: return
        if (awake) {
            main.removeCallbacks(darkSettle)
            w.resumeTimers(); w.onResume()
            lastTouchAt = SystemClock.elapsedRealtime() // a wake implies presence — don't instantly snap home
            if (!frontendConnected && !authLatched) armWatchdog(INITIAL_HANDSHAKE_MS)
            val frontendHealthy = frontendConnected && !authLatched && !interstitialShown
            if (!wasAwake && awake) {
                if (frontendHealthy) armWakeMediaRecovery(w, rendererGeneration)
                else if (!authLatched && !interstitialShown) wakeMediaRecovery.defer(rendererGeneration)
            }
        } else {
            wakeMediaRecovery.invalidate()
            main.removeCallbacks(watchdog) // a paused WebView can't complete the handshake — don't loop
            when (screenOffStep(frontendConnected, authLatched, reloadDue())) {
                ScreenOffStep.FREEZE -> { w.onPause(); w.pauseTimers() }
                // A load still in flight: leave the WebView live, untouched, so it can draw and connect.
                // onConnectionStatus freezes it the moment it connects, darkSettle at the end of the window.
                ScreenOffStep.SETTLE -> {
                    Log.i(TAG, "screen off before the dashboard connected — keeping the load live for ${DARK_SETTLE_MS}ms")
                    scheduleDarkSettle()
                }
                ScreenOffStep.QUIET_RELOAD -> {
                    // Reload to shed memory, but DON'T pause timers yet — a paused WebView can't load. Let it
                    // run; onConnectionStatus freezes it the moment it connects, or darkSettle freezes it after
                    // the settle window if it never does. Either way it doesn't churn all night.
                    w.onPause()
                    reloadPending = false
                    lastFullLoadAt = SystemClock.elapsedRealtime()
                    doReloadNoWatchdog("quiet reload (screen off, due)")
                    scheduleDarkSettle()
                }
            }
        }
    }

    /** WebView resume does not guarantee that HA's existing WebRTC cards rebuild a transport. After a
     * real wake, sample only visible playing/autoplay media and allow one full-page recovery if none of
     * it advances. Every callback is owned by both the renderer generation and the wake cycle. */
    private fun armWakeMediaRecovery(
        w: WebView,
        generation: Long,
        ticket: WakeMediaRecoveryTicket = wakeMediaRecovery.begin(generation),
    ) {
        main.postDelayed({
            if (!wakeMediaCurrent(ticket, w)) return@postDelayed
            w.evaluateJavascript(WakeMediaRecoveryScript.arm(ticket.cycle)) { result ->
                if (!wakeMediaCurrent(ticket, w)) return@evaluateJavascript
                when (wakeMediaRecovery.onArmResult(ticket, javascriptIntResult(result) ?: -1)) {
                    WakeMediaRecoveryAction.INSPECT ->
                        main.postDelayed({ inspectWakeMedia(ticket, w) }, WAKE_MEDIA_SAMPLE_MS)
                    WakeMediaRecoveryAction.NONE, WakeMediaRecoveryAction.RELOAD -> Unit
                }
            }
        }, WAKE_MEDIA_SETTLE_MS)
    }

    private fun inspectWakeMedia(ticket: WakeMediaRecoveryTicket, w: WebView) {
        if (!wakeMediaCurrent(ticket, w)) return
        w.evaluateJavascript(WakeMediaRecoveryScript.inspect(ticket.cycle)) { result ->
            if (!wakeMediaCurrent(ticket, w)) return@evaluateJavascript
            if (wakeMediaRecovery.onInspectResult(ticket, javascriptIntResult(result) == 1) == WakeMediaRecoveryAction.RELOAD) {
                Log.w(TAG, "visible dashboard media did not resume after screen wake — reloading once")
                BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime())
                doReload("visible media stalled after screen wake")
            }
        }
    }

    private fun wakeMediaCurrent(ticket: WakeMediaRecoveryTicket, w: WebView): Boolean =
        screenAwake && wakeMediaRecovery.owns(ticket) && rendererCurrent(ticket.rendererGeneration, w)

    /** A reload is worthwhile now if memory pressure asked for one, or it's been a long time since the
     *  last full load (WebView memory accretes over days). */
    private fun reloadDue(): Boolean =
        reloadPending || (SystemClock.elapsedRealtime() - lastFullLoadAt > RELOAD_INTERVAL_MS)

    /** Portable memory ceiling (see [periodicCheck]): reload invisibly at a screen-off past the interval,
     *  or force a brief visible reload past the hard cap so a panel that never sleeps still sheds memory. */
    private fun onPeriodicCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (authLatched) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        // The memory-ceiling reload measures idle from lastFullLoadAt, which the on-panel sign-in never
        // sets because it performs no dashboard load. On a panel that has been up for longer than the
        // interval that reads as "idle for the whole uptime", so the first check would reload — throwing
        // away a sign-in the user is part-way through, and reintroducing exactly the reload churn this
        // screen exists to end. Nothing here needs shedding: the sign-in page is a single small form.
        if (signInShownForUrl != null) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        val idle = SystemClock.elapsedRealtime() - lastFullLoadAt
        when {
            idle < RELOAD_INTERVAL_MS -> {}
            !screenAwake -> {
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("periodic (screen off, ${idle / 3_600_000}h idle)")
                scheduleDarkSettle()
            }
            idle >= RELOAD_HARD_MS -> doReload("periodic hard cap (${idle / 3_600_000}h idle)")
            else -> reloadPending = true // visible now — wait for the next screen-off to reload invisibly
        }
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (maintenanceFence.stop(this)) return
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // The sign-in form has no accreted memory worth shedding, and reloading it would discard a
        // part-entered Home Assistant login. Leave it alone; it is short-lived by nature.
        if (signInShownForUrl != null) return
        // UI_HIDDEN is a routine lifecycle signal, NOT memory pressure — it's delivered on every
        // screen-off (activities stop when the display sleeps), so reacting to it made every screen-off
        // full-reload the frontend, defeating the 6-hour quiet-reload ceiling in onScreenChanged.
        if (level == TRIM_MEMORY_UI_HIDDEN) return
        // Any real trim signal means shed the WebView's accreted memory. Gate at RUNNING_LOW so we catch
        // BOTH the pre-14 foreground RUNNING_* levels AND the pressure levels Android 14 actually
        // delivers (BACKGROUND / MODERATE / COMPLETE, all ≥ RUNNING_LOW's value) — the previous
        // RUNNING_MODERATE..CRITICAL window was dead code on the fleet's Android-14 panels.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            reloadPending = true
            // If we're not the visible dashboard, reload right now rather than risk an OOM renderer kill.
            if (!screenAwake || !BuiltinDashboard.foreground) {
                reloadPending = false
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("memory pressure L$level")
                if (!screenAwake) scheduleDarkSettle()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (maintenanceFence.stop(this)) return
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !authLatched &&
            (!screenAwake || !BuiltinDashboard.foreground)) {
            lastFullLoadAt = SystemClock.elapsedRealtime()
            doReloadNoWatchdog("onLowMemory")
            if (!screenAwake) scheduleDarkSettle()
        }
    }

    /** Hand off to ha-paneld's admin launcher and finish — the never-strand fallback for a missing
     *  WebView or a crash-looping renderer. */
    private fun fallbackToLauncher() {
        statusScreens.cancelAdmissionAutoRetry()
        runCatching {
            startActivity(Intent(this, AdminLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "admin-launcher fallback failed: ${it.message}") }
        finish()
    }

    /** Hand off to MainActivity's wordmark + QR/configure page. This is the required first visible
     *  surface for a fresh install or cleared configuration; the admin launcher is only for explicit
     *  panel-admin entry and crash/recovery administration. */
    private fun fallbackToFirstRunSurface() {
        statusScreens.cancelAdmissionAutoRetry()
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "first-run surface fallback failed: ${it.message}") }
        finish()
    }

    /** Allow a renderer rebuild if within budget. The budget lives in [BuiltinDashboard] (process-
     *  global), NOT here: fallbackToLauncher() finishes this instance and the kiosk/watchdog return
     *  loop starts a fresh one — a per-instance counter would reset every relaunch, turning a reliably
     *  crashing page into an infinite fallback/relaunch churn. Exhaustion engages the renderer latch
     *  ([BuiltinDashboard.rendererLatched]), which suppresses automatic relaunches for a cooldown. */
    private fun allowRebuild(): Boolean =
        BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())

    /** A singleTask relaunch reaching the live renderer means one of three things, disambiguated here:
     *  a **navigate** ([BuiltinDashboard.navPath] set — one-shot, consumed), an explicit **reload**
     *  ([BuiltinDashboard.consumeReloadRequest] — set by reloadDashboard / a credential change), or a
     *  plain **bring-to-foreground** (the kiosk/watchdog return loops). A healthy foregrounded page is
     *  left untouched — snapping back from Recents must not blank the dashboard with a full reload. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (maintenanceFence.stop(this)) return
        setIntent(intent)
        entityGate.supersedePreparation()
        newIntentJob?.cancel()
        newIntentJob = activityScope.launch { handleNewIntent(intent) }
    }

    private suspend fun handleNewIntent(intent: Intent?) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (!::activityConfig.isInitialized) return // the changed epoch makes initial preparation repeat
        val filterEpoch = entityGate.filterPreparationEpoch
        val config = activityConfig
        if (shouldRouteDashboardHomeToAdmin(config.launcherPackage, packageName, intent?.action, intent?.categories)) {
            Log.i(TAG, "HOME invoked with Panel admin selected — opening the admin launcher")
            fallbackToLauncher()
            return
        }
        if (!config.builtInRendererReady()) {
            // Same escape as onCreate. This path matters just as much: saving a Home Assistant URL from
            // the browser relaunches this singleTask activity, so a running panel arrives HERE rather
            // than in onCreate, and bouncing would undo the save the user just made.
            if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
                Log.i(TAG, "Home Assistant URL set without credentials — continuing to the on-panel sign-in")
            } else {
                // Same never-strand guard as onCreate: the URL was cleared while we were running — a load
                // would strand the built-in renderer without a usable authenticated route. Return to the
                // QR/configure surface so the user can repair the panel from a browser.
                Log.w(TAG, "built-in renderer no longer ready — opening the first-run configure surface instead")
                fallbackToFirstRunSurface()
                return
            }
        }
        val prepared = entityGate.prepareEntityFilter(config)
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || filterEpoch != entityGate.filterPreparationEpoch) return
        val normalizedUrl = config.haEffectiveUrl.trim().trimEnd('/')
        if (haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
            if (signInShownForUrl == normalizedUrl && web != null) {
                Log.i(TAG, "on-panel sign-in still pending — keeping the live sign-in WebView")
            } else {
                buildAndLoad(config)
            }
            return
        }
        // Credentials arrived while the on-panel sign-in was showing — typically because the user signed
        // in from a browser instead, but also provisioning or a token restore. The current WebView is the
        // bare sign-in page: its client only permits the OAuth navigation and it carries no dashboard
        // bridge, so the reuse path below would load the dashboard URL into it, HA would find no external
        // auth and bounce straight back to its own login, and the panel would appear to loop the sign-in
        // page forever. Tear it down and build the real renderer instead.
        if (signInShownForUrl != null && !haSignInPending(config.haUrl, config.haToken, config.haRefreshToken)) {
            Log.i(TAG, "credentials present — leaving the on-panel sign-in for the dashboard")
            BuiltinDashboard.consumeSupersededReload()
            activityConfig = config
            unlatchAuth("credentials arrived during sign-in")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            entityGate.configureEntityFilter(config, prepared)
            buildAndLoad(config)
            return
        }
        if (compatibilityReadyUrl != null && compatibilityReadyUrl != normalizedUrl) {
            Log.i(TAG, "Home Assistant endpoint changed — rebuilding and rechecking the V2 renderer")
            BuiltinDashboard.consumeSupersededReload()
            activityConfig = config
            compatibilityJob?.cancel()
            compatibilityCheckingOwner = null
            compatibilityReadyUrl = null
            compatibilityAttempts.invalidate()
            unlatchAuth("Home Assistant endpoint change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            entityGate.configureEntityFilter(config, prepared)
            buildAndLoad(config)
            return
        }
        val activeHomeDashboardOwner = homeDashboardResolution?.owner ?: homeDashboardCheckingOwner
        if (activeHomeDashboardOwner != null && activeHomeDashboardOwner != homeDashboardOwner(config)) {
            Log.i(TAG, "dashboard authority changed — resolving the authenticated dashboard list again")
            BuiltinDashboard.consumeSupersededReload()
            invalidateHomeDashboardResolution()
            unlatchAuth("dashboard authority change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            entityGate.configureEntityFilter(config, prepared)
            buildAndLoad(config)
            return
        }
        // The filter endpoint reloads this singleTask activity after committing. Document-start scripts
        // cannot be replaced in an existing WebView, so a filter-set change or learning-mode change
        // deliberately rebuilds only the WebView while keeping the foreground service and app process alive.
        val nextFilterSignature = prepared.signature
        if (nextFilterSignature != entityGate.entityFilterSignature) {
            Log.i(TAG, "entity instrumentation changed — rebuilding dashboard WebView")
            // The learner's reload lands here with its reason pending. Only it is a learning restart;
            // a hand edit of the filter or a settings change announces as a lone restart.
            statusScreens.announceDeliberateRestart(
                getString(R.string.optimizing_entities_restart),
                learning = BuiltinDashboard.consumeSupersededReload() == BuiltinDashboard.LEARNING_RELOAD_REASON,
            )
            unlatchAuth("entity-filter change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            entityGate.configureEntityFilter(config, prepared)
            buildAndLoad(config)
            return
        }
        // Same reason as the filter set above: the theme policy is carried by a document-start script,
        // which the WebView fixes at creation. Rebuilding is also what makes the change reversible —
        // the fresh script runs the hand-back transaction before Home Assistant's own bootstrap reads
        // the store, so the restored value is the one the frontend boots with.
        val nextThemeSignature = config.dashboardThemeEffective
        if (nextThemeSignature != dashboardThemeSignature) {
            Log.i(TAG, "dashboard theme policy changed — rebuilding dashboard WebView")
            BuiltinDashboard.consumeSupersededReload()
            statusScreens.announceDeliberateRestart(getString(R.string.applying_dashboard_theme))
            unlatchAuth("theme policy change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            entityGate.configureEntityFilter(config, prepared)
            buildAndLoad(config)
            return
        }
        val nav = BuiltinDashboard.consumeNavPath()
        val reload = BuiltinDashboard.consumeReloadRequest()
        val reloadReason = BuiltinDashboard.consumeReloadReason()
        if (reload) statusScreens.announceDeliberateRestart(reloadReason)
        val w = web
        val healthy = w != null && frontendConnected && !authLatched && !interstitialShown
        if (healthy && !reload) bridge.updateKioskPreference(config.dashboardNativeKiosk)
        if (nav == null && !reload && healthy) return   // foreground-only: page is fine, nothing to do
        if (nav != null && !reload && healthy) {
            // Navigate on a healthy page: an instant bus re-navigate (same as idle-return), not a full
            // page load — the JS bundle + websocket stay live.
            Log.i(TAG, "navigate -> /$nav (bus)")
            sendBusNavigate(nav)
            return
        }
        unlatchAuth("new load") // a deliberate reload/navigate (or a config change) is the retry consent
        retryPolicy.reset()
        interstitialShown = false
        if (w == null) { buildAndLoad(config); return }  // defensive: relaunched with no WebView built
        // Re-apply force-dark (only set at WebView creation otherwise) and, on the no-system-dark-mode
        // panels, write the panel's dark_mode into HA's own per-device theme store before the fresh
        // load — that store is what actually re-renders HA (see selectedThemeJs; force-dark alone is a
        // no-op on HA). The write must precede loadUrl: localStorage is synchronous and origin-scoped,
        // so the reloaded frontend boots straight into the new scheme.
        applyDashboardForceDark(w)
        val targetPath = nav ?: resolvedHomeDashboard(config)
            ?: return readmitForHomeDashboard(if (reload) "reload" else "relaunch")
        val url = ExternalAuthProtocol.dashboardUrl(config.haEffectiveUrl, targetPath)
        noteDeliberateDashboardNavigation()
        noteAppNavigationTarget(targetPath)
        val generation = rendererGeneration
        // Same suppression as the seed in createWebView: while Dark/Light owns the `dark` field, a
        // dark_mode toggle must not write it as well. The policy's own document-start script re-applies
        // on the load this reload is about to start, so nothing is lost by staying out of the way.
        if (android.os.Build.VERSION.SDK_INT < 29 && !DashboardTheme.forces(config.dashboardThemeEffective)) {
            // The theme write must COMPLETE before the navigation — evaluateJavascript is async (queued
            // to the JS thread), and a loadUrl issued right after can tear the page down first, losing
            // the write. The result callback runs after evaluation, on the UI thread.
            w.evaluateJavascript(ExternalAuthProtocol.selectedThemeJs(Config(this).darkMode, onlyIfAbsent = false)) {
                if (rendererCurrent(generation, w)) {
                    if (!rotateBusDocument(w, config, generation)) return@evaluateJavascript
                    bridge.expectPageStart(url)
                    w.loadUrl(url)
                }
            }
        } else {
            if (!rotateBusDocument(w, config, generation)) return
            bridge.expectPageStart(url)
            w.loadUrl(url)
        }
        onLoadStarted()
    }

    // --- frontend-handshake watchdog + reconnect ---

    /** Called after every page load that should be health-checked (initial, navigate, watchdog reload,
     *  network-regain reload): reset the connected state + arm the handshake watchdog — but only while the
     *  screen is awake, since a paused WebView can't run the JS that completes the handshake. */
    private fun onLoadStarted() {
        wakeMediaRecovery.invalidate()
        frontendConnected = false
        clearedThisLoad = false
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordLoadStart(lastFullLoadAt) // TTI origin (first call since process start = cold)
        if (screenAwake) { armWatchdog(INITIAL_HANDSHAKE_MS); return }
        // A load issued while dark (a remote reload, a rebuild) may land in a WebView that an earlier
        // screen-off froze, where it can never connect. Run it live through the settle window instead;
        // the watchdog stays off until the screen wakes, so a dark panel still never reload-loops.
        main.removeCallbacks(watchdog)
        // resumeTimers() is process-wide: a superseded instance must not unfreeze the owner's page.
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        web?.let { it.onResume(); it.resumeTimers() }
        Log.i(TAG, "load started while the screen is dark — running it live for ${DARK_SETTLE_MS}ms")
        scheduleDarkSettle()
    }

    private fun armWatchdog(ms: Long) {
        if (destroyed) return
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, ms)
    }

    private fun onWatchdogTimeout() {
        // Never retry on a dead activity or latched — both runaway loops. While frozen (screen off) the
        // policy answers NONE: a paused page cannot complete the handshake, so that fire is not a miss.
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || frontendConnected || authLatched) return
        val step = retryPolicy.onWatchdogFired(screenAwake)
        if (step == HandshakeRecoveryStep.NONE) return
        if (bridge.handshakeTimedOut()) {
            statusScreens.showBlockedAdmissionScreen(
                getString(R.string.home_assistant_unresponsive),
                getString(R.string.home_assistant_unresponsive_detail),
                AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
            )
            return
        }
        if (step != HandshakeRecoveryStep.RELOAD) {
            escalateHandshakeRecovery(step)
            return
        }
        Log.w(TAG, "frontend handshake watchdog fired (no connection-status:connected) — reloading")
        // The reconnect grace expired: this load now owns recovery, so a later connection callback must
        // not revive the pre-timeout wake ticket against the replacement target.
        wakeMediaRecovery.invalidate()
        if (!reloadTarget()) return
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordRendererReload(lastFullLoadAt) // involuntary: handshake stalled
        BuiltinDashboard.recordLoadStart(lastFullLoadAt)      // warm TTI origin for the recovery load
        clearedThisLoad = false
        armWatchdog(retryPolicy.afterRetry())
    }

    /**
     * A reload has already failed [DashboardRetryPolicy.consecutiveMisses] times in a row, so do more
     * than reload. [HandshakeRecoveryStep.FRESH_LOAD] loads the dashboard target afresh into the held
     * WebView (a new bus and auth document, never `reload()`) and re-runs the authenticated dashboard
     * resolution behind it, which corrects the page if the answer changed. [HandshakeRecoveryStep.RECREATE_WEBVIEW]
     * replaces the WebView as well, through the same teardown and build a settings change uses. Neither
     * touches the crash budget or relaunches anything: the renderer crash path stays the only owner of
     * both.
     */
    private fun escalateHandshakeRecovery(step: HandshakeRecoveryStep) {
        val misses = retryPolicy.consecutiveMisses
        if (step == HandshakeRecoveryStep.FRESH_LOAD) {
            Log.w(
                TAG,
                "frontend handshake missed $misses times in a row — escalating: fresh dashboard resolution " +
                    "and load, then WebView recreation, with backoff",
            )
        }
        val config = Config(this)
        // The resolution the page is showing, if it still belongs to the current credential and path. It
        // goes back to provisional, so the resolution below refreshes it live instead of reusing it.
        val resolved = homeDashboardResolution
            ?.takeIf { it.owner == homeDashboardOwner(config) && it.resolution.path != null }
        val recreate = step == HandshakeRecoveryStep.RECREATE_WEBVIEW || resolved == null || web == null
        Log.w(
            TAG,
            "frontend handshake watchdog fired (miss $misses) — " +
                if (recreate) "recreating the dashboard WebView" else "fresh dashboard load",
        )
        // Same reason as the reload: this load now owns recovery.
        wakeMediaRecovery.invalidate()
        resolved?.let { homeDashboardResolution = it.copy(confirmed = false) }
        if (recreate) {
            // As at every other rebuild site: a surviving flag would make the new WebView's first load
            // error skip the reconnecting page and leave Chromium's own error page on the panel.
            interstitialShown = false
            teardownWeb()
            buildAndLoad(config)
        } else {
            loadCorrectedHomeDashboard()
            // A failed bus rotation already replaced the page with a blocked screen; resolving now would
            // build a renderer over it.
            if (web != null) {
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
                resolveHomeDashboardAndLoad(config)
            }
        }
        // No renderer means a native screen took over and owns its own recovery.
        if (web == null) return
        BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary: handshake stalled
        // The new load armed the cold-start window; keep it no shorter than the reload it replaced.
        armWatchdog(maxOf(INITIAL_HANDSHAKE_MS, retryPolicy.afterRetry()))
    }

    /** Fired from the external-bus `connection-status` message: the frontend telling us it connected or
     *  dropped its websocket. Posted onto the main thread, so it can race onDestroy — guard on [destroyed]. */
    private fun onConnectionStatus(
        event: ExternalBusProtocol.ConnectionEvent,
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!bridge.current(generation, session)) return
        if (event == ExternalBusProtocol.ConnectionEvent.CONNECTED) {
            frontendConnected = true
            BuiltinDashboard.recordConnected(SystemClock.elapsedRealtime()) // TTI: load-start → interactive
            // First-ever proven render: from here on, an unfinished setup journey is a REPAIR of a panel
            // that once worked, and the wizard words it that way instead of reading like a factory reset.
            if (::activityConfig.isInitialized && !activityConfig.setupEverCompleted) {
                activityConfig.setupEverCompleted = true
            }
            interstitialShown = false // real page demonstrably loaded
            statusScreens.clearReloadNotice()
            unlatchAuth("frontend connected") // auth demonstrably works — clear any stale latch + counters
            main.removeCallbacks(watchdog)
            retryPolicy.escalatedTo?.let { reached ->
                Log.i(
                    TAG,
                    "frontend handshake recovered after ${retryPolicy.consecutiveMisses} consecutive misses " +
                        "(escalated to ${reached.name.lowercase()})",
                )
            }
            retryPolicy.reset()
            wakeMediaRecovery.activateDeferred(generation)?.let { ticket ->
                web?.let { w -> armWakeMediaRecovery(w, generation, ticket) }
            }
            if (!clearedThisLoad) {
                // Drop the auth/redirect history entries so Back can't reach a stale login page, and
                // persist cookies so an unclean process death right after login doesn't lose the session.
                web?.clearHistory(); clearedThisLoad = true
                runCatching { CookieManager.getInstance().flush() }
            }
            captureDashboardTheme(generation, session)
            // If we connected while the screen is off (a screen-off memory reload), freeze now — the fresh
            // page is loaded + connected, so there's nothing left to do behind the dark screen.
            if (!screenAwake) { main.removeCallbacks(darkSettle); web?.let { it.onPause(); it.pauseTimers() } }
            Log.i(TAG, "frontend connected")
            bridge.connected(session)
        } else {
            val wasConnected = frontendConnected
            frontendConnected = false
            // auth-invalid is the frontend saying HA refused its token. One can be a race around a token
            // refresh; several in a row mean the credential is dead — latch instead of reload-looping.
            if (event == ExternalBusProtocol.ConnectionEvent.AUTH_INVALID && ++authInvalids >= AUTH_INVALID_LATCH) {
                latchAuthFailure("repeated auth-invalid")
                return
            }
            bridge.disconnected(session)
            // disconnected: give the frontend's own reconnect a grace window, then reload — but only
            // while awake (a frozen WebView can't reconnect; wake re-arms the handshake watchdog).
            if (screenAwake && !authLatched) {
                val delay = retryPolicy.connectionFailureDelay(wasConnected)
                Log.i(TAG, "frontend '${event.wireValue}' — arming retry watchdog in ${delay}ms (wasConnected=$wasConnected)")
                armWatchdog(delay)
            }
        }
    }

    /** Remember HA's own per-device theme so the native pre-WebView launch screen matches it on the
     * next boot, and publish the scheme the frontend is ACTUALLY rendering so `/api/v1/status` can say
     * when an explicit Home Assistant theme is overriding this panel's Dashboard theme. The JS returns
     * only booleans/null in a fixed two-letter shape, avoiding any localStorage contents in logs. */
    private fun captureDashboardTheme(
        generation: Long,
        session: ExternalBusController.Session,
    ) {
        if (!bridge.current(generation, session)) return
        // Theme-update events can arrive faster than WebView callbacks. Only the newest read from the
        // current connection may publish, so an older answer cannot overwrite a later HA choice.
        val observationEpoch = ++themeObservationEpoch
        web?.evaluateJavascript(ExternalAuthProtocol.THEME_OBSERVATION_JS) { result ->
            if (!bridge.current(generation, session) || !frontendConnected ||
                observationEpoch != themeObservationEpoch
            ) return@evaluateJavascript
            val observed = ExternalAuthProtocol.parseThemeObservation(result)
            when (observed.storedDark) {
                true -> Config(this).setDashboardThemeDark(true)
                false -> Config(this).setDashboardThemeDark(false)
                // An explicit absence is an observation too. Recording only true/false made the key
                // write-only, so a stored theme that later went away (or a forced one this panel just
                // handed back) kept outranking the system setting on every native screen.
                null -> if (observed.valid) Config(this).clearDashboardThemeDark()
            }
            RendererAdmissionRuntime.setEffectiveTheme(activityOwner, observed.effectiveDark)
        }
    }

    /** The token refresher got a terminal rejection from HA. One could be a freak intermediary event;
     *  two consecutive responses mean the unchanged login settings cannot recover — latch. */
    private fun onAuthRejected(generation: Long) {
        if (!rendererCurrent(generation) || authLatched) return
        if (++refreshRejects >= REFRESH_REJECT_LATCH) latchAuthFailure("login settings rejected by HA")
    }

    /** Stop the retry machinery and show a clear on-panel message naming the fix — a panel retrying a
     *  rejected login settings forever is churn with no exit, and a silent blank is a never-blank violation.
     *  The `:8888` health warnings surface the same state off-panel via [BuiltinDashboard.authLatched]. */
    private fun latchAuthFailure(why: String) {
        if (authLatched) return
        authLatched = true
        renderAuthLatchPage()
        Log.e(TAG, "auth latched ($why) — showing fix instructions, no further retries until reload/reconfig")
    }

    /**
     * Draw the terminal sign-in page, separately from latching.
     *
     * Latching is one-way and guards its own re-entry, so a theme change could not redraw this page
     * through it — the guard returned before any drawing happened and the page kept the palette and
     * artwork it was first built with.
     */
    private fun renderAuthLatchPage() {
        val stillLatched = authLatched
        if (!stillLatched) return
        BuiltinDashboard.setActivityAuthLatched(activityOwner, true)
        main.removeCallbacks(watchdog)
        web?.let(bridge::suspend)
        // Panel Assistant's own screen, and for the sharpest reason on any of them: the line names Home
        // Assistant, the browser behind it normally shows Home Assistant, and the decision being reported —
        // stop retrying — is entirely the panel's.
        blankWebDocument()
        val phoneUrl = configureQrPath(AdmissionOutcome.CREDENTIAL_REFUSED)?.let { path ->
            statusScreens.provenSignInUrl(path) { if (authLatched) renderAuthLatchPage() }
        }
        showWebCover { cover ->
            val notice = cover.notice
            cover.setBody(
                getString(R.string.notice_pill_action_needed),
                phoneUrl?.let { notice.qr(it, getString(R.string.config_qr_description, it), getString(R.string.notice_qr_sign_in_again)) },
                notice.line(getString(R.string.sign_in_rejected_title)),
                notice.footer(getString(R.string.sign_in_rejected_terminal_detail)),
                // The fix names the address, so it is offered only with the address it names.
                phoneUrl?.let { notice.footer(getString(R.string.sign_in_rejected_fix, it.substringBefore('#'))) },
            )
        }
    }

    /**
     * Replace whatever the WebView was drawing with an empty page in the panel's background, so neither a
     * stale dashboard nor Android's grey error page sits behind the cover or shows through a rebuild.
     */
    private fun blankWebDocument() {
        val background = statusPalette(StatusSurface.darkFor(this, Config(this))).background
        web?.loadDataWithBaseURL(null, "<!doctype html><html><body style=\"background:$background;margin:0\"></body></html>", "text/html", "utf-8", null)
    }

    private fun unlatchAuth(why: String) {
        authInvalids = 0; refreshRejects = 0
        if (!authLatched) return
        authLatched = false
        // A latched page runs no automatic retry, so reaching here is a deliberate reload or change:
        // that consent also lets the shared credential authority ask Home Assistant once more.
        HaCredentialManager.forgetRefusal()
        BuiltinDashboard.setActivityAuthLatched(activityOwner, false)
        Log.i(TAG, "auth latch cleared ($why)")
    }

    private fun onNetworkAvailable() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // Ignore only the registration callback when the activity started online. If it started without
        // a default network, its first onAvailable is the boot-time Wi-Fi arrival and must recover now.
        if (networkRecovery?.onAvailable() != true) return
        if (signInShownForUrl != null) {
            // The physical sign-in view has no V2 bus document or handshake. Let its ordinary browser
            // connection recover; routing it through dashboard reload/watchdog machinery can erase a
            // partially typed password and can never satisfy the dashboard handshake.
            Log.i(TAG, "network regained during on-panel sign-in — leaving the live sign-in page intact")
            return
        }
        if (web == null) {
            val waitMs = statusScreens.endNetworkWait()
            Log.i(TAG, "network became available during startup after ${waitMs}ms — creating dashboard WebView")
            retryPolicy.reset()
            if (homeDashboardCheckingOwner != null) {
                invalidateHomeDashboardResolution(resetRetry = false)
            }
            buildAndLoad(Config(this))
            if (!screenAwake) scheduleDarkSettle()
            return
        }
        if (frontendConnected || !screenAwake) return // frozen page reconnects itself on wake
        Log.i(TAG, "network regained while frontend not connected — reloading immediately")
        retryPolicy.reset() // a changed environment deserves a fresh fast cadence if HA is still starting
        doReload("network regained")
    }

    /** Pull-to-refresh: a full `WebView.reload()` re-boots the entire frontend app (seconds of blank),
     *  which is overkill for a healthy page — ask the running frontend to re-navigate to the current
     *  view over the external bus instead (instant; JS bundle + websocket stay live). The full reload is
     *  kept for the case that actually needs it: a frontend that isn't connected. */
    private fun lightRefresh() {
        val w = web
        if (w == null || !frontendConnected) { doReload("pull-to-refresh (frontend not connected)"); return }
        // Pull twice in quick succession = full hard reload (the browser's reload-vs-hard-reload
        // pattern, no extra UI): the light navigate re-renders from the frontend's in-memory config,
        // which can't pick up a YAML-mode dashboard edit — a second deliberate pull escalates.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLightRefreshAt < HARD_REFRESH_WINDOW_MS) {
            lastLightRefreshAt = 0L
            doReload("pull-to-refresh (hard, double-pull)")
            return
        }
        lastLightRefreshAt = now
        val path = runCatching { android.net.Uri.parse(w.url).path }.getOrNull().orEmpty()
            .ifBlank {
                resolvedHomeDashboard(Config(this)) ?: run {
                    Log.i(TAG, "pull-to-refresh ignored — no page route and the home dashboard is not resolved yet")
                    swipe?.isRefreshing = false
                    return
                }
            }
        Log.i(TAG, "pull-to-refresh -> light navigate ($path)")
        sendBusNavigate(path)
        // No page-load events fire for a bus navigate — clear the spinner after a short beat.
        val generation = rendererGeneration
        main.postDelayed({ if (rendererCurrent(generation, w)) swipe?.isRefreshing = false }, LIGHT_REFRESH_SPINNER_MS)
    }

    private fun sendBusNavigate(path: String) {
        noteDeliberateDashboardNavigation()
        noteAppNavigationTarget(path)
        bridge.navigate(path)
    }

    /** Every navigation that CHOOSES a target — bus navigates (user, Home Assistant, idle return, correction),
     *  full loads of a chosen URL, and navigation observed inside the frontend itself — bumps this.
     *  Mechanical reloads of the same target do not. The async cache correction compares against it
     *  so it can never overwrite a navigation that arrived after the provisional page was chosen. */
    private fun noteDeliberateDashboardNavigation() {
        dashboardNavigationEpoch++
    }

    /** Record what the app itself last aimed the page at, so frontend-observed navigation to the
     *  SAME dashboard (our own load echoing back, or a redirect within it) is not mistaken for the
     *  user going somewhere else. */
    private fun noteAppNavigationTarget(path: String) {
        appNavigatedDashboardRoute = path
    }

    /** True when a frontend-observed location names a different dashboard from the one the app last
     *  aimed at — i.e. genuine user/SPA navigation away from it. An unparseable or same-root
     *  location is deliberately NOT treated as foreign: over-counting would cancel legitimate
     *  corrections, and the correction is a convenience while being pulled off a page is not. */
    private fun observedDashboardNavigationIsForeign(url: String): Boolean {
        val observed = runCatching { android.net.Uri.parse(url).path }.getOrNull() ?: return false
        val claimed = appNavigatedDashboardRoute ?: return false
        // Once the frontend has connected, the page belongs to the user: ANY route change is their
        // choice, including a view change inside the same dashboard (/alpha -> /alpha/view-2),
        // which a root-only comparison silently allowed a delayed correction to overrule. Before the
        // first connection the page is still OUR load settling — the frontend normalising to a
        // default view, or a server redirect — so only a different dashboard root counts, otherwise
        // every launch would cancel its own correction.
        val foreign = if (frontendConnected) {
            HomeDashboardLaunchCache.sameDashboardRoute(observed, claimed).not()
        } else {
            io.panelassistant.android.util.DashboardPath.let { it.firstSegment(observed) != it.firstSegment(claimed) }
        }
        if (foreign) appNavigatedDashboardRoute = observed
        return foreign
    }

    /** Idle return-to-home (opt-in): after the configured minutes with no touch, swap the frontend back
     *  to the home dashboard — a bus navigate with replace, so it's instant and keeps history flat. */
    private fun onIdleCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (!screenAwake || !frontendConnected || authLatched) return
        val config = Config(this)
        val minutes = config.dashboardIdleReturnMin
        val tick = DashboardIdleReturnPolicy.tick(
            minutes = minutes,
            idleMs = SystemClock.elapsedRealtime() - lastTouchAt,
            homeDashboard = { resolvedHomeDashboard(config) },
        ) { home ->
            val current = runCatching { android.net.Uri.parse(web?.url) }.getOrNull()
            DashboardIdleReturnPolicy.target(
                currentPath = current?.path.orEmpty(),
                currentQuery = current?.encodedQuery,
                currentFragment = current?.fragment,
                homeDashboard = home,
            )
        }
        if (idleUnresolvedNotice.shouldLog(tick)) {
            Log.w(TAG, "idle ${minutes}min — home dashboard not resolved yet; idle return waits for it")
        }
        if (tick !is DashboardIdleReturnPolicy.Tick.Return) return
        Log.i(TAG, "idle ${minutes}min — returning to home dashboard (/${tick.target})")
        sendBusNavigate(tick.target)
    }

    /** Register once and return whether Android already has a default network. When false, onCreate
     *  holds the WebView back entirely: loading HA's cached shell while offline only exposes HA's own
     *  long connection-failed countdown on top of Chromium's main-frame failure. */
    private fun registerNetworkCallback(): Boolean {
        conn = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val initiallyAvailable = conn?.activeNetwork != null
        networkRecovery = NetworkRecoveryGate(initiallyAvailable)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { onNetworkAvailable() } }
            override fun onLost(network: Network) { runOnUiThread {
                networkRecovery?.onLost()
            } }
        }
        netCallback = cb
        // One default-network callback for the activity's lifetime (unregistered in onDestroy) — never a
        // per-load registration, which would hit Android's per-app callback limit on a forever process.
        val registered = runCatching {
            val manager = conn ?: error("ConnectivityManager unavailable")
            manager.registerDefaultNetworkCallback(cb)
        }.onFailure { Log.w(TAG, "network callback register failed: ${it.message}") }.isSuccess
        // Never strand on the waiting screen if this OEM cannot register the callback: fall back to the
        // existing WebView watchdog path, which can still recover by polling loads.
        return initiallyAvailable || !registered
    }

    /** Re-load the dashboard: a plain reload normally, but a fresh loadUrl of the real dashboard when
     *  the WebView is currently showing the "Reconnecting…" interstitial (reloading THAT would just
     *  re-show the interstitial forever). */
    private fun reloadTarget(): Boolean {
        val w = web ?: return false
        val config = Config(this)
        val fresh = shownPage.needsFreshLoad(config.haEffectiveUrl, interstitialShown, dashboardRenderer = signInShownForUrl == null)
        // Only a fresh load needs the home dashboard; unresolved, admission loads it instead.
        val home = if (fresh) {
            resolvedHomeDashboard(config) ?: run {
                readmitForHomeDashboard("reload over the reconnecting page")
                return false
            }
        } else null
        if (!rotateBusDocument(w, config, rendererGeneration)) return false
        if (home != null) {
            interstitialShown = false
            val target = currentUrl(config, home)
            bridge.expectPageStart(target)
            w.loadUrl(target)
        } else {
            w.url?.let(bridge::expectPageStart)
            w.reload()
        }
        return true
    }

    /** Reload + arm the handshake watchdog (only fires while awake) — for user/health-driven reloads. */
    private fun doReload(reason: String) {
        Log.i(TAG, "reload: $reason")
        if (!reloadTarget()) return
        onLoadStarted()
    }

    /** Reload without arming the watchdog — for screen-off memory reloads, where the settle timer (not the
     *  watchdog) governs when the page freezes, so the watchdog can't turn a dark reload into a loop. */
    private fun doReloadNoWatchdog(reason: String) {
        Log.i(TAG, "reload: $reason")
        frontendConnected = false
        clearedThisLoad = false
        if (!reloadTarget()) return
    }

    /** A main-frame load failed (HA down / network out / DNS): replace Android's native gray error page
     *  with a branded dark "reconnecting" screen while the handshake watchdog keeps retrying behind it.
     *  The native error page can't be styled or suppressed any other way, and a wall panel showing
     *  `net::ERR_CONNECTION_REFUSED` between retries reads as broken rather than waiting. */
    /** Draw the reconnect page again, e.g. after a theme change while it is the visible surface. */
    private fun reissueReconnecting() {
        // Redrawing is not a new outage: the time unreachable carries on from where it was.
        val since = reconnectingSinceMs
        interstitialShown = false
        reconnectingSinceMs = since
        showReconnecting(lastInterstitialDetail)
    }

    private fun showReconnecting(detail: String) {
        if (destroyed || authLatched || interstitialShown) return
        interstitialShown = true
        statusScreens.clearReloadNotice() // the reconnecting screen says it better
        lastInterstitialDetail = detail
        Log.w(TAG, "main-frame load error ($detail) — showing reconnecting page; watchdog retries continue")
        web?.let(bridge::suspend)
        // Whatever was on screen is being replaced by a page that is not Home Assistant; until this one is
        // drawn and reported, a retry must not trust an earlier Home Assistant page as the one showing.
        shownPage.forget()
        blankWebDocument()
        if (reconnectingSinceMs == 0L) reconnectingSinceMs = SystemClock.elapsedRealtime()
        showWebCover { cover ->
            val notice = cover.notice
            val hero = notice.hero("")
            webCover.countUp(hero, reconnectingSinceMs)
            cover.setBody(
                getString(R.string.notice_pill_reconnecting),
                hero,
                notice.line(getString(R.string.dashboard_reconnecting)),
                notice.footer(getString(R.string.dashboard_unreachable_retry, retryPolicy.initialRetryMs / 1_000L, retryPolicy.maxRetryMs / 1_000L)),
                notice.footer(detail, small = true),
            )
        }
    }

    /** The URL to show: a pending navigate path (consumed — one-shot, so crash rebuilds and
     *  interstitial recoveries return to home rather than replaying a stale navigate), else the
     *  resolved [home] dashboard. */
    private fun currentUrl(config: Config, home: String): String = ExternalAuthProtocol.dashboardUrl(
        config.haEffectiveUrl,
        BuiltinDashboard.consumeNavPath() ?: home,
    )

    private fun resolvedHomeDashboard(config: Config): String? =
        ownedHomeDashboardPath(homeDashboardResolution, homeDashboardOwner(config))

    /** A load of the home dashboard was asked for while none is resolved for the current owner: hand the
     *  panel back to admission, which resolves it (launch cache first, then live) and loads home itself.
     *  The same teardown-and-rebuild an authority change takes. */
    private fun readmitForHomeDashboard(why: String) {
        Log.i(TAG, "$why before the home dashboard resolved — re-entering admission")
        teardownWeb()
        buildAndLoad(Config(this))
    }

    // Publish foreground state so SystemController.dashboardState can drive the watchdog + kiosk
    // return-loop from an in-process signal instead of a root pidof/dumpsys probe.
    //
    // The precise signal is TOP-RESUMED, not focus or resume/pause. On some OEM launchers (verified on
    // rk3576/Android-14 quickstep) opening Overview/Recents leaves the dashboard both window-FOCUSED and
    // RESUMED behind a translucent overlay — so onPause and onWindowFocusChanged never fire — yet the
    // dashboard is no longer the top activity the user is on. onTopResumedActivityChanged (API 29+) is
    // the lifecycle form of dumpsys `topResumedActivity` and flips exactly on that transition.
    // onResume/onPause are the API<29 baseline (older panels lack the callback; their launchers also
    // predate translucent Overview, so resume/pause suffices there).
    override fun onResume() {
        super.onResume()
        if (maintenanceFence.stop(this)) return
        if (resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) dashboardIsTopResumed = true
        if (!::activityConfig.isInitialized) return
        NativeLocale.apply(activityConfig.uiLanguage)
        // Below API 29 onTopResumedActivityChanged is never delivered, so resume owns visibility there.
        if (resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) onAdmissionVisibilityChanged(true)
        BuiltinDashboard.setActivityForeground(activityOwner, dashboardIsTopResumed)
        io.panelassistant.android.assist.VoiceOverlays.show(this)
        if (::activityConfig.isInitialized) applyRendererScreenPolicy()
        applyFullscreen()
        applyZoom()
        // Reconcile the outage card with the canonical clock on wake: `postDelayed` runs on uptime,
        // which pauses through deep sleep while the canonical window does not, so a recovery notice
        // that lapsed during sleep would otherwise stay visible until its stalled timer caught up.
        redrawLifecycleBar()
    }

    /** Camera trial: request CAMERA only when [Config.cameraEnabled] is already on and the
     *  permission is not already held. Called both from the settings-change listener above (the switch
     *  flips while this activity is resumed) and from [onResume] (the switch flipped while backgrounded). */
    private fun requestCameraPermissionIfNeeded() {
        // Camera trial: whether to ask is the session owner's decision — it alone knows the profile
        // declares a camera, the switch is on and the permission is missing — and a denial is remembered
        // durably. Reaching here means the delivery gate has seen the lifecycle's ON_RESUME, so the
        // dialog can be raised; a guard on the lifecycle state here would reject a signal that lands
        // during onResume itself and never retry it.
        if (!io.panelassistant.android.camera.CameraPermissionPrompt.shouldAsk()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        io.panelassistant.android.camera.CameraPermissionPrompt.asking()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        io.panelassistant.android.camera.CameraPermissionPrompt.answered(granted)
        Log.d(TAG, "camera permission ${if (granted) "granted" else "denied"}")
    }

    /** The foreground built-in renderer owns its timeout policy directly (see [RendererScreenHold]). */
    private fun applyRendererScreenPolicy() = rendererScreenHold.apply()

    /** Page zoom (%). Re-read + applied on resume so a live `dashboard_zoom` change lands; the POST
     *  path also reloads the page, since a fresh load is where the initial scale reliably takes effect. */
    private fun applyZoom() {
        web?.setInitialScale((resources.displayMetrics.density * Config(this).dashboardZoom).toInt())
    }

    /**
     * Edge-to-edge dashboard (issue #25): hide the Android status + navigation bars while the dashboard is
     * up. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE keeps the admin escape — a swipe from a screen edge
     * reveals the bars briefly — so this can never strand anyone. Now that the dashboard is our own
     * activity this is plain app-level immersive (no root, unlike suppressing bars for a foreign
     * renderer). Re-asserted on resume and on regaining window focus, because the system restores bars
     * after transient reveals and some dialogs. Toggle: Configure → Built-in renderer → "Hide Android system bars".
     */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Config(this).dashboardFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (maintenanceFence.stop(this)) return
        if (hasFocus && ::activityConfig.isInitialized) applyFullscreen()
    }
    override fun onPause() {
        io.panelassistant.android.assist.VoiceOverlays.hide()
        dashboardIsTopResumed = false
        onAdmissionVisibilityChanged(false)            // the retry stays armed; only the repaint stops
        BuiltinDashboard.setActivityForeground(activityOwner, false)
        super.onPause()
    }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (maintenanceFence.stop(this)) return
        dashboardIsTopResumed = isTopResumedActivity
        if (!resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) {
            onAdmissionVisibilityChanged(isTopResumedActivity)
        }
        BuiltinDashboard.setActivityForeground(activityOwner, isTopResumedActivity)
    }

    /** configChanges declares uiMode, so a system dark/light flip lands here instead of recreating the
     *  activity — re-mirror it into the 10..12 force-dark flag (the frontend re-themes live off the
     *  resulting prefers-color-scheme change; no reload). No-op below 10 (config-driven) and on 13+. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (maintenanceFence.stop(this)) return
        if (android.os.Build.VERSION.SDK_INT in 29..32) web?.let { applyDashboardForceDark(it) }
        if (!::activityConfig.isInitialized) return
        statusScreens.onConfigurationChanged()
    }

    /** The detail last shown on the reconnect page, so a theme change can redraw it unchanged. */
    private var lastInterstitialDetail: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndLoad(config: Config) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        // Sign-in is checked FIRST, ahead of the entity-bootstrap hold. The hold derives only from the
        // learning/filter flags, so on a panel with entity learning enabled and no credential yet it is
        // entered unconditionally and never left — the bootstrap it waits for needs an authenticated
        // Home Assistant connection that only the sign-in below can produce. Ordered the other way this
        // is a second, quieter deadlock sitting directly behind the readiness gate.
        val url = config.haEffectiveUrl.trim().trimEnd('/')
        if (haSignInPending(url, config.haToken, config.haRefreshToken)) {
            showPhysicalHaSignIn(url)
            return
        }
        // Held until the wizard's entity-filter question is answered. Placed AFTER sign-in (which mints the
        // credential this hold requires) and BEFORE any WebView is created, because the whole purpose is that
        // the panel's first render is already the filtered one — loading unfiltered and reloading afterwards
        // is the slow, laggy first impression this prevents.
        if (entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = url,
                haToken = config.haToken,
                haRefreshToken = config.haRefreshToken,
                entityFilterAnswered = config.setupEntityFilterAnswered,
                setupEverCompleted = config.setupEverCompleted,
                entityFilterEnabled = config.dashboardEntityLearningEnabled,
            )
        ) {
            entityGate.showWaitingForEntityFilterAnswer()
            return
        }
        if (entityGate.holdsRenderer(config)) {
            entityGate.showWaitingForEntityBootstrap()
            return
        }
        val owner = DashboardV2CompatibilityOwner(url, config.haAuthSnapshot().stableOwner(), config.haRouteEpoch())
        if (secureBridgeAdmission() == AdmissionOutcome.BRIDGE_UNAVAILABLE) {
            statusScreens.showBlockedAdmissionScreen(
                getString(R.string.web_viewer_too_old_title),
                getString(R.string.web_viewer_too_old_detail),
                AdmissionOutcome.BRIDGE_UNAVAILABLE,
                heroWord = getString(R.string.notice_hero_ancient_webview),
            )
            return
        }
        if (compatibilityReadyUrl == url) {
            resolveHomeDashboardAndLoad(config)
            return
        }
        if (compatibilityCheckingOwner == owner && compatibilityJob?.isActive == true) return
        compatibilityJob?.cancel()
        val compatibilityTicket = compatibilityAttempts.start(owner)
        compatibilityCheckingOwner = owner
        statusScreens.showAdmissionProgressScreen(
            R.string.checking_ha_compatibility,
            R.string.notice_hero_version_check,
            R.string.notice_line_ha_compatibility,
            R.string.checking_ha_compatibility_detail,
        )
        compatibilityJob = activityScope.launch {
            val result = DashboardV2CompatibilityProbe(
                config,
                stillCurrent = {
                    !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
                        compatibilityAttempts.owns(compatibilityTicket, compatibilityOwner(config))
                },
            ).check()
            val currentConfig = Config(this@DashboardActivity)
            val currentOwner = compatibilityOwner(currentConfig)
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) ||
                !compatibilityAttempts.owns(compatibilityTicket, currentOwner)
            ) return@launch
            compatibilityCheckingOwner = null
            when (val admission = DashboardV2Admission.resolve(result, config.cachedHaServerVersion(config.haUrl))) {
                is DashboardV2Admission.Compatible -> {
                    // Admitted on a cached version is recorded DISTINCTLY from a live pass. The panel
                    // starts, but nothing about the server was confirmed this time — and when the
                    // page then hits the same wall the check could not cross, the panel is admitted
                    // and blank while every other health signal it has still reads normal.
                    RendererAdmissionRuntime.record(
                        owner = activityOwner,
                        state = RendererAdmissionState.ADMITTED,
                        nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        admittedOnCachedVersion = !admission.live,
                        evidence = (result as? DashboardV2ProbeResult.Unavailable)?.evidence
                            ?: HaTransportEvidence.NONE,
                    )
                    if (admission.live) {
                        config.setHaServerVersionIfOwned(config.haUrl, admission.version)
                    } else {
                        val detail = (result as DashboardV2ProbeResult.Unavailable).detail
                        Log.w(TAG, "HA version check unavailable; using previously verified ${admission.version} ($detail)")
                    }
                    compatibilityReadyUrl = url
                    bridge.resetHandshake()
                    buildAndLoad(currentConfig)
                }
                is DashboardV2Admission.Blocked -> when (val blocked = admission.result) {
                    is DashboardV2ProbeResult.UnsupportedHa -> {
                        config.setHaServerVersionIfOwned(config.haUrl, blocked.version)
                        statusScreens.showBlockedAdmissionScreen(
                            getString(R.string.ha_upgrade_required),
                            getString(R.string.ha_upgrade_required_detail, blocked.version),
                            AdmissionOutcome.UNSUPPORTED_HA,
                            heroWord = getString(R.string.notice_hero_upgrade_ha),
                        )
                    }
                    // A degraded proxy or captive-portal response lands here too, so probe again at
                    // the ceiling cadence rather than never.
                    is DashboardV2ProbeResult.Unverifiable -> statusScreens.showBlockedAdmissionScreen(
                        getString(R.string.ha_version_unverifiable),
                        getString(R.string.ha_version_unverifiable_detail, blocked.version?.let { " (“$it”)" }.orEmpty()),
                        AdmissionOutcome.VERSION_UNVERIFIABLE,
                    )
                    // Two different situations, kept as separate outcomes so a diagnostic surface can
                    // tell them apart, but BOTH person-repaired. Neither runs a timer: an absent
                    // credential has nothing to re-ask with, and replaying an unchanged rejected one
                    // every few minutes is what triggers Home Assistant's login-attempt banning, which
                    // would turn a repairable refusal into a locked-out account. Only a resolution
                    // carrying no authentication verdict at all retries by itself.
                    //
                    // The copy below must therefore never promise that the panel keeps checking. It
                    // did between the ceiling-cadence proposal and the review correction that kept
                    // this outcome manual, which left the screen promising a retry that never came.
                    DashboardV2ProbeResult.AuthenticationFailed -> {
                        val neverSignedIn = config.haToken.isBlank() && config.haRefreshToken.isBlank()
                        // Nothing recorded WHY the panel reached this verdict, leaving no way to tell a
                        // refusal from a probe that ran too early.
                        Log.w(
                            TAG,
                            "HA admission refused authentication (neverSignedIn=$neverSignedIn) — " +
                                "no token was accepted for the compatibility check",
                        )
                        statusScreens.showBlockedAdmissionScreen(
                            if (neverSignedIn) {
                                getString(R.string.ha_sign_in_needed)
                            } else {
                                getString(R.string.ha_sign_in_rejected)
                            },
                            if (neverSignedIn) {
                                getString(R.string.ha_sign_in_needed_detail)
                            } else {
                                getString(R.string.ha_sign_in_rejected_detail)
                            },
                            if (neverSignedIn) {
                                AdmissionOutcome.SIGN_IN_REQUIRED
                            } else {
                                AdmissionOutcome.CREDENTIAL_REFUSED
                            },
                        )
                    }
                    is DashboardV2ProbeResult.Unavailable -> statusScreens.showBlockedAdmissionScreen(
                        getString(R.string.cannot_reach_ha),
                        statusScreens.localizedHaUnavailableDetail(blocked),
                        AdmissionOutcome.TRANSPORT_FAILED,
                        blocked.evidence,
                    )
                    is DashboardV2ProbeResult.Compatible -> error("compatible result cannot be blocked")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPhysicalHaSignIn(haUrl: String) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        statusScreens.cancelAdmissionAutoRetry()
        // Already showing sign-in for this exact URL: keep the live WebView. Every config save relaunches
        // this activity, and the browser form posts on each save, so rebuilding here would discard a
        // part-typed Home Assistant password whenever anything else was saved from another device.
        if (signInShownForUrl == haUrl && web != null) {
            Log.i(TAG, "on-panel sign-in already showing for this endpoint — keeping the current screen")
            return
        }
        signInShownForUrl = haUrl
        signInLoadRetries = 0
        if (web != null) teardownWeb()
        val generation = rendererGate.open()
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            setBackgroundColor(BG_DARK)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!rendererCurrent(generation, view)) return true
                    return !panelHaOAuthNavigationAllowed(haUrl, request.url.toString())
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!rendererCurrent(generation, view) || !isPanelHaOAuthCallback(url)) return
                    view.evaluateJavascript(
                        "document.body&&document.body.dataset?document.body.dataset.haOauthStatus:\"\"",
                    ) { result ->
                        if (!rendererCurrent(generation, view)) return@evaluateJavascript
                        if (result?.trim('"') == "success") {
                            main.postDelayed({
                                if (rendererCurrent(generation, view)) {
                                    compatibilityReadyUrl = null
                                    compatibilityCheckingOwner = null
                                    compatibilityAttempts.invalidate()
                                    buildAndLoad(Config(this@DashboardActivity))
                                }
                            }, 750L)
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                    // A main-frame error here is usually TRANSIENT: the sign-in start page is served by
                    // this panel's own local HTTP server, which restarts briefly during a config
                    // reconfigure — exactly when a first-run user is mid-setup. On hardware review that
                    // produced a terminal "sign-in unavailable" over a sign-in the user may have been in
                    // the middle of. Retry quietly first; only a persistent failure earns the verdict.
                    if (signInLoadRetries < SIGN_IN_LOAD_RETRIES_MAX) {
                        signInLoadRetries++
                        Log.i(TAG, "panel sign-in page load failed — retry $signInLoadRetries/$SIGN_IN_LOAD_RETRIES_MAX")
                        main.postDelayed({
                            if (rendererCurrent(generation, view) && !destroyed) {
                                view.loadUrl(panelHaOAuthStartUrl(haUrl))
                            }
                        }, SIGN_IN_LOAD_RETRY_MS)
                        return
                    }
                    statusScreens.showBlockedAdmissionScreen(
                        getString(R.string.ha_sign_in_page_failed),
                        getString(R.string.ha_sign_in_page_failed_detail),
                        AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
                    )
                }
            }
        }
        web = view
        val content = FrameLayout(this).apply {
            setBackgroundColor(BG_DARK)
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        // The status frame's redraw belongs to the status frame. Left armed here, a later
        // configuration change replaced this live sign-in WebView with an obsolete status screen and
        // could strand the panel, because the transition that produced that screen will not repeat.
        statusScreens.clearRerender()
        root = content
        setContentView(content)
        view.loadUrl(panelHaOAuthStartUrl(haUrl))
    }

    private fun compatibilityOwner(config: Config): DashboardV2CompatibilityOwner =
        DashboardV2CompatibilityOwner(
            normalizedUrl = config.haEffectiveUrl.trim().trimEnd('/'),
            authOwner = config.haAuthSnapshot().stableOwner(),
            routeEpoch = config.haRouteEpoch(),
        )

    private fun homeDashboardOwner(config: Config): HomeDashboardResolutionOwner =
        HomeDashboardResolutionOwner(
            authOwner = config.haAuthSnapshot().stableOwner(),
            configuredPath = config.homeDashboard.trim(),
        )

    private fun invalidateHomeDashboardResolution(resetRetry: Boolean = true) {
        homeDashboardJob?.cancel()
        homeDashboardJob = null
        homeDashboardCheckingOwner = null
        homeDashboardResolution = null
        provisionalHomeDashboardEpoch = null
        homeDashboardAttempts.invalidate()
        if (resetRetry) admissionRetryPolicy.reset()
    }

    /** The one admission retry sequence — the automatic timer and the manual Retry button both run
     *  exactly this, so the two recovery paths cannot diverge. */
    private fun retryAdmission(resetBackoff: Boolean) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        invalidateHomeDashboardResolution(resetRetry = resetBackoff)
        compatibilityCheckingOwner = null
        compatibilityReadyUrl = null
        compatibilityAttempts.invalidate()
        bridge.resetHandshake()
        buildAndLoad(Config(this))
    }

    /** Top-visibility, not merely resumed: a translucent Overview can take top-resumed status without
     *  calling onPause, and repainting behind it is invisible work. The retry stays armed throughout. */
    private fun onAdmissionVisibilityChanged(visible: Boolean) {
        if (destroyed) return
        statusScreens.onAdmissionVisibilityChanged(visible)
        // Coming forward is what makes a microphone foreground service startable again on Android 14,
        // so a claim the platform refused while the panel was in the background is retried here.
        if (visible) PaneldService.notifyActivityForeground()
    }

    /** Resolve before WebView creation; entity learning may be disabled or may never have scanned. */
    private fun resolveHomeDashboardAndLoad(config: Config) {
        val owner = homeDashboardOwner(config)
        val owned = homeDashboardResolution?.takeIf { it.owner == owner }
        if (owned != null && owned.confirmed) {
            val path = owned.resolution.path
            if (path == null) {
                statusScreens.showBlockedAdmissionScreen(
                    getString(R.string.no_dashboard_title),
                    getString(R.string.no_dashboard_detail),
                    AdmissionOutcome.NO_LEGAL_DASHBOARD,
                )
            } else {
                buildCompatibleAndLoad(config, path)
            }
            return
        }
        // Launch accelerator, no-renderer branch. This is the ordinary cold-start route — a fresh
        // process (and a genuine activity recreation, which brings a new instance with a null `web`)
        // arrives here, not at the still-held-renderer branch below. It also carries every rebuild
        // that tore the renderer down first, which is why it comes BEFORE the in-flight-job early
        // return: a renderer crash while the authenticated resolution is running would otherwise
        // leave the panel blank until that resolution finishes or fails, defeating the immediate
        // cached fallback at the one moment it is most needed.
        if (web == null) {
            (owned?.resolution?.path ?: config.cachedHomeDashboardLaunchPath())?.let { path ->
                if (owned == null) {
                    homeDashboardResolution = OwnedHomeDashboardResolution(
                        owner,
                        EntityLearningProtocol.HomeDashboardResolution(
                            path, EntityLearningProtocol.HomeDashboardSource.CACHED,
                        ),
                        confirmed = false,
                    )
                }
                Log.i(
                    TAG,
                    "home dashboard cache-accelerated launch path=$path renderer=none " +
                        "(cold start or rebuild) — the authenticated resolution refreshes behind it",
                )
                buildCompatibleAndLoad(config, path)
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
            }
        }
        if (homeDashboardCheckingOwner == owner && homeDashboardJob?.isActive == true) return
        homeDashboardJob?.cancel()
        val ticket = homeDashboardAttempts.start(owner)
        homeDashboardCheckingOwner = owner
        // Launch accelerator, still-held-renderer branch: the last path a live resolution produced for
        // this exact owner (endpoint + credential + configured home_dashboard) navigates immediately,
        // and the authenticated resolution below re-runs behind the rendering page. Its answer always
        // wins — the same path confirms the page, a different one corrects it, a confirmed empty list
        // clears the cache.
        //
        // Reachable only when this activity instance still holds a renderer from an earlier load (an
        // in-process relaunch or admission retry, where no teardown intervened). The branch above has
        // already accelerated every launch that started with no renderer, and its provisional
        // resolution makes `provisionalPath` non-null, so the two sites are mutually exclusive.
        var provisionalPath = homeDashboardResolution?.takeIf { it.owner == owner && !it.confirmed }
            ?.resolution?.path
        if (provisionalPath == null && owned == null) {
            config.cachedHomeDashboardLaunchPath()?.let { cached ->
                provisionalPath = cached
                homeDashboardResolution = OwnedHomeDashboardResolution(
                    owner,
                    EntityLearningProtocol.HomeDashboardResolution(
                        cached, EntityLearningProtocol.HomeDashboardSource.CACHED,
                    ),
                    confirmed = false,
                )
                Log.i(
                    TAG,
                    "home dashboard cache-accelerated launch path=$cached renderer=held " +
                        "(relaunch over the renderer this activity still holds) — " +
                        "the authenticated resolution refreshes behind it",
                )
                buildCompatibleAndLoad(config, cached)
                provisionalHomeDashboardEpoch = dashboardNavigationEpoch
            }
        }
        // Not a renderer CHECKING: compatibility already admitted this renderer, and recording CHECKING here
        // outlived the dashboard it loaded (status `checking` while rendered, after every launch-cache miss).
        if (provisionalPath == null) statusScreens.showV2CompatibilityScreen(
            getString(R.string.selecting_ha_dashboard), getString(R.string.selecting_ha_dashboard_detail), null,
            AdmissionRetryClass.MANUAL_ONLY, null, getString(R.string.notice_hero_finding_dashboards), getString(R.string.notice_line_ha_dashboard),
        )
        homeDashboardJob = activityScope.launch {
            val resolution = withContext(Dispatchers.IO) {
                EntityLearningRuntime.resolveHomeDashboard(
                    owner.configuredPath,
                    stillCurrent = {
                        !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
                            homeDashboardAttempts.owns(ticket, homeDashboardOwner(Config(this@DashboardActivity)))
                    },
                    // Refreshing behind a rendered provisional page PROMISES a live answer, so the
                    // process-local authority must not answer for it: a warm relaunch would
                    // otherwise miss an account-default change, a removed dashboard or revoked
                    // access and confirm the cached path against a stale in-process result.
                    forceLive = provisionalPath != null,
                )
            }
            val currentConfig = Config(this@DashboardActivity)
            val currentOwner = homeDashboardOwner(currentConfig)
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) ||
                !homeDashboardAttempts.owns(ticket, currentOwner)
            ) return@launch
            homeDashboardCheckingOwner = null
            val shownPath = homeDashboardResolution
                ?.takeIf { it.owner == currentOwner && !it.confirmed }
                ?.resolution?.path
            if (resolution == null) {
                // A transient failure neither erases the persisted cache nor tears down the page it
                // admitted: with the provisional dashboard rendering, retry quietly behind it.
                if (shownPath == null || web == null) {
                    statusScreens.showBlockedAdmissionScreen(
                        getString(R.string.dashboard_list_unavailable),
                        getString(R.string.dashboard_list_unavailable_detail),
                        AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
                    )
                } else {
                    statusScreens.cancelAdmissionAutoRetry()
                    admissionRetryPolicy.nextDelayMs(
                        admissionRetryClass(AdmissionOutcome.DASHBOARD_LIST_UNREADABLE),
                    )?.let { statusScreens.armAdmissionAutoRetry(it, getString(R.string.dashboard_list_unavailable)) }
                }
                return@launch
            }
            admissionRetryPolicy.reset()
            homeDashboardResolution = liveHomeDashboardResolution(currentOwner, resolution)
            updateSubstituteChip()
            val launchOwner = HomeDashboardLaunchCache.ownerFingerprint(
                currentOwner.authOwner, currentOwner.configuredPath,
            )
            if (resolution.path == null) {
                // Confirmed by a COMPLETED list read — distinct from the transient branch above: the
                // account has no legal dashboards, so replaying the cache next launch would navigate
                // somewhere the list just proved unreachable. The cache goes with the screen.
                if (!currentConfig.clearHomeDashboardLaunchPathIfOwned(launchOwner)) {
                    // The durable delete did not commit, so the stale row is still there for the next
                    // launch (or a Retry) to replay. Reporting the settled no-dashboard state here
                    // would claim a convergence that did not happen; keep the retry armed instead so
                    // the invalidation is attempted again.
                    Log.e(TAG, "home dashboard cache invalidation did not commit — retrying before settling")
                    statusScreens.showBlockedAdmissionScreen(
                        getString(R.string.no_dashboard_title),
                        getString(R.string.no_dashboard_cache_clear_detail),
                        AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
                    )
                    return@launch
                }
                statusScreens.showBlockedAdmissionScreen(
                    getString(R.string.no_dashboard_title),
                    getString(R.string.no_dashboard_detail),
                    AdmissionOutcome.NO_LEGAL_DASHBOARD,
                )
                return@launch
            }
            currentConfig.setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)
            Log.i(TAG, "home dashboard resolved source=${resolution.source} path=${resolution.path}")
            val provisionalEpoch = provisionalHomeDashboardEpoch
            provisionalHomeDashboardEpoch = null
            when {
                shownPath == null || web == null -> buildCompatibleAndLoad(currentConfig, resolution.path)
                HomeDashboardLaunchCache.refreshOutcome(shownPath, resolution) ==
                    HomeDashboardLaunchCache.RefreshOutcome.CORRECTED -> {
                    // A navigation chosen AFTER the provisional page (Home Assistant navigate, idle return,
                    // pull-to-refresh target, a relaunch load) outranks the correction: the resolved
                    // value is already stored for every later navigation, but the page the user is
                    // looking at is not yanked back.
                    if (dashboardNavigationEpoch != provisionalEpoch) {
                        Log.i(
                            TAG,
                            "home dashboard corrected $shownPath -> ${resolution.path} in store only — newer navigation wins on screen",
                        )
                    } else {
                        Log.i(TAG, "home dashboard corrected $shownPath -> ${resolution.path} (cached path superseded)")
                        navigateAfterHomeDashboardCorrection(resolution.path)
                    }
                }
                else -> Unit // CONFIRMED — the rendering page is already the resolved dashboard
            }
        }
    }

    /** Move an already rendering page onto the corrected home dashboard: instantly over the external
     *  bus when the frontend is live, otherwise a fresh load of the corrected target — a mid-load or
     *  interstitial page would otherwise finish on the superseded dashboard and stay there.
     *
     *  A bus navigate has no page-load events, so its completion is PROVEN, not assumed: one bounded
     *  verification reads the page's location afterwards and, if the correction did not land (and no
     *  newer navigation arrived meanwhile), escalates to the full corrected load, whose handshake
     *  watchdog then owns convergence. Without this a silently dropped bus command would leave the
     *  screen on the superseded dashboard while store and resolution both claim the corrected one. */
    private fun navigateAfterHomeDashboardCorrection(path: String) {
        val w = web ?: return
        if (frontendConnected && !authLatched && !interstitialShown) {
            sendBusNavigate(path)
            val issuedEpoch = dashboardNavigationEpoch
            val generation = rendererGeneration
            main.postDelayed(
                {
                    if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return@postDelayed
                    if (!rendererCurrent(generation, web) || dashboardNavigationEpoch != issuedEpoch) return@postDelayed
                    val currentPath = runCatching { android.net.Uri.parse(web?.url).path }.getOrNull()
                    if (!HomeDashboardLaunchCache.correctionConverged(currentPath, path)) {
                        Log.w(TAG, "home dashboard correction unverified after bus navigate — loading the corrected target")
                        loadCorrectedHomeDashboard()
                    }
                },
                CORRECTION_VERIFY_MS,
            )
            return
        }
        loadCorrectedHomeDashboard()
    }

    /** Full-load half of the correction: the same rotate + load + watchdog route every other fresh
     *  navigation uses, reading the corrected target through [currentUrl]. */
    private fun loadCorrectedHomeDashboard() {
        val w = web ?: return
        val config = Config(this)
        // Unresolved here means the owner moved after the correction; its rebuild loads the new home.
        val home = resolvedHomeDashboard(config) ?: return
        if (!rotateBusDocument(w, config, rendererGeneration)) return
        interstitialShown = false
        noteDeliberateDashboardNavigation()
        val target = currentUrl(config, home)
        noteAppNavigationTarget(android.net.Uri.parse(target).path.orEmpty())
        bridge.expectPageStart(target)
        w.loadUrl(target)
        onLoadStarted()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildCompatibleAndLoad(config: Config, homePath: String) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (compatibilityReadyUrl != config.haEffectiveUrl.trim().trimEnd('/')) return
        if (entityGate.holdsRenderer(config)) {
            entityGate.showWaitingForEntityBootstrap()
            return
        }
        // Admission succeeded: a surviving blocked-screen timer firing under the live dashboard would
        // tear it down to re-probe, so it dies here, and the back-off starts fresh next time.
        statusScreens.cancelAdmissionAutoRetry()
        admissionRetryPolicy.reset()
        entityGate.onRendererAdmitted()
        val generation = rendererGate.open()
        rendererGeneration = generation
        rendererRoute = HaConnectionRoutes.current(config)
        // The page carries a live HA session, so only expose the WebView's DevTools socket when network
        // adb is deliberately on (a debug posture) — not by default. The CDP relay (also off by default)
        // is the LAN publisher on top of this.
        WebView.setWebContentsDebuggingEnabled(
            shouldEnableWebViewDebugging(config.networkAdbEnabled, config.hardenedSecurityEnabled),
        )
        // A rebuild (renderer crash) discards the old root that held any fullscreen (onShowCustomView)
        // view. Clear the stale references so the crash didn't leave customView non-null — otherwise every
        // future onShowCustomView would be rejected and fullscreen video would be permanently broken.
        customView = null
        customViewCallback = null
        bridge.openDocumentForCreate(config, generation)
        val w = try {
            createWebView(config, generation)
        } catch (e: Throwable) {
            bridge.abandonCreate()
            if (e is EntityFilterInterceptorUnavailable) {
                entityGate.showWaitingForEntityBootstrap()
                return
            }
            if (e is ExternalV2BridgeUnavailable) {
                statusScreens.showBlockedAdmissionScreen(
                    getString(R.string.home_assistant_could_not_open),
                    getString(R.string.home_assistant_could_not_open_detail),
                    AdmissionOutcome.BRIDGE_ATTACH_FAILED,
                )
                return
            }
            // A missing / updating / broken system WebView (exactly the population WebView-auto-heal
            // targets) throws here. As a HOME activity we must not crash — Android would relaunch us
            // into a crash-loop — so fall back to the admin launcher. This consumes the rebuild budget
            // too: the kiosk/watchdog loops relaunch us, createWebView throws again, and without the
            // budget that cycle would churn forever; with it the renderer latches after a burst and
            // retries once per cooldown (plenty for a WebView update to finish installing).
            Log.e(TAG, "system WebView unavailable — falling back to admin launcher", e)
            BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())
            fallbackToLauncher()
            return
        }
        web = w
        shownPage.forget()
        // Wrap in a pull-to-refresh layout: a drag that starts at the very top edge of the screen and
        // pulls down does a light reload of the current page (no app relaunch). The gesture is gated on
        // its ORIGIN (see EdgePullRefreshLayout) — a downward drag that begins inside the dashboard
        // content never triggers it, so scrolling views and adjusting cards behave normally (#29).
        // The spinner is cleared when the page finishes (or errors) — see the WebViewClient.
        val refresh = EdgePullRefreshLayout(this).apply {
            addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { lightRefresh() }
        }
        swipe = refresh
        // A dark root behind the (transparent) WebView so a reload never flashes white — very visible on
        // a wall panel at night. Also hosts the fullscreen-video view from onShowCustomView. The root is a
        // BottomSwipeFrame so a bottom-edge swipe-up reveals the soft navbar in-process (see the class) —
        // replacing the service's overlay strip that made the dashboard's bottom band tap-dead.
        val container = BottomSwipeFrame(
            this,
            enabled = { BuiltinDashboard.navbarSwipeEnabled },
            onSwipeUp = { BuiltinDashboard.requestNavbarReveal() },
        ).apply {
            setBackgroundColor(BG_DARK)
            addView(refresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root = container
        setContentView(container)
        statusScreens.clearRerender()
        noteDeliberateDashboardNavigation()
        // Attached to the same frame the fullscreen-video view uses, so the dashboard keeps rendering
        // underneath. Seeded from the CURRENT state because a renderer rebuilt mid-outage must not come
        // up looking calm.
        lifecycleBar = HaLifecycleBar.attach(this, container) { visible ->
            if (rendererCurrent(generation, w)) {
                w.evaluateJavascript(InjectionScript.lifecycleNoticeJs(visible), null)
            }
        }
        networkChip = HaNetworkChip.attach(this, container)
        substituteChip = DashboardSubstituteChip.attach(this, container)
        updateSubstituteChip()
        redrawLifecycleBar()
        val target = currentUrl(config, homePath)
        noteAppNavigationTarget(android.net.Uri.parse(target).path.orEmpty())
        bridge.expectPageStart(target)
        w.loadUrl(target)
        onLoadStarted()
        // buildAndLoad is reached from service-triggered singleTask intents and renderer recovery as
        // well as initial startup. Own dark settling here so no caller can leave a replacement WebView
        // running indefinitely behind a sleeping display while it waits for a connection callback.
        if (deferReadyEntityBootstrapUntilWake(screenAwake)) scheduleDarkSettle()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        config: Config,
        generation: Long,
    ): WebView = createDashboardWebView(this, config, activityOwner, { dashboardThemeSignature = it }, entityGate, bridge).apply {
        webChromeClient = dashboardChromeClient(generation)
        webViewClient = object : WebViewClient() {
            // A dead renderer process must not take the app down with it: rebuild the WebView in place
            // (else Android kills the whole process + the panel's HTTP service), but within a budget
            // so a reliably-crashing page falls back to the admin launcher instead of spinning.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer process gone (crash=${detail.didCrash()})")
                val owned = rendererCurrent(generation, view)
                // An OOM-killed renderer can leave a corrupted Chromium code-cache behind, which can
                // crash the REBUILT renderer too — clear it (best-effort; the view is defunct and may
                // throw) so the crash budget isn't burned on a persistent on-disk cause.
                runCatching { view.clearCache(true) }
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (owned) {
                    rendererGate.invalidate()
                    bridge.invalidateForCrash()
                    detachLifecycleBar()
                    web = null
                    shownPage.forget()
                    main.removeCallbacks(watchdog)
                    main.removeCallbacks(darkSettle)
                    runCatching { customViewCallback?.onCustomViewHidden() }
                    customView = null
                    customViewCallback = null
                    runOnUiThread {
                        if (allowRebuild()) {
                            BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary crash rebuild
                            buildAndLoad(Config(this@DashboardActivity))
                        } else { Log.e(TAG, "renderer crash-looping — falling back to admin launcher"); fallbackToLauncher() }
                    }
                }
                return true
            }

            // Keep navigation on the dashboard authority (the panel has no browser). A same-host HTTP
            // dashboard may upgrade to HTTPS behind a proxy/HSTS, but an HTTPS dashboard may never
            // downgrade and hand its external-auth bridge to cleartext content.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!rendererCurrent(generation, view)) return true
                val allowed = dashboardNavigationAllowed(config.haEffectiveUrl, request.url.toString(), allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))
                if (allowed && request.isForMainFrame) {
                    // Ownership transfers when the navigation BEGINS. doUpdateVisitedHistory alone is
                    // too late: a correction completing between these two callbacks would overwrite a
                    // main-frame navigation the user had already started.
                    if (observedDashboardNavigationIsForeign(request.url.toString())) {
                        noteDeliberateDashboardNavigation()
                    }
                    if (!rotateBusDocument(view, config, generation)) return true
                    bridge.expectPageStart(request.url.toString())
                    onLoadStarted()
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!rendererCurrent(generation, view)) return

                Log.d(TAG, "page load started (ha=${dashboardNavigationAllowed(config.haEffectiveUrl, url, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))})")
                val expected = bridge.consumeExpectedPageStart()
                if (!dashboardNavigationAllowed(config.haEffectiveUrl, url, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))) {
                    // Native recovery/auth-latch documents are intentionally bridge-free. Their
                    // loadData navigation also reaches this callback, so never let the redirect
                    // backstop reattach either V2 object to an opaque/local document.
                    bridge.suspend(view)
                    return
                }
                if (expected == url) return
                // Redirects and renderer-initiated reloads can bypass shouldOverrideUrlLoading. They
                // are real navigations that were previously only claimed at doUpdateVisitedHistory,
                // leaving a window in which a delayed correction could still win; claim here too.
                // `expected == url` above already returned for echoes of app-issued loads.
                if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()
                // Rotate only the typed document context here; both listeners were attached before the
                // first load and remain installed throughout this navigation.
                runCatching { bridge.beginDocument(view, config, generation) }
                    .onSuccess { onLoadStarted() }
                    .onFailure {
                        Log.e(TAG, "secure V2 document rotation failed", it)
                        statusScreens.showBlockedAdmissionScreen(
                            getString(R.string.home_assistant_stopped_loading),
                            getString(R.string.home_assistant_stopped_loading_detail),
                            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
                        )
                    }
            }

            // The page now on screen. onPageStarted is documented as a load STARTING, and a load that hangs
            // leaves the previous page drawn; this callback fires only once that previous page will no
            // longer be drawn, so a hung load can never make a retry believe Home Assistant is showing.
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!rendererCurrent(generation, view)) return
                shownPage.onCommitVisible(url)
                // Non-HA documents (the reconnecting page) suspended the bus session in onPageStarted, so
                // only a Home Assistant document can commit here.
                bridge.commitDocument(generation)
                Log.d(TAG, "page shown (ha=${dashboardNavigationAllowed(config.haEffectiveUrl, url, allowHttpsUpgrade = !HaConnectionRoutes.hasLearned(config))})")
            }

            // Real navigation inside Home Assistant's own frontend — a tapped link, a back gesture,
            // or an SPA history change that never reaches onPageStarted. The launch-cache correction
            // must observe THIS, not only the app's own dispatch sites: a user who walks away from
            // the provisional dashboard while the background answer is still pending would otherwise
            // be yanked back by the delayed correction.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!rendererCurrent(generation, view)) return
                if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()
            }

            // Stop the pull-to-refresh spinner once the (main-frame) load settles, success or error, so
            // it never spins forever on a hung reload.
            override fun onPageFinished(view: WebView, url: String) {
                if (!rendererCurrent(generation, view)) return
                bridge.finishDocument(generation)
                swipe?.isRefreshing = false
                // A reload creates a new document even if the card's visibility did not change.
                view.evaluateJavascript(InjectionScript.lifecycleNoticeJs(
                    haLifecycleNoticeState(
                        io.panelassistant.android.sensors.HaLifecycleRuntime.snapshot(),
                        RendererAdmissionRuntime.current()?.takeIf { it.owner == activityOwner },
                    ) != null,
                ), null)
                // HA's frontend viewport can override pre-load zoom; restore the configured
                // built-in renderer scale once the document finishes loading.
                applyZoom()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                // Replace Android's un-styleable gray net::ERR_* page with the branded reconnecting
                // screen; the handshake watchdog (already armed by this load) keeps retrying behind it.
                val detail = "${error.description}".replace("<", "&lt;")
                runOnUiThread { showReconnecting(detail) }
            }

            // HTTP errors are consulted ONLY for the main frame: a sub-resource 401/404 (a HACS module,
            // a picture card) is that card's problem, not the page's — reacting to it (HACA's early
            // mistake) turns one broken card into a broken dashboard. A main-frame error just logs;
            // recovery is the handshake watchdog's job (no `connected` will arrive → reload w/ backoff).
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                Log.w(TAG, "main-frame HTTP ${errorResponse.statusCode}")
            }
        }
    }

    /**
     * Without a WebChromeClient, three dashboard interactions fail SILENTLY: JS `confirm()` (the frontend
     * uses it for destructive actions — the UI hangs on an unanswered result), fullscreen video, and
     * getUserMedia permission requests. This provides the minimum: native dialogs for confirm/alert,
     * fullscreen show/hide, and a permission handler that grants only resources whose Android permission
     * we actually hold — mic is out of scope (the manifest declares no RECORD_AUDIO today, so this stays
     * denied) and the camera is refused outright, because the camera trial's session owner is the only
     * thing allowed to open the device and a page's `getUserMedia()` is not one of its subscribers —
     * else denies explicitly so the card shows its own error instead of hanging.
     */
    private fun dashboardChromeClient(generation: Long) = object : WebChromeClient() {
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            // Showing a dialog on a finishing/destroyed activity throws BadTokenException and takes the
            // whole process down. If we can't show it, cancel the JS call so the frontend isn't left hung.
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.cancel(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.confirm(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!rendererCurrent(generation)) { callback.onCustomViewHidden(); return }
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            root?.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            if (!rendererCurrent(generation)) return
            customView?.let { root?.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!rendererCurrent(generation)) { request.deny(); return }
            val granted = webViewCaptureGrants(
                requested = request.resources,
                permissionHeld = { permission ->
                    ContextCompat.checkSelfPermission(this@DashboardActivity, permission) == PackageManager.PERMISSION_GRANTED
                },
                microphoneIdle = MicrophoneAdmission.isIdle,
                webViewCaptureAllowed = MicrophoneAdmission.webViewCaptureAllowed,
            )
            if (granted.isNotEmpty()) request.grant(granted) else request.deny()
        }
    }

    companion object {
        internal const val TAG = "ha-paneld/dashboard"
        /** Camera trial: the CAMERA runtime-permission request raised when the camera
         *  setting turns on. Distinct from any other request code — this activity had none before. */
        private const val REQUEST_CAMERA_PERMISSION = 4801
        private const val SIGN_IN_LOAD_RETRIES_MAX = 4
        private const val SIGN_IN_LOAD_RETRY_MS = 4_000L
        private const val ADMISSION_COUNTDOWN_TICK_MS = 1_000L  // repaint cadence of the visible retry countdown
        private const val INITIAL_HANDSHAKE_MS = 25_000L        // generous: a cold PX30 frontend can need 20s+
        private const val RELOAD_INTERVAL_MS = 6 * 60 * 60 * 1000L   // shed WebView memory at a screen-off, ~6h
        private const val RELOAD_HARD_MS = 26 * 60 * 60 * 1000L      // force a visible reload if never idle-dark
        private const val PERIODIC_CHECK_MS = 30 * 60 * 1000L        // how often the memory-ceiling check runs
        private const val DARK_SETTLE_MS = 30_000L              // let a screen-off reload load before freezing
        internal const val BG_DARK = PanelTokens.Overlay.DASHBOARD_DARK
        private const val LIGHT_REFRESH_SPINNER_MS = 800L       // bus navigate is instant; brief spinner ack
        private const val HARD_REFRESH_WINDOW_MS = 6_000L       // second pull inside this = full hard reload
        private const val CORRECTION_VERIFY_MS = 10_000L        // bus correction must prove itself by then
        private const val IDLE_CHECK_MS = 60_000L               // idle return-to-home tick
        private const val AUTH_INVALID_LATCH = 3                // consecutive auth-invalid events → latch
        private const val REFRESH_REJECT_LATCH = 2              // consecutive definitive refresh rejections → latch
        private const val WAKE_MEDIA_SETTLE_MS = 3_000L         // let resumeTimers/onResume reach the page first
        private const val WAKE_MEDIA_SAMPLE_MS = 6_000L         // tolerate slow camera transport reconstruction
    }
}

/** Normalize only the route portion; query values and fragments are opaque navigation state. */
internal fun normalizeDashboardTarget(rawTarget: String): String {
    val raw = rawTarget.trim()
    val suffixAt = listOf(raw.indexOf('?'), raw.indexOf('#')).filter { it >= 0 }.minOrNull() ?: raw.length
    return raw.substring(0, suffixAt).trim('/').plus(raw.substring(suffixAt))
}
