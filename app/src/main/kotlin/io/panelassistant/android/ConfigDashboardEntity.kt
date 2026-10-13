package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.Config.Companion.CONFIG_LOCK
import io.panelassistant.android.Config.Companion.DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY

// Built-in renderer entity filter: the owner-scoped filter, learning and override state, and the instance
// and dashboard keys that own it. Backup and restore staging of this state stays in Config.

internal enum class DashboardEntityDefaultResolverMigration {
    NOT_NEEDED,
    REBOOTSTRAP,
    PERSIST_FAILED,
}

/** Dashboard paths are ownership boundaries. Query/fragment state does not identify a Lovelace dashboard. */
internal fun normalizeDashboardEntityPath(raw: String): String {
    val value = raw.trim().ifBlank { "/" }
    val parsedPath = runCatching { java.net.URI(value).path }.getOrNull().orEmpty().ifBlank { value }
    val leading = if (parsedPath.startsWith('/')) parsedPath else "/$parsedPath"
    return leading.replace(Regex("/{2,}"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
}

/** Length-prefixing avoids collisions if a future instance-key encoding contains the separator. */
/**
 * The dashboard an entity-learning scope belongs to, reduced to its ROOT.
 *
 * Learning fetches `lovelace/config` for the dashboard root and extracts entities from the whole
 * document, so every view of one dashboard yields the same learned set. Scoping ownership by the full
 * route would therefore discard a filter that a rescan reproduces byte for byte — the panel would drop
 * its filter and re-learn every time the home view changed, which is precisely what choosing a specific
 * view is for. This is deliberately NOT [normalizeDashboardEntityPath]: that one keeps the full route,
 * because navigation, idle return and no-op save detection must still see a view change as a change.
 */
internal fun dashboardEntityScopePath(raw: String): String {
    val normalized = normalizeDashboardEntityPath(raw)
    val root = normalized.trim('/').substringBefore('/')
    return if (root.isBlank()) "/" else "/$root"
}

/** The dashboard-scope portion of a target key produced by [dashboardEntityTargetKey], or "" if
 *  unparseable. Operator intent is owned by instance, so this component is inert for ownership and
 *  records only where the intent was first captured — which is why adoption preserves it rather than
 *  restamping it with whatever dashboard happens to be configured at adoption time. */
internal fun dashboardEntityPathOf(targetKey: String): String {
    val separator = targetKey.indexOf(':')
    if (separator <= 0) return ""
    val length = targetKey.substring(0, separator).toIntOrNull() ?: return ""
    val start = separator + 1
    if (length <= 0 || start + length > targetKey.length) return ""
    return targetKey.substring(start + length)
}

/** The instance portion of a target key produced by [dashboardEntityTargetKey], or "" if unparseable. */
internal fun dashboardEntityInstanceOf(targetKey: String): String {
    val separator = targetKey.indexOf(':')
    if (separator <= 0) return ""
    val length = targetKey.substring(0, separator).toIntOrNull() ?: return ""
    val start = separator + 1
    if (length <= 0 || start + length > targetKey.length) return ""
    return targetKey.substring(start, start + length)
}

internal fun dashboardEntityTargetKey(instanceKey: String, dashboardPath: String): String {
    val key = instanceKey.trim()
    if (key.isEmpty()) return ""
    val path = dashboardEntityScopePath(dashboardPath)
    return "${key.length}:$key$path"
}

/**
 * Experimental built-in-renderer entity filter. The exact entity ids deliberately live outside the
 * settings registry: they are a potentially large, installation-specific API input rather than a
 * settled product setting or an HA entity. The dedicated `/api/v1/dashboard/entity-filter` endpoint
 * is the only management surface and reports count/hash rather than echoing the ids.
 */
val Config.dashboardEntityFilterEnabled: Boolean
    get() = !dashboardEntityDefaultResolverMigrationRequired &&
        !dashboardEntityDefaultResolverRebootstrapPending &&
        entityStateOwnedByCurrent("dashboard_entity_filter_instance") &&
        prefs.getBoolean("dashboard_entity_filter_enabled", false)
val Config.dashboardEntityFilterIds: List<String>
    get() = if (entityStateOwnedByCurrent("dashboard_entity_filter_instance")) rawDashboardEntityFilterIds else emptyList()
internal val Config.rawDashboardEntityFilterIds: List<String>
    get() = prefs.getString("dashboard_entity_filter_ids", "").orEmpty()
        .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()

/** The evidence namespace currently selected for the configured HA endpoint. A URL-derived key is
 *  used until mDNS can tie the authenticated endpoint to HA's stable core.uuid. */
val Config.dashboardEntityInstanceKey: String get() = prefs.getString("dashboard_entity_instance", "").orEmpty()
val Config.dashboardEntityInstanceOrigin: String get() = prefs.getString("dashboard_entity_instance_origin", "").orEmpty()
val Config.dashboardEntityInstanceUuid: String get() = prefs.getString("dashboard_entity_instance_uuid", "").orEmpty()
val Config.dashboardEntityDashboardPath: String
    get() = prefs.getString("dashboard_entity_dashboard_path", "").orEmpty()
val Config.dashboardEntityTargetKey: String
    get() = dashboardEntityInstanceKey.takeIf(String::isNotBlank)?.let {
        dashboardEntityTargetKey(it, dashboardEntityDashboardPath)
    }.orEmpty()

/**
 * Ownership for USER-AUTHORED state (pins and forced exclusions), which follows the Home Assistant
 * instance and deliberately NOT the dashboard.
 *
 * A learned list is derived data: re-scoping it costs a rescan and it comes back identical. A pin or
 * an exclusion is the operator's own work, cannot be regenerated, and is equally valid on every
 * dashboard of the same instance — so a dashboard change must never silently withdraw it. The
 * instance still bounds it, because an entity id is only meaningful inside one Home Assistant:
 * `light.office` on another instance is an unrelated entity that merely shares a string.
 *
 * Losing an exclusion is the quieter failure of the two. A dropped pin shows up as a card that
 * stops updating; a dropped exclusion silently re-subscribes to something removed on purpose, and
 * the only symptom is the panel slowly getting heavier again.
 */
private fun Config.entityStateOwnedByInstance(ownerPreference: String): Boolean {
    val ownerKey = dashboardEntityInstanceOf(prefs.getString(ownerPreference, "").orEmpty())
    val currentKey = dashboardEntityInstanceKey
    if (ownerKey.isBlank() && currentKey.isBlank() && dashboardEntityInstanceOrigin.isBlank()) return true
    if (currentKey.isBlank() || ownerKey != currentKey) return false
    return dashboardEntityInstanceOrigin == canonicalHaOrigin(haUrl)
}

private fun Config.entityStateOwnedByCurrent(ownerPreference: String): Boolean {
    val current = dashboardEntityTargetKey
    val owner = prefs.getString(ownerPreference, "").orEmpty()
    // Entirely blank selection/owner is the pre-instance-identity compatibility state. Once a URL or
    // dashboard setter records a target change, even legacy unowned state must fail closed immediately.
    if (current.isBlank() && owner.isBlank() && dashboardEntityInstanceOrigin.isBlank() &&
        dashboardEntityDashboardPath.isBlank()) return true
    if (current.isBlank()) return false
    val configuredOrigin = canonicalHaOrigin(haUrl) ?: return false
    val configuredPath = dashboardEntityScopePath(homeDashboard)
    val scopeAgrees = configuredPath == "/" || dashboardEntityDashboardPath == configuredPath
    return current == owner && dashboardEntityInstanceOrigin == configuredOrigin && scopeAgrees
}

/** Select the URL-keyed namespace and dashboard after a target change. Existing ownership is left
 *  behind, so a different/unverified HA or dashboard cannot inherit filter state. Null means the
 *  durable preference transaction failed and callers must not proceed with the selected target. */
fun Config.prepareDashboardEntityInstance(
    origin: String,
    dashboardPath: String,
    legacyKey: String,
): String? = synchronized(CONFIG_LOCK) {
    if (legacyKey.isBlank()) return null
    val canonicalOrigin = canonicalHaOrigin(origin) ?: return null
    val normalizedPath = dashboardEntityScopePath(dashboardPath)
    val currentOrigin = dashboardEntityInstanceOrigin
    val currentKey = dashboardEntityInstanceKey
    val currentPath = dashboardEntityDashboardPath
    if (currentKey.isNotBlank() && currentOrigin == canonicalOrigin && currentPath == normalizedPath) return currentKey
    val firstBinding = currentKey.isBlank() && currentOrigin.isBlank() && currentPath.isBlank()
    // A dashboard change at a verified origin retains its stable instance namespace. An origin change
    // must fall back to its URL-derived key until mDNS proves that it is merely an alias.
    val selectedKey = currentKey.takeIf { it.isNotBlank() && currentOrigin == canonicalOrigin } ?: legacyKey
    val selectedUuid = dashboardEntityInstanceUuid.takeIf {
        selectedKey == currentKey && currentOrigin == canonicalOrigin
    }.orEmpty()
    val selectedTarget = dashboardEntityTargetKey(selectedKey, normalizedPath)
    val committed = applyBatch {
        edit {
            putString("dashboard_entity_instance_origin", canonicalOrigin)
            putString("dashboard_entity_instance", selectedKey)
            putString("dashboard_entity_instance_uuid", selectedUuid)
            putString("dashboard_entity_dashboard_path", normalizedPath)
            // Derived owners are re-rooted in the same transaction that roots the path, because
            // nothing else ever would. An install upgrading from route-qualified ownership holds
            // `…:key/lovelace/kiosk` while the new target key is `…:key/lovelace`; doing this only at
            // first binding left every existing install owned by a key that can no longer be
            // constructed, so the filter read as unowned and the renderer closed — and a startup that
            // found the catalogue already synced skipped the rescan meant to repair it, making the
            // hold permanent rather than a single re-learn.
            //
            // Re-rooting is lossless, not a re-learn: learning fetches per dashboard ROOT and
            // extracts from the whole document, so a view and its dashboard yield the same set. The
            // list behind the owner is already correct. A DIFFERENT root is not migrated — that is a
            // genuine dashboard change, and failing closed so it re-learns is the intended behaviour.
            for (preference in listOf("dashboard_entity_filter_instance", "dashboard_entity_applied_instance")) {
                val owner = prefs.getString(preference, "").orEmpty()
                val reRoot = when {
                    owner.isBlank() -> firstBinding
                    dashboardEntityInstanceOf(owner) != selectedKey -> false
                    else -> dashboardEntityScopePath(dashboardEntityPathOf(owner)) == normalizedPath
                }
                if (reRoot) putString(preference, selectedTarget)
            }
            if (firstBinding && prefs.getString("dashboard_entity_override_instance", "").isNullOrBlank()) {
                putString("dashboard_entity_override_instance", selectedTarget)
            }
        }
    }
    return selectedKey.takeIf { committed }
}

/** Adopt a UUID namespace only if the endpoint is still the one that was authenticated/discovered.
 *  First adoption moves legacy ownership without altering a known-good list; later URL aliases for
 *  the same UUID simply select the already-owned stable key. */
fun Config.adoptDashboardEntityInstance(
    expectedOrigin: String,
    expectedDashboardPath: String,
    uuid: String,
    legacyKey: String,
    stableKey: String,
): Boolean = synchronized(CONFIG_LOCK) {
    if (uuid.isBlank() || legacyKey.isBlank() || stableKey.isBlank()) return false
    val canonicalOrigin = canonicalHaOrigin(expectedOrigin) ?: return false
    val normalizedPath = dashboardEntityScopePath(expectedDashboardPath)
    if (dashboardEntityInstanceOrigin != canonicalOrigin || dashboardEntityDashboardPath != normalizedPath) return false
    val current = dashboardEntityInstanceKey
    if (current != legacyKey && current != stableKey) return false
    val legacyTarget = dashboardEntityTargetKey(legacyKey, normalizedPath)
    val stableTarget = dashboardEntityTargetKey(stableKey, normalizedPath)
    return applyBatch {
        edit {
            putString("dashboard_entity_instance", stableKey)
            putString("dashboard_entity_instance_origin", canonicalOrigin)
            putString("dashboard_entity_instance_uuid", uuid)
            putString("dashboard_entity_dashboard_path", normalizedPath)
            val filterOwner = prefs.getString("dashboard_entity_filter_instance", "").orEmpty()
            if (filterOwner.isBlank() || filterOwner == legacyKey || filterOwner == legacyTarget) {
                putString("dashboard_entity_filter_instance", stableTarget)
            }
            val appliedOwner = prefs.getString("dashboard_entity_applied_instance", "").orEmpty()
            if (appliedOwner.isBlank() || appliedOwner == legacyKey || appliedOwner == legacyTarget) {
                putString("dashboard_entity_applied_instance", stableTarget)
            }
            // The filter and applied owners above are DERIVED state, scoped to the dashboard root, so
            // whole-key equality is the right test for them. Operator intent is not: a pin or
            // exclusion is owned by the Home Assistant instance alone. Comparing its whole
            // dashboard-qualified key stranded any intent recorded before a dashboard change — the
            // paths differ, adoption skipped it, and the entries then read as owned by the legacy
            // instance under the stable key: invisible to the operator and overwritten by the next
            // edit. Match on the instance component alone, and keep the owner's own path so the
            // record still says where the intent was captured.
            val overrideOwner = prefs.getString("dashboard_entity_override_instance", "").orEmpty()
            if (overrideOwner.isBlank() || overrideOwner == legacyKey ||
                dashboardEntityInstanceOf(overrideOwner) == legacyKey
            ) {
                val capturedPath = dashboardEntityPathOf(overrideOwner).ifBlank { normalizedPath }
                putString("dashboard_entity_override_instance",
                    dashboardEntityTargetKey(stableKey, capturedPath))
            }
        }
    }
}

/** Productized learner request. Distinct from runtime filter-active so first sync can fail open. */
val Config.dashboardEntityLearningEnabled: Boolean
    get() = boolPref("dashboard_entity_learning")
fun Config.setDashboardEntityLearningEnabled(enabled: Boolean) { edit { putBoolean("dashboard_entity_learning", enabled) } }

/** Which evidence sources may automatically change the live subscription. Evidence is still
 * collected when either switch is off so the Entities tab can present it for manual pinning. */
val Config.dashboardEntityAutoStatic: Boolean
    get() = boolPref("dashboard_entity_auto_static")
val Config.dashboardEntityAutoRuntime: Boolean
    get() = boolPref("dashboard_entity_auto_runtime")

/**
 * v220 initially resolved a blank/default dashboard as ordinary Lovelace. That could publish an
 * exact allow-list for a different panel than the frontend rendered. Invalidate that one ambiguous
 * automatic result once, while retaining its ids as inactive rollback/debug evidence, and require a
 * fresh scan using the complete user -> system -> built-in Home resolution chain.
 *
 * [dashboardEntityFilterEnabled] also checks this migration latch, so a failed preference commit
 * cannot briefly reactivate the suspect allow-list.
 */
internal fun Config.migrateDashboardEntityDefaultResolver(): DashboardEntityDefaultResolverMigration =
    synchronized(CONFIG_LOCK) {
    if (!dashboardEntityDefaultResolverMigrationRequired) return DashboardEntityDefaultResolverMigration.NOT_NEEDED
    val committed = applyBatch { edit {
        putInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION)
        putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, dashboardEntityTargetKey)
        putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, dashboardEntityTargetKey)
        putBoolean("dashboard_entity_filter_enabled", false)
        putBoolean("dashboard_entity_learning_applied", false)
        putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
    } }
    return when {
        !committed -> DashboardEntityDefaultResolverMigration.PERSIST_FAILED
        else -> DashboardEntityDefaultResolverMigration.REBOOTSTRAP
    }
}

