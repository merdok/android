package io.panelassistant.android.http

import io.panelassistant.android.sameOriginDashboardRoute
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.util.DashboardPath

/** What an archive's settings would restore to, and why any of them cannot. */
internal data class RestoreSettingsDecision(
    val accepted: Map<String, String>,
    val errors: List<String>,
)

/**
 * A stored value from an older archive, in the form the current validator can read.
 *
 * `home_dashboard` had no validator before this release, so an archive can hold a whole address. The
 * path canonicalizer refuses a URL scheme, so handing it one returns null and falls back to the
 * original, which then fails validation — and because a restore is all or nothing, that single
 * historical value takes the entire archive with it, precisely when its owner needs it. This panel's
 * own origin is stripped first, exactly as the live store does on upgrade. A route naming a different
 * server is left alone and still refused, rather than silently retargeted at someone else's dashboard.
 */
internal fun restorableSettingValue(key: String, value: String, configuredOrigin: String?): String =
    when (key) {
        "home_dashboard" -> {
            val candidate = sameOriginDashboardRoute(value, configuredOrigin) ?: value
            if (DashboardPath.followsAccountDefault(candidate)) ""
            else DashboardPath.canonical(candidate, preserveRoute = true) ?: value
        }
        else -> value
    }

/** Only structural unavailability may omit a live setting, before it can admit actuator intent.
 * Validation and security admission still see the original archive; failed supported work remains a failure. */
internal fun unavailableRestoreSettings(values: Map<String, String>, capabilities: Capabilities): List<String> =
    values.filter { (key, value) ->
        SettingsRegistry.spec(key)?.let { it.liveApply && !it.canApplyValue(value, capabilities) } == true
    }.keys.toList()

internal fun RestoreConfigPlan.withAvailableLiveSettings(capabilities: Capabilities): RestoreConfigPlan {
    val unavailable = unavailableRestoreSettings(values, capabilities)
    if (unavailable.isEmpty()) return this
    return copy(
        values = values - unavailable.toSet(),
        warnings = warnings + unavailable.map { "$it: unavailable on this panel" },
        skipped = skipped + unavailable,
    )
}

/** The restore plan's per-setting decision, separated from the transport so it can be asserted. */
internal fun planRestoreSettings(
    migrated: Map<String, String>,
    configuredOrigin: String?,
): RestoreSettingsDecision {
    val accepted = LinkedHashMap<String, String>()
    val errors = ArrayList<String>()
    for ((key, value) in migrated) {
        val spec = SettingsRegistry.spec(key)
        when {
            SettingsRegistry.isPersistedExposure(key) -> {
                val normalized = SettingValue.parseBool(value)?.toString()
                if (normalized == null) errors += "$key: expected a boolean" else accepted[key] = normalized
            }
            spec == null -> errors += "$key: unknown setting"
            spec.readOnly || spec.transient -> errors += "$key: setting cannot be restored"
            else -> when (
                val validated = SettingValue.validate(spec, restorableSettingValue(key, value, configuredOrigin))
            ) {
                is Validation.Ok -> accepted[key] = validated.normalized
                is Validation.Bad -> errors += "$key: ${validated.reason}"
            }
        }
    }
    return RestoreSettingsDecision(accepted, errors)
}
