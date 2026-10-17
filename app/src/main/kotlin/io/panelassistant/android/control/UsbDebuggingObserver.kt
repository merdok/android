package io.panelassistant.android.control

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.state.StateConverger.Observation

/** Observes Android's USB debugging setting; it does not control ADB or prove a USB connection. */
internal class UsbDebuggingObserver(
    private val sdkInt: () -> Int,
    private val previewSdkInt: () -> Int = { 0 },
    private val readSetting: () -> String?,
) {
    constructor(context: Context) : this(
        sdkInt = { Build.VERSION.SDK_INT },
        previewSdkInt = { Build.VERSION.PREVIEW_SDK_INT },
        readSetting = { Settings.Global.getString(context.contentResolver, Settings.Global.ADB_ENABLED) },
    )

    fun state(): Boolean? {
        // Android 17 redacts this setting to 0 for ordinary apps. A redacted zero is not OFF.
        // AOSP source: https://android.googlesource.com/platform/frameworks/base/+/5d466bf8025ed8a68d9d49b47289f4f5cefbc139
        // A preview of 17 can still identify itself as API 36.
        if (sdkInt() >= 37 || (sdkInt() == 36 && previewSdkInt() > 0)) return null
        return when (runCatching(readSetting).getOrNull()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun observation(): Observation.Reportable = when (state()) {
        true -> Observation.Known("ON")
        false -> Observation.Known("OFF")
        null -> Observation.NoReading(StateConverger.NoReadingReason.NOT_READABLE)
    }

    fun statusText(): String = when (state()) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }
}
