package io.panelassistant.android

import android.content.Intent
import io.panelassistant.android.platform.EXTRA_DASHBOARD_RETURN

internal fun shouldRouteDashboardHomeToAdmin(
    configuredLauncherPackage: String,
    // Nullable as the platform reports it: an activity with no package (only off-device) never routes.
    ownPackage: String?,
    action: String?,
    categories: Set<String>?,
    // The app's own HOME intent back to this dashboard (EXTRA_DASHBOARD_RETURN) is a return, not a HOME press.
    dashboardReturn: Boolean,
): Boolean = configuredLauncherPackage == ownPackage && !dashboardReturn &&
    action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_HOME) == true

internal fun Intent?.routesDashboardHomeToAdmin(launcherPackage: String, ownPackage: String?): Boolean =
    shouldRouteDashboardHomeToAdmin(
        launcherPackage, ownPackage, this?.action, this?.categories,
        dashboardReturn = this?.getBooleanExtra(EXTRA_DASHBOARD_RETURN, false) == true,
    )

/** The intent shape Android treats as a HOME launch (ActivityRecord.isHomeIntent), so it keeps the activity
 *  in the HOME task, which a component-explicit start never reaches. */
internal fun isHomeLaunch(action: String?, categories: Set<String>?, hasDataOrType: Boolean): Boolean =
    action == Intent.ACTION_MAIN && categories == setOf(Intent.CATEGORY_HOME) && !hasDataOrType

internal fun Intent?.isHomeLaunch(): Boolean = this != null && isHomeLaunch(action, categories, data != null || type != null)
