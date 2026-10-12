package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.sensors.SensorReporter
import io.ktor.http.ContentType
import io.ktor.server.routing.Route

internal fun Route.sensorValuesRoute(
    appContext: Context,
    sensors: SensorReporter,
    volumePercent: () -> Int,
    effectiveBrightness: () -> Int,
) = ops {
    // Live Sensors card: last-published values + live extras. Volume is the current
    // media-stream percent; brightness is the system setting (0-255, -1 unknown).
    get("/sensors") {
        // Effective backlight first (reflects firmware dims); raw setting as fallback.
        val bright = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        call.respondText(
            """{${sensors.valuesJson()},"volume_pct":${runCatching { volumePercent() }.getOrDefault(-1)},"brightness":$bright}""",
            ContentType.Application.Json,
        )
    }
}
