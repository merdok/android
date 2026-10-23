package io.panelassistant.android.state

import io.panelassistant.android.control.BrightnessSetting
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal class ScreenBrightnessCommandTest : PanelRuntimeRig() {
    private class Setting : BrightnessSetting {
        var level = 80
        var accepted = false
        var writes = 0
        override fun read() = level
        override fun write(value: Int): Boolean {
            writes++
            if (accepted) level = value
            return accepted
        }
    }

    @Test fun aRefusedExplicitBrightnessNeverPublishesTheRequestedLevelAndRetryRecovers() {
        val setting = Setting()
        val rig = rig(brightnessSetting = setting)
        val states = CopyOnWriteArrayList<String>()
        try {
            rig.core.addStateSink { channel, observation ->
                if (channel == "screen" && observation is StateConverger.Observation.Known) states += observation.payload
            }
            rig.start()
            val refused = submitNative(rig, "screen", CommandValue.Light(true, 200))
            assertTrue("refused brightness fails: $refused", refused is PanelAssistantCommandResult.Failed)
            assertTrue("explicit command reached its brightness actuator", setting.writes > 0)
            assertEquals(80, setting.level)
            assertFalse(states.any { JSONObject(it).optInt("brightness") == 200 })
            setting.accepted = true
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true, 200)))
            assertEquals(200, setting.level)
            assertEquals(200, JSONObject(states.last()).getInt("brightness"))
            val writes = setting.writes
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true)))
            assertEquals("bare ON does not write brightness", writes, setting.writes)
        } finally { rig.close() }
    }
}
