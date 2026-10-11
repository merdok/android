package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.util.AppInstaller

/**
 * The self-update route available to the panel now; a specific APK still has its own admission.
 * [confirmedSelfUpdate] counts: a rootless panel that may ask Android to install its own update with a
 * tap reports `api`, so Panel Assistant sends or offers the update instead of raising "no update route".
 */
internal fun installCapabilityStatusJson(
    privilege: PrivilegedRouteObservation,
    confirmedSelfUpdate: Boolean = false,
): String {
    val route = if (AppInstaller.selectInstallRoute(
            privilege.directSuReady, privilege.helperRootReady, confirmedSelfUpdate,
        ) != AppInstaller.InstallRoute.NONE
    ) "api" else "none"
    return "\"install_capability\":\"$route\""
}
