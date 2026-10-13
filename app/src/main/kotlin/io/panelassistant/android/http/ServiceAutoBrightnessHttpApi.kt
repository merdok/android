package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.stableOwner
import io.panelassistant.android.control.AmbientThemeReason
import io.panelassistant.android.control.AmbientThemeReport
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.sensors.HaAmbientLuxSubscriber
import io.panelassistant.android.sensors.HaAmbientSourceValidation
import io.panelassistant.android.sensors.SensorReporter
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The adaptive-brightness HTTP surface over the service's live controllers. Moved out of PaneldService
 * unchanged; the providers are read on each call, as the service's late-initialised fields were.
 */
internal class ServiceAutoBrightnessHttpApi(
    private val config: () -> Config,
    private val autoBright: () -> AutoBrightnessController,
    private val sensors: () -> SensorReporter,
    private val haAmbientLux: () -> HaAmbientLuxSubscriber,
    private val refreshInputs: () -> Unit,
) : AutoBrightnessHttpApi {
    override fun ambientTheme(): AmbientThemeReport {
        val runtime = autoBright().status().ambientTheme
        return AmbientThemeReport(
            reason = AmbientThemeReason.of(
                autoBrightness = config().autoBrightness,
                hasLightSource = sensors().hasLight() || config().autoBrightnessHaEntity.isNotBlank(),
                verdictDark = config().dashboardAmbientDark,
                sourceAvailable = runtime.sourceAvailable,
            ),
            level = runtime.level,
        )
    }

    override fun statusJson(): String {
        val runtime = autoBright().status()
        val haStatus = haAmbientLux().latestStatus()
        val selected = config().autoBrightnessHaEntity.trim().takeIf(String::isNotBlank)
        val candidate = selected?.let { id ->
            haAmbientLux().latestCandidates().items.firstOrNull { it.entityId == id }
        }
        return JSONObject().apply {
            put("available", true)
            put("state", if (runtime.enabled) "enabled" else "disabled")
            put("mode", runtime.mode?.name?.lowercase() ?: "waiting")
            put("paused", runtime.manualPreference.active)
            put("preferenceActive", runtime.manualPreference.active)
            put("localSourcePresent", sensors().hasLight())
            put("sourceLabel", candidate?.friendlyName ?: selected ?: if (sensors().hasLight()) {
                "Panel ambient sensor"
            } else {
                "No ambient light source"
            })
            put("entityId", selected ?: JSONObject.NULL)
            put("sourceAvailable", runtime.sourceAvailable)
            put("sourceRevision", runtime.sourceRevision)
            put("latestLux", runtime.latestLux ?: JSONObject.NULL)
            put("expectedLux", runtime.expectedLux ?: JSONObject.NULL)
            put("automaticTarget", runtime.automaticTarget ?: JSONObject.NULL)
            put("appliedTarget", runtime.appliedTarget ?: JSONObject.NULL)
            put("minimumPercent", config().autoBrightnessMinimumPercent)
            put("maximumPercent", config().autoBrightnessMaximumPercent)
            put("maximumBrightness", io.panelassistant.android.control.AdaptiveLuxCurve.percentToBrightness(config().autoBrightnessMaximumPercent))
            put(
                "minimumBrightness",
                io.panelassistant.android.control.AdaptiveLuxCurve.percentToBrightness(
                    config().autoBrightnessMinimumPercent,
                ),
            )
            put("autoAuthority", runtime.manualPreference.autoAuthority)
            put("manualRemainingMs", runtime.manualPreference.remainingMs)
            if (selected != null) put("detail", haStatus.detail)
        }.toString()
    }

    override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?, maximumPercent: Int?): String {
        val nowEpochMinute = System.currentTimeMillis() / 60_000L
        val cutoffMinute = nowEpochMinute - hours * 60L
        val previewSensitivity = sensitivity ?: config().autoBrightnessResponsePercent
        val previewMinimum = minimumPercent ?: config().autoBrightnessMinimumPercent
        val previewMaximum = maximumPercent ?: config().autoBrightnessMaximumPercent
        require(previewMaximum > previewMinimum) { "maximum_percent must be above minimum_percent" }
        val snapshot = autoBright().chartSnapshot(
            previewSensitivity,
            previewMinimum,
            previewMaximum,
        )
        val points = snapshot.points
            .filter { it.epochMinute >= cutoffMinute }
        val zone = autoBright().timeZone()
        val today = Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayMetadata = mutableMapOf<Int, Pair<String, Int>>()
        return JSONObject().apply {
            put("available", true)
            put("hours", hours)
            put("bucket_minutes", 5)
            put("sensitivity", previewSensitivity)
            put("minimum_percent", previewMinimum)
            put("maximum_percent", previewMaximum)
            put("maximum_brightness", io.panelassistant.android.control.AdaptiveLuxCurve.percentToBrightness(previewMaximum))
            put("minimum_brightness", io.panelassistant.android.control.AdaptiveLuxCurve.percentToBrightness(previewMinimum))
            put("now_epoch_minute", nowEpochMinute)
            put("sourceRevision", snapshot.sourceRevision)
            // Freshness describes the newest point actually returned to this chart request,
            // rather than a newer raw minute that has not completed its five-minute bucket.
            put("latestEpochMinute", points.lastOrNull()?.epochMinute ?: JSONObject.NULL)
            put("time_zone", zone.id)
            put("points", JSONArray().apply {
                points.forEach { point -> put(JSONObject().apply {
                    val local = Calendar.getInstance(zone).apply {
                        timeInMillis = point.epochMinute * 60_000L
                    }
                    val dayKey = local.get(Calendar.YEAR) * 400 + local.get(Calendar.DAY_OF_YEAR)
                    val (localDay, dayAge) = dayMetadata.getOrPut(dayKey) {
                        val day = String.format(
                            Locale.US,
                            "%04d-%02d-%02d",
                            local.get(Calendar.YEAR),
                            local.get(Calendar.MONTH) + 1,
                            local.get(Calendar.DAY_OF_MONTH),
                        )
                        val localMidnight = local.clone() as Calendar
                        localMidnight.set(Calendar.HOUR_OF_DAY, 0)
                        localMidnight.set(Calendar.MINUTE, 0)
                        localMidnight.set(Calendar.SECOND, 0)
                        localMidnight.set(Calendar.MILLISECOND, 0)
                        var age = 0
                        while (localMidnight.before(today) && age <= 7) {
                            localMidnight.add(Calendar.DAY_OF_MONTH, 1)
                            age += 1
                        }
                        day to age
                    }
                    put("epochMinute", point.epochMinute)
                    put("localDay", localDay)
                    put("minuteOfDay", local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE))
                    put("dayAge", dayAge)
                    put("observedMeanLux", point.observedMeanLux)
                    put("minLux", point.minLux)
                    put("maxLux", point.maxLux)
                    put("expectedLux", point.expectedLux)
                    put("proposedBrightness", point.proposedBrightness)
                }) }
            })
        }.toString()
    }

    override fun haSourcesJson(query: String, limit: Int): String {
        haAmbientLux().refreshCandidates()
        val projection = haAmbientLux().latestCandidates()
        val needle = query.lowercase()
        val items = projection.items.asSequence()
            .filter { needle.isBlank() || it.entityId.lowercase().contains(needle) || it.friendlyName.lowercase().contains(needle) }
            .take(limit)
        return JSONObject().apply {
            put("available", projection.error.isBlank())
            put("refreshing", haAmbientLux().candidateRefreshInFlight())
            if (projection.error.isNotBlank()) put("detail", projection.error)
            put("items", JSONArray().apply { items.forEach { source -> put(JSONObject().apply {
                put("entityId", source.entityId)
                put("friendlyName", source.friendlyName)
                put("unit", source.unit)
                put("currentLux", source.currentLux ?: JSONObject.NULL)
                put("available", source.available)
                put("lastUpdatedEpochMs", source.lastUpdatedEpochMs ?: JSONObject.NULL)
            }) } })
        }.toString()
    }

    override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation =
        when (val result = haAmbientLux().validateSource(entityId)) {
            is HaAmbientSourceValidation.Ready -> AutoBrightnessHttpValidation(
                AutoBrightnessHttpAction.ok(),
                result.authOwner,
            )
            is HaAmbientSourceValidation.Rejected -> AutoBrightnessHttpValidation(
                AutoBrightnessHttpAction(
                    statusCode = result.statusCode,
                    json = JSONObject().put("ok", false).put("error", result.error)
                        .put("message", result.message).toString(),
                ),
            )
        }

    override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction {
        if (entityId != null) {
            val previous = config().autoBrightnessHaEntity
            val validation = validateHaSource(entityId)
            if (validation.action.statusCode !in 200..299) return validation.action
            val committed = config().synchronizedTransaction {
                if (validation.authOwner == null || config().haAuthSnapshot().stableOwner() != validation.authOwner ||
                    config().autoBrightnessHaEntity != previous
                ) false else config().applyBatch { config().setAutoBrightnessHaEntity(entityId) }
            }
            if (!committed) return AutoBrightnessHttpAction(
                409,
                """{"ok":false,"error":"ha-source-validation-stale","message":"Home Assistant settings changed during the check. Try again."}""",
            )
        } else {
            config().setAutoBrightnessHaEntity("")
        }
        refreshInputs()
        autoBright().reapplyLatest()
        return AutoBrightnessHttpAction.ok()
    }

    override fun resetHistory(): AutoBrightnessHttpAction {
        autoBright().resetHistory()
        return AutoBrightnessHttpAction.ok()
    }

    override fun resumeFullAuto(): AutoBrightnessHttpAction {
        autoBright().resumeFullAuto()
        return AutoBrightnessHttpAction.ok()
    }
}
