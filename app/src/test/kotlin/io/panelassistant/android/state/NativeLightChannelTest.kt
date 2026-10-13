package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The light answer through the production state core: the native hello's descriptor and unsupported
 * lists. Described (true) creates the entity, settled absent (false) removes it, and never reported
 * (null) creates nothing and deletes nothing.
 */
internal class NativeLightChannelTest : PanelRuntimeRig() {
    @Test(timeout = 90_000) fun aSensorThatNeverReportedCreatesNoEntityUntilItsFirstReading() {
        var answer: Boolean? = null
        val rig = rig(lightChannel = { answer })
        try {
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            rig.start()
            assertOffered(native, described = false, unsupported = false)

            answer = true
            rig.core.notifyLightAvailabilityChanged()

            assertOffered(native, described = true, unsupported = false)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000) fun aSettledAbsentSensorIsStatedUnsupported() {
        val rig = rig(lightChannel = { false })
        try {
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            rig.start()
            assertOffered(native, described = false, unsupported = true)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000) fun aSensorThatReportedStaysDescribed() {
        val rig = rig(lightChannel = { true })
        try {
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            rig.start()
            assertOffered(native, described = true, unsupported = false)
        } finally {
            rig.close()
        }
    }

    private fun assertOffered(native: PanelAssistantStateReporter, described: Boolean, unsupported: Boolean) {
        val offer = native.offer()
        assertEquals("illuminance descriptor in hello", described, offer.descriptors.any { it.channel == "illuminance" })
        assertEquals("illuminance stated unsupported", unsupported, "illuminance" in offer.unsupported)
        assertTrue("unrelated screen channel stays described", offer.descriptors.any { it.channel == "screen" })
        assertFalse("screen is not withdrawn", "screen" in offer.unsupported)
    }
}
