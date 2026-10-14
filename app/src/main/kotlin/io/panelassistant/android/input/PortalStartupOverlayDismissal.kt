package io.panelassistant.android.input

import android.view.accessibility.AccessibilityEvent

/**
 * Return from Meta's denied overlay request once during the first thirty seconds of a new boot.
 * The same page opened by the owner in that window also returns; later visits stay open.
 */
internal class PortalStartupOverlayDismissal(
    profileId: String,
    bootCount: Int,
    readBoot: () -> Int?,
    writeBoot: (Int) -> Boolean,
    private val label: String,
    private val now: () -> Long,
    private val activeWindow: () -> Window?,
    private val back: () -> Boolean,
    private val post: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
) : AutoCloseable {
    data class Toggle(val visible: Boolean, val enabled: Boolean, val checkable: Boolean, val checked: Boolean)
    data class Window(val id: Int, val packageName: String?, val headers: List<String?>, val toggles: List<Toggle>)

    // Record the observed boot before arming: a process/service restart cannot spend it again, even
    // when Android's BACK result is uncertain. The first installation only establishes a baseline.
    private val armed = runCatching {
        val prior = readBoot()
        if (!supportsProfile(profileId) || bootCount < 0 || prior != null && bootCount <= prior) false
        else writeBoot(bootCount) && prior != null && prior >= 0 &&
            label.isNotBlank() && now() in 0L until BOOT_WINDOW_MS
    }.getOrDefault(false)
    private var activityWindow: Int? = null
    private var queued = false
    private var attempted = false
    @Volatile private var closed = false

    /** Content events retain the activity identity only for the same window, never their widget class. */
    fun onEvent(type: Int, packageName: CharSequence?, className: CharSequence?, windowId: Int) {
        if (!armed || closed || attempted || now() !in 0L until BOOT_WINDOW_MS) return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            activityWindow = windowId.takeIf {
                it >= 0 && packageName?.toString() == SETTINGS && className?.toString() == ACTIVITY
            }
        } else if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (packageName?.toString() != SETTINGS || activityWindow != windowId || queued) return
        queued = true
        post(checkWindow)
    }

    private fun matches(window: Window?): Boolean = window != null && window.id == activityWindow &&
        window.packageName == SETTINGS && window.headers.size == 1 && window.headers.single() == label &&
        window.toggles.size == 1 && window.toggles.single().let {
            it.visible && it.enabled && it.checkable && !it.checked
        }

    private val checkWindow = Runnable {
        queued = false
        if (!closed && !attempted && now() in 0L until BOOT_WINDOW_MS) {
            val window = activeWindow()
            // The event may already be stale, or the owner may navigate while the tree is read.
            if (matches(window) && activeWindow() == window && !closed && !attempted &&
                now() in 0L until BOOT_WINDOW_MS) {
                attempted = true
                back()
            }
        }
    }

    override fun close() {
        closed = true
        cancel(checkWindow)
    }

    companion object {
        const val PACKAGE = "com.facebook.alohaapps.controlcenter"
        const val SETTINGS = "com.android.settings"
        const val ACTIVITY = "com.android.settings.Settings\$AppDrawOverlaySettingsActivity"
        const val HEADER_ID = "com.android.settings:id/entity_header_title"
        const val SWITCH_ID = "android:id/switch_widget"
        const val BOOT_WINDOW_MS = 30_000L
        const val BOOT_KEY = "portal_overlay_observed_boot"
        fun supportsProfile(id: String): Boolean = id == "meta-portal" || id == "meta-portal-10-gen2"
    }
}
