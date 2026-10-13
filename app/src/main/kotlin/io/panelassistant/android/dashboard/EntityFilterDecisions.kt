package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject

internal fun visibleDashboardIssuesJson(
    storedIssuesJson: String,
    showAdvisories: Boolean,
    dynamicExpressionsJson: String = "[]",
): String {
    val visible = visibleDashboardIssues(storedIssuesJson, showAdvisories)
    val visibleJson = visible.toString()
    val counts = EntityCatalogIssuePersistence.counts(visibleJson)
    return JSONObject()
        .put("items", visible)
        .put("dashboard_issue_count", counts.first)
        .put("blocking_issue_count", counts.second)
        .put("ignored_issue_count", EntityCatalogIssuePersistence.ignoredCount(visibleJson))
        .put("dynamic_expressions", JSONArray(dynamicExpressionsJson))
        .toString()
}

internal fun entityStreamMode(held: Boolean, filtered: Boolean): String =
    when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" }

/**
 * An allowed dashboard-strategy finding on a filtered stream (issue #133 follow-up). Home Assistant
 * builds a strategy's cards from the entities it receives, so with the check allowed an entity outside
 * the subscription gets no card and never reaches runtime learning; nothing the panel observes can
 * list it, so the configure pages say so instead. Visibility only: the filter is never changed here.
 */
internal fun strategySelectorAllowed(streamMode: String, visibleIssues: JSONArray): Boolean =
    streamMode == "filtered" && (0 until visibleIssues.length()).any { index ->
        val issue = visibleIssues.optJSONObject(index) ?: return@any false
        issue.optBoolean("ignored") && issue.optString("presentation_code") ==
            DashboardConfigurationLint.PresentationCode.DASHBOARD_STRATEGY.wireName
    }

internal fun visibleDashboardIssues(storedIssuesJson: String, showAdvisories: Boolean): JSONArray {
    val stored = JSONArray(EntityCatalogIssuePersistence.boundExistingJson(storedIssuesJson))
    return JSONArray().apply {
        for (index in 0 until stored.length()) {
            val issue = stored.optJSONObject(index) ?: continue
            val safetyFinding = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
            if (showAdvisories || safetyFinding) put(issue)
        }
    }
}

internal fun canActivateLearnedEntitySet(defaultResolverRebootstrapPending: Boolean): Boolean =
    !defaultResolverRebootstrapPending

/** First-run compatibility defaults are durable choices, not a blanket weakening of later scans. */
internal fun defaultIgnoredIssueFingerprints(
    initialActivationPending: Boolean,
    bootstrap: Boolean,
    issues: Collection<JSONObject>,
): Set<String> {
    if (!bootstrap || !initialActivationPending) return emptySet()
    return ignorableIssueFingerprints(issues)
}

/** The blocking findings a default may take: never a fence, and never an advisory that blocks nothing. */
internal fun ignorableIssueFingerprints(issues: Collection<JSONObject>): Set<String> = issues.asSequence()
    .filter(EntityCatalogIssuePersistence::canIgnore)
    .mapNotNull { it.optString("fingerprint").takeIf(String::isNotBlank) }
    .toSet()

/**
 * Whether an upgrade-forced re-bootstrap is eligible for recovery at all.
 *
 * The migration latch is the only trigger: a person's own "Reset learned data" also forces a bootstrap,
 * but that person is looking at the Entities page where the rules are shown, and deciding for them
 * there would be a surprise. A retained allow-list is the evidence that filtering on this dashboard was
 * accepted before; without one there is nothing to restore and the decision is genuinely load-bearing.
 */
internal fun upgradeRecoveryAdmissible(
    upgradeRebootstrapPending: Boolean,
    retainedFilterIds: Collection<String>,
): Boolean = upgradeRebootstrapPending && retainedFilterIds.isNotEmpty()

/**
 * Whether the set now restorable carries the COMPLETE filter the panel was running.
 *
 * A subset is not a partial success, it is silent canonical loss. The apply overwrites the stored
 * subscription with what it installs and clears the one-shot migration latch, so ids missing here are
 * missing for good: no later pass has any record that they were ever in the filter, and the only
 * symptom is cards that quietly stop updating. The caller must therefore evaluate this against the
 * exact post-commit candidate — `commitSync` advances `missing_streak` for entities absent from the
 * scan and `desiredIds` drops rows that reach three, so a pre-commit measurement can overstate what
 * survives. Anything short of complete leaves the decision hold standing, which a person can resolve.
 */
