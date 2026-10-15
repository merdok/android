package io.panelassistant.android.dashboard

import io.panelassistant.android.dashboardEntityScopePath
import io.panelassistant.android.DashboardEntityDefaultResolverMigration
import io.panelassistant.android.HaConnectionRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

/** Serialize compound entity-learning preference mutations on the manager's intrinsic monitor. */
internal inline fun <T> withEntityLearningMutationLock(owner: Any, mutation: () -> T): T =
    synchronized(owner, mutation)

/** Publish one scan's SQLite evidence and stored overrides under the same manager generation check.
 * A cancelled coroutine can remain on-CPU after [Job.isActive] becomes false; keeping these writes
 * under the mutation monitor prevents a reset from clearing the catalog between the two writes. */
internal inline fun commitEntityLearningSyncEvidence(
    owner: Any,
    ensureCurrent: () -> Unit,
    commitSync: () -> Unit,
    applyStoredOverrides: () -> Unit,
    publishDiagnostics: () -> Unit,
) = synchronized(owner) {
    ensureCurrent()
    commitSync()
    applyStoredOverrides()
    publishDiagnostics()
}

internal fun shouldInitializeEntityLearningOnStart(enabled: Boolean): Boolean = enabled

/**
 * Retire an old effect generation before lazy initialization can start its owned bootstrap sync.
 *
 * Reversing this order cancels the fresh sync immediately and leaves a first-time opt-in held until
 * the hourly maintenance loop. Keep the ordering behind a small pure seam so the lifecycle contract
 * remains deterministic without opening the SQLite-backed manager in a unit test.
 */
internal inline fun initializeEntityLearningAfterEffectInvalidation(
    invalidate: () -> Unit,
    initialize: () -> Boolean,
): Boolean {
    invalidate()
    return initialize()
}

internal data class EntityLearningEffectState(
    val origin: String,
    val instanceKey: String,
    val targetKey: String,
    val dashboardPath: String,
    val credentialFingerprint: String,
    val learningEnabled: Boolean,
    val applied: Boolean,
    val filterEnabled: Boolean,
    val filterIds: List<String>,
    val autoStatic: Boolean,
    val autoRuntime: Boolean,
    val overrides: Map<String, String>,
    val forceBootstrap: Boolean,
    val initialActivationPending: Boolean = false,
    /** The default-resolver migration disabled a running filter and owes this target a re-bootstrap. */
    val upgradeRebootstrapPending: Boolean = false,
    val routeEpoch: Long = 0L,
)

/**
 * Whether an authenticated resolution names a different dashboard than a scan is scoped for.
 *
 * Under Auto the scope is a standing binding until a resolution answers; comparing by DASHBOARD rather
 * than by route is what lets a view of the same dashboard keep its learned list while a genuinely
 * different dashboard gives it up.
 *
 * Only Auto rebinds. An explicit setting always scopes to the configured dashboard, so when this Home
 * Assistant does not offer it and the resolver stands another in, a rebind could never take: the next
 * pass scoped to the configured path again, disagreed again, and relaunched with no backoff, about
 * twice a second, while the renderer stayed held (a panel moved to another Home Assistant). The
 * stand-in is scanned under the configured scope instead, and the panel says it is showing it.
 */
internal fun resolvedScopeRequiresRebind(configuredHomeDashboard: String, currentScope: String, resolved: String): Boolean =
    io.panelassistant.android.util.DashboardPath.followsAccountDefault(configuredHomeDashboard) &&
        dashboardEntityScopePath(currentScope) != dashboardEntityScopePath(resolved)

internal data class EntityLearningSyncSnapshot(
    val generation: Long,
    val baseUrl: String,
    val authToken: String,
    val configuredHomeDashboard: String,
    val homeDashboardAuthorityKey: HomeDashboardResolutionAuthority.Key,
    val state: EntityLearningEffectState,
    val route: HaConnectionRoute? = null,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val learningEnabled get() = state.learningEnabled
    val applied get() = state.applied
    val filterEnabled get() = state.filterEnabled
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime
    val overrides get() = state.overrides
    val forceBootstrap get() = state.forceBootstrap
    val initialActivationPending get() = state.initialActivationPending
    val upgradeRebootstrapPending get() = state.upgradeRebootstrapPending

    fun matchesCurrent(currentGeneration: Long, currentState: EntityLearningEffectState): Boolean =
        generation == currentGeneration && state == currentState
}

internal data class EntityLearningPromotionSnapshot(
    val generation: Long,
    val state: EntityLearningEffectState,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime

    fun isEligible(
        currentGeneration: Long,
        currentState: EntityLearningEffectState,
        blockingIssues: Int,
    ): Boolean = generation == currentGeneration && state == currentState &&
        state.learningEnabled && state.applied && state.autoRuntime && blockingIssues == 0
}

