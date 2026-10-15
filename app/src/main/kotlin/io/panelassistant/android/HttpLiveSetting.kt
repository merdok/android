package io.panelassistant.android

import io.panelassistant.android.config.SettingSpec

/**
 * The HTTP live-setting lane persists a value before the shared command path applies it, so that path's
 * own auto_sleep write then reads UNCHANGED and never refreshes the controller. This lane refreshes it.
 */
internal fun commitHttpLiveSetting(
    config: Config,
    spec: SettingSpec,
    value: String,
    refreshAutoSleep: () -> Unit,
): Boolean {
    val wasAutoSleep = config.autoSleep
    if (!config.commitRaw(spec, value)) return false
    if (spec.key == "auto_sleep" && config.autoSleep != wasAutoSleep) refreshAutoSleep()
    return true
}
