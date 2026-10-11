package io.panelassistant.android.platform

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A vendor process that switches the backlight while Android stays awake, for the
 * [io.panelassistant.android.device.ScreenOff.VENDOR_RPC] route. Seamed so
 * [io.panelassistant.android.control.ScreenController] runs on the JVM.
 */
interface VendorScreen {
    /** Ask the vendor to switch the backlight. False when it did not answer or refused. */
    fun set(on: Boolean): Boolean

    /** The backlight node's level; null when the node cannot be read. */
    fun level(): Int?

    /** Whether the backlight node reads above zero; null when the node cannot be read. */
    fun lit(): Boolean? = level()?.let { it > 0 }

    /** Stop the vendor driving the backlight from its own light sensor, so the app's level is the only
     *  one. Idempotent; false when the vendor did not answer. */
    fun holdBrightness(): Boolean
}

/** No vendor route: never answers and never reads, so the route falls back. */
object NoVendorScreen : VendorScreen {
    override fun set(on: Boolean) = false
    override fun level(): Int? = null
    override fun holdBrightness() = false
}

/**
 * Shelly's Stargate: its unauthenticated Gen2 RPC `Ui.Screen.Set` on loopback, at the port the profile
 * declares for its local service (`platform.launcher.vendor_service.port`). Stargate is a system app, so
 * it writes the backlight node the app cannot; it writes it behind Android's back, so `SCREEN_BRIGHTNESS`
 * is unchanged and only the node shows the result. Blocking; never call it on the main thread.
 */
class LocalRpcVendorScreen(private val port: Int) : VendorScreen {
    override fun set(on: Boolean): Boolean = call("Ui.Screen.Set?on=$on")

    // Measured on the X2i: with `brightness.auto` off, Stargate's level follows SCREEN_BRIGHTNESS.
    override fun holdBrightness(): Boolean =
        call("Ui.SetConfig?config=" + URLEncoder.encode("""{"brightness":{"auto":false}}""", "UTF-8"))

    private fun call(method: String): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$port/rpc/$method").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            // A Gen2 error is a 200-free reply or a body carrying `code`; a success carries neither.
            connection.responseCode == 200 && !connection.inputStream.bufferedReader().readText().contains("\"code\"")
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    override fun level(): Int? = runCatching {
        File(BACKLIGHT_CLASS).listFiles()?.sortedBy { it.name }?.firstNotNullOfOrNull { dir ->
            runCatching { File(dir, "actual_brightness").readText().trim().toInt() }.getOrNull()
        }
    }.getOrNull()

    private companion object {
        const val BACKLIGHT_CLASS = "/sys/class/backlight"
        const val TIMEOUT_MS = 2_000
    }
}