internal fun upgradeRecoveryPreservesFilter(
    retainedFilterIds: Collection<String>,
    recoveredIds: Collection<String>,
): Boolean {
    // Nothing retained means nothing to preserve, and `containsAll` would vacuously agree. An empty
    // candidate needs no clause of its own: it cannot contain a non-empty retained list, so the
    // superset check below is the single place this policy lives. A separate emptiness guard was tried
    // and removed — the mutation battery proved no test could tell it from its absence.
    if (retainedFilterIds.isEmpty()) return false
    return recoveredIds.toSet().containsAll(retainedFilterIds.toSet())
}

/** Ignoring a saturated selector means omitting its complete linter projection, never a partial set. */
internal fun effectiveLintEntityIds(
    lint: DashboardConfigurationLint.Result,
    ignoredFingerprints: Set<String>,
): Set<String> = if (lint.issues.any {
        it.type == DashboardConfigurationLint.IssueType.SELECTOR_BUDGET && it.fingerprint in ignoredFingerprints
    }) emptySet() else lint.safeEntityIds

/** Merge only independent bounded evidence after the linter projection has applied its safety policy. */
internal fun deriveStaticEntityIds(
    scannedEntityIds: Collection<String>,
    catalogIds: Set<String>,
    expandedTargets: Collection<String>,
    lintEntityIds: Collection<String>,
): java.util.SortedSet<String> = sortedSetOf<String>().apply {
    addAll(scannedEntityIds.filter(catalogIds::contains))
    addAll(expandedTargets)
    addAll(lintEntityIds)
}

internal data class EntitySubscriptionPreview(
    val currentCount: Int,
    val additions: Int,
    val removals: Int,
    val streamChange: Boolean,
)

/** Pure current→policy preview used by both status and the confirmed apply result. */
internal fun previewEntitySubscription(
    filtered: Boolean,
    currentIds: Collection<String>,
    catalogCount: Int,
    desiredIds: Collection<String>,
): EntitySubscriptionPreview {
    val desired = desiredIds.toSet()
    if (!filtered) return EntitySubscriptionPreview(
        currentCount = catalogCount,
        additions = 0,
        removals = (catalogCount - desired.size).coerceAtLeast(0),
        // Enabling an empty allow-list is still a real transport change: the existing unfiltered
        // WebView must reload so subscribe_entities is rewritten to entity_ids=[].
        streamChange = true,
    )
    val current = currentIds.toSet()
    return EntitySubscriptionPreview(
        currentCount = current.size,
        additions = (desired - current).size,
        removals = (current - desired).size,
        streamChange = current != desired,
    )
}

internal data class BlockingIssueSelection(val fingerprints: List<String>, val allIgnorable: Boolean)

/** One atomic preflight for every surface that offers the bulk-ignore action. */
internal fun blockingIssueSelection(rawIssues: String): BlockingIssueSelection = runCatching {
    val issues = JSONArray(rawIssues)
    val fingerprints = mutableListOf<String>()
    var sawBlocking = false
    var allIgnorable = true
    for (index in 0 until issues.length()) {
        val issue = issues.optJSONObject(index)?.takeIf { it.optBoolean("blocking", false) } ?: continue
        sawBlocking = true
        val fingerprint = issue.optString("fingerprint").takeIf(String::isNotBlank)
        if (!EntityCatalogIssuePersistence.canIgnore(issue) || fingerprint == null) {
            allIgnorable = false
        } else {
            fingerprints += fingerprint
        }
    }
    BlockingIssueSelection(fingerprints, sawBlocking && allIgnorable)
}.getOrDefault(BlockingIssueSelection(emptyList(), false))

/**
 * Which entity ids an Entities-tab query may return, or null for "no id restriction".
 *
 * Extracted as a pure function because the restriction is composed here, upstream of the store's SQL
 * predicate, and a row the query never selects cannot be rescued by any predicate. Reviewing that
 * predicate's text proves nothing about it.
 *
 * `review` deliberately reaches beyond the active ids. A pinned entity dropped from the applied set
 * after repeated misses is exactly the row Review exists to show, and restricting to the active ids
 * made it unreachable — invisible to the operator and impossible to unpin. `subscribed` keeps the
 * active ids exactly, because it reports the LIVE subscription and folding pins in would overstate
 * what the panel is watching.
 */
internal fun entityQueryIncludeIds(
    subscribed: Boolean,
    review: Boolean,
    held: Boolean,
    filtered: Boolean,
    activeIds: Set<String>,
    pinnedIds: Set<String>,
): Set<String>? = when {
    (subscribed || review) && held -> emptySet()
    review && filtered -> activeIds + pinnedIds
    subscribed && filtered -> activeIds
    else -> null
}
