package io.panelassistant.android

import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry

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

/**
 * One live-setting value from the panel's own doors (the HTTP config API, the :8888 form, import and
 * restore, the kiosk escape gesture). [authority] journals it, so a value the draining dispatcher does not
 * admit is replayed against its replacement; [applyLiveSetting] then runs it through the dispatcher's
 * settings entry, in the one order Panel Assistant's commands share.
 */
internal fun requestLiveSetting(
    authority: LiveSettingAuthority,
    config: Config,
    commands: () -> PanelCommandDispatcher,
    key: String,
    value: String,
    refreshAutoSleep: () -> Unit,
): LiveSettingRequestOutcome = authority.applyOrQueueOutcomeObserved(
    key = key,
    value = value,
    previousValue = SettingsRegistry.spec(key)?.let(config::getRaw),
) { appliedKey, appliedValue, previous ->
    applyLiveSetting(config, commands(), appliedKey, appliedValue, previous, refreshAutoSleep)
}

internal fun applyLiveSetting(
    config: Config,
    commands: PanelCommandDispatcher,
    key: String,
    value: String,
    previousValue: String?,
    refreshAutoSleep: () -> Unit,
): LiveSettingApplication {
    val spec = SettingsRegistry.spec(key)
        ?: return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
    val previous = previousValue ?: config.getRaw(spec)
    // Network ADB must retain its durable ownership marker until the controller has cleared every
    // classic/TLS property and authoritatively read them all inactive. Persisting false here first
    // made AdbController.set(false) conclude that the listener was external and skip teardown.
    // voice_enabled must not be persisted ahead of handleVoiceEnabled's live hasMicrophone check —
    // this generic path runs before dispatchSetting, so pre-persisting ON here would durably record
    // the setting even when the bridge refuses to act on it for lack of a microphone. handleVoiceEnabled
    // owns persistence itself (mirrors network_adb, whose actuator is also the sole writer).
    // Kiosk ON is durable only once the lock engaged, so its handler persists it; an OFF is fail-safe and
    // durable before it queues, so an unlock asked for while the dispatcher drains survives a restart.
    val actuationOwnsPersistence =
        key == "navbar_mode" || key == "network_adb" || key == "voice_enabled" ||
            key == "kiosk_lock" && SettingValue.parseBool(value) != false
    if (!spec.transient && !actuationOwnsPersistence && !commitHttpLiveSetting(config, spec, value, refreshAutoSleep)) {
        return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
    }
    return commands.applySettingObserved(key, value, previous.takeUnless { spec.transient })
}
