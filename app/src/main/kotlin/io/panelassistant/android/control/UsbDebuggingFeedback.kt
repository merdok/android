package io.panelassistant.android.control

import android.content.Context
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import io.panelassistant.android.Notice
import io.panelassistant.android.NoticeForm
import io.panelassistant.android.i18n.CatalogueLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Main-looper feedback for observed USB-setting changes, never an ADB controller. */
internal class UsbDebuggingFeedback private constructor(
    handler: Handler?,
    private val readState: () -> Boolean?,
    private val isStopping: () -> Boolean,
    private val unregister: (ContentObserver) -> Unit,
    private val show: (Boolean) -> (() -> Unit)?,
) : ContentObserver(handler), AutoCloseable {
    private var previous: Boolean? = null
    private var closed = false
    private var cancelShown: (() -> Unit)? = null

    override fun onChange(selfChange: Boolean) {
        if (closed) return
        if (isStopping()) {
            close()
            return
        }
        val current = readState()
        if (isStopping()) {
            close()
            return
        }
        val prior = previous
        previous = current
        if (current == null) {
            cancelFeedback()
        } else if (prior != null && current != prior) {
            cancelFeedback()
            cancelShown = show(current)
        }
    }

    private fun cancelFeedback() {
        val cancel = cancelShown
        cancelShown = null
        cancel?.invoke()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            cancelFeedback()
        } finally {
            unregister(this)
        }
    }

    companion object {
        fun supportsProfile(id: String): Boolean = id == "meta-portal" || id == "meta-portal-10-gen2"

        /** Registration and all subsequent callbacks/close run on the same main looper. */
        fun observe(
            profileId: String,
            handler: Handler?,
            readState: () -> Boolean?,
            isStopping: () -> Boolean,
            register: (ContentObserver) -> Unit,
            unregister: (ContentObserver) -> Unit,
            show: (Boolean) -> (() -> Unit)?,
        ): UsbDebuggingFeedback? {
            if (!supportsProfile(profileId) || isStopping()) return null
            val observer = UsbDebuggingFeedback(handler, readState, isStopping, unregister, show)
            try {
                register(observer)
                // Seed after registration; queued notifications cannot invent a startup transition.
                observer.previous = readState()
                if (isStopping()) {
                    observer.close()
                    return null
                }
                return observer
            } catch (error: Exception) {
                runCatching { observer.close() }
                throw error
            }
        }

        /** Cancellation invalidates preparation, already-posted UI work and existing feedback. */
        fun showWhenPrepared(
            isStopping: () -> Boolean,
            prepare: (ready: (String) -> Unit) -> (() -> Unit),
            post: (() -> Unit) -> Unit,
            show: (String) -> (() -> Unit)?,
        ): () -> Unit {
            // Only the main looper reads/writes these; preparation only posts its result there.
            var cancelled = false
            var cancelShown: (() -> Unit)? = null
            val cancelPreparation = prepare { text ->
                post {
                    if (!cancelled && !isStopping()) cancelShown = show(text)
                }
            }
            return {
                cancelled = true
                cancelPreparation()
                cancelShown?.invoke()
                cancelShown = null
            }
        }

        /** Main-looper lifetime shared by the overlay and its normal long-Toast fallback. */
        fun presentStatus(
            canOverlay: () -> Boolean,
            attachOverlay: () -> (() -> Unit),
            showToast: () -> (() -> Unit),
            schedule: (Long, () -> Unit) -> (() -> Unit),
        ): () -> Unit {
            val remove = if (runCatching(canOverlay).getOrDefault(false)) {
                runCatching(attachOverlay).getOrElse { showToast() }
            } else showToast()
            var removed = false
            var cancelTimeout: (() -> Unit)? = null
            val cancel = {
                if (!removed) {
                    removed = true
                    cancelTimeout?.invoke()
                    remove()
                }
            }
            try {
                cancelTimeout = schedule(8_000L, cancel)
            } catch (error: Exception) {
                cancel()
                throw error
            }
            return cancel
        }

        private fun showStatus(context: Context, handler: Handler, text: String): () -> Unit = presentStatus(
            canOverlay = { Settings.canDrawOverlays(context) },
            attachOverlay = {
                // This card belongs to the setting observer, independently of HA lifecycle notices.
                val notice = Notice(context, backdropDark = false, form = NoticeForm.OVERLAY,
                    brand = false, surfaceAlpha = 0xFF)
                notice.setBody(notice.sentence(text))
                val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    notice.widthPx, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = notice.px(12f)
                    // Android 12+ otherwise blocks touches through an untrusted opaque overlay.
                    alpha = if (Build.VERSION.SDK_INT >= 31) {
                        context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
                            .coerceAtMost(0.8f)
                    } else 0.8f
                }
                windows.addView(notice.view, params)
                val remove: () -> Unit = {
                    runCatching { windows.removeViewImmediate(notice.view) }
                        .onFailure { Log.w("ha-paneld", "could not remove USB debugging feedback", it) }
                }
                remove
            },
            showToast = {
                Log.i("ha-paneld", "USB debugging feedback using long Toast fallback")
                val toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG)
                toast.show()
                toast::cancel
            },
            schedule = { delay, expire ->
                val task = Runnable { expire() }
                handler.postDelayed(task, delay)
                val cancel: () -> Unit = { handler.removeCallbacks(task) }
                cancel
            },
        )

        fun observePortal(
            context: Context,
            profileId: String,
            handler: Handler,
            scope: CoroutineScope,
            readState: () -> Boolean?,
            isStopping: () -> Boolean,
            uiLanguage: () -> String?,
        ): UsbDebuggingFeedback? = runCatching { observe(
            profileId, handler, readState, isStopping,
            register = { context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, it,
            ) },
            unregister = { observer ->
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
                    .onFailure { Log.w("ha-paneld", "could not release USB debugging feedback", it) }
            },
            show = { enabled ->
                showWhenPrepared(
                    isStopping = isStopping,
                    prepare = { ready ->
                        val job = scope.launch(Dispatchers.IO) {
                            runCatching {
                                val strings = CatalogueLoader.assets(context)
                                    .strings(CatalogueLoader.nativeLocale(uiLanguage()))
                                strings.get("dashboard.fact.usb_debugging") + ": " +
                                    strings.get(if (enabled) "dashboard.value.on" else "dashboard.value.off")
                            }.onSuccess(ready)
                                .onFailure { Log.w("ha-paneld", "could not prepare USB debugging feedback", it) }
                        }
                        val cancel: () -> Unit = { job.cancel() }
                        cancel
                    },
                    post = { handler.post(it) },
                    show = { text ->
                        runCatching {
                            val cancel = showStatus(context.applicationContext, handler, text)
                            Log.i("ha-paneld", "USB debugging feedback: ${if (enabled) "on" else "off"}")
                            cancel
                        }.onFailure { Log.w("ha-paneld", "could not show USB debugging feedback", it) }.getOrNull()
                    },
                )
            },
        ) }.onFailure { Log.w("ha-paneld", "could not observe USB debugging feedback", it) }.getOrNull()
    }
}
