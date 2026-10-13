package io.panelassistant.android.http

import io.panelassistant.android.camera.CameraSurface
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.cameraRoutes(
    camera: CameraSurface,
) {
    // Exactly the object `/api/v1/status` carries under `camera`, served alone so the
    // Dashboard's camera card can poll it every couple of seconds without rebuilding
    // the whole status document. One projection, one renderer: the bytes are produced
    // by the same `statusJson()`, so the card and the status object cannot drift.
    //
    // Unadmitted for the same reason `/sensors` is: there is no work here to gate. The
    // call reads the session's own state under its lock and never opens the camera, so
    // an idle panel stays at zero cost, and the identical bytes are already readable
    // from `/api/v1/status` — this adds no exposure, only a cheaper way to ask.
    ops {
        get("/camera/status") {
            call.response.headers.append("Cache-Control", "no-store")
            call.respondText(camera.presentation().statusJson(), ContentType.Application.Json)
        }
    }
}
