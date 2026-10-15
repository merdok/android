package io.panelassistant.android.control

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.panelassistant.android.i18n.CatalogueLoader

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

        fun observePortal(
            context: Context,
            profileId: String,
            handler: Handler,
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
                runCatching {
                    val strings = CatalogueLoader.assets(context).strings(CatalogueLoader.nativeLocale(uiLanguage()))
                    val text = strings.get("dashboard.fact.usb_debugging") + ": " +
                        strings.get(if (enabled) "dashboard.value.on" else "dashboard.value.off")
                    if (isStopping()) return@runCatching null
                    val toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT)
                    toast.show()
                    Log.i("ha-paneld", "USB debugging feedback: ${if (enabled) "on" else "off"}")
                    val cancel: () -> Unit = toast::cancel
                    cancel
                }.onFailure { Log.w("ha-paneld", "could not show USB debugging feedback", it) }.getOrNull()
            },
        ) }.onFailure { Log.w("ha-paneld", "could not observe USB debugging feedback", it) }.getOrNull()
    }
}
