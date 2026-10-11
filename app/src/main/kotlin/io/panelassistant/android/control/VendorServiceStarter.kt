package io.panelassistant.android.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import io.panelassistant.android.PanelTokens
import io.panelassistant.android.device.VendorService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts a profile-declared vendor activity whose creation starts a local service
 * (`platform.launcher.vendor_service`), once, hidden under a cover.
 *
 * Shelly's Stargate serves the local RPC Home Assistant's Shelly integration uses only once its main
 * activity has been created, and nothing creates it after a boot while this app is Home. Starting it
 * shows a vendor screen, so the start happens beneath a full-screen opaque overlay and the dashboard is
 * brought back before the overlay goes: the user never sees the vendor screen. Without the overlay grant
 * nothing is started, because an uncovered vendor screen is the one outcome this must never produce.
 *
 * Every request probes the port first, so a service that is already listening is left alone. At most one
 * run at a time, at most [MAX_ATTEMPTS] vendor starts per process and none within [RETRY_INTERVAL_MS] of
 * the last. Never throws.
 */
class VendorServiceStarter(
    private val service: VendorService?,
    private val seams: Seams,
    private val executor: Executor,
    private val attempts: AtomicInteger = AtomicInteger(),
    /** Run on the worker whenever a check finds the service up, so vendor settings can be re-asserted. */
    private val onUp: () -> Unit = {},
) {
    /** The platform edges, injected so the behaviour runs on the JVM. Cover calls block until done. */
    interface Seams {
        fun listening(port: Int): Boolean
        fun canDrawOverlays(): Boolean
        fun interactive(): Boolean
        /** Show the opaque cover; false when it could not be shown. */
        fun addCover(): Boolean
        fun removeCover()
        fun startVendor(service: VendorService)
        fun bringDashboardBack()
        /** The dashboard is resumed in front again, so lifting the cover cannot uncover the vendor screen. */
        fun dashboardInFront(): Boolean
        fun now(): Long
        fun sleep(ms: Long)
        fun stopping(): Boolean
    }

    enum class State(val wire: String) {
        PENDING("pending"),
        UP("up"),
        STARTED("started"),
        FAILED("failed"),
        DEFERRED("deferred:screen-off"),
        NO_OVERLAY("skipped:no-overlay"),
        NONE("skipped:none"),
    }

    @Volatile var state: State = if (service == null) State.NONE else State.PENDING
        private set
    private val running = AtomicBoolean(false)
    @Volatile private var lastAttemptAt: Long? = null

    /** Ask for a check, and a start if nothing listens: at service start and on every screen-on. */
    fun request() {
        if (service == null) return
        runCatching { executor.execute { run() } }.onFailure { Log.w(TAG, "vendor service check could not be queued", it) }
    }

    fun diagnosticLine(): String =
        "[vendor-service] port=${service?.port ?: "-"} state=${state.wire} attempts=${attempts.get()}"

    private fun run() {
        val service = service ?: return
        if (!running.compareAndSet(false, true)) return
        try {
            if (seams.stopping()) return
            if (seams.listening(service.port)) {
                state = State.UP
                runCatching(onUp).onFailure { Log.w(TAG, "vendor service follow-up failed", it) }
                return
            }
            if (!seams.canDrawOverlays()) {
                if (state != State.NO_OVERLAY) Log.w(TAG, "vendor service on ${service.port} is down; no overlay grant, so it is not started")
                state = State.NO_OVERLAY
                return
            }
            // The vendor activity turns the screen on; wait for the next screen-on rather than wake it.
            if (!seams.interactive()) {
                state = State.DEFERRED
                return
            }
            if (attempts.get() >= MAX_ATTEMPTS) return
            val now = seams.now()
            lastAttemptAt?.let { if (now - it < RETRY_INTERVAL_MS) return }
            lastAttemptAt = now
            attempts.incrementAndGet()
            state = attempt(service)
            Log.i(TAG, "vendor service on ${service.port}: ${state.wire}")
            if (state == State.STARTED) runCatching(onUp).onFailure { Log.w(TAG, "vendor service follow-up failed", it) }
        } catch (t: Throwable) {
            state = State.FAILED
            Log.w(TAG, "vendor service start failed", t)
        } finally {
            running.set(false)
        }
    }

    private fun attempt(service: VendorService): State {
        if (!seams.addCover()) return State.NO_OVERLAY
        try {
            seams.startVendor(service)
            val deadline = seams.now() + START_TIMEOUT_MS
            while (!seams.stopping()) {
                if (seams.listening(service.port)) return State.STARTED
                if (seams.now() >= deadline) break
                seams.sleep(POLL_MS)
            }
            return State.FAILED
        } finally {
            runCatching { seams.bringDashboardBack() }.onFailure { Log.w(TAG, "dashboard could not be brought back", it) }
            // Measured on the X2i: the dashboard took longer than a fixed hold to come back after a cold
            // vendor start, so the cover was lifted over Stargate's screen. Wait for the dashboard itself.
            runCatching {
                val back = seams.now() + RETURN_TIMEOUT_MS
                while (!seams.dashboardInFront() && seams.now() < back) seams.sleep(POLL_MS)
                seams.sleep(COVER_HOLD_MS)
            }
            runCatching { seams.removeCover() }.onFailure { Log.w(TAG, "vendor cover removal failed", it) }
        }
    }

    companion object {
        private const val TAG = "VendorService"
        const val MAX_ATTEMPTS = 3
        const val RETRY_INTERVAL_MS = 10 * 60_000L
        const val START_TIMEOUT_MS = 6_000L
        const val POLL_MS = 200L
        const val COVER_HOLD_MS = 700L
        const val RETURN_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 500

        private val PROCESS_ATTEMPTS = AtomicInteger()
        private val WORKER: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ha-paneld-vendor-service").apply { isDaemon = true } }
        }

        /** The starter of the live service generation, for the diagnostics dump. */
        @Volatile private var installed: VendorServiceStarter? = null

        fun diagnosticLine(): String? = installed?.diagnosticLine()

        /** The real starter for [context], sharing one worker thread and one attempt budget per process. */
        fun forService(
            context: Context,
            service: VendorService?,
            returnSurface: () -> (() -> Boolean),
            stopping: () -> Boolean,
            onUp: () -> Unit = {},
        ): VendorServiceStarter =
            VendorServiceStarter(service, AndroidSeams(context.applicationContext, returnSurface, stopping), WORKER, PROCESS_ATTEMPTS, onUp)
                .also { installed = it }
    }

    private class AndroidSeams(
        private val context: Context,
        private val returnSurface: () -> (() -> Boolean),
        private val isStopping: () -> Boolean,
    ) : Seams {
        private val main = Handler(Looper.getMainLooper())
        private val windows = context.getSystemService(WindowManager::class.java)
        private var cover: View? = null
        private var surfaceInFront: () -> Boolean = { false }

        override fun listening(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

        override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

        override fun interactive(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive != false

        override fun addCover(): Boolean = onMain {
            val view = View(context).apply { setBackgroundColor(PanelTokens.Overlay.DASHBOARD_DARK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
            windows.addView(view, params)
            cover = view
            true
        }.let { added ->
            // A late add still lands on main after the wait gave up; the removal queued behind it clears it.
            if (added != true) removeCover()
            added == true
        }

        override fun removeCover() {
            onMain { cover?.let { windows.removeViewImmediate(it) }; cover = null }
        }

        override fun startVendor(service: VendorService) {
            context.startActivity(
                Intent().setComponent(ComponentName(service.packageName, service.className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        override fun bringDashboardBack() {
            surfaceInFront = returnSurface()
        }

        override fun dashboardInFront(): Boolean = surfaceInFront()

        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun sleep(ms: Long) = Thread.sleep(ms)
        override fun stopping(): Boolean = isStopping()

        /** Run [block] on the main thread and wait for it; null if it threw or did not finish in time. */
        private fun <T> onMain(block: () -> T): T? {
            var result: T? = null
            val done = CountDownLatch(1)
            main.post {
                result = runCatching(block).onFailure { Log.w(TAG, "vendor cover window operation failed", it) }.getOrNull()
                done.countDown()
            }
            return if (done.await(2, TimeUnit.SECONDS)) result else null
        }
    }
}