/**
 * One-time latch for collapsing route-keyed catalogue rows onto their dashboard root.
 *
 * Deliberately a preference latch rather than a schema step: this is a DATA reconciliation, the
 * schema contract forbids ad-hoc conditionals in `onUpgrade`, and a version bump here would collide
 * with the schema work already reserved by another lane. The store migration is idempotent, so a
 * failed commit costs a repeat rather than corruption.
 */
val Config.dashboardEntityScopeNamespaceMigrationRequired: Boolean
    get() = prefs.getInt("dashboard_entity_scope_namespace_version", 0) < 1

fun Config.markDashboardEntityScopeNamespaceMigrated(): Boolean =
    applyBatch { edit { putInt("dashboard_entity_scope_namespace_version", 1) } }

private val Config.dashboardEntityDefaultResolverMigrationRequired: Boolean
    get() {
        val target = dashboardEntityTargetKey
        if (target.isBlank() || !dashboardEntityLearningEnabled ||
            normalizeDashboardEntityPath(homeDashboard) != "/") return false
        // Narrowed, not removed. What this invalidates is an allow-list that may have been derived
        // while a blank dashboard resolved AMBIGUOUSLY — as ordinary Lovelace rather than through
        // the user -> system -> built-in Home chain — and so could describe a different panel than
        // the frontend rendered. A panel whose learning scope is a NAMED dashboard did not derive
        // its list that way, and Auto now retains and reconciles that binding instead of collapsing
        // it, so there is no ambiguity left to discard. Without this, every switch to "Account
        // default" threw away learned entities AND the operator's pins for a dashboard the panel
        // was already using. The protection still stands for the case it was written for: a panel
        // with no established dashboard, where a blank value really could resolve to anything.
        if (dashboardEntityDashboardPath.let { it.isNotBlank() && it != "/" }) return false
        return prefs.getInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, 0) <
            DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION ||
            prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, "").orEmpty() != target
    }

