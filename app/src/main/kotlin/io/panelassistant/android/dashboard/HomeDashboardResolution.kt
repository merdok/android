package io.panelassistant.android.dashboard

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.HaAuthSnapshot
import io.panelassistant.android.stableOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class HomeDashboardCatalog(
    /** True only when the authenticated dashboard-list request completed; empty items can then mean zero. */
    val queried: Boolean = false,
    val items: List<EntityLearningProtocol.HomeDashboardChoice> = emptyList(),
    val userDefault: String? = null,
    val systemDefault: String? = null,
    val default: EntityLearningProtocol.HomeDashboardDefault = EntityLearningProtocol.HomeDashboardDefault(),
)

/** One process-local answer shared by the renderer and entity scanner for the same HA authority. */
internal class HomeDashboardResolutionAuthority {
    internal data class Key(
        val baseUrl: String,
        val authOwner: HaAuthOwner,
        val configuredPath: String,
    )

    private data class Cached(
        val key: Key,
        val resolution: EntityLearningProtocol.HomeDashboardResolution,
    )

    private val mutex = Mutex()
    private var cached: Cached? = null

    /**
     * [forceLive] bypasses the memo for this call and replaces it with what the account answers now.
     *
     * The key is endpoint, credential owner and CONFIGURED path — and under `Auto` none of those change
     * when the Home Assistant account's default dashboard changes. A process that had already resolved
     * once would therefore return the superseded answer for its whole lifetime, so the scan that
     * promises to reconcile the scope could never observe the disagreement it exists to correct. Any
     * caller that promises a live answer, rather than a consistent one within a single pass, must ask
     * for one.
     */
    suspend fun resolve(
        key: Key,
        stillCurrent: () -> Boolean,
        /** Skip the process-local answer and read Home Assistant again, then republish the result.
         *  The launch cache refreshes behind an already rendering provisional page and PROMISES a
         *  live answer: reusing this cache there would silently miss an account-default change, a
         *  removed dashboard or a revoked access across a warm activity relaunch. */
        forceLive: Boolean = false,
        read: suspend () -> EntityLearningProtocol.HomeDashboardResolution?,
    ): EntityLearningProtocol.HomeDashboardResolution? = mutex.withLock {
        cached?.takeIf { !forceLive && it.key == key }?.resolution?.takeIf { stillCurrent() } ?: run {
            if (!stillCurrent()) return@withLock null
            // A null READ is a transient failure (socket, auth, cancellation) and changes nothing:
            // the previous positive answer stays, so a flaky moment cannot erase a working target.
            val resolution = read() ?: return@withLock null
            if (!stillCurrent()) return@withLock null
            // A completed read reporting NO legal dashboard is authoritative for the account, so it
            // must also RETIRE any positive answer this process is still holding. Merely declining
            // to cache the zero left the stale positive in place, where an ordinary later resolve
            // replayed it and the launch cache persisted and opened a path the account cannot reach.
            // The zero itself is still not cached: the recovery screen tells the user to create or
            // grant a dashboard and retry, and that retry must reach Home Assistant again.
            if (resolution.path != null) {
                cached = Cached(key, resolution)
            } else {
                cached = cached?.takeIf { it.key != key }
            }
            resolution
        }
    }
}

internal data class OwnedAuthenticatedHomeDashboardAuthority(
    val key: HomeDashboardResolutionAuthority.Key,
    val credentialFingerprint: String,
)

/** Bind a returned access token to the exact stable credential owner that authorized the request. */
internal fun ownedAuthenticatedHomeDashboardAuthority(
    baseUrl: String,
    configuredPath: String,
    ownerBeforeAuthentication: HaAuthOwner,
    snapshotAfterAuthentication: HaAuthSnapshot,
    returnedAccessToken: String,
): OwnedAuthenticatedHomeDashboardAuthority? {
    val ownerAfter = snapshotAfterAuthentication.stableOwner()
    if (ownerAfter != ownerBeforeAuthentication || snapshotAfterAuthentication.accessToken != returnedAccessToken) {
        return null
    }
    return OwnedAuthenticatedHomeDashboardAuthority(
        HomeDashboardResolutionAuthority.Key(baseUrl, ownerAfter, configuredPath),
        EntityLearningProtocol.hash(
            listOf(
                snapshotAfterAuthentication.url,
                snapshotAfterAuthentication.accessToken,
                snapshotAfterAuthentication.refreshToken,
                snapshotAfterAuthentication.tokenExpiry.toString(),
                snapshotAfterAuthentication.clientId,
            ).joinToString("\u0000"),
        ),
    )
}

