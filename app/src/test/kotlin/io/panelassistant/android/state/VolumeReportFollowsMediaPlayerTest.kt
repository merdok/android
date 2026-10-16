package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Panel Assistant's media player shows and sets the volume through the volume channel, so on a panel with
 * a speaker the volume is reported whatever its exposure pip says; without one the pip still decides.
 */
internal class VolumeReportFollowsMediaPlayerTest : PanelRuntimeRig() {
    private fun reportsVolume(rig: Rig): Boolean {
        val reported = CopyOnWriteArrayList<String>()
        rig.core.addStateSink { channel, _ -> reported += channel }
        rig.core.reconcile("volume")
        return "volume" in reported
    }

    @Test fun aPanelWithASpeakerReportsItsVolumeEvenWhenUnexposed() {
        val rig = rig(configure = { it.setHaExposed("volume", false) })
        try {
            assertTrue(reportsVolume(rig))
        } finally { rig.close() }
    }

    @Test fun aPanelWithoutASpeakerReportsItsVolumeOnlyWhileExposed() {
        val hidden = rig(media = null, configure = { it.setHaExposed("volume", false) })
        try {
            assertFalse(reportsVolume(hidden))
        } finally { hidden.close() }
        val shown = rig(media = null)
        try {
            assertTrue(reportsVolume(shown))
        } finally { shown.close() }
    }
}
