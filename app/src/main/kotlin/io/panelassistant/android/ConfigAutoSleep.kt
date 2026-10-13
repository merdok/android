package io.panelassistant.android

import android.util.Log
import io.panelassistant.android.Config.Companion.AUTO_SLEEP_GENERATION_PREF
import io.panelassistant.android.Config.Companion.CONFIG_LOCK
import io.panelassistant.android.Config.Companion.TAG
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// Auto-sleep: the opt-in flag, its activity source, compare-and-set writes and the per-area source
// exclusions.

/** Opt-in activity policy. Physical screen light state and explicit manual screen commands remain
 * separate authorities; this flag only admits the automatic policy runtime. */
val Config.autoSleep: Boolean get() = boolPref("auto_sleep")
internal fun Config.migrateAutoSleepSource(): Boolean = synchronized(CONFIG_LOCK) {
    prefs.contains("auto_sleep_source") || durableCommit { putString("auto_sleep_source", absentAutoSleepSource) }
}
val Config.autoSleepSource: String get() = prefs.getString("auto_sleep_source", null)
    ?.takeIf { it in specOf("auto_sleep_source").options }
    ?: effectiveDefault(specOf("auto_sleep_source"))
val Config.autoSleepTouchDelaySeconds: Int get() = intPrefInRange("auto_sleep_touch_delay_seconds")
internal val Config.autoSleepGeneration: Long get() = synchronized(CONFIG_LOCK) {
    prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L)
}
internal fun Config.setAutoSleep(on: Boolean): AutoSleepWriteResult = synchronized(CONFIG_LOCK) {
    if (boolPref("auto_sleep") == on) return@synchronized AutoSleepWriteResult.UNCHANGED
    // The caller immediately asks the runtime to refresh, so durability must precede that refresh.
    val generation = nextAutoSleepGenerationLocked()
    if (durableCommit {
            putString("auto_sleep_source", autoSleepSource)
            putBoolean("auto_sleep", on)
            putLong(AUTO_SLEEP_GENERATION_PREF, generation)
        }
    ) AutoSleepWriteResult.COMMITTED
    else AutoSleepWriteResult.FAILED
}

/** Null means a newer setting value superseded this compare-and-set request. */
internal fun Config.setAutoSleepIf(
    expected: Boolean,
    expectedGeneration: Long,
    on: Boolean,
): AutoSleepWriteResult? = synchronized(CONFIG_LOCK) {
    if (boolPref("auto_sleep") != expected ||
        prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L) != expectedGeneration
    ) return@synchronized null
    setAutoSleep(on)
}

internal fun Config.nextAutoSleepGenerationLocked(): Long =
    (prefs.getLong(AUTO_SLEEP_GENERATION_PREF, 0L) + 1L).coerceAtLeast(1L)

/** Durable expert exclusions for automatic Area-source discovery. They deliberately remain
 * outside SettingsRegistry: entity ids are installation-specific runtime inputs, not fleet
 * settings or Home Assistant entities. Area dashboard_entity is never used to prune this memory. */
internal fun Config.autoSleepExcludedEntityIds(areaId: String): Set<String> = synchronized(CONFIG_LOCK) {
    val area = normalizeAutoSleepAreaId(areaId)
    if (area.isBlank()) emptySet() else {
        val all = readAutoSleepExclusions()
        autoSleepExclusionScopes().flatMapTo(linkedSetOf()) { scope -> all[scope]?.get(area).orEmpty() }
    }
}

