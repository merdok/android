package io.panelassistant.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.panelassistant.android.PaneldService
import io.panelassistant.android.R
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.AndroidSystemEnv
import io.panelassistant.android.util.GuardDbProcessAdmission
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional hardware-button capture. Hardware/capacitive buttons that emit standard Android
 * KeyEvents are reported to HA via [ButtonBus] → Home Assistant `event.<panel>_button`. Enabled at
 * provisioning with `settings put secure enabled_accessibility_services <pkg>/<this>`.
 *
 * ⚠️ Per-hardware uncertain: panels whose buttons are GPIO-only (no KeyEvent) won't surface here
 * and would need a vendor GPIO HAL (load-if-present), not this service. Needs on-device validation.
 * Requires `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in the a11y config XML.
 */
class PanelAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return
        instance = this
        revivePaneldService()
        chooserShown = false
        if (rebindForWindowContent()) return
        // After an in-place update the chooser is already showing when Android binds the service again.
        scheduleHomeChooserAnswer()
    }

    /**
     * Re-enable this service once per process when its connection predates window-content retrieval
     * ([HomeChooserPolicy.rebindEntries]), so the very update that adds it already answers the HOME
     * chooser. Needs WRITE_SECURE_SETTINGS (granted at install); without it nothing is written.
     */
    private fun rebindForWindowContent(): Boolean {
        if (rebindTried) return false
        rebindTried = true
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabled = Settings.Secure.getString(contentResolver, key)
        val others = HomeChooserPolicy.rebindEntries(
            enabled, packageName, javaClass.name, serviceInfo?.capabilities ?: return false,
        ) ?: return false
        Log.i(TAG, "re-enabling the accessibility service to gain window content")
        Thread {
            runCatching {
                Settings.Secure.putString(contentResolver, key, others)
                Thread.sleep(REBIND_GAP_MS)
            }
            runCatching { Settings.Secure.putString(contentResolver, key, enabled) }
                .onFailure { Log.w(TAG, "could not re-enable the accessibility service", it) }
        }.start()
        return true
    }

    /**
     * Bring [PaneldService] up when the process was revived only for this accessibility bind.
     *
     * A foreground-start timeout kills the process *and* drops the started-service record, so there is
     * no START_STICKY re-create waiting to happen. The accessibility service is bound by the system
     * independently of that record, so the process can come back for it alone — no `:8888`, no Home Assistant link and
     * no log shipping. Observed lasting over eight hours, until `MainActivity` was started by hand.
     * This is the route back.
     *
     * Three reasons this cannot start a second instance or fight a deliberate stop:
     * - `PaneldService.start` issues `startForegroundService`, and Android delivers that to the single
     *   existing instance when one is running; `onStartCommand`'s `started` guard makes the redelivery
     *   inert, so `:8888` is never bound twice.
     * - `onServiceConnected` fires on a bind, not on a timer, so there is no loop to run. A deliberate
     *   stop does not unbind accessibility and therefore does not come back through here.
     * - `PaneldService.start` itself refuses while an upgrade holds the service down, and while the
     *   bridge is retired or Guard DB maintenance is required.
     *
     * Never let this throw: `onServiceConnected` failing takes the accessibility bind down with it, and
     * on Android 12+ a foreground start from a background process can be refused outright.
     */
    private fun revivePaneldService() {
        runCatching { PaneldService.start(this) }
            .onFailure { Log.w(TAG, "could not revive PaneldService from the accessibility bind", it) }
    }

    /** Whether this connection pressed HOME to show the chooser; see [HomeChooserPolicy.next]. */
    @Volatile private var chooserShown = false

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName == "android") {
            scheduleHomeChooserAnswer()
        }
    }

    /** Read the chooser once it has settled; a window that has gone by then is simply not answered. */
    private fun scheduleHomeChooserAnswer() {
        mainHandler.removeCallbacks(answerHomeChooser)
        mainHandler.postDelayed(answerHomeChooser, HOME_CHOOSER_SETTLE_MS)
    }

    /**
     * Answer Android's HOME chooser; see [HomeChooserPolicy.next].
     *
     * Always is tapped, not clicked: on Meta's Portal an `ACTION_CLICK` only recorded HOME as chosen once
     * (`mAlways=false`), while a tap on the button's centre, Panel Assistant's ADB method, makes it stick.
     */
    private val answerHomeChooser = Runnable {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return@Runnable
        runCatching {
            val root = rootInActiveWindow
            val always = root?.findAccessibilityNodeInfosByViewId(HomeChooserPolicy.ALWAYS_ID)?.singleOrNull()
            val title = root?.findAccessibilityNodeInfosByViewId(HomeChooserPolicy.TITLE_ID)?.singleOrNull()
            val step = HomeChooserPolicy.next(
                home = AndroidSystemEnv(this).defaultHome(),
                windowPackage = root?.packageName,
                title = title?.text,
                alwaysEnabled = always?.isEnabled,
                appName = getString(R.string.app_name),
                shown = chooserShown,
            )
            when (step) {
                HomeChooserPolicy.Step.NONE -> Unit
                HomeChooserPolicy.Step.SHOW_CHOOSER -> {
                    chooserShown = true
                    Log.i(TAG, "HOME is unowned; showing the chooser=${performGlobalAction(GLOBAL_ACTION_HOME)}")
                }
                HomeChooserPolicy.Step.TAP_ALWAYS -> {
                    val box = Rect().also { always?.getBoundsInScreen(it) }
                    Log.i(TAG, "HOME chooser offers this app; tapped Always=${!box.isEmpty && tap(box.centerX(), box.centerY())}")
                }
                HomeChooserPolicy.Step.DISMISS ->
                    Log.i(TAG, "HOME chooser offers another app; dismissed=${performGlobalAction(GLOBAL_ACTION_BACK)}")
            }
        }.onFailure { Log.w(TAG, "could not read the HOME chooser", it) }
    }

    /**
     * One PNG of the default display through Android 11's accessibility capture, the route a panel
     * without root or the helper has. A capture that finishes after [timeoutMs] releases its buffer.
     * Android refuses captures closer together than about a third of a second; that is a null here,
     * and the panel page keeps showing its last good image.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun capturePng(timeoutMs: Long): ByteArray? {
        val result = AtomicReference<ScreenshotResult?>()
        val abandoned = AtomicBoolean(false)
        val done = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                result.set(screenshot)
                // Whichever side takes the result second finds null, so the buffer is closed exactly once.
                if (abandoned.get()) result.getAndSet(null)?.hardwareBuffer?.close()
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "accessibility screenshot failed: $errorCode")
                done.countDown()
            }
        })
        val finished = runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            abandoned.set(true)
            result.getAndSet(null)?.hardwareBuffer?.close()
            return null
        }
        val shot = result.get() ?: return null
        return shot.hardwareBuffer.use { buffer ->
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return@use null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            ByteArrayOutputStream().use { out ->
                software.compress(Bitmap.CompressFormat.PNG, 100, out)
                software.recycle()
                out.toByteArray()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return super.onKeyEvent(event)
        val name = KeyEvent.keyCodeToString(event.keyCode)
        ButtonEventPolicy.accessibility(
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            eventType = name,
        )?.let {
            Log.d(TAG, "key down: $name")
            ButtonBus.emit(it)
        }
        // Do not consume — let the system handle the key normally.
        return super.onKeyEvent(event)
    }

    companion object : AccessibilityActions {
        private const val TAG = "ha-paneld/buttons"

        // Set while the a11y service is connected; lets the command path perform global nav
        // actions (back/recents/home) with no root — performGlobalAction is an a11y capability.
        @Volatile
        private var instance: PanelAccessibilityService? = null

        override fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        override fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false


        /**
         * Dispatch a short accessibility gesture and wait for its completion when called off-main, as
         * all production callers are. Completion matters to the navbar overlay: it stays non-touchable
         * until the injected tap has landed, avoiding a feedback loop into its own edge strip.
         */
        override fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            val completed = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val accepted = runCatching { service.dispatchGesture(gesture, callback, handler) }
                .getOrDefault(false)
            if (!accepted) return false
            if (Looper.myLooper() == Looper.getMainLooper()) return true
            return runCatching { done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get() }
                .getOrDefault(false)
        }

        override fun canScreenshot(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

        override fun screenshot(timeoutMs: Long): ByteArray? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            if (Looper.myLooper() == Looper.getMainLooper()) return null
            val service = instance ?: return null
            return runCatching { service.capturePng(timeoutMs) }
                .onFailure { Log.w(TAG, "accessibility screenshot threw", it) }
                .getOrNull()
        }

        private const val GESTURE_TIMEOUT_MS = 1000L
        private const val HOME_CHOOSER_SETTLE_MS = 600L
        private const val REBIND_GAP_MS = 1500L
        @Volatile private var rebindTried = false
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
