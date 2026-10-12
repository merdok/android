package io.panelassistant.android.state

import io.panelassistant.android.device.PanelButtonKeys
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `button` event channel through the real state core: offered unless the profile declares no keys. */
internal class NativeButtonChannelTest : PanelRuntimeRig() {
    @Test(timeout = 60_000)
    fun aPanelProfiledWithoutKeysStatesTheButtonEventUnsupported() {
        val rig = rig(buttonKeys = PanelButtonKeys.NONE)
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertFalse("no button event is described", "button" in shape.served)
            assertTrue("it is stated unsupported, so Panel Assistant removes the entity", "button" in shape.unsupported)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 60_000)
    fun aPanelWithUnknownKeysKeepsTheButtonEventWhileKeyCaptureRuns() {
        val rig = rig(buttonKeys = PanelButtonKeys.UNKNOWN)
        try {
            rig.start()
            val shape = rig.core.nativeChannelShape()
            assertTrue("button" in shape.served)
            assertFalse("button" in shape.unsupported)
        } finally {
            rig.close()
        }
    }
}
