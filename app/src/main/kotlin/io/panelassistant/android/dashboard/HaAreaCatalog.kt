package io.panelassistant.android.dashboard

import io.panelassistant.android.Config
import io.panelassistant.android.HaConnectionRoutes
import io.panelassistant.android.HaCredentialManager
import io.panelassistant.android.haUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The area registry, this panel's device row, and what the signed-in account may do about it. */
internal data class HaAreaCatalog(
    /** Credential/endpoint owner actually used for this read; callers reject stale catalogs. */
    val ownerKey: String = "",
    val queried: Boolean = false,
    val admin: Boolean = false,
    val areas: List<io.panelassistant.android.http.HaAreaProtocol.HaArea> = emptyList(),
    val device: io.panelassistant.android.http.HaAreaProtocol.PanelDeviceArea =
        io.panelassistant.android.http.HaAreaProtocol.PanelDeviceArea(),
    /** The signed-in account's LOGIN name (admin sessions only: `config/auth/list` is the sole API
     *  that exposes it, and it is admin-gated). Blank when unavailable. The wizard prefills the
     *  username with it — the Mosquitto add-on authenticates against HA users. */
    val haUsername: String = "",
)

/**
 * Read the area registry and this panel's device row in one authenticated session — registry LISTS
 * are readable by any user; only writes need an admin. `queried=false` means "could not ask Home
 * Assistant", which callers must keep distinct from "device has no area".
 */
internal suspend fun haAreaCatalog(config: Config): HaAreaCatalog = withContext(Dispatchers.IO) {
    val ownerKey = credentialFingerprint(config)
    val configuredBase = config.haUrl.trim().trimEnd('/')
    if (configuredBase.isBlank()) return@withContext HaAreaCatalog(ownerKey = ownerKey)
    val auth = HaCredentialManager.resolve(config)
    val token = auth.session?.accessToken ?: return@withContext HaAreaCatalog(ownerKey = ownerKey)
    val route = auth.route ?: return@withContext HaAreaCatalog(ownerKey = ownerKey)
    val base = route.url
    runCatching {
        var catalog = HaAreaCatalog()
        withHaSocket(config, base, token, route) { request ->
            val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
            val isAdmin = user?.optBoolean("is_admin") == true || user?.optBoolean("is_owner") == true
            val areas = io.panelassistant.android.http.HaAreaProtocol.areas(
                request(JSONObject().put("type", "config/area_registry/list")),
            )
            val deviceRegistry = request(JSONObject().put("type", "config/device_registry/list"))
            val device = io.panelassistant.android.http.HaAreaProtocol.panelDeviceArea(
                deviceRegistry,
                areas,
                panelAssistantEntryIds(config),
            )
            // Login shortname, best-effort: only config/auth/list carries it and only admins may
            // call it (verified against core source — auth/current_user has display name only).
            val haUsername = if (isAdmin) runCatching {
                val userId = user?.optString("id").orEmpty()
                val rows = request(JSONObject().put("type", "config/auth/list")).optJSONArray("result")
                (0 until (rows?.length() ?: 0)).asSequence()
                    .mapNotNull { rows?.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == userId }
                    ?.optString("username")?.takeUnless { it.isNullOrBlank() || it == "null" }
                    .orEmpty()
            }.getOrDefault("") else ""
            catalog = HaAreaCatalog(
                ownerKey = ownerKey, queried = true, admin = isAdmin, areas = areas,
                device = device, haUsername = haUsername,
            )
        }
        check(HaConnectionRoutes.isCurrent(config, route)) { "Home Assistant route changed during catalog read" }
        catalog
    }.getOrDefault(HaAreaCatalog(ownerKey = ownerKey))
}

/** The Panel Assistant entry the last `hello` named, which is this panel's device. */
private fun panelAssistantEntryIds(config: Config): Set<String> = setOfNotNull(HaConnectionRoutes.panelAssistantEntryId(config))

/** Non-secret digest identifying the HA endpoint and credential generation used by area operations. */
internal fun haAreaOwnerKey(config: Config): String = credentialFingerprint(config)

/** Admin write-back of an area request; blank repairs only a current literal-null assignment. */
internal suspend fun applyRequestedArea(
    config: Config,
    areaName: String,
    expectedOwnerKey: String? = null,
): Boolean =
    withContext(Dispatchers.IO) {
        val requested = io.panelassistant.android.config.normalizedHaAreaName(areaName)
        if (expectedOwnerKey != null && credentialFingerprint(config) != expectedOwnerKey) return@withContext false
        val configuredBase = config.haUrl.trim().trimEnd('/')
        if (configuredBase.isBlank()) return@withContext false
        val auth = HaCredentialManager.resolve(config)
        val token = auth.session?.accessToken ?: return@withContext false
        val route = auth.route ?: return@withContext false
        val base = route.url
        runCatching {
            var moved = false
            withHaSocket(config, base, token, route) { request ->
                val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
                val isAdmin = user?.optBoolean("is_admin") == true || user?.optBoolean("is_owner") == true
                if (!isAdmin) return@withHaSocket
                val areas = io.panelassistant.android.http.HaAreaProtocol.areas(
                    request(JSONObject().put("type", "config/area_registry/list")),
                )
                val deviceRegistry = request(JSONObject().put("type", "config/device_registry/list"))
                val device = io.panelassistant.android.http.HaAreaProtocol.panelDeviceArea(
                    deviceRegistry,
                    areas,
                    panelAssistantEntryIds(config),
                )
                if (!device.found || device.deviceId.isBlank()) return@withHaSocket
                if (requested.isBlank()) {
                    if (device.areaId.isBlank()) { moved = true; return@withHaSocket }
                    if (!io.panelassistant.android.http.HaAreaProtocol.hasLiteralNullAssignment(device, areas)) {
                        return@withHaSocket
                    }
                }
                if (requested.isNotBlank() && device.areaName.equals(requested, ignoreCase = true)) { moved = true; return@withHaSocket }
                if (expectedOwnerKey != null && credentialFingerprint(config) != expectedOwnerKey) return@withHaSocket
                val areaId: Any = if (requested.isBlank()) JSONObject.NULL else {
                    io.panelassistant.android.http.HaAreaProtocol.resolveAreaId(areas, requested)
                        ?: request(
                            JSONObject().put("type", "config/area_registry/create").put("name", requested),
                        ).optJSONObject("result")?.optString("area_id")?.trim().takeUnless { it.isNullOrBlank() }
                        ?: return@withHaSocket
                }
                if (expectedOwnerKey != null && credentialFingerprint(config) != expectedOwnerKey) return@withHaSocket
                val updated = request(
                    JSONObject().put("type", "config/device_registry/update")
                        .put("device_id", device.deviceId)
                        .put("area_id", areaId),
                )
                moved = updated.optJSONObject("result") != null
            }
            check(HaConnectionRoutes.isCurrent(config, route)) { "Home Assistant route changed during area update" }
            moved
        }.getOrDefault(false)
    }
