package io.panelassistant.android.sensors

/** The verified Portal vendor payload is estimated CCT followed by CIE XYZ, not RGB channels. */
internal const val PORTAL_COLOUR_SENSOR_TYPE = 65537

internal fun supportsPortalColourTemperature(
    profileId: String, type: Int, name: String, vendor: String, stringType: String,
): Boolean = profileId == "meta-portal-10-gen2" && type == PORTAL_COLOUR_SENSOR_TYPE &&
    name == "rgb sensor" && vendor == "tcs34x0" && stringType == "android.sensor.rgb"

/** Diagnostic receipt snapshot only. Vendor event timestamps on this HAL are epoch based. */
internal class PortalColourTemperature(private val present: Boolean) {
    private data class Reading(val kelvin: Float, val receivedAt: Long)
    @Volatile private var reading: Reading? = null

    fun observe(values: FloatArray, receivedAt: Long) {
        if (!present) return
        reading = if (values.size >= 4 && (0..3).all { values[it].isFinite() } &&
            values[0] > 0f && values[1] >= 0f && values[2] > 0f && values[3] >= 0f
        ) Reading(values[0], receivedAt) else null
    }

    fun clear() { reading = null }

    fun json(now: Long): String {
        if (!present) return "\"present\":false"
        val sample = reading
        val kelvin = sample?.let { formatSensorValue(it.kelvin) } ?: "null"
        val age = sample?.let { (now - it.receivedAt).coerceAtLeast(0L) / 1000L }
        return "\"present\":true,\"kelvin\":$kelvin,\"age_s\":${age ?: "null"}"
    }
}