internal val Config.dashboardEntityDefaultResolverRebootstrapPending: Boolean
    get() {
        val target = dashboardEntityTargetKey
        return target.isNotBlank() &&
            prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, "").orEmpty() == target
    }

/** Clear the durable hold only after the replacement subscription has committed successfully. */
internal fun Config.clearDashboardEntityDefaultResolverRebootstrapPending(): Boolean = synchronized(CONFIG_LOCK) {
    if (!dashboardEntityDefaultResolverRebootstrapPending) return true
    return applyBatch { edit { remove(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY) } }
}

/** Change automatic-learning mode without exposing a half-disabled stream. A fresh enable clears
 * the activation latch and reactivates any current-owner preserved allow-list for safe observation;
 * disabling turns interception off but keeps that list for a later re-enable. */
fun Config.commitDashboardEntityLearningMode(enabled: Boolean, clearApplied: Boolean): Boolean {
    val restorePreservedFilter = enabled && dashboardEntityFilterIds.isNotEmpty()
    val firstOptIn = enabled && !dashboardEntityLearningEnabled && rawDashboardEntityFilterIds.isEmpty() &&
        prefs.getString("dashboard_entity_applied_instance", "").isNullOrBlank()
    return applyBatch {
        edit {
            putBoolean("dashboard_entity_learning", enabled)
            if (firstOptIn) putBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, true)
            if (clearApplied) {
                putBoolean("dashboard_entity_learning_applied", false)
                putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
            }
            // Keep the old list under its old owner, but disable interception globally. Otherwise
            // switching back to that owner later would silently reactivate a filter after learning
            // was explicitly disabled on another target.
            if (!enabled) {
                putBoolean("dashboard_entity_filter_enabled", false)
            } else if (restorePreservedFilter) {
                putBoolean("dashboard_entity_filter_enabled", true)
            }
        }
    }
}