internal enum class RuntimePromotionDecision { UNCHANGED, PROMOTE, CAPPED }

/** A promotion that would not change the applied set is never refused, so the window cap marks the
 * learner degraded only when a wanted promotion is actually held back. */
internal fun runtimePromotionDecision(
    desired: Collection<String>,
    applied: Collection<String>,
    promotionsInWindow: Int,
    maxPromotions: Int,
): RuntimePromotionDecision = when {
    desired.isEmpty() || desired.toSet() == applied.toSet() -> RuntimePromotionDecision.UNCHANGED
    promotionsInWindow >= maxPromotions -> RuntimePromotionDecision.CAPPED
    else -> RuntimePromotionDecision.PROMOTE
}

internal suspend fun <T> withEntityLearningDeadline(
    timeoutMs: Long,
    block: suspend CoroutineScope.() -> T,
): T = kotlinx.coroutines.withTimeout(timeoutMs, block)

/** Effect-changing operations own a replacement generation; the prior scan must release the mutex
 * before a replacement can run and must no longer occupy the admission slot. */
internal fun supersedeEntityLearningSync(current: Job?, clearQueuedRerun: () -> Unit = {}): Job? {
    clearQueuedRerun()
    current?.cancel()
    return null
}

/** Persist failures only for the generation and target that admitted the scan. */
internal fun shouldPersistEntityLearningFailure(
    requestGeneration: Long,
    currentGeneration: Long,
    requestInstance: String,
    requestPath: String,
    currentInstance: String,
    currentPath: String,
): Boolean = requestGeneration == currentGeneration &&
    requestInstance == currentInstance && requestPath == currentPath

/** A resolver-policy upgrade must rescan even when the old, potentially wrong catalog has synced. */
/**
 * [autoScopeUnverifiedThisProcess] is what makes `Auto` safe across a restart.
 *
 * Every other reason here is a property of stored state, and under `Auto` stored state cannot answer
 * the only question that matters: the account default can move while the panel is stopped, and nothing
 * on disk records that. A nonzero `lastSyncAt` therefore certified a filter for the dashboard the panel
 * used to show — so the renderer opened the new dashboard while the previous dashboard's allow-list
 * stayed active until a manual or daily sync happened to run. Forcing a live resolution inside the scan
 * cannot fix that on its own, because this decision is what determines whether a scan runs at all.
 *
 * The cost is one resolution per process under `Auto`, which is the price of the scope being a fact
 * about the account rather than about this panel.
 */
internal fun shouldSyncEntityLearningOnStartup(
    learningEnabled: Boolean,
    lastSyncAt: Long,
    resolverMigration: DashboardEntityDefaultResolverMigration,
    forceBootstrap: Boolean = false,
    analyzerPolicyStale: Boolean = false,
    autoScopeUnverifiedThisProcess: Boolean = false,
): Boolean = learningEnabled && resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED &&
    (forceBootstrap || analyzerPolicyStale || autoScopeUnverifiedThisProcess ||
        resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP || lastSyncAt == 0L)

/** An empty installation starts narrow; any stored manual list is preserved for explicit review. */
internal fun shouldBootstrapEntityLearning(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
): Boolean = learningEnabled && !applied && configuredIds.isEmpty()

internal enum class AutomaticSyncDecision { BLOCKED, OBSERVE, APPLY, BOOTSTRAP }

/** Pure safety policy: a blocking scan is atomic rejection, and disabled learning is scan-only. */
internal fun automaticSyncDecision(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
    blockingIssues: Boolean,
    forceBootstrap: Boolean = false,
): AutomaticSyncDecision = when {
    blockingIssues -> AutomaticSyncDecision.BLOCKED
    !learningEnabled -> AutomaticSyncDecision.OBSERVE
    applied -> AutomaticSyncDecision.APPLY
    forceBootstrap || configuredIds.isEmpty() -> AutomaticSyncDecision.BOOTSTRAP
    else -> AutomaticSyncDecision.OBSERVE
}

/**
 * Whether a resolved-scope rebind may still be written durably.
 *
 * The currency checks around the read guard the READ. This decision happens later and mutates durable
 * target state, and between the two a cooperatively cancelled pass can be overtaken: the endpoint or
 * the configured dashboard changes, and rebinding on the older snapshot overwrites the newer selection.
 * Every component of the snapshot the decision was made from has to still describe the panel.
 */
internal fun resolvedScopeRebindIsStillCurrent(
    snapshotConfigured: String,
    currentConfigured: String,
    snapshotOrigin: String,
    currentOrigin: String,
    generationMatches: Boolean,
): Boolean = snapshotConfigured == currentConfigured &&
    snapshotOrigin == currentOrigin &&
    generationMatches
