package io.panelassistant.android.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/** Protect GET routes whose generation starts material work from opaque cross-origin browser loads. */
internal suspend fun admitActiveRead(
    call: ApplicationCall,
    allowLegacyNavigation: Boolean = false,
): Boolean = admitActiveRead(call.op(), allowLegacyNavigation)

/** A managed request reaches the panel through a door with its own origin checks, so it is admitted. */
internal suspend fun admitActiveRead(
    call: OpCall,
    allowLegacyNavigation: Boolean = false,
): Boolean {
    if (call.http == null) return true
    if (OriginGuard.activeReadAllowed(
            origin = call.request.headers["Origin"],
            referer = call.request.headers["Referer"],
            host = call.request.headers["Host"],
            fetchSite = call.request.headers["Sec-Fetch-Site"],
            accept = call.request.headers["Accept"],
            userAgent = call.request.headers["User-Agent"],
            allowLegacyNavigation = allowLegacyNavigation,
        )
    ) return true
    // Name what was actually wrong. The old text said "cross-origin" for every refusal, including
    // requests carrying no origin information at all — a misdiagnosis that sends the reader hunting
    // a CORS misconfiguration that does not exist.
    val site = call.request.headers["Sec-Fetch-Site"]?.trim()?.lowercase()
    val message = when {
        site == "cross-site" || site == "same-site" ->
            "refused: this panel does not serve active reads to another site."
        else ->
            "refused: this active read could not be verified as same-origin. Open the address " +
                "directly, or use the link on the panel's Configure page."
    }
    call.respondText("$message\n", status = HttpStatusCode.Forbidden)
    return false
}