/** True after a fresh installation has safely bootstrapped or an observed set was explicitly applied. */
val Config.dashboardEntityLearningApplied: Boolean
    get() = entityStateOwnedByCurrent("dashboard_entity_applied_instance") &&
        boolPref("dashboard_entity_learning_applied")

internal val Config.dashboardEntityInitialActivationPending: Boolean
    get() = prefs.getBoolean(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY, false)

/** Backup-safe expert overrides; the derived catalog and metrics remain rebuildable SQLite state. */
val Config.dashboardEntityOverrides: Map<String, String>
    get() = if (!entityStateOwnedByInstance("dashboard_entity_override_instance")) emptyMap() else
        stringPref("dashboard_entity_overrides").lineSequence().mapNotNull { line ->
        val marker = line.firstOrNull() ?: return@mapNotNull null
        val id = line.drop(1).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        when (marker) { '+' -> id to "pinned"; '-' -> id to "forced_exclude"; else -> null }
    }.toMap()

fun Config.setDashboardEntityOverrides(values: Map<String, String>): Boolean {
    val encoded = values.toSortedMap().mapNotNull { (id, value) ->
        when (value) { "pinned" -> "+$id"; "forced_exclude" -> "-$id"; else -> null }
    }.joinToString("\n")
    return applyBatch { edit {
        putString("dashboard_entity_overrides", encoded)
        putString("dashboard_entity_override_instance", dashboardEntityTargetKey)
    } }
}

