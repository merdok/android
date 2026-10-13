package io.panelassistant.android.dashboard

internal enum class DashboardProbeOutcome { UNCHANGED, CHANGED }

/**
 * Whether a held dashboard changed in a way that gives a new scan something to say.
 *
 * A different resolved dashboard is a change even when both revisions match, because the scope the
 * catalogue is bound to no longer describes what the panel would open. A blank stored revision means no
 * scan ever committed; a live revision is always a hash, so the comparison owes a scan there too.
 */
internal fun dashboardProbeOutcome(
    configuredHomeDashboard: String,
    resolvedPath: String,
    boundPath: String,
    liveRevision: String,
    storedRevision: String,
): DashboardProbeOutcome = when {
    resolvedScopeRequiresRebind(configuredHomeDashboard, boundPath, resolvedPath) -> DashboardProbeOutcome.CHANGED
    liveRevision != storedRevision -> DashboardProbeOutcome.CHANGED
    else -> DashboardProbeOutcome.UNCHANGED
}

/** A probe failure is transient unless it is the one fault a person has to repair. */
internal fun probeFailureSurfacesProblem(message: String?): Boolean =
    classifyEntityBootstrapProblem("degraded", message.orEmpty()) == EntityBootstrapProblem.AUTHENTICATION

internal enum class PeriodicMaintenanceAction { NONE, SYNC, PROBE }

/**
 * What the daily maintenance tick owes a catalogue. A catalogue synced within the day owes nothing. One
 * held only on a person's decision is settled: resyncing it cannot change the answer, so the tick asks
 * whether the dashboard changed instead and lets the answer decide whether a scan follows.
 */
internal fun periodicMaintenanceAction(
    sinceLastSyncMs: Long,
    dailyMs: Long,
    heldOnDecision: Boolean,
): PeriodicMaintenanceAction = when {
    sinceLastSyncMs < dailyMs -> PeriodicMaintenanceAction.NONE
    heldOnDecision -> PeriodicMaintenanceAction.PROBE
    else -> PeriodicMaintenanceAction.SYNC
}

/** Why the renderer is held, for the status surface and the person reading it off-panel. */
enum class EntityBootstrapHoldReason(val wireName: String) {
    /** The scan answered and a flagged rule awaits a person's decision; catalogue resync is suspended. */
    DECISION("decision"),
    /** Home Assistant rejected the panel's credential; the sign-in needs repair. */
    AUTHENTICATION("authentication"),
    /** The scan did not finish; the watchdog keeps retrying it. */
    SYNCHRONIZATION("synchronization"),
    /** The first scan has not answered yet. */
    SYNCHRONIZING("synchronizing"),
}

internal fun entityBootstrapHoldReason(
    held: Boolean,
    blockingIssues: Int,
    problem: EntityBootstrapProblem?,
): EntityBootstrapHoldReason? = when {
    !held -> null
    problem == EntityBootstrapProblem.AUTHENTICATION -> EntityBootstrapHoldReason.AUTHENTICATION
    problem == EntityBootstrapProblem.SYNCHRONIZATION -> EntityBootstrapHoldReason.SYNCHRONIZATION
    blockingIssues > 0 -> EntityBootstrapHoldReason.DECISION
    else -> EntityBootstrapHoldReason.SYNCHRONIZING
}

enum class EntityBootstrapProblem { AUTHENTICATION, SYNCHRONIZATION }

/** A scan's failure when Home Assistant gave it no session and did not reject the credential. */
internal const val TOKEN_UNAVAILABLE = "Home Assistant token unavailable"

internal fun classifyEntityBootstrapProblem(state: String, error: String): EntityBootstrapProblem? {
    if (state != "degraded" || error.isBlank()) return null
    val normalized = error.lowercase()
    // No session yet is not a refusal: during first-run sign-in the credential changes under a running scan,
    // which then reports no token. The hold keeps its progress screen and the watchdog retries the scan.
    if (TOKEN_UNAVAILABLE.lowercase() in normalized) return null
    return if (
        "http 401" in normalized || "http 403" in normalized || "credential rejected" in normalized
    ) EntityBootstrapProblem.AUTHENTICATION else EntityBootstrapProblem.SYNCHRONIZATION
}