private data class AuthenticatedHomeDashboardChoices(
    val items: List<EntityLearningProtocol.HomeDashboardChoice>,
)

/** Read only the authenticated legal set shared by catalog display and renderer resolution. */
private suspend fun readAuthenticatedHomeDashboardChoices(
    request: suspend (JSONObject) -> JSONObject,
): AuthenticatedHomeDashboardChoices {
    val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
        ?: error("Home Assistant current-user response missing result")
    val isAdmin = user.optBoolean("is_admin") || user.optBoolean("is_owner")
    // Render admission needs the complete authenticated set. Treat a rejected/failed panel list as an
    // unavailable catalog rather than misclassifying built-in defaults as out-of-list or reporting a
    // false zero-dashboard result.
    val panels = request(JSONObject().put("type", "get_panels")).optJSONObject("result")
        ?: error("Home Assistant panel list missing result")
    val dashboards = request(JSONObject().put("type", "lovelace/dashboards/list"))
        .optJSONArray("result") ?: error("Home Assistant dashboard list missing result")
    val choices = EntityLearningProtocol.panelDashboardChoices(panels, isAdmin) +
        EntityLearningProtocol.homeDashboardChoices(dashboards, isAdmin)
    return AuthenticatedHomeDashboardChoices(choices)
}

private suspend fun readHomeDashboardDefault(
    request: suspend (JSONObject) -> JSONObject,
    command: String,
    label: String,
): String? {
    val result = request(JSONObject().put("type", command).put("key", "core"))
        .optJSONObject("result") ?: error("Home Assistant $label response missing result")
    return result.optJSONObject("value")?.optString("default_panel")
}

/** Parse one complete catalog for configuration UI without touching entity-learning state or scan APIs. */
internal suspend fun readHomeDashboardCatalog(
    request: suspend (JSONObject) -> JSONObject,
): HomeDashboardCatalog {
    val choices = readAuthenticatedHomeDashboardChoices(request).items
    val userDefault = readHomeDashboardDefault(request, "frontend/get_user_data", "user-default")
    val systemDefault = readHomeDashboardDefault(request, "frontend/get_system_data", "system-default")
    return HomeDashboardCatalog(
        queried = true,
        items = choices,
        userDefault = userDefault,
        systemDefault = systemDefault,
        default = EntityLearningProtocol.homeDashboardDefault(userDefault, systemDefault, choices),
    )
}

/** Resolve in authority order; lower-priority commands cannot block an already legal answer. */
internal suspend fun readHomeDashboardResolution(
    request: suspend (JSONObject) -> JSONObject,
    homeDashboard: String,
): EntityLearningProtocol.HomeDashboardResolution {
    val choices = readAuthenticatedHomeDashboardChoices(request).items
    val explicit = EntityLearningProtocol.resolveHomeDashboard(
        homeDashboard, null, null, choices, allowFirstLegalFallback = false,
    )
    if (explicit.source == EntityLearningProtocol.HomeDashboardSource.EXPLICIT) return explicit

    val userDefault = readHomeDashboardDefault(request, "frontend/get_user_data", "user-default")
    val user = EntityLearningProtocol.resolveHomeDashboard(
        homeDashboard, userDefault, null, choices, allowFirstLegalFallback = false,
    )
    if (user.source == EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT) return user

    val systemDefault = readHomeDashboardDefault(request, "frontend/get_system_data", "system-default")
    return EntityLearningProtocol.resolveHomeDashboard(homeDashboard, userDefault, systemDefault, choices)
}

/**
 * Whether a scan must obtain a live home-dashboard resolution rather than reuse the process memo.
 *
 * The memo is keyed by endpoint, credential owner and CONFIGURED path. Under `Auto` the configured
 * path is a constant, so the account's default dashboard can move without changing the key — and a
 * process that resolved once would answer with the superseded dashboard for its whole lifetime. The
 * scan is the thing that promises to notice that move, so under `Auto` it must ask for a live answer.
 */
internal fun homeDashboardResolutionMustBeLive(configuredHomeDashboard: String): Boolean =
    io.panelassistant.android.util.DashboardPath.followsAccountDefault(configuredHomeDashboard)
