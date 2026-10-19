package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Panel Assistant's media player shows and sets the volume through the volume channel, so on a panel with
 * a speaker the volume is reported whatever its old exposure preference says.
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

}
