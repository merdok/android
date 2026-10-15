package io.panelassistant.android

import io.panelassistant.android.util.HaLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Open in HA" shows a device page only while the Panel Assistant entry and native server that resolved it still own it. */
class HaDeviceLinkConfigTest {
    @Test fun nativeHaTargetChangeInvalidatesDeviceLinkResolvedOnAnotherServer() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "https://renderer-a.example",
                "ha_device_url" to "https://other-b.example/config/devices/device/stale-id",
                "ha_link_at" to 1234L,
                "ha_link_target" to HaLink.resolutionTarget("https://other-b.example", "entry-id"),
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("https://renderer-c.example/", "native-token")

        assertEquals("https://renderer-c.example", prefs.values["ha_url"])
        assertFalse("a device id belongs to one HA instance", prefs.values.containsKey("ha_device_url"))
        assertFalse("the replacement target must resolve immediately", prefs.values.containsKey("ha_link_at"))
        assertFalse("the stale cache owner must be discarded", prefs.values.containsKey("ha_link_target"))
    }

    @Test fun equivalentNativeHaUrlPreservesResolvedDeviceLink() {
        val existing = "https://ha.example/config/devices/device/current-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "https://ha.example",
                "ha_device_url" to existing,
                "ha_link_at" to 1234L,
                "ha_link_target" to HaLink.resolutionTarget("https://ha.example", "entry-id"),
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("https://ha.example/", "replacement-token")

        assertEquals(existing, prefs.values["ha_device_url"])
        assertEquals(1234L, prefs.values["ha_link_at"])
        assertEquals(HaLink.resolutionTarget("https://ha.example", "entry-id"), prefs.values["ha_link_target"])
    }

    @Test fun openInHaNeverExposesDeviceIdOwnedByAnotherNativeServer() {
        val entry = "entry-id"
        val native = "https://native-a.example/ha"
        val staleDevice = "https://other-b.example/config/devices/device/stale-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to native,
                "ha_base_url" to "https://companion-c.example",
                "ha_device_url" to staleDevice,
                "ha_link_target" to HaLink.resolutionTarget("https://other-b.example", entry),
                "ha_link_at" to 1234L,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals("the native renderer target is safe while its device id resolves", native, config.haLinkUrl)
        assertFalse(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget(native, entry),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }

    @Test fun openInHaUsesDeviceIdOnlyWhenItsNativeTargetOwnsIt() {
        val entry = "entry-id"
        val native = "https://native-a.example/ha"
        val device = "$native/config/devices/device/current-id"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to native,
                "ha_device_url" to device,
                "ha_link_target" to HaLink.resolutionTarget(native, entry),
                "ha_link_at" to 1234L,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals("no learned Panel Assistant entry owns the device link", native, config.haLinkUrl)
        assertTrue(HaConnectionRoutes.learn(
            config, HaConnectionRoutes.current(config),
            HaConnectionAdvertisement("a".repeat(32), "b".repeat(32), emptyList()), entry,
        ))
        assertEquals(device, config.haLinkUrl)
        assertTrue(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget("$native/", entry),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }

    @Test fun legacyDeviceLinkWithoutTargetOwnershipIsNeverFresh() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_device_url" to "https://old.example/config/devices/device/legacy-id",
                "ha_link_at" to 1999L,
            ),
        )
        val config = Config(prefs.instance)

        assertFalse(config.haDeviceLinkIsFresh(
            HaLink.resolutionTarget("https://old.example", "entry-id"),
            nowMs = 2000L,
            ttlMs = 10_000L,
        ))
    }
}
