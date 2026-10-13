package io.panelassistant.android.platform

/** Optional accessibility-service boundary for global navigation, gesture injection and screen capture. */
interface AccessibilityActions {
    /** Perform Android's global Back action. */
    fun back(): Boolean

    /** Perform Android's global Recents action. */
    fun recents(): Boolean

    /** Inject and complete a tap at device-pixel coordinates. */
    fun tap(x: Int, y: Int): Boolean

    /** Whether [screenshot] can work now: the service is connected on Android 11 or later. */
    fun canScreenshot(): Boolean = false

    /** Capture the default display as PNG within [timeoutMs], or null. Never call on the main thread. */
    fun screenshot(timeoutMs: Long): ByteArray? = null
}
