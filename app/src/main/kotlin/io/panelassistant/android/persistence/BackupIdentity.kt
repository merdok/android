package io.panelassistant.android.persistence

import io.panelassistant.android.util.Json
import org.json.JSONObject

/**
 * Which device and which installed identity wrote a backup.
 *
 * `panel_id` names a panel to its owner and is restorable configuration, so it cannot also prove which
 * physical device an archive came from: a freshly installed app has not adopted it yet. The discovery
 * id identifies the installation. It is a domain-separated digest of the stored random identity.
 * A settings restore never transfers it, so restoring onto another installation cannot clone discovery.
 */
object BackupIdentity {
    const val DISCOVERY_ID_KEY = "discovery_id"
    const val PACKAGE_KEY = "package"
    const val DEVICE_LINK_KEY = "legacy_did"
    private val DISCOVERY_ID_RE = Regex("[0-9a-f]{64}")

    /** The manifest members, each prefixed with a comma; an unavailable discovery id is omitted. */
    fun manifestFragment(discoveryId: String?, packageName: String, deviceLink: String? = null): String = buildString {
        if (discoveryId != null && DISCOVERY_ID_RE.matches(discoveryId)) {
            append(",\"$DISCOVERY_ID_KEY\":").append(Json.str(discoveryId))
        }
        append(",\"$PACKAGE_KEY\":").append(Json.str(packageName))
        if (deviceLink != null && DISCOVERY_ID_RE.matches(deviceLink)) {
            append(",\"$DEVICE_LINK_KEY\":").append(Json.str(deviceLink))
        }
    }

    /** The same reinstall link the panel reports in health. It is never copied into discovery identity.
     * Older archives keep their historical name comparison; a present link can never fall back to it. */
    fun sameRestoreDevice(manifest: JSONObject, ownDeviceLink: String?, legacyPanelIdMatches: Boolean): Boolean {
        if (!manifest.has(DEVICE_LINK_KEY)) return legacyPanelIdMatches
        val archived = manifest.opt(DEVICE_LINK_KEY) as? String ?: return false
        return DISCOVERY_ID_RE.matches(archived) && archived == ownDeviceLink
    }
}
