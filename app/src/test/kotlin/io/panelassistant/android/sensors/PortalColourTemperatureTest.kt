package io.panelassistant.android.sensors

import org.junit.Assert.*
import org.junit.Test

class PortalColourTemperatureTest {
    private val sample = floatArrayOf(2726.5618f, 14.922554f, 13.844314f, 2.6933305f)

    @Test fun `only the verified profile and vendor source expose CCT semantics`() {
        fun supported(profile: String = "meta-portal-10-gen2", type: Int = 65537,
                      name: String = "rgb sensor", vendor: String = "tcs34x0",
                      stringType: String = "android.sensor.rgb") =
            supportsPortalColourTemperature(profile, type, name, vendor, stringType)
        assertTrue(supported())
        assertFalse(supported(profile = "meta-portal"))
        assertFalse(supported(type = 65538))
        assertFalse(supported(name = "SpectralColorSensor"))
        assertFalse(supported(vendor = "other"))
        assertFalse(supported(stringType = "other"))
        val absent = PortalColourTemperature(false)
        absent.observe(sample, 5000)
        assertEquals("\"present\":false", absent.json(8000))
    }

    @Test fun `diagnostics publish estimated kelvin and monotonic receipt age`() {
        val sensor = PortalColourTemperature(true)
        assertEquals("\"present\":true,\"kelvin\":null,\"age_s\":null", sensor.json(9000))
        sensor.observe(sample, 5000)
        assertEquals("\"present\":true,\"kelvin\":2726.6,\"age_s\":3", sensor.json(8500))
        assertTrue(sensor.json(4500).endsWith("\"age_s\":0"))
        sensor.observe(sample.copyOf().apply { this[0] = 4000f }, 10000)
        assertEquals("\"present\":true,\"kelvin\":4000,\"age_s\":1", sensor.json(11000))
        sensor.clear()
        assertEquals("\"present\":true,\"kelvin\":null,\"age_s\":null", sensor.json(12000))
    }

    @Test fun `malformed or degenerate payload never publishes a measurement`() {
        val invalid = listOf(floatArrayOf(), sample.copyOf(3), sample.copyOf().apply { this[0] = 0f },
            sample.copyOf().apply { this[0] = -1f }, sample.copyOf().apply { this[1] = -1f },
            sample.copyOf().apply { this[2] = 0f }, sample.copyOf().apply { this[3] = -1f }) +
            (0..3).flatMap { slot -> listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
                .map { value -> sample.copyOf().apply { this[slot] = value } } }
        for (values in invalid) {
            val sensor = PortalColourTemperature(true)
            sensor.observe(sample, 5000)
            sensor.observe(values, 6000)
            assertEquals("\"present\":true,\"kelvin\":null,\"age_s\":null", sensor.json(7000))
        }
    }
}
