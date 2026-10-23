package io.panelassistant.android

import io.panelassistant.android.DashboardRendererRig.Companion.HA
import org.junit.Assert.assertEquals
import org.junit.Test

class LowMemorySignInTest {
    @Test fun lowMemoryPreservesSignInThroughActivityCallback() {
        DashboardRendererRig(prefs = mapOf("ha_url" to HA)).use { rig ->
            val page = RecordingWebView("$HA/auth/authorize?client_id=http%3A%2F%2F127.0.0.1%3A8888%2F")
            rig.set("signInShownForUrl", HA)
            rig.phase = RendererPhase.SIGN_IN
            rig.show(page, committed = null)
            rig.setPaced("screenAwake", false)
            rig.set("maintenanceFence", GuardDbActivityMaintenanceFence())
            rig.activity.onLowMemory()
            assertEquals(emptyList<String>(), page.calls)
            assertEquals(RendererPhase.SIGN_IN, rig.phase)
        }
    }

    @Test fun sameSignInIsPreservedBySiblingTrimCallback() {
        DashboardRendererRig(prefs = mapOf("ha_url" to HA)).use { rig ->
            val page = RecordingWebView("$HA/auth/authorize")
            rig.set("signInShownForUrl", HA)
            rig.phase = RendererPhase.SIGN_IN
            rig.show(page, committed = null)
            rig.setPaced("screenAwake", false)
            rig.set("maintenanceFence", GuardDbActivityMaintenanceFence())
            rig.pacer.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(emptyList<String>(), page.calls)
        }
    }

    @Test fun dashboardStillReloadsUnderSameLowMemoryCallback() {
        DashboardRendererRig().use { rig ->
            val page = RecordingWebView("$HA/lovelace/0")
            rig.show(page)
            rig.phase = RendererPhase.CONNECTED
            rig.setPaced("screenAwake", false)
            rig.set("maintenanceFence", GuardDbActivityMaintenanceFence())
            rig.activity.onLowMemory()
            assertEquals(listOf("reload", "onResume", "resumeTimers"), page.calls)
        }
    }
}