internal fun Config.setAutoSleepSourceIncluded(areaId: String, entityId: String, included: Boolean): Boolean =
    synchronized(CONFIG_LOCK) {
        val scopes = autoSleepExclusionScopes()
        val scope = scopes.firstOrNull().orEmpty()
        val area = normalizeAutoSleepAreaId(areaId)
        val entity = normalizeAutoSleepEntityId(entityId)
        if (scope.isBlank() || area.isBlank() || entity.isBlank()) return@synchronized false
        val all = readAutoSleepExclusions().mapValuesTo(linkedMapOf()) { (_, areas) ->
            areas.mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toMutableSet() }
        }
        val merged = scopes.flatMapTo(linkedSetOf()) { storedScope -> all[storedScope]?.get(area).orEmpty() }
        if (included) merged.remove(entity) else merged.add(entity)
        // UUID adoption is asynchronous. Collapse the URL fallback into the stable namespace on
        // the first later mutation so an old exclusion cannot reappear after being included.
        scopes.forEach { storedScope ->
            all[storedScope]?.remove(area)
            if (all[storedScope]?.isEmpty() == true) all.remove(storedScope)
        }
        val areas = all.getOrPut(scope) { linkedMapOf() }
        if (merged.isNotEmpty()) areas[area] = merged
        if (areas.isEmpty()) all.remove(scope)
        val encoded = JSONObject().apply {
            all.toSortedMap().forEach { (storedScope, storedAreas) ->
                put(storedScope, JSONObject().apply {
                    storedAreas.toSortedMap().forEach { (storedArea, storedIds) ->
                        put(storedArea, JSONArray(storedIds.toSortedSet().toList()))
                    }
                })
            }
        }.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_AUTO_SLEEP_EXCLUSIONS_BYTES) {
            return@synchronized false
        }
        durableCommit {
            putString(AUTO_SLEEP_EXCLUSIONS_PREF, encoded)
        }
    }

/** Null means the opaque key belonged to a different HA installation by commit time. */
internal fun Config.setAutoSleepSourceIncludedIfScope(
    expectedScope: String,
    areaId: String,
    entityId: String,
    included: Boolean,
): Boolean? = synchronized(CONFIG_LOCK) {
    if (expectedScope.isBlank() || autoSleepExclusionScope() != expectedScope) null
    else setAutoSleepSourceIncluded(areaId, entityId, included)
}

internal fun Config.autoSleepExclusionScope(): String {
    return autoSleepExclusionScopes().firstOrNull().orEmpty()
}

private fun Config.autoSleepExclusionScopes(): List<String> {
    val stable = dashboardEntityInstanceUuid.trim().lowercase(Locale.ROOT)
        .takeIf(String::isNotBlank)?.let { "uuid:$it" }
    val origin = canonicalHaOrigin(haUrl)?.lowercase(Locale.ROOT)?.let { "origin:$it" }
    return listOfNotNull(stable, origin).distinct()
}

private fun Config.readAutoSleepExclusions(): Map<String, Map<String, Set<String>>> {
    val raw = prefs.getString(AUTO_SLEEP_EXCLUSIONS_PREF, "").orEmpty()
    if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_AUTO_SLEEP_EXCLUSIONS_BYTES) return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        root.keys().asSequence().associateWith { scope ->
            val areas = root.getJSONObject(scope)
            areas.keys().asSequence().mapNotNull { rawArea ->
                val area = normalizeAutoSleepAreaId(rawArea).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val values = areas.getJSONArray(rawArea)
                area to (0 until values.length()).mapNotNullTo(linkedSetOf()) { index ->
                    normalizeAutoSleepEntityId(values.optString(index)).takeIf(String::isNotBlank)
                }
            }.toMap()
        }
    }.onFailure { Log.w(TAG, "ignoring malformed auto-sleep source exclusions") }.getOrDefault(emptyMap())
}

private fun normalizeAutoSleepAreaId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    .takeIf { it.length <= 255 && it.matches(Regex("[a-z0-9_-]+")) }.orEmpty()

private fun normalizeAutoSleepEntityId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    .takeIf { it.length <= 255 && it.matches(Regex("binary_sensor\\.[a-z0-9_]+")) }.orEmpty()

private const val AUTO_SLEEP_EXCLUSIONS_PREF = "auto_sleep_source_exclusions_v1"
private const val MAX_AUTO_SLEEP_EXCLUSIONS_BYTES = 256 * 1024
