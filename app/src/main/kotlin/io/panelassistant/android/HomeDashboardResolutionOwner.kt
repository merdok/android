package io.panelassistant.android

import io.panelassistant.android.dashboard.EntityLearningProtocol
import io.panelassistant.android.dashboard.homeDashboardIsSubstitute

internal data class HomeDashboardResolutionOwner(
    val authOwner: HaAuthOwner,
    val configuredPath: String,
)

/** Owns the only scan-independent dashboard-list result allowed to admit the renderer. */
internal class HomeDashboardResolutionAttemptGate {
    data class Ticket(val epoch: Long, val owner: HomeDashboardResolutionOwner)

    @Volatile private var current: Ticket? = null
    private var nextEpoch = 0L

    @Synchronized fun start(owner: HomeDashboardResolutionOwner): Ticket =
        Ticket(++nextEpoch, owner).also { current = it }

    fun owns(ticket: Ticket, currentOwner: HomeDashboardResolutionOwner): Boolean =
        current == ticket && ticket.owner == currentOwner

    @Synchronized fun invalidate() { current = null }
}

internal data class OwnedHomeDashboardResolution(
    val owner: HomeDashboardResolutionOwner,
    val resolution: EntityLearningProtocol.HomeDashboardResolution,
    /** False while the value is the persisted launch cache — already rendering, awaiting the live
     *  answer that confirms, corrects or (on a confirmed empty list) replaces it. */
    val confirmed: Boolean = true,
)

/**
 * The home dashboard path [resolution] holds for [owner], or null while there is none yet: nothing
 * resolved, resolution invalidated for a re-admission, a setting or credential change that has not
 * reached the activity's rebuild, or a confirmed list with no legal dashboard. Null means "not yet",
 * never an error; each caller decides what waiting looks like.
 */
internal fun ownedHomeDashboardPath(
    resolution: OwnedHomeDashboardResolution?,
    owner: HomeDashboardResolutionOwner,
): String? = resolution?.takeIf { it.owner == owner }?.resolution?.path

/**
 * A live [resolution] as the dashboard holds it. A stand-in for a configured dashboard this Home
 * Assistant lacks stays provisional, so the next rebuild or retry asks Home Assistant again and moves
 * onto that dashboard once the owner creates it, instead of keeping the stand-in for the activity's life.
 */
internal fun liveHomeDashboardResolution(
    owner: HomeDashboardResolutionOwner,
    resolution: EntityLearningProtocol.HomeDashboardResolution,
): OwnedHomeDashboardResolution = OwnedHomeDashboardResolution(
    owner, resolution, confirmed = !homeDashboardIsSubstitute(owner.configuredPath, resolution),
)