/** Persist the complete experimental filter atomically so enabled cannot reference a partial list. */
fun Config.setDashboardEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
    val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
        .distinct().sorted().joinToString("\n")
    return applyBatch {
        edit {
            putString("dashboard_entity_filter_ids", normalized)
            putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
            putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
        }
    }
}

/** Publish an automatic subscription and its activation latch as one owner-scoped transaction. */
fun Config.commitDashboardEntitySubscription(
    enabled: Boolean,
    entityIds: Collection<String>,
    applied: Boolean,
): Boolean {
    val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
        .distinct().sorted().joinToString("\n")
    return applyBatch { edit {
        putString("dashboard_entity_filter_ids", normalized)
        // Automatic learning may intentionally install an empty allow-list after the user ignores
        // an unsafe selector. Enabled-empty means "subscribe nothing", never "fall back unfiltered".
        putBoolean("dashboard_entity_filter_enabled", enabled)
        putBoolean("dashboard_entity_learning_applied", applied)
        putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
        putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        if (applied) remove(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY)
    } }
}

/** Reset rebuildable learning preferences in one commit. The default preserves the known-good live
 * allow-list; an explicit clean-slate reset also clears it without disabling automatic learning. */
fun Config.commitDashboardEntityEvidenceReset(clearFilter: Boolean): Boolean {
    val target = dashboardEntityTargetKey
    if (target.isBlank()) return false
    return applyBatch { edit {
        putString("dashboard_entity_overrides", "")
        putString("dashboard_entity_override_instance", target)
        putBoolean("dashboard_entity_learning_applied", false)
        putString("dashboard_entity_applied_instance", target)
        remove(DASHBOARD_ENTITY_INITIAL_ACTIVATION_PENDING_KEY)
        if (clearFilter) {
            putString("dashboard_entity_filter_ids", "")
            putBoolean("dashboard_entity_filter_enabled", false)
            putString("dashboard_entity_filter_instance", target)
        }
    } }
}

