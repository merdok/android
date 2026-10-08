package io.panelassistant.android.http

import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.appVersion
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.Json

internal fun panelBrowserTitle(
    friendlyName: String,
    section: String? = null,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
): String {
    val panel = friendlyName.trim().ifBlank { "ha-paneld" }
    val suffix = section?.trim().orEmpty()
    val title = if (suffix.isBlank()) panel else "$panel · $suffix"
    return "$title · ${appVersion(versionName, versionCode)}"
}

/** Same-order nullable overlay for `/status`; invalid cardinality omits the whole additive field. */
internal fun installWarningPresentationsJson(
    warnings: List<String>,
    presentations: List<InstallPresentation?>,
): String? {
    if (warnings.size != presentations.size || warnings.size > 11) return null
    return presentations.joinToString(separator = ",", prefix = "[", postfix = "]") { it?.json() ?: "null" }
}

/** Render one trusted Dashboard control without accepting pre-quoted HTML attribute fragments. */
internal fun dashboardControlButtonHtml(
    action: String,
    labelHtml: String,
    disabledReason: String?,
    style: String = "",
): String {
    require(action.matches(Regex("[a-z_]+"))) { "invalid Dashboard control action" }
    val styleAttr = style.takeIf(String::isNotBlank)?.let { " style=\"${esc(it)}\"" }.orEmpty()
    val titleAttr = disabledReason?.let { " title=\"${esc(it)}\"" }.orEmpty()
    val disabledAttr = if (disabledReason != null) " disabled" else ""
    return "<button class=\"pbtn\"$styleAttr$titleAttr onclick=\"act('$action')\"$disabledAttr>$labelHtml</button>"
}

internal fun installFormWantsHtml(accept: String?): Boolean =
    accept?.contains("text/html", ignoreCase = true) == true

/** JSON projected into browser pages. Install, Entities and shared Runtime controls need per-key
 * provenance to distinguish a genuine translation from an English compatibility fallback. */
internal fun browserI18nPayload(strings: AppStrings, prefixes: Set<String>): String {
    val resolved = strings.resolved(prefixes)
    val entries = resolved.entries.joinToString(",") { (key, localized) ->
        "${Json.str(key)}:${Json.str(localized.text)}"
    }
    val provenance = if (
        "entities." in prefixes || "install." in prefixes || "runtime." in prefixes
    ) {
        val languages = resolved.entries.joinToString(",") { (key, localized) ->
            "${Json.str(key)}:${Json.str(localized.language)}"
        }
        ",\"languages\":{$languages}"
    } else ""
    // Pages keep ?lang on their own links for exactly these locales; the debug pseudolocale included.
    val locales = (AppLocale.RELEASE_LOCALES + AppLocale.PSEUDO).joinToString(",") { Json.str(it) }
    return "{\"locale\":${Json.str(strings.requestedLocale)},\"locales\":[$locales],\"strings\":{$entries}$provenance}"
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}
