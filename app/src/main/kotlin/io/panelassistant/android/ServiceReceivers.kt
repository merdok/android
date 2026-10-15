package io.panelassistant.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewCompat
import io.panelassistant.android.audio.MicrophoneMuteWatch
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.VendorServiceStarter
import io.panelassistant.android.util.ProfileRestartCoordinator
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.SingleFlightExecutor

/**
 * The broadcast receivers the service registers for its own lifetime: adopting a physical screen wake
 * that did not pass through ha-paneld, and rebinding a newly installed WebView provider while the
 * dashboard is parked on an unavailable bridge. Both register under one lock and are released together
 * on the calling thread, so a startup still registering and a teardown releasing cannot interleave.
 *
 * [vendorServiceStarter], [screen], [core] and [webViewRebindRestart] are read when a broadcast
 * arrives, never captured: the service creates this unit before any of them exists.
 */
internal class ServiceReceivers(
    private val context: Context,
    private val teardownBoundary: ServiceTeardownBoundary,
    private val microphoneMute: MicrophoneMuteWatch,
    private val vendorServiceStarter: () -> VendorServiceStarter?,
    private val screen: () -> ScreenController,
    private val core: () -> PanelStateCore,
    private val webViewRebindRestart: () -> ProfileRestartCoordinator,
) {
    // Guards both receiver fields, so a startup registering on the runtime lane and onDestroy releasing
    // on the main thread cannot interleave (see releaseServiceReceivers).
    private val receiverLock = Any()
    // Adopting a wake nobody here performed gets its own worker rather than sharing the observation
    // tick. Single-flight is right for it: the work is
    // idempotent and generation-guarded, so a dropped duplicate costs nothing.
    private val screenWakeWorker = SingleFlightExecutor("ha-paneld-screen-reconcile")
    private var screenOnReceiver: BroadcastReceiver? = null
    private var webViewRebindReceiver: BroadcastReceiver? = null

    /**
     * Adopt a wake that ha-paneld did not perform.
     *
     * `ACTION_SCREEN_ON` is the platform announcing its own display came back. That matters because the keyevent
     * screen-off route puts Android itself to sleep, and Android can be woken from its power key or a
     * wake-capable touchscreen with this process uninvolved — the only route where a wake reaches the
     * panel without passing through ScreenController. Without it a hand-woken screen stays marked off
     * with its renderer frozen: lit, stale and un-tappable.
     *
     * The receiver only hands off. Reconciliation reads the backlight through root or the daemon on
     * the bl_power routes, which must not happen on the main thread.
     */
    fun startScreenOnReconciliation() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON || teardownBoundary.isStopping) return
                vendorServiceStarter()?.request()
                screenWakeWorker.execute {
                    if (teardownBoundary.isStopping) return@execute
                    if (!screen().reconcilePhysicalWake()) return@execute
                    Log.i(TAG, "adopted a physical wake that did not pass through ha-paneld")
                    // Best-effort only, and deliberately after the reconciliation: telling Home
                    // Assistant is the part that is allowed to fail here, not the recovery.
                    runCatching { core().publishScreenOn() }
                        .onFailure { Log.d(TAG, "screen-on publish after a physical wake failed", it) }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        // ACTION_SCREEN_ON is a protected system broadcast, so unlike the navbar's @hide volume action
        // this one is still delivered to a NOT_EXPORTED receiver — the tighter of the two flags.
        synchronized(receiverLock) {
            if (screenOnReceiver != null || teardownBoundary.isStopping) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
            }.onSuccess { screenOnReceiver = receiver }
                .onFailure { Log.w(TAG, "screen-on reconciliation could not be registered", it) }
        }
    }

    /**
     * Unregister every receiver this service registered, on the calling thread. Android sweeps a destroyed
     * service's receivers as soon as onDestroy returns and reports each one still registered as leaked,
     * so this cannot wait for the runtime lane, whose queued teardown may overrun the service deadline.
     * The start functions take the same lock and register nothing once teardown has begun, so a startup
     * still in flight either registers before this runs or not at all.
     */
    fun releaseServiceReceivers() = synchronized(receiverLock) {
        for (receiver in listOfNotNull(screenOnReceiver, webViewRebindReceiver)) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        screenOnReceiver = null
        webViewRebindReceiver = null
        microphoneMute.close()
    }

    /**
     * The physical-wake reconciliation publishes INTO the network runtime, so it must be provably
     * terminal before that runtime is retired. Called on the runtime lane after the receiver is
     * unregistered, so no new work can be posted; joins a reconciliation already in flight.
     */
    fun closeScreenWakeWorker(closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        closeOwnerResult("screen reconcile worker") {
            screenWakeWorker.closeAndJoin(
                minOf(deadline.remainingMs(), PaneldService.WAKE_WORKER_JOIN_MS),
            )
        }
    }

    /**
     * The blocking admission verdict the LIVE renderer generation is parked on, when a WebView provider
     * change could repair it; null in every other case, including a healthy dashboard and a panel blocked
     * on something a new engine cannot fix.
     *
     * [RendererAdmissionRuntime] is the authority rather than anything held here, and that is the whole
     * answer to stale generations: it is never persisted, it refuses a write from a renderer that no
     * longer owns the activity, and it re-applies that ownership test on every read — so a destroyed or
     * replaced activity's verdict stops being visible without a teardown hook to forget.
     */
    private fun blockedProviderRepairableAdmission(): AdmissionOutcome? {
        val record = RendererAdmissionRuntime.current()?.record ?: return null
        if (record.state != RendererAdmissionState.BLOCKED) return null
        return record.outcome?.takeIf { providerRepairableAdmission(it) }
    }

    /** The package that provides this panel's WebView right now, or null when none resolves. */
    private fun resolveWebViewProviderIdentity(): WebViewProviderIdentity? = runCatching {
        WebViewCompat.getCurrentWebViewPackage(context)?.let {
            WebViewProviderIdentity(
                packageName = it.packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(it),
                versionName = it.versionName,
            )
        }
    }.getOrNull()

    /**
     * Notice a WebView provider being installed, updated or repaired while the built-in renderer is
     * parked on "Secure dashboard bridge unavailable", and take the one action that can clear it.
     *
     * That screen is deliberately terminal on a timer ([admissionRetryClass] gives it
     * [AdmissionRetryClass.MANUAL_ONLY]) because a missing WebView capability is not time-dependent, and
     * this does not change that: no cadence, no ladder, no polling, and every other blocked verdict keeps
     * the network and Home Assistant-version recovery it already had. The trigger is the install event
     * itself, and the panel asks nothing in between.
     *
     * Registration is paired with the service's own lifetime rather than with the screen, so the receiver
     * cannot outlive an activity or be registered twice by a repaint; the decision is cheap, and it asks
     * the panel-local questions before it ever queries the provider.
     */
    /**
     * Let a blocked status screen offer the repair this service already knows how to perform.
     *
     * The screen is handed three narrow questions rather than a reference to this service: what the panel
     * can do, how to start it, and what the installer is saying. Nothing here is a second implementation
     * of the repair — [ServiceComponentOperations.installComponent] is the same entry point the Install page posts to, so a panel
     * cannot end up with two ways to install an engine that disagree about what "busy" means.
     *
     * The capability is refreshed off the main thread and then cached. It is refreshed again on each
     * update tick because both of its inputs can change while the panel sits on the blocked screen:
     * a root helper can finish installing, and a profile can arrive with a build pinned for this model.
     */
    fun startWebViewRebindWatch() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (teardownBoundary.isStopping) return
                // Resolved at most once per broadcast, and only if the cheap questions did not already
                // answer: an app updating in the background on a healthy panel costs nothing here.
                val provider = lazy { resolveWebViewProviderIdentity() }
                val blocked = blockedProviderRepairableAdmission()
                val decision = webViewRebindDecision(
                    action = intent?.action,
                    changedPackage = intent?.data?.schemeSpecificPart,
                    replacingExistingInstall = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true,
                    blockedOutcome = blocked,
                    resolveProvider = provider::value,
                )
                when (decision) {
                    WebViewRebindDecision.REBIND ->
                        if (webViewRebindRestart().request()) {
                            Log.i(
                                TAG,
                                "WebView provider installed while the dashboard bridge was unavailable " +
                                    "(${provider.value?.describe() ?: "unresolved"}) — restarting to bind it",
                            )
                        } else {
                            Log.i(TAG, "a WebView provider rebind is already pending in this process")
                        }
                    // A panel that is not parked on a bridge screen has nothing to say about an install,
                    // and a broadcast this rule does not observe is not an event. Everything else is a
                    // decision taken WHILE the dashboard was unavailable, which is worth naming: it is
                    // the difference between "the engine was replaced and nothing happened" and "what
                    // was installed was not the engine".
                    WebViewRebindDecision.NOT_BLOCKED,
                    WebViewRebindDecision.UNRELATED_ACTION,
                    -> Unit

                    else -> if (blocked != null) {
                        Log.i(
                            TAG,
                            "package install ignored while the dashboard bridge was unavailable: " +
                                decision.name.lowercase(),
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // Package broadcasts carry the subject as a `package:` URI; without the scheme the filter
            // matches nothing at all rather than matching everything.
            addDataScheme("package")
        }
        // Both are protected system broadcasts, so — as with the screen-on receiver — the tighter
        // NOT_EXPORTED flag still receives them.
        synchronized(receiverLock) {
            if (webViewRebindReceiver != null || teardownBoundary.isStopping) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
            }.onSuccess { webViewRebindReceiver = receiver }
                .onFailure { Log.w(TAG, "WebView rebind watch could not be registered", it) }
        }
    }

    private companion object {
        const val TAG = PaneldService.TAG
    }
}
