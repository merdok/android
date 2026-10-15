package io.panelassistant.android.control

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
    private var cancelToast: (() -> Unit)? = null

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
            cancelToast = show(current)
        }
    }

    private fun cancelFeedback() {
        val cancel = cancelToast
        cancelToast = null
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

        /** Cancellation invalidates preparation, already-posted UI work and an existing Toast. */
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
                            val toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT)
                            toast.show()
                            Log.i("ha-paneld", "USB debugging feedback: ${if (enabled) "on" else "off"}")
                            val cancel: () -> Unit = toast::cancel
                            cancel
                        }.onFailure { Log.w("ha-paneld", "could not show USB debugging feedback", it) }.getOrNull()
                    },
                )
            },
        ) }.onFailure { Log.w("ha-paneld", "could not observe USB debugging feedback", it) }.getOrNull()
    }
}
