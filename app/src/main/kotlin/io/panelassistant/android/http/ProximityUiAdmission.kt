package io.panelassistant.android.http

/**
 * Admits the HTML UI on the LAN over HTTP: the marker plus a same-origin browser Origin or Referer. A cross-site
 * page cannot send the marker without a CORS preflight the panel never answers. Calibration collection
 * additionally requires Begin on the visible physical panel.
 */
internal fun proximityUiRequestAllowed(
    origin: String?, referer: String?, host: String?, fetchSite: String?, uiMarker: String?,
): Boolean {
    val browser = !origin.isNullOrBlank() || !referer.isNullOrBlank()
    val lanUi = uiMarker == "1" && browser && (fetchSite == null || fetchSite == "same-origin")
    return lanUi && OriginGuard.allowed("POST", origin, referer, host)
}
