package io.panelassistant.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android's own device name (Settings > About > Device name), kept equal to the name the owner chose for
 * the panel. Only a chosen name is written: an unset friendly name leaves Android's name alone, so the
 * model or a vendor default never becomes a name. Bluetooth is left alone too, because Meta mirrors its
 * own Portal name there. Writing needs WRITE_SECURE_SETTINGS, which Panel Assistant grants at install.
 */
internal class AndroidDeviceName(
    private val granted: () -> Boolean,
    private val read: () -> String?,
    private val write: (String) -> Boolean,
) {
    enum class Outcome { NOT_CHOSEN, UNCHANGED, WRITTEN, NOT_GRANTED, FAILED }

    fun follow(chosen: String?): Outcome {
        if (chosen.isNullOrBlank()) return Outcome.NOT_CHOSEN
        if (read() == chosen) return Outcome.UNCHANGED
        if (!granted()) return Outcome.NOT_GRANTED
        return if (runCatching { write(chosen) }.getOrDefault(false) && read() == chosen) Outcome.WRITTEN else Outcome.FAILED
    }

    /** What the diagnostics dump shows, so a panel without network ADB can still be read back. */
    fun diagnostic(): String = "secure_settings=${granted()} device_name=\"${read().orEmpty()}\""

    companion object {
        fun of(context: Context) = AndroidDeviceName(
            granted = {
                runCatching {
                    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            },
            read = { runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() },
            write = { Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, it) },
        )
    }
}