/** Commit a promotion policy and any resulting live subscription as one durable preference change.
 *  A null [activeEntityIds] means the current stream is unchanged. */
fun Config.commitDashboardEntityPromotionPolicy(
    staticRefs: Boolean,
    runtimeRefs: Boolean,
    activeEntityIds: Collection<String>?,
    applied: Boolean,
): Boolean {
    val normalized = activeEntityIds?.asSequence()?.map(String::trim)?.filter(String::isNotEmpty)
        ?.distinct()?.sorted()?.joinToString("\n")
    return applyBatch { edit {
        putBoolean("dashboard_entity_auto_static", staticRefs)
        putBoolean("dashboard_entity_auto_runtime", runtimeRefs)
        putBoolean("dashboard_entity_learning_applied", applied)
        putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        if (normalized != null) {
            putString("dashboard_entity_filter_ids", normalized)
            putBoolean("dashboard_entity_filter_enabled", applied)
            putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
        }
    } }
}

/** Atomically leave automatic learning and install a manually managed filter for the selected target.
 *  An enabled manual filter must contain at least one id; a target must have been durably prepared. */
fun Config.commitDashboardManualEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
    val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
        .distinct().sorted().joinToString("\n")
    val target = dashboardEntityTargetKey
    if (enabled && normalized.isEmpty()) return false
    if (target.isBlank() || canonicalHaOrigin(haUrl) != dashboardEntityInstanceOrigin ||
        dashboardEntityScopePath(homeDashboard).let { it != "/" && it != dashboardEntityDashboardPath }
    ) return false
    return applyBatch { edit {
        putBoolean("dashboard_entity_learning", false)
        putBoolean("dashboard_entity_learning_applied", false)
        putString("dashboard_entity_applied_instance", target)
        putString("dashboard_entity_filter_ids", normalized)
        putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
        putString("dashboard_entity_filter_instance", target)
    } }
}

/** Immediately hide filter state when the configured endpoint changes. The URL-derived instance key
 *  is selected later by [prepareDashboardEntityInstance], once its legacy key has been calculated. */
internal fun Config.stageDashboardEntityHaUrlChange(editor: SharedPreferences.Editor, nextUrl: String) {
    val currentEndpoint = haUrl.trim().trimEnd('/')
    val nextEndpoint = nextUrl.trim().trimEnd('/')
    if (currentEndpoint != nextEndpoint) clearHaDeviceLink(editor)
    val current = canonicalHaOrigin(haUrl) ?: haUrl.trim().trimEnd('/')
    val next = canonicalHaOrigin(nextUrl) ?: nextUrl.trim().trimEnd('/')
    if (current == next) return
    editor.remove("dashboard_entity_instance")
        .remove("dashboard_entity_instance_uuid")
        .putString("dashboard_entity_instance_origin", next)
}

private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY =
    "dashboard_entity_default_resolver_version"
private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY =
    "dashboard_entity_default_resolver_target"
private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY =
    "dashboard_entity_default_resolver_pending"
private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION = 1
